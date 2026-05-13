package com.anonymous.sidebar

import android.animation.*
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.graphics.*
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.accessibilityservice.AccessibilityService
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.MediaStore
import android.os.*
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.*
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class SidebarOverlayService : Service() {

    private lateinit var wm: WindowManager
    private var handleView: View? = null
    private var handleViewAlt: View? = null
    private var pillInnerView: View? = null
    private var pillInnerViewAlt: View? = null
    private var lastActiveSide = "right"
    private var panelView: View? = null
    private var dismissOverlay: View? = null
    private var allAppsView: View? = null
    private var allAppsDismissOverlay: View? = null
    private var contextMenuView: View? = null
    private var showingDrawer = false
    private var shown = false

    private var vibrationEnabled = true

    // Collapsible controls strip state
    private var ctrlWrapperView: FrameLayout? = null
    private var ctrlChevronView: TextView? = null
    private var controlsExpanded = false
    private var ctrlFullH = 0

    private val iconTypeface: android.graphics.Typeface by lazy {
        android.graphics.Typeface.createFromAsset(assets, "fonts/MaterialIcons-Regular.ttf")
    }

    private val appLoadExecutor = Executors.newSingleThreadExecutor()
    // Keyed by package name; populated on service start so panel and drawer open instantly.
    private val appIconCache = ConcurrentHashMap<String, Pair<String, Drawable>>()
    @Volatile private var cachedAllApps: List<Triple<String, String, Drawable>>? = null

    // Fullscreen detection — only active when auto-hide-fullscreen pref is on.
    // 2 s interval is plenty; tighter polling prevents deep-sleep unnecessarily.
    private val fsHandler = Handler(Looper.getMainLooper())
    private val fsRunnable = object : Runnable {
        override fun run() {
            if (!shown) {
                val vis = if (isSystemFullscreen()) View.INVISIBLE else View.VISIBLE
                handleView?.visibility = vis
                handleViewAlt?.visibility = vis
            }
            fsHandler.postDelayed(this, 2000)
        }
    }

    // Torch state
    private var torchEnabled = false
    private var torchCameraId: String? = null
    private var cameraManager: CameraManager? = null
    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchCameraId) torchEnabled = enabled
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                // No point polling for fullscreen state while the screen is off.
                fsHandler.removeCallbacks(fsRunnable)
                return
            }
            if (!Settings.canDrawOverlays(context)) return
            Handler(Looper.getMainLooper()).post {
                if (handleView?.windowToken == null) {
                    addHandle()
                } else {
                    val autoHide = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
                        .getBoolean(Prefs.AUTO_HIDE_FULLSCREEN, false)
                    if (autoHide) {
                        fsHandler.removeCallbacks(fsRunnable)
                        fsHandler.post(fsRunnable)
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, filter)

        cameraManager = getSystemService(CAMERA_SERVICE) as? CameraManager
        cameraManager?.let { cm ->
            try {
                torchCameraId = cm.cameraIdList.firstOrNull { id ->
                    cm.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }
                torchCameraId?.let {
                    cm.registerTorchCallback(torchCallback, Handler(Looper.getMainLooper()))
                }
            } catch (e: Exception) { Log.w(TAG, "Torch init failed", e) }
        }

        if (Settings.canDrawOverlays(this)) {
            Handler(Looper.getMainLooper()).post { addHandle() }
        }
        appLoadExecutor.submit { prefetchAppData() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Settings.canDrawOverlays(this)) {
            Handler(Looper.getMainLooper()).post { refreshHandle() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        fsHandler.removeCallbacks(fsRunnable)
        appLoadExecutor.shutdown()
        runCatching { unregisterReceiver(screenReceiver) }
        torchCameraId?.let { runCatching { cameraManager?.unregisterTorchCallback(torchCallback) } }
        Handler(Looper.getMainLooper()).post {
            handleView?.let { runCatching { wm.removeView(it) } }
            handleViewAlt?.let { runCatching { wm.removeView(it) } }
            panelView?.let { runCatching { wm.removeView(it) } }
            dismissOverlay?.let { runCatching { wm.removeView(it) } }
            allAppsView?.let { runCatching { wm.removeView(it) } }
            allAppsDismissOverlay?.let { runCatching { wm.removeView(it) } }
            contextMenuView?.let { runCatching { wm.removeView(it) } }
        }
    }

    private fun refreshHandle() {
        fsHandler.removeCallbacks(fsRunnable)
        handleView?.let { runCatching { wm.removeView(it) } }
        handleViewAlt?.let { runCatching { wm.removeView(it) } }
        handleView = null; handleViewAlt = null
        pillInnerView = null; pillInnerViewAlt = null
        addHandle()
    }

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    // ── Prefs ─────────────────────────────────────────────────────────────────

    data class PillPrefs(
        val height: Int, val width: Int, val position: Float,
        val side: String, val opacity: Float, val theme: String,
        val panelColor: String
    )

    data class OverlayPrefs(
        val autoHideFullscreen: Boolean,
        val showLabels: Boolean,
        val vibration: Boolean,
        val sensitivity: Int,
        val quickControlsEnabled: Boolean,
        val showTorch: Boolean,
        val showAutoRotate: Boolean,
        val showAutoBrightness: Boolean,
        val showRingerMode: Boolean,
        val showAllApps: Boolean,
        val showEdit: Boolean,
        val gesturesEnabled: Boolean,
        val gestureSwipeIn: String,
        val gestureSwipeUp: String,
        val gestureSwipeDown: String,
        val gestureDoubleTap: String,
        val showBrightnessSlider: Boolean,
        val showVolumeSlider: Boolean,
        val showQuickShare: Boolean,
        val showPower: Boolean,
        val showQr: Boolean,
        val showDnd: Boolean
    )

    private fun pillPrefs(): PillPrefs {
        val p = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
        return PillPrefs(
            p.getInt(Prefs.PILL_HEIGHT, 80), p.getInt(Prefs.PILL_WIDTH, 36),
            p.getFloat(Prefs.PILL_POSITION, 0.5f),
            p.getString(Prefs.PILL_SIDE, "right") ?: "right",
            p.getFloat(Prefs.PILL_OPACITY, 1.0f),
            p.getString(Prefs.PILL_THEME, "dark") ?: "dark",
            p.getString(Prefs.PANEL_COLOR, "") ?: ""
        )
    }

    private fun resolvePanelBg(rawColor: String, alpha: Int, light: Boolean): Int {
        if (rawColor.isNotEmpty()) {
            try {
                val c = Color.parseColor(rawColor)
                return Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))
            } catch (_: IllegalArgumentException) {}
        }
        return if (light) Color.argb(alpha, 255, 251, 254) else Color.argb(alpha, 28, 27, 31)
    }

    private fun resolveControlsBg(panelBg: Int, rawColor: String, light: Boolean): Int {
        if (rawColor.isEmpty()) return if (light) Color.argb(255, 220, 212, 230) else Color.argb(255, 54, 52, 59)
        val hsv = FloatArray(3)
        Color.colorToHSV(panelBg, hsv)
        val delta = if (hsv[2] < 0.5f) 0.22f else -0.18f
        hsv[2] = (hsv[2] + delta).coerceIn(0f, 1f)
        return Color.HSVToColor(255, hsv)
    }

    private fun overlayPrefs(): OverlayPrefs {
        val p = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
        return OverlayPrefs(
            p.getBoolean(Prefs.AUTO_HIDE_FULLSCREEN, false),
            p.getBoolean(Prefs.SHOW_LABELS, true),
            p.getBoolean(Prefs.VIBRATION, true),
            p.getInt(Prefs.SWIPE_SENSITIVITY, 16),
            p.getBoolean(Prefs.QUICK_CONTROLS_ENABLED, true),
            p.getBoolean(Prefs.SHOW_TORCH, true),
            p.getBoolean(Prefs.SHOW_AUTO_ROTATE, true),
            p.getBoolean(Prefs.SHOW_AUTO_BRIGHTNESS, true),
            p.getBoolean(Prefs.SHOW_RINGER_MODE, true),
            p.getBoolean(Prefs.SHOW_ALL_APPS, true),
            p.getBoolean(Prefs.SHOW_EDIT, true),
            p.getBoolean(Prefs.GESTURES_ENABLED, true),
            p.getString(Prefs.GESTURE_SWIPE_IN, "panel") ?: "panel",
            p.getString(Prefs.GESTURE_SWIPE_UP, "none") ?: "none",
            p.getString(Prefs.GESTURE_SWIPE_DOWN, "notifications") ?: "notifications",
            p.getString(Prefs.GESTURE_DOUBLE_TAP, "none") ?: "none",
            p.getBoolean(Prefs.SHOW_BRIGHTNESS_SLIDER, false),
            p.getBoolean(Prefs.SHOW_VOLUME_SLIDER, false),
            p.getBoolean(Prefs.SHOW_QUICK_SHARE, false),
            p.getBoolean(Prefs.SHOW_POWER, false),
            p.getBoolean(Prefs.SHOW_QR, false),
            p.getBoolean(Prefs.SHOW_DND, false)
        )
    }

    // ── Handle ────────────────────────────────────────────────────────────────

    private fun handleParams(prefs: PillPrefs, side: String): WindowManager.LayoutParams {
        val pillHeight = dp(prefs.height)
        val screenHeight = resources.displayMetrics.heightPixels
        val yPos = (prefs.position * screenHeight - pillHeight / 2).toInt()
            .coerceIn(0, screenHeight - pillHeight)
        return WindowManager.LayoutParams(
            maxOf(dp(prefs.width), dp(24)), pillHeight,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = if (side == "left") Gravity.START or Gravity.TOP
                      else Gravity.END or Gravity.TOP
            x = dp(2)
            y = yPos
        }
    }

    private fun buildHandle(prefs: PillPrefs, oPrefs: OverlayPrefs, side: String): Pair<View, View> {
        val pill = View(this).apply {
            background = PullTabDrawable(highlighted = false, prefs.theme, prefs.panelColor)
            alpha = prefs.opacity
        }
        val container = FrameLayout(this).apply {
            addView(pill, FrameLayout.LayoutParams(dp(prefs.width), FrameLayout.LayoutParams.MATCH_PARENT).apply {
                gravity = if (side == "left") Gravity.START else Gravity.END
            })
        }
        installSwipeListener(container, oPrefs.sensitivity, side)
        wm.addView(container, handleParams(prefs, side))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            container.post {
                container.systemGestureExclusionRects = listOf(
                    android.graphics.Rect(0, 0, container.width, container.height)
                )
            }
        }
        return Pair(container, pill)
    }

    private fun addHandle() {
        val prefs = pillPrefs()
        val oPrefs = overlayPrefs()
        vibrationEnabled = oPrefs.vibration
        val primarySide = if (prefs.side == "both") "left" else prefs.side
        val (c, p) = buildHandle(prefs, oPrefs, primarySide)
        handleView = c; pillInnerView = p
        if (prefs.side == "both") {
            val (cR, pR) = buildHandle(prefs, oPrefs, "right")
            handleViewAlt = cR; pillInnerViewAlt = pR
            lastActiveSide = "right"
        }
        if (oPrefs.autoHideFullscreen) {
            fsHandler.removeCallbacks(fsRunnable)
            fsHandler.post(fsRunnable)
        }
    }

    private fun installSwipeListener(handle: View, sensitivityDp: Int = 16, triggerSide: String = "right") {
        var downX = 0f
        var downY = 0f
        var lastTapTime = 0L
        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; true }
                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    val adx = Math.abs(dx)
                    val ady = Math.abs(dy)
                    val threshold = dp(sensitivityDp)
                    val oPrefs = overlayPrefs()
                    when {
                        adx >= threshold && adx > ady -> {
                            lastActiveSide = triggerSide
                            if (oPrefs.gesturesEnabled) executeGestureAction(oPrefs.gestureSwipeIn)
                            else toggle()
                        }
                        oPrefs.gesturesEnabled && ady >= threshold && ady > adx && dy < 0 -> executeGestureAction(oPrefs.gestureSwipeUp)
                        oPrefs.gesturesEnabled && ady >= threshold && ady > adx && dy > 0 -> executeGestureAction(oPrefs.gestureSwipeDown)
                        adx < dp(8) && ady < dp(8) -> {
                            val now = System.currentTimeMillis()
                            if (now - lastTapTime < 300L) {
                                if (oPrefs.gesturesEnabled) executeGestureAction(oPrefs.gestureDoubleTap)
                                lastTapTime = 0L
                            } else {
                                lastTapTime = now
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> true
                else -> false
            }
        }
    }

    private fun executeGestureAction(action: String) {
        when (action) {
            "panel"          -> toggle()
            "notifications"  -> expandStatusBar(quick = false)
            "quick_settings" -> expandStatusBar(quick = true)
            "all_apps"       -> { if (!shown && !showingDrawer) showAllAppsDrawer() }
        }
    }

    private fun expandStatusBar(quick: Boolean) {
        try {
            val sbm = getSystemService("statusbar")
            val method = if (quick) "expandSettingsPanel" else "expandNotificationsPanel"
            sbm?.javaClass?.getMethod(method)?.invoke(sbm)
        } catch (e: Exception) { Log.w(TAG, "expandStatusBar($quick) failed", e) }
    }

    private fun vibrate() {
        if (!vibrationEnabled) return
        val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION") vib.vibrate(30)
        }
    }

    private fun toggle() {
        vibrate()
        if (shown) hidePanel() else showPanel()
    }

    // ── Panel ─────────────────────────────────────────────────────────────────

    private fun showPanel() {
        if (shown) return
        shown = true
        val prefs = pillPrefs()
        val oPrefs = overlayPrefs()
        val pkgs = loadFavorites()
        val pm = packageManager
        val light = prefs.theme == "light"
        val effectiveSide = if (prefs.side == "both") lastActiveSide else prefs.side

        val hasQC = oPrefs.quickControlsEnabled &&
            (oPrefs.showTorch || oPrefs.showAutoRotate || oPrefs.showAutoBrightness || oPrefs.showRingerMode ||
             oPrefs.showBrightnessSlider || oPrefs.showVolumeSlider || oPrefs.showQuickShare ||
             oPrefs.showPower || oPrefs.showQr || oPrefs.showDnd)
        val hasActions = oPrefs.quickControlsEnabled && (oPrefs.showAllApps || oPrefs.showEdit)
        val hasControls = hasQC || hasActions

        val screenHeight = resources.displayMetrics.heightPixels
        val maxPanelHeight = (screenHeight * 0.72).toInt()
        val cols = 2
        val rows = if (pkgs.isEmpty()) 1 else (pkgs.size + 1) / 2
        val rowH = if (oPrefs.showLabels) dp(82) else dp(68)
        val numCtrlRows = (if (hasQC) listOf(
            oPrefs.showTorch || oPrefs.showAutoRotate,
            oPrefs.showAutoBrightness || oPrefs.showRingerMode || oPrefs.showQuickShare,
            oPrefs.showPower || oPrefs.showQr || oPrefs.showDnd,
            oPrefs.showBrightnessSlider,
            oPrefs.showVolumeSlider
        ).count { it } else 0) + (if (hasActions) 1 else 0)
        val controlsH = if (hasControls) dp(8) + numCtrlRows * dp(60) + (numCtrlRows - 1) * dp(6) + dp(8) + dp(6) else 0
        val appsH = dp(4) + rows * rowH + dp(16)
        val panelHeight = (dp(24) + controlsH + appsH).coerceAtMost(maxPanelHeight)

        val overlay = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { hidePanel() }
        }
        wm.addView(overlay, WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ))
        dismissOverlay = overlay

        // M3 color tokens
        val panelBg    = resolvePanelBg(prefs.panelColor, 248, light)
        val controlsBg = resolveControlsBg(panelBg, prefs.panelColor, light)
        val effectiveLight = if (prefs.panelColor.isNotEmpty()) {
            val hsv = FloatArray(3); Color.colorToHSV(panelBg, hsv); hsv[2] > 0.5f
        } else light
        val tileBg     = if (effectiveLight) Color.argb(130, 115, 105, 130) else Color.argb(130, 55, 52, 62)
        val tileActive = if (effectiveLight) Color.argb(255, 103, 80, 164)  else Color.argb(255, 79, 55, 139)
        val indClr     = if (effectiveLight) Color.argb(60, 0, 0, 0)        else Color.argb(80, 255, 255, 255)
        val labelColor = if (effectiveLight) Color.argb(230, 28, 27, 31)    else Color.argb(230, 230, 225, 229)
        val emptyColor = if (effectiveLight) Color.argb(160, 73, 69, 79)    else Color.argb(160, 202, 196, 208)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(panelBg)
                cornerRadius = dp(22).toFloat()
            }
            elevation = dp(8).toFloat()
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
        }

        // Quick controls strip — different background, sits above the favorites grid
        val controlsStrip = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(controlsBg)
                cornerRadius = dp(16).toFloat()
            }
            setPadding(dp(6), dp(8), dp(6), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8); marginEnd = dp(8); topMargin = dp(6); bottomMargin = dp(6) }
        }

        if (hasQC) {
            // Swipe down on the strip when expanded → collapse
            val stripGd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true
                override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                    if (e1 == null) return false
                    if (e2.y - e1.y > dp(16) && controlsExpanded) { animateControls(); return true }
                    return false
                }
            })
            controlsStrip.setOnTouchListener { _, event -> stripGd.onTouchEvent(event) }

            if (oPrefs.showBrightnessSlider) {
                controlsStrip.addView(makeSliderRow(
                    "\uE430", getBrightness(), 0, 255, labelColor, tileActive, tileBg
                ) { setBrightness(it) }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            if (oPrefs.showVolumeSlider) {
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                controlsStrip.addView(makeSliderRow(
                    "\uE050", am.getStreamVolume(AudioManager.STREAM_MUSIC),
                    0, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                    labelColor, tileActive, tileBg
                ) { v ->
                    try { am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) }
                    catch (e: Exception) { Log.w(TAG, "setStreamVolume failed", e) }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { if (oPrefs.showBrightnessSlider) topMargin = dp(6) })
            }

            val ctrlRow1 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { if (controlsStrip.childCount > 0) topMargin = dp(6) }
            }
            if (oPrefs.showTorch) ctrlRow1.addView(makeControlTile("Torch", { "\uEA0B" }, tileBg, tileActive,
                { torchEnabled }, { if (torchEnabled) "On" else "Off" }, stripGd) { toggleTorch() })
            if (oPrefs.showAutoRotate) ctrlRow1.addView(makeControlTile("Rotate", { "\uE1C1" }, tileBg, tileActive,
                { isAutoRotateEnabled() }, { if (isAutoRotateEnabled()) "On" else "Off" }, stripGd) { toggleAutoRotate() })
            if (ctrlRow1.childCount > 0) controlsStrip.addView(ctrlRow1)

            val ctrlRow2 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            }
            if (oPrefs.showAutoBrightness) ctrlRow2.addView(makeControlTile("Brightness", { "\uE1AB" }, tileBg, tileActive,
                { isAutoBrightnessEnabled() }, { if (isAutoBrightnessEnabled()) "Auto" else "Manual" }, stripGd) { toggleAutoBrightness() })
            if (oPrefs.showRingerMode) ctrlRow2.addView(makeControlTile("Ringer", { getRingerIcon() }, tileBg, tileActive,
                { isRingerActive() }, { getRingerSubtitle() }, stripGd) { toggleRingerMode() })
            if (oPrefs.showQuickShare) ctrlRow2.addView(makeControlTile("Share", { "\uE80D" }, tileBg, tileActive,
                { false }, { "" }, stripGd) { launchQuickShare() })
            if (ctrlRow2.childCount > 0) controlsStrip.addView(ctrlRow2)

            val ctrlRow3 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            }
            if (oPrefs.showPower) ctrlRow3.addView(makeControlTile("Power", { "\uE8AC" }, tileBg, tileActive,
                { false }, { "" }, stripGd) { openPowerMenu() })
            if (oPrefs.showQr) ctrlRow3.addView(makeControlTile("QR Scan", { "\uF206" }, tileBg, tileActive,
                { false }, { "" }, stripGd) { launchQrScanner() })
            if (oPrefs.showDnd) ctrlRow3.addView(makeControlTile("DND", { if (isDndEnabled()) "\uE644" else "\uE7F4" }, tileBg, tileActive,
                { isDndEnabled() }, { if (isDndEnabled()) "On" else "Off" }, stripGd) { toggleDnd() })
            if (ctrlRow3.childCount > 0) controlsStrip.addView(ctrlRow3)
        }
        if (hasActions) {
            val actionsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { if (hasQC && controlsStrip.childCount > 0) topMargin = dp(6) }
            }
            if (oPrefs.showAllApps) actionsRow.addView(makeControlTile("All Apps", { "\uE5C3" }, tileBg, tileActive,
                { false }, { "" }) { showAllAppsDrawer() })
            if (oPrefs.showEdit) actionsRow.addView(makeControlTile("Edit", { "\uE3C9" }, tileBg, tileActive,
                { false }, { "" }) {
                hidePanel()
                getSharedPreferences(Prefs.FILE, MODE_PRIVATE).edit().putString(Prefs.LAUNCH_TAB, "apps").apply()
                packageManager.getLaunchIntentForPackage(packageName)
                    ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT) }
                    ?.let { startActivity(it) }
            })
            if (actionsRow.childCount > 0) controlsStrip.addView(actionsRow)
        }

        // Favorites app grid
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(16))
        }

        if (pkgs.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "No favorites yet.\nOpen the Sidebar app\nto choose your apps."
                setTextColor(emptyColor)
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(20), dp(16), dp(20))
            })
        } else {
            var row: LinearLayout? = null
            for ((index, pkg) in pkgs.withIndex()) {
                if (index % cols == 0) {
                    row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    }
                    container.addView(row)
                }
                val cached = appIconCache[pkg]
                if (cached != null) {
                    row?.addView(makeAppCell(pkg, cached.first, cached.second, labelColor, oPrefs.showLabels))
                } else {
                    try {
                        val info = pm.getApplicationInfo(pkg, 0)
                        val name = pm.getApplicationLabel(info).toString()
                        val icon = pm.getApplicationIcon(pkg)
                        row?.addView(makeAppCell(pkg, name, icon, labelColor, oPrefs.showLabels))
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to load app info for $pkg", e)
                        row?.addView(View(this).apply {
                            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                        })
                    }
                }
            }
            val rem = pkgs.size % cols
            if (rem != 0) {
                repeat(cols - rem) {
                    row?.addView(View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                }
            }
        }

        scroll.addView(container)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        if (hasControls) {
            if (hasQC) {
                ctrlFullH = controlsH - dp(6)
                controlsExpanded = false
                val wrapper = FrameLayout(this).apply {
                    clipChildren = true
                    clipToPadding = true
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0)
                }
                (controlsStrip.layoutParams as LinearLayout.LayoutParams).bottomMargin = 0
                wrapper.addView(controlsStrip)
                ctrlWrapperView = wrapper
                root.addView(wrapper)
            } else {
                root.addView(controlsStrip)
            }
        }

        if (hasQC && hasControls) {
            val chevronTv = TextView(this).apply {
                text = "Swipe up"
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(indClr)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(22))
            }
            ctrlChevronView = chevronTv

            val handle = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            handle.addView(chevronTv)
            handle.addView(TextView(this).apply {
                text = "Controls"
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(indClr)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(24))
            })

            val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true
                override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                    if (e1 == null) return false
                    val dy = e2.y - e1.y
                    if (Math.abs(dy) < dp(16)) return false
                    if (dy < 0 && !controlsExpanded) { animateControls(); return true }
                    if (dy > 0 && controlsExpanded)  { animateControls(); return true }
                    return false
                }
            })
            handle.setOnTouchListener { _, event -> gd.onTouchEvent(event) }
            root.addView(handle)
        }

        val handleCenterY = (prefs.position * screenHeight).toInt()
        val panelY = (handleCenterY - panelHeight / 2)
            .coerceIn(0, screenHeight - panelHeight)
        val panelGravity = if (effectiveSide == "left") Gravity.START or Gravity.TOP
                           else Gravity.END or Gravity.TOP
        wm.addView(root, WindowManager.LayoutParams(
            dp(200), panelHeight,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = panelGravity; x = dp(8); y = panelY })

        panelView = root
        handleView?.visibility = View.INVISIBLE
        handleViewAlt?.visibility = View.INVISIBLE

        val slideFrom = if (effectiveSide == "left") -dp(216).toFloat() else dp(216).toFloat()
        root.translationX = slideFrom
        root.alpha = 0f
        root.scaleX = 0.94f
        root.scaleY = 0.94f
        root.animate().withLayer()
            .translationX(0f).alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(260).setInterpolator(OvershootInterpolator(1.1f))
            .start()
    }

    private fun animateControls() {
        val wrapper = ctrlWrapperView ?: return
        val chevron = ctrlChevronView
        val fromH = if (controlsExpanded) ctrlFullH else 0
        val toH   = if (controlsExpanded) 0 else ctrlFullH
        ValueAnimator.ofInt(fromH, toH).apply {
            duration = 220
            interpolator = if (controlsExpanded) AccelerateInterpolator(1.4f) else DecelerateInterpolator(1.4f)
            addUpdateListener { anim ->
                val h = anim.animatedValue as Int
                (wrapper.layoutParams as LinearLayout.LayoutParams).height = h
                wrapper.requestLayout()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    controlsExpanded = !controlsExpanded
                    chevron?.text = if (controlsExpanded) "∨" else "∧"
                }
            })
            start()
        }
    }

    private fun hidePanel() {
        if (!shown) return
        shown = false
        ctrlWrapperView = null
        ctrlChevronView = null
        controlsExpanded = false
        val panel = panelView ?: run {
            dismissOverlay?.let { runCatching { wm.removeViewImmediate(it) } }
            dismissOverlay = null
            handleView?.visibility = View.VISIBLE
            handleViewAlt?.visibility = View.VISIBLE
            return
        }
        panelView = null
        val prefs = pillPrefs()
        val effectiveSide = if (prefs.side == "both") lastActiveSide else prefs.side
        val overlay = dismissOverlay
        dismissOverlay = null
        val handle = handleView
        val handleAlt = handleViewAlt

        val slideTo = if (effectiveSide == "left") -dp(216).toFloat() else dp(216).toFloat()
        panel.animate().withLayer()
            .translationX(slideTo).alpha(0f).scaleX(0.94f).scaleY(0.94f)
            .setDuration(180).setInterpolator(AccelerateInterpolator(1.5f))
            .withEndAction {
                panel.alpha = 0f
                runCatching { wm.removeViewImmediate(panel) }
                overlay?.let { runCatching { wm.removeViewImmediate(it) } }
                if (!showingDrawer) {
                    handle?.visibility = View.VISIBLE
                    handleAlt?.visibility = View.VISIBLE
                }
            }
            .start()
    }

    // ── All Apps drawer ───────────────────────────────────────────────────────

    private fun showAllAppsDrawer() {
        showingDrawer = true
        hidePanel()
        vibrate()
        val p = pillPrefs()
        val light = p.theme == "light"
        val dm = resources.displayMetrics
        val drawerW = (dm.widthPixels * 0.8).toInt()
        val drawerH = (dm.heightPixels * 0.72).toInt()

        val panelBg    = resolvePanelBg(p.panelColor, 252, light)
        val effectiveLight = if (p.panelColor.isNotEmpty()) {
            val hsv = FloatArray(3); Color.colorToHSV(panelBg, hsv); hsv[2] > 0.5f
        } else light
        val tileBg     = if (effectiveLight) Color.argb(130, 115, 105, 130) else Color.argb(130, 55, 52, 62)
        val labelColor = if (effectiveLight) Color.argb(140, 28, 27, 31)    else Color.argb(140, 230, 225, 229)
        val indClr     = if (effectiveLight) Color.argb(60, 0, 0, 0)        else Color.argb(80, 255, 255, 255)

        val dismissView = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { hideAllAppsDrawer() }
        }
        wm.addView(dismissView, WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ))
        allAppsDismissOverlay = dismissView

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(panelBg); cornerRadius = dp(24).toFloat() }
            elevation = dp(8).toFloat()
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
        }

        // Header row
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        header.addView(TextView(this).apply {
            text = "All Apps"
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(labelColor)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(this).apply {
            text = "✕"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(labelColor)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply { setColor(tileBg); cornerRadius = dp(20).toFloat() }
            isClickable = true; isFocusable = true
            setOnClickListener { hideAllAppsDrawer() }
        })
        root.addView(header)

        root.addView(View(this).apply {
            setBackgroundColor(indClr)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        })

        // Search bar
        val hintColor = Color.argb(120, Color.red(labelColor), Color.green(labelColor), Color.blue(labelColor))
        val searchBox = android.widget.EditText(this).apply {
            hint = "Search apps"
            textSize = 14f
            setTextColor(labelColor)
            setHintTextColor(hintColor)
            background = GradientDrawable().apply { setColor(tileBg); cornerRadius = dp(20).toFloat() }
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(12), dp(8), dp(12), dp(8)) }
        }
        root.addView(searchBox)

        // Scrollable grid
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(16))
        }
        grid.addView(TextView(this).apply {
            text = "Loading…"
            setTextColor(labelColor)
            gravity = Gravity.CENTER
            setPadding(0, dp(40), 0, dp(40))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        scroll.addView(grid)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        wm.addView(root, WindowManager.LayoutParams(
            drawerW, drawerH,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER })
        allAppsView = root

        root.alpha = 0f; root.scaleX = 0.92f; root.scaleY = 0.92f
        root.animate().withLayer().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(240).setInterpolator(OvershootInterpolator(1.0f)).start()

        val pm = packageManager
        val appList = mutableListOf<Triple<String, String, Drawable>>()

        fun rebuildGrid(query: String) {
            val filtered = if (query.isEmpty()) appList
                           else appList.filter { it.second.lowercase().contains(query.lowercase()) }
            grid.removeAllViews()
            if (filtered.isEmpty()) {
                grid.addView(TextView(this).apply {
                    text = if (query.isEmpty()) "" else "No results"
                    setTextColor(labelColor)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(40), 0, dp(40))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                })
                return
            }
            var row: LinearLayout? = null
            for ((i, app) in filtered.withIndex()) {
                if (i % 4 == 0) {
                    row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    }
                    grid.addView(row)
                }
                val (pkg, name, icon) = app
                row?.addView(makeAppCell(pkg, name, icon, labelColor, true) {
                    hideAllAppsDrawer()
                    doLaunch(pkg)
                })
            }
            val rem = filtered.size % 4
            if (rem != 0) repeat(4 - rem) {
                row?.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
            }
        }

        searchBox.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { rebuildGrid(s?.toString() ?: "") }
        })

        val snapshot = cachedAllApps
        if (snapshot != null) {
            appList.addAll(snapshot)
            rebuildGrid(searchBox.text.toString())
        } else {
            appLoadExecutor.submit {
                try {
                    val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
                    @Suppress("DEPRECATION")
                    val rawApps: List<ResolveInfo> = pm.queryIntentActivities(intent, PackageManager.GET_META_DATA)
                    val entries = rawApps.sortedBy { it.loadLabel(pm).toString().lowercase() }.mapNotNull { app ->
                        val pkg = app.activityInfo.packageName
                        val name = app.loadLabel(pm).toString()
                        try { Triple(pkg, name, pm.getApplicationIcon(pkg)) }
                        catch (e: Exception) { Log.w(TAG, "No icon for $pkg", e); null }
                    }
                    Handler(Looper.getMainLooper()).post {
                        if (allAppsView == null) return@post
                        appList.addAll(entries)
                        rebuildGrid(searchBox.text.toString())
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load app list", e)
                }
            }
        }
    }

    private fun hideAllAppsDrawer() {
        val drawer = allAppsView ?: return
        allAppsView = null
        showingDrawer = false
        val dismiss = allAppsDismissOverlay
        allAppsDismissOverlay = null
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(drawer.windowToken, 0)
        handleView?.visibility = View.VISIBLE
        handleViewAlt?.visibility = View.VISIBLE
        drawer.animate().withLayer().alpha(0f).scaleX(0.92f).scaleY(0.92f)
            .setDuration(160).setInterpolator(AccelerateInterpolator(1.5f))
            .withEndAction {
                runCatching { wm.removeViewImmediate(drawer) }
                dismiss?.let { runCatching { wm.removeViewImmediate(it) } }
            }
            .start()
    }

    // ── Control tile helpers ──────────────────────────────────────────────────

    private fun makeControlTile(
        label: String,
        getIcon: () -> String,
        bgColor: Int, activeColor: Int,
        getActive: () -> Boolean,
        getSubtitle: () -> String,
        swipeGd: GestureDetector? = null,
        onClick: () -> Unit
    ): LinearLayout {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, dp(60), 1f).apply {
                marginStart = dp(3); marginEnd = dp(3)
            }
            isClickable = true
            isFocusable = true
        }

        val iconTv = TextView(this).apply {
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = iconTypeface
        }
        val subtitleTv = TextView(this).apply {
            textSize = 9f
            gravity = Gravity.CENTER
            setTextColor(Color.argb(180, 255, 255, 255))
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(1) }
        }

        fun update() {
            tile.background = GradientDrawable().apply {
                setColor(if (getActive()) activeColor else bgColor)
                cornerRadius = dp(12).toFloat()
            }
            iconTv.text = getIcon()
            subtitleTv.text = getSubtitle()
        }
        update()

        tile.setOnClickListener {
            onClick()
            Handler(Looper.getMainLooper()).postDelayed({ update() }, 120)
        }
        var touchDownX = 0f; var touchDownY = 0f; var touchSwiped = false
        tile.setOnTouchListener { _, event ->
            swipeGd?.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownX = event.rawX; touchDownY = event.rawY; touchSwiped = false
                    tile.animate().withLayer().scaleX(0.90f).scaleY(0.90f).setDuration(80).start()
                }
                MotionEvent.ACTION_MOVE ->
                    if (Math.abs(event.rawX - touchDownX) > dp(8) ||
                        Math.abs(event.rawY - touchDownY) > dp(8)) touchSwiped = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    tile.animate().withLayer().scaleX(1f).scaleY(1f).setDuration(160)
                        .setInterpolator(OvershootInterpolator(1.8f)).start()
            }
            touchSwiped
        }

        tile.addView(iconTv)
        tile.addView(TextView(this).apply {
            text = label
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
        })
        tile.addView(subtitleTv)
        return tile
    }

    // ── Control tile state & toggles ──────────────────────────────────────────

    private fun isAutoRotateEnabled(): Boolean = try {
        Settings.System.getInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION) == 1
    } catch (_: Settings.SettingNotFoundException) { false }

    private fun isAutoBrightnessEnabled(): Boolean = try {
        Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) ==
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    } catch (_: Settings.SettingNotFoundException) { false }

    private fun toggleTorch() {
        val cid = torchCameraId ?: return
        try { cameraManager?.setTorchMode(cid, !torchEnabled) }
        catch (e: Exception) { Log.w(TAG, "Torch toggle failed", e) }
    }

    private fun toggleAutoRotate() {
        if (!Settings.System.canWrite(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:$packageName"))
                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            return
        }
        Settings.System.putInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION,
            if (isAutoRotateEnabled()) 0 else 1)
    }

    private fun toggleAutoBrightness() {
        if (!Settings.System.canWrite(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:$packageName"))
                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            return
        }
        val new = if (isAutoBrightnessEnabled()) Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                  else Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, new)
    }

    // ── Ringer helpers ────────────────────────────────────────────────────────

    private fun getRingerMode(): Int =
        (getSystemService(AUDIO_SERVICE) as AudioManager).ringerMode

    private fun getRingerIcon(): String = when (getRingerMode()) {
        AudioManager.RINGER_MODE_SILENT  -> "\uE7F6"  // notifications_off
        AudioManager.RINGER_MODE_VIBRATE -> "\uE62D"  // vibration
        else                             -> "\uE7F4"  // notifications
    }

    private fun getRingerSubtitle(): String = when (getRingerMode()) {
        AudioManager.RINGER_MODE_SILENT  -> "Silent"
        AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"
        else                             -> "Normal"
    }

    private fun isRingerActive(): Boolean =
        getRingerMode() != AudioManager.RINGER_MODE_NORMAL

    private fun toggleRingerMode() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val next = when (am.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL  -> AudioManager.RINGER_MODE_VIBRATE
            AudioManager.RINGER_MODE_VIBRATE ->
                if (nm.isNotificationPolicyAccessGranted) AudioManager.RINGER_MODE_SILENT
                else AudioManager.RINGER_MODE_NORMAL
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        try { am.ringerMode = next } catch (e: Exception) { Log.w(TAG, "Ringer toggle failed", e) }
    }

    private fun launchQuickShare() {
        hidePanel()
        val candidates = listOf(
            Intent("com.google.android.gms.RECEIVE_NEARBY").setType("*/*"),
            Intent("com.google.android.gms.nearby.sharing.UNIFIED").setType("*/*"),
            Intent().setComponent(android.content.ComponentName(
                "com.google.android.gms",
                "com.google.android.gms.nearby.sharing.main.MainActivity"
            )),
            Intent("android.settings.NEARBY_SHARING_SETTINGS")
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { startActivity(intent); Log.d(TAG, "QuickShare launched: $intent"); return }
            catch (e: Exception) { Log.w(TAG, "QuickShare intent failed: $intent — ${e.message}") }
        }
    }

    private fun openPowerMenu() {
        hidePanel()
        val svc = SidebarAccessibilityService.instance
        if (svc != null) svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
        else startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    }

    private fun launchQrScanner() {
        hidePanel()
        val candidates = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("zxing://scan/")),
            Intent().setComponent(ComponentName("com.google.ar.lens",
                "com.google.vr.apps.ornament.app.lens.LensLauncherActivity")),
            Intent().setComponent(ComponentName("com.oppo.camera",
                "com.oppo.camera.activity.OppoCameraActivity")),
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { startActivity(intent); return }
            catch (e: Exception) { Log.w(TAG, "QR intent failed: ${e.message}") }
        }
    }

    private fun isDndEnabled(): Boolean {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        return nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    }

    private fun toggleDnd() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            return
        }
        val next = if (isDndEnabled()) NotificationManager.INTERRUPTION_FILTER_ALL
                   else NotificationManager.INTERRUPTION_FILTER_NONE
        try { nm.setInterruptionFilter(next) } catch (e: Exception) { Log.w(TAG, "DND toggle failed", e) }
    }

    // ── Brightness helpers ────────────────────────────────────────────────────

    private fun getBrightness(): Int = try {
        Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    } catch (_: Settings.SettingNotFoundException) { 127 }

    private fun setBrightness(value: Int) {
        if (!Settings.System.canWrite(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:$packageName"))
                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            return
        }
        Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
    }

    // ── Slider row ────────────────────────────────────────────────────────────

    private fun makeSliderRow(
        icon: String,
        initial: Int, min: Int, max: Int,
        iconColor: Int,
        accentColor: Int,
        trackBgColor: Int,
        onChange: (Int) -> Unit
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        row.addView(TextView(this).apply {
            text = icon
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(iconColor)
            typeface = iconTypeface
            layoutParams = LinearLayout.LayoutParams(dp(28), LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        val trackH = dp(10)
        val r = trackH / 2f
        val bgGd = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE; cornerRadius = r; setColor(trackBgColor)
            setSize(-1, trackH)
        }
        val fillGd = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE; cornerRadius = r; setColor(accentColor)
            setSize(-1, trackH)
        }
        val trackDrawable = LayerDrawable(arrayOf(bgGd, ClipDrawable(fillGd, Gravity.START, ClipDrawable.HORIZONTAL))).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
        val seekBar = SeekBar(this).apply {
            this.max = max - min
            progress = (initial - min).coerceIn(0, max - min)
            progressDrawable = trackDrawable
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            splitTrack = false
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                marginStart = dp(6)
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) onChange(progress + min)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        row.addView(seekBar)
        return row
    }

    // ── App cell ──────────────────────────────────────────────────────────────

    private fun makeAppCell(
        pkg: String, name: String, icon: Drawable,
        labelColor: Int = Color.WHITE, showLabel: Boolean = true,
        onClick: () -> Unit = { launch(pkg) }
    ): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(6), dp(10), dp(6), dp(10))
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
            setOnLongClickListener { showAppContextMenu(pkg, name, it); true }
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN ->
                        animate().withLayer().scaleX(0.85f).scaleY(0.85f).setDuration(80).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        animate().withLayer().scaleX(1f).scaleY(1f).setDuration(160)
                            .setInterpolator(OvershootInterpolator(2f)).start()
                }
                false
            }
        }
        cell.addView(ImageView(this).apply {
            setImageDrawable(icon)
            val size = dp(48)
            layoutParams = LinearLayout.LayoutParams(size, size)
            scaleType = ImageView.ScaleType.FIT_CENTER
        })
        if (showLabel) {
            cell.addView(TextView(this).apply {
                text = name
                setTextColor(labelColor)
                textSize = 11f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(5) }
            })
        }
        return cell
    }

    private fun launch(pkg: String) {
        hidePanel()
        doLaunch(pkg)
    }

    private fun doLaunch(pkg: String) {
        vibrate()
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            Log.w(TAG, "No launch intent for $pkg")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, "Can't open this app", Toast.LENGTH_SHORT).show()
            }
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch $pkg", e)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, "Failed to launch app", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showAppContextMenu(pkg: String, name: String, anchor: View) {
        hideContextMenu()
        val prefs = pillPrefs()
        val light = prefs.theme == "light"
        val panelBg = resolvePanelBg(prefs.panelColor, 252, light)
        val effectiveLight = if (prefs.panelColor.isNotEmpty()) {
            val hsv = FloatArray(3); Color.colorToHSV(panelBg, hsv); hsv[2] > 0.5f
        } else light
        val tileBg = if (effectiveLight) Color.argb(130, 115, 105, 130) else Color.argb(130, 55, 52, 62)
        val labelColor = if (effectiveLight) Color.argb(140, 28, 27, 31) else Color.argb(140, 230, 225, 229)

        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val dm = resources.displayMetrics
        val menuW = dp(180)
        var mx = loc[0] + anchor.width / 2 - menuW / 2
        var my = loc[1] + anchor.height / 2
        mx = mx.coerceIn(dp(8), dm.widthPixels - menuW - dp(8))
        my = my.coerceIn(dp(8), dm.heightPixels - dp(140))

        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(panelBg); cornerRadius = dp(16).toFloat() }
            elevation = dp(10).toFloat()
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        menu.addView(TextView(this).apply {
            text = name
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.argb(160, Color.red(labelColor), Color.green(labelColor), Color.blue(labelColor)))
            setPadding(dp(14), dp(10), dp(14), dp(6))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        menu.addView(View(this).apply {
            setBackgroundColor(Color.argb(30, 0, 0, 0))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                setMargins(dp(8), dp(2), dp(8), dp(2))
            }
        })
        fun item(label: String, action: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(labelColor)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isClickable = true; isFocusable = true
            background = GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = dp(12).toFloat() }
            setOnClickListener { hideContextMenu(); action() }
        }
        menu.addView(item("Open") { doLaunch(pkg) })
        val freeformEnabled = packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT) ||
            Settings.Global.getInt(contentResolver, "enable_freeform_support", 0) == 1
        if (freeformEnabled) {
            menu.addView(item("Freeform") { hidePanel(); doLaunchFreeform(pkg) })
        }

        val container = FrameLayout(this).apply {
            setOnClickListener { hideContextMenu() }
            addView(menu, FrameLayout.LayoutParams(menuW, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = mx; topMargin = my
            })
        }
        wm.addView(container, WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ))
        contextMenuView = container
        menu.alpha = 0f; menu.scaleX = 0.88f; menu.scaleY = 0.88f
        menu.animate().withLayer().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(180).setInterpolator(OvershootInterpolator(1.5f)).start()
    }

    private fun hideContextMenu() {
        contextMenuView?.let { runCatching { wm.removeView(it) } }
        contextMenuView = null
    }

    private fun doLaunchFreeform(pkg: String) {
        vibrate()
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: run {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, "Can't open this app", Toast.LENGTH_SHORT).show()
            }
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        val opts = ActivityOptions.makeBasic()
        try {
            ActivityOptions::class.java
                .getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                .invoke(opts, 5) // WINDOWING_MODE_FREEFORM = 5
        } catch (_: Exception) {}
        val (sw, sh) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
        val w = (sw * 0.75f).toInt()
        val h = (sh * 0.65f).toInt()
        val left = (sw - w) / 2
        val top = (sh - h) / 4
        opts.launchBounds = Rect(left, top, left + w, top + h)
        try {
            startActivity(intent, opts.toBundle())
        } catch (e: Exception) {
            Log.e(TAG, "Freeform launch failed for $pkg", e)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, "Freeform not supported", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun prefetchAppData() {
        try {
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            @Suppress("DEPRECATION")
            val entries = pm.queryIntentActivities(intent, PackageManager.GET_META_DATA)
                .sortedBy { it.loadLabel(pm).toString().lowercase() }
                .mapNotNull { app ->
                    val pkg = app.activityInfo.packageName
                    val name = app.loadLabel(pm).toString()
                    try {
                        val icon = pm.getApplicationIcon(pkg)
                        appIconCache[pkg] = Pair(name, icon)
                        Triple(pkg, name, icon)
                    } catch (e: Exception) { null }
                }
            cachedAllApps = entries
        } catch (e: Exception) {
            Log.e(TAG, "Prefetch failed", e)
        }
    }

    private fun loadFavorites(): List<String> {
        val json = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
            .getString(Prefs.FAVORITES, "[]") ?: "[]"
        return buildList {
            try {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) add(arr.getString(i))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse favorites JSON", e)
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun isSystemFullscreen(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = wm.currentWindowMetrics.windowInsets
                .getInsets(android.view.WindowInsets.Type.systemBars())
            insets.top == 0 && insets.bottom == 0
        } else {
            val real = resources.displayMetrics.heightPixels
            val app = DisplayMetrics().also {
                @Suppress("DEPRECATION") wm.defaultDisplay.getMetrics(it)
            }
            app.heightPixels >= real
        }
    } catch (_: Exception) { false }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel("sidebar_svc", "Floating Panel", NotificationManager.IMPORTANCE_MIN)
            ch.setShowBadge(false)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, "sidebar_svc")
            .setContentTitle("Floating Panel")
            .setContentText("Pull-tab active on screen edge")
            .setSmallIcon(android.R.drawable.ic_menu_sort_by_size)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private inner class PullTabDrawable(
        private val highlighted: Boolean,
        private val theme: String = "dark",
        private val handleColor: String = ""
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (handleColor.isNotEmpty()) {
                runCatching {
                    val base = Color.parseColor(handleColor)
                    val alpha = if (highlighted) 230 else 210
                    Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base))
                }.getOrElse { if (highlighted) Color.argb(230, 100, 100, 100) else Color.argb(210, 100, 100, 100) }
            } else when {
                highlighted && theme == "light" -> Color.argb(230, 160, 160, 160)
                highlighted                     -> Color.argb(230, 80, 80, 80)
                theme == "light"                -> Color.argb(210, 255, 251, 254)
                else                            -> Color.argb(210, 28, 27, 31)
            }
        }
        private val rect = RectF()
        override fun draw(canvas: Canvas) {
            rect.set(bounds)
            canvas.drawRoundRect(rect, rect.width() / 2f, rect.width() / 2f, paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf; invalidateSelf() }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    companion object {
        private const val TAG = "SidebarOverlayService"
    }
}
