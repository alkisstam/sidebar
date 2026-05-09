package com.anonymous.sidebar

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
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

class SidebarOverlayService : Service() {

    private enum class DragState { IDLE, DRAGGING }

    private lateinit var wm: WindowManager
    private var handleView: View? = null
    private var pillInnerView: View? = null
    private var panelView: View? = null
    private var dismissOverlay: View? = null
    private var shown = false
    private var dragState = DragState.IDLE
    private var vibrationEnabled = true

    // Fullscreen detection — only active when auto-hide-fullscreen pref is on.
    // 2 s interval is plenty; tighter polling prevents deep-sleep unnecessarily.
    private val fsHandler = Handler(Looper.getMainLooper())
    private val fsRunnable = object : Runnable {
        override fun run() {
            val handle = handleView
            if (handle != null && !shown && dragState == DragState.IDLE) {
                handle.visibility = if (isSystemFullscreen()) View.INVISIBLE else View.VISIBLE
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
                    val autoHide = getSharedPreferences("sidebar_prefs", MODE_PRIVATE)
                        .getBoolean("auto_hide_fullscreen", false)
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
        runCatching { unregisterReceiver(screenReceiver) }
        torchCameraId?.let { runCatching { cameraManager?.unregisterTorchCallback(torchCallback) } }
        Handler(Looper.getMainLooper()).post {
            handleView?.let { runCatching { wm.removeView(it) } }
            panelView?.let { runCatching { wm.removeView(it) } }
            dismissOverlay?.let { runCatching { wm.removeView(it) } }
        }
    }

    private fun refreshHandle() {
        fsHandler.removeCallbacks(fsRunnable)
        handleView?.let { runCatching { wm.removeView(it) } }
        handleView = null
        pillInnerView = null
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
        val side: String, val opacity: Float, val theme: String
    )

    data class OverlayPrefs(
        val autoHideFullscreen: Boolean,
        val showLabels: Boolean,
        val vibration: Boolean,
        val sensitivity: Int
    )

    private fun pillPrefs(): PillPrefs {
        val p = getSharedPreferences("sidebar_prefs", MODE_PRIVATE)
        return PillPrefs(
            p.getInt("pill_height", 80), p.getInt("pill_width", 36),
            p.getFloat("pill_position", 0.5f),
            p.getString("pill_side", "right") ?: "right",
            p.getFloat("pill_opacity", 1.0f),
            p.getString("pill_theme", "dark") ?: "dark"
        )
    }

    private fun overlayPrefs(): OverlayPrefs {
        val p = getSharedPreferences("sidebar_prefs", MODE_PRIVATE)
        return OverlayPrefs(
            p.getBoolean("auto_hide_fullscreen", false),
            p.getBoolean("show_labels", true),
            p.getBoolean("vibration", true),
            p.getInt("swipe_sensitivity", 16)
        )
    }

    // ── Handle ────────────────────────────────────────────────────────────────

    private fun handleParams(prefs: PillPrefs): WindowManager.LayoutParams {
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
            gravity = if (prefs.side == "left") Gravity.START or Gravity.TOP
                      else Gravity.END or Gravity.TOP
            x = dp(2)
            y = yPos
        }
    }

    private fun addHandle() {
        val prefs = pillPrefs()
        val oPrefs = overlayPrefs()
        vibrationEnabled = oPrefs.vibration
        val pill = View(this).apply {
            background = PullTabDrawable(highlighted = false, prefs.theme)
            alpha = prefs.opacity
        }
        val pillGravity = if (prefs.side == "left") Gravity.START else Gravity.END
        val container = FrameLayout(this).apply {
            addView(pill, FrameLayout.LayoutParams(dp(prefs.width), FrameLayout.LayoutParams.MATCH_PARENT).apply {
                gravity = pillGravity
            })
        }
        installSwipeListener(container, oPrefs.sensitivity)
        wm.addView(container, handleParams(prefs))
        handleView = container
        pillInnerView = pill
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            container.post {
                container.systemGestureExclusionRects = listOf(
                    android.graphics.Rect(0, 0, container.width, container.height)
                )
            }
        }
        if (oPrefs.autoHideFullscreen) {
            fsHandler.removeCallbacks(fsRunnable)
            fsHandler.post(fsRunnable)
        }
    }

    private fun installSwipeListener(handle: View, sensitivityDp: Int = 16) {
        var downX = 0f
        var downY = 0f
        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; true }
                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (Math.abs(dx) >= dp(sensitivityDp) && Math.abs(dx) > Math.abs(dy)) toggle()
                    true
                }
                MotionEvent.ACTION_MOVE -> true
                else -> false
            }
        }
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

    // ── Drag mode ─────────────────────────────────────────────────────────────

    private fun enterDragMode() {
        dragState = DragState.DRAGGING
        val handle = handleView ?: return
        val prefs = pillPrefs()
        pillInnerView?.background = PullTabDrawable(highlighted = true, prefs.theme)
        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_MOVE -> {
                    val params = handle.layoutParams as WindowManager.LayoutParams
                    val screenHeight = resources.displayMetrics.heightPixels
                    params.y = (event.rawY.toInt() - params.height / 2)
                        .coerceIn(0, screenHeight - params.height)
                    runCatching { wm.updateViewLayout(handle, params) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val params = handle.layoutParams as WindowManager.LayoutParams
                    val screenHeight = resources.displayMetrics.heightPixels
                    val newPos = ((params.y + params.height / 2f) / screenHeight).coerceIn(0.05f, 0.95f)
                    getSharedPreferences("sidebar_prefs", MODE_PRIVATE)
                        .edit().putFloat("pill_position", newPos).apply()
                    exitDragMode()
                    true
                }
                else -> false
            }
        }
    }

    private fun exitDragMode() {
        dragState = DragState.IDLE
        val handle = handleView ?: return
        val prefs = pillPrefs()
        val oPrefs = overlayPrefs()
        pillInnerView?.background = PullTabDrawable(highlighted = false, prefs.theme)
        pillInnerView?.alpha = prefs.opacity
        handle.setOnTouchListener(null)
        installSwipeListener(handle, oPrefs.sensitivity)
    }

    // ── Panel ─────────────────────────────────────────────────────────────────

    private fun showPanel() {
        if (shown) return
        shown = true
        vibrate()
        val prefs = pillPrefs()
        val oPrefs = overlayPrefs()
        val pkgs = loadFavorites()
        val pm = packageManager
        val light = prefs.theme == "light"

        val qcPrefs = getSharedPreferences("sidebar_prefs", MODE_PRIVATE)
        val quickControlsEnabled = qcPrefs.getBoolean("quick_controls_enabled", true)
        val showTorch = qcPrefs.getBoolean("show_torch", true)
        val showAutoRotate = qcPrefs.getBoolean("show_auto_rotate", true)
        val showAutoBrightness = qcPrefs.getBoolean("show_auto_brightness", true)
        val showRingerMode = qcPrefs.getBoolean("show_ringer_mode", true)
        val qcAny = quickControlsEnabled && (showTorch || showAutoRotate || showAutoBrightness || showRingerMode)

        val screenHeight = resources.displayMetrics.heightPixels
        val maxPanelHeight = (screenHeight * 0.72).toInt()
        val rows = if (pkgs.isEmpty()) 1 else (pkgs.size + 1) / 2
        val rowH = if (oPrefs.showLabels) dp(82) else dp(68)
        // Controls strip: top+bottom padding + 2 tile rows + gap between rows
        val controlsH = if (qcAny) dp(8) + dp(60) + dp(6) + dp(60) + dp(8) + dp(6) else 0
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
        val panelBg    = if (light) Color.argb(248, 255, 251, 254) else Color.argb(248, 28, 27, 31)
        val controlsBg = if (light) Color.argb(255, 237, 232, 242) else Color.argb(255, 43, 41, 48)
        val tileBg     = if (light) Color.argb(220, 231, 224, 236) else Color.argb(220, 55, 52, 62)
        val tileActive = if (light) Color.argb(255, 103, 80, 164)  else Color.argb(255, 79, 55, 139)
        val indClr     = if (light) Color.argb(60, 0, 0, 0)        else Color.argb(80, 255, 255, 255)
        val labelColor = if (light) Color.argb(230, 28, 27, 31)    else Color.argb(230, 230, 225, 229)
        val emptyColor = if (light) Color.argb(160, 73, 69, 79)    else Color.argb(160, 202, 196, 208)

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

        // Drag indicator
        root.addView(View(this).apply {
            background = GradientDrawable().apply {
                setColor(indClr)
                cornerRadius = dp(2).toFloat()
            }
            isLongClickable = true
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(10)
                bottomMargin = dp(10)
            }
            setOnLongClickListener {
                hidePanel()
                Handler(Looper.getMainLooper()).postDelayed({ enterDragMode() }, 300)
                true
            }
        })

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
            ).apply { marginStart = dp(8); marginEnd = dp(8); bottomMargin = dp(6) }
        }

        if (quickControlsEnabled) {
            val ctrlRow1 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            if (showTorch) ctrlRow1.addView(makeControlTile("Torch", { "⚡" }, tileBg, tileActive,
                { torchEnabled }, { if (torchEnabled) "On" else "Off" }) { toggleTorch() })
            if (showAutoRotate) ctrlRow1.addView(makeControlTile("Rotate", { "↻" }, tileBg, tileActive,
                { isAutoRotateEnabled() }, { if (isAutoRotateEnabled()) "On" else "Off" }) { toggleAutoRotate() })
            if (ctrlRow1.childCount > 0) controlsStrip.addView(ctrlRow1)

            val ctrlRow2 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            }
            if (showAutoBrightness) ctrlRow2.addView(makeControlTile("Brightness", { "☀" }, tileBg, tileActive,
                { isAutoBrightnessEnabled() }, { if (isAutoBrightnessEnabled()) "Auto" else "Manual" }) { toggleAutoBrightness() })
            if (showRingerMode) ctrlRow2.addView(makeControlTile("Ringer", { getRingerIcon() }, tileBg, tileActive,
                { isRingerActive() }, { getRingerSubtitle() }) { toggleRingerMode() })
            if (ctrlRow2.childCount > 0) controlsStrip.addView(ctrlRow2)
        }
        if (qcAny) root.addView(controlsStrip)

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
                if (index % 2 == 0) {
                    row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    }
                    container.addView(row)
                }
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
            if (pkgs.size % 2 != 0) {
                row?.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
            }
        }

        scroll.addView(container)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val panelGravity = if (prefs.side == "left") Gravity.START or Gravity.CENTER_VERTICAL
                           else Gravity.END or Gravity.CENTER_VERTICAL
        wm.addView(root, WindowManager.LayoutParams(
            dp(200), panelHeight,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = panelGravity; x = dp(8) })

        panelView = root
        handleView?.visibility = View.INVISIBLE

        val slideFrom = if (prefs.side == "left") -dp(216).toFloat() else dp(216).toFloat()
        root.translationX = slideFrom
        root.alpha = 0f
        root.scaleX = 0.94f
        root.scaleY = 0.94f
        root.animate()
            .translationX(0f).alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(320).setInterpolator(OvershootInterpolator(1.1f))
            .start()
    }

    private fun hidePanel() {
        if (!shown) return
        shown = false
        val panel = panelView ?: run {
            dismissOverlay?.let { runCatching { wm.removeViewImmediate(it) } }
            dismissOverlay = null
            if (dragState == DragState.IDLE) handleView?.visibility = View.VISIBLE
            return
        }
        panelView = null
        val side = pillPrefs().side
        val overlay = dismissOverlay
        dismissOverlay = null
        val handle = handleView
        val wasDrag = dragState == DragState.DRAGGING

        val slideTo = if (side == "left") -dp(216).toFloat() else dp(216).toFloat()
        panel.animate()
            .translationX(slideTo).alpha(0f).scaleX(0.94f).scaleY(0.94f)
            .setDuration(200).setInterpolator(AccelerateInterpolator(1.5f))
            .withEndAction {
                panel.alpha = 0f
                runCatching { wm.removeViewImmediate(panel) }
                overlay?.let { runCatching { wm.removeViewImmediate(it) } }
                if (!wasDrag) handle?.visibility = View.VISIBLE
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
        tile.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    tile.animate().scaleX(0.90f).scaleY(0.90f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    tile.animate().scaleX(1f).scaleY(1f).setDuration(200)
                        .setInterpolator(OvershootInterpolator(1.8f)).start()
            }
            false
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
        AudioManager.RINGER_MODE_SILENT  -> "🔕"
        AudioManager.RINGER_MODE_VIBRATE -> "📳"
        else                             -> "🔔"
    }

    private fun getRingerSubtitle(): String = when (getRingerMode()) {
        AudioManager.RINGER_MODE_SILENT  -> "Silent"
        AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"
        else                             -> "Ring"
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

    // ── App cell ──────────────────────────────────────────────────────────────

    private fun makeAppCell(
        pkg: String, name: String, icon: Drawable,
        labelColor: Int = Color.WHITE, showLabel: Boolean = true
    ): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(6), dp(10), dp(6), dp(10))
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { launch(pkg) }
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN ->
                        animate().scaleX(0.85f).scaleY(0.85f).setDuration(80).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        animate().scaleX(1f).scaleY(1f).setDuration(200)
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

    private fun loadFavorites(): List<String> {
        val json = getSharedPreferences("sidebar_prefs", MODE_PRIVATE)
            .getString("favorites", "[]") ?: "[]"
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
        private val theme: String = "dark"
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = when {
                highlighted && theme == "light" -> Color.argb(230, 160, 160, 160)
                highlighted                     -> Color.argb(230, 110, 110, 255)
                theme == "light"                -> Color.argb(210, 220, 220, 220)
                else                            -> Color.argb(210, 60, 60, 190)
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
