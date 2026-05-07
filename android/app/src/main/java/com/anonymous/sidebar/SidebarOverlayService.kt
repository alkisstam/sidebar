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
import android.widget.*
import androidx.core.app.NotificationCompat
import org.json.JSONArray

class SidebarOverlayService : Service() {

    private enum class DragState { IDLE, DRAGGING }

    private lateinit var wm: WindowManager
    private var handleView: View? = null
    private var panelView: View? = null
    private var dismissOverlay: View? = null
    private var shown = false
    private var dragState = DragState.IDLE

    // Fullscreen detection
    private val fsHandler = Handler(Looper.getMainLooper())
    private val fsRunnable = object : Runnable {
        override fun run() {
            val handle = handleView
            if (handle != null && !shown && dragState == DragState.IDLE) {
                handle.visibility = if (isSystemFullscreen()) View.INVISIBLE else View.VISIBLE
            }
            fsHandler.postDelayed(this, 500)
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

    // Control page status bar
    private var ctrlTimeView: TextView? = null
    private var ctrlBattView: TextView? = null
    private val statusHandler = Handler(Looper.getMainLooper())
    private val statusRunnable = object : Runnable {
        override fun run() {
            updateStatus()
            statusHandler.postDelayed(this, 10_000)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!Settings.canDrawOverlays(context)) return
            Handler(Looper.getMainLooper()).post {
                val detached = handleView?.windowToken == null
                if (detached) addHandle()
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
        }
        registerReceiver(screenReceiver, filter)

        // Init torch
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
            dp(prefs.width), pillHeight,
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
        val handle = View(this).apply {
            background = PullTabDrawable(highlighted = false, prefs.theme)
            alpha = prefs.opacity
        }
        installSwipeListener(handle, oPrefs.sensitivity)
        wm.addView(handle, handleParams(prefs))
        handleView = handle
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            handle.post {
                handle.systemGestureExclusionRects = listOf(
                    android.graphics.Rect(0, 0, handle.width, handle.height)
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
                    if (Math.abs(dx) >= dp(sensitivityDp) && Math.abs(dx) > Math.abs(dy)) {
                        toggle()
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> true
                else -> false
            }
        }
    }

    private fun vibrate() {
        if (!overlayPrefs().vibration) return
        val vib = getSystemService(VIBRATOR_SERVICE) as? Vibrator ?: return
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
        handle.background = PullTabDrawable(highlighted = true, prefs.theme)
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
        handle.background = PullTabDrawable(highlighted = false, prefs.theme)
        handle.alpha = prefs.opacity
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

        val screenHeight = resources.displayMetrics.heightPixels
        val maxPanelHeight = (screenHeight * 0.72).toInt()
        val rows = if (pkgs.isEmpty()) 1 else (pkgs.size + 1) / 2
        val rowH = if (oPrefs.showLabels) dp(82) else dp(68)
        val favH = dp(8) + rows * rowH + dp(16)
        val ctrlH = dp(8) + dp(28) + dp(4) + dp(76) * 3 + dp(8) * 2 + dp(16)
        val headerH = dp(44) // drag indicator + dots
        val panelHeight = (headerH + maxOf(favH, ctrlH)).coerceAtMost(maxPanelHeight)

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

        val light = prefs.theme == "light"
        val panelBg = if (light) Color.argb(245, 240, 240, 245) else Color.argb(245, 18, 18, 32)
        val indicatorColor = if (light) Color.argb(60, 0, 0, 0) else Color.argb(80, 255, 255, 255)
        val dotActive = if (light) Color.argb(200, 30, 30, 30) else Color.WHITE
        val dotInactive = if (light) Color.argb(60, 30, 30, 30) else Color.argb(60, 255, 255, 255)

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
        val dragIndicator = View(this).apply {
            background = GradientDrawable().apply {
                setColor(indicatorColor)
                cornerRadius = dp(2).toFloat()
            }
            isLongClickable = true
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(10)
                bottomMargin = dp(6)
            }
            setOnLongClickListener {
                hidePanel()
                Handler(Looper.getMainLooper()).postDelayed({ enterDragMode() }, 300)
                true
            }
        }
        root.addView(dragIndicator)

        // Page dots
        val dot1 = makeDot(dotActive)
        val dot2 = makeDot(dotInactive)
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(16)
            ).apply { bottomMargin = dp(4) }
            addView(dot1)
            addView(dot2)
        })

        // Pager
        val pagerWidth = dp(200)
        val pager = FrameLayout(this).apply { clipChildren = true }

        val favPage = buildFavoritesPage(pkgs, prefs, oPrefs.showLabels)
        pager.addView(favPage, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val ctrlPage = buildControlPage(prefs)
        ctrlPage.translationX = pagerWidth.toFloat()
        pager.addView(ctrlPage, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        root.addView(pager, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // Swipe to switch pages
        var currentPage = 0
        val gesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vX: Float, vY: Float): Boolean {
                if (Math.abs(vX) <= Math.abs(vY) * 1.2f) return false
                if (vX < 0 && currentPage == 0) {
                    currentPage = 1
                    favPage.animate().translationX(-pagerWidth.toFloat()).setDuration(200).start()
                    ctrlPage.animate().translationX(0f).setDuration(200).start()
                    dot1.background = makeDotDrawable(dotInactive)
                    dot2.background = makeDotDrawable(dotActive)
                } else if (vX > 0 && currentPage == 1) {
                    currentPage = 0
                    favPage.animate().translationX(0f).setDuration(200).start()
                    ctrlPage.animate().translationX(pagerWidth.toFloat()).setDuration(200).start()
                    dot1.background = makeDotDrawable(dotActive)
                    dot2.background = makeDotDrawable(dotInactive)
                }
                return true
            }
        })
        root.setOnTouchListener { _, event -> gesture.onTouchEvent(event); false }

        val panelGravity = if (prefs.side == "left") Gravity.START or Gravity.CENTER_VERTICAL
                           else Gravity.END or Gravity.CENTER_VERTICAL
        val params = WindowManager.LayoutParams(
            dp(200), panelHeight,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = panelGravity; x = dp(8) }

        wm.addView(root, params)
        panelView = root
        handleView?.visibility = View.INVISIBLE
        updateStatus()
        statusHandler.post(statusRunnable)

        val slideFrom = if (prefs.side == "left") -dp(216).toFloat() else dp(216).toFloat()
        root.translationX = slideFrom
        root.alpha = 0f
        root.animate()
            .translationX(0f).alpha(1f)
            .setDuration(220).setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun hidePanel() {
        if (!shown) return
        shown = false
        statusHandler.removeCallbacks(statusRunnable)
        ctrlTimeView = null
        ctrlBattView = null
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
            .translationX(slideTo).alpha(0f)
            .setDuration(180).setInterpolator(AccelerateInterpolator())
            .withEndAction {
                panel.alpha = 0f
                runCatching { wm.removeViewImmediate(panel) }
                overlay?.let { runCatching { wm.removeViewImmediate(it) } }
                if (!wasDrag) handle?.visibility = View.VISIBLE
            }
            .start()
    }

    // ── Panel pages ───────────────────────────────────────────────────────────

    private fun makeDot(color: Int) = View(this).apply {
        background = makeDotDrawable(color)
        layoutParams = LinearLayout.LayoutParams(dp(6), dp(6)).apply {
            marginStart = dp(3); marginEnd = dp(3)
        }
    }

    private fun makeDotDrawable(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun buildFavoritesPage(pkgs: List<String>, prefs: PillPrefs, showLabels: Boolean): ScrollView {
        val light = prefs.theme == "light"
        val labelColor = if (light) Color.argb(255, 30, 30, 30) else Color.WHITE
        val emptyColor = if (light) Color.argb(160, 50, 50, 50) else Color.argb(160, 255, 255, 255)
        val pm = packageManager

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
                setPadding(dp(16), dp(40), dp(16), dp(40))
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
                    row?.addView(makeAppCell(pkg, name, icon, labelColor, showLabels))
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
        return scroll
    }

    private fun buildControlPage(prefs: PillPrefs): LinearLayout {
        val light = prefs.theme == "light"
        val tileBg = if (light) Color.argb(200, 200, 200, 210) else Color.argb(200, 40, 40, 60)
        val tileActive = Color.argb(220, 0, 122, 255)
        val statusColor = if (light) Color.argb(220, 20, 20, 20) else Color.argb(220, 255, 255, 255)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(16))
        }

        // Status bar: time (left) + battery (right)
        val timeView = TextView(this).apply {
            textSize = 12f
            setTextColor(statusColor)
        }
        val battView = TextView(this).apply {
            textSize = 12f
            setTextColor(statusColor)
        }
        ctrlTimeView = timeView
        ctrlBattView = battView
        page.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(28)
            ).apply { bottomMargin = dp(4) }
            addView(timeView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(4)
            })
            addView(battView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(4) })
        })

        val row1 = tileRow()
        row1.addView(makeControlTile("Torch", { "⚡" }, tileBg, tileActive,
            { torchEnabled }, { if (torchEnabled) "On" else "Off" }) { toggleTorch() })
        row1.addView(makeControlTile("Do Not Disturb", { "🌙" }, tileBg, tileActive,
            { isDndEnabled() }, { if (isDndEnabled()) "On" else "Off" }) { toggleDnd() })
        page.addView(row1)

        val row2 = tileRow().apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
        }
        row2.addView(makeControlTile("Auto-rotate", { "↻" }, tileBg, tileActive,
            { isAutoRotateEnabled() }, { if (isAutoRotateEnabled()) "On" else "Off" }) { toggleAutoRotate() })
        row2.addView(makeControlTile("Auto-bright", { "☀" }, tileBg, tileActive,
            { isAutoBrightnessEnabled() }, { if (isAutoBrightnessEnabled()) "On" else "Off" }) { toggleAutoBrightness() })
        page.addView(row2)

        val row3 = tileRow().apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
        }
        row3.addView(makeControlTile("Ringer", { getRingerIcon() }, tileBg, tileActive,
            { isRingerActive() }, { getRingerSubtitle() }) { toggleRingerMode() })
        row3.addView(makeControlTile("Sleep", { "⏱" }, tileBg, tileActive,
            { isScreenTimeoutLong() }, { getScreenTimeoutSubtitle() }) { toggleScreenTimeout() })
        page.addView(row3)

        return page
    }

    private fun tileRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

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
            layoutParams = LinearLayout.LayoutParams(0, dp(76), 1f).apply {
                marginStart = dp(4); marginEnd = dp(4)
            }
            isClickable = true
            isFocusable = true
        }

        val iconTv = TextView(this).apply {
            textSize = 22f
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
                cornerRadius = dp(14).toFloat()
            }
            iconTv.text = getIcon()
            subtitleTv.text = getSubtitle()
        }
        update()

        tile.setOnClickListener {
            onClick()
            Handler(Looper.getMainLooper()).postDelayed({ update() }, 120)
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
            ).apply { topMargin = dp(3) }
        })
        tile.addView(subtitleTv)
        return tile
    }

    // ── Control tile state & toggles ──────────────────────────────────────────

    private fun isDndEnabled(): Boolean {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        return nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    }

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

    private fun toggleDnd() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            return
        }
        val new = if (isDndEnabled()) NotificationManager.INTERRUPTION_FILTER_ALL
                  else NotificationManager.INTERRUPTION_FILTER_PRIORITY
        nm.setInterruptionFilter(new)
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

    // ── Ring mode helpers ─────────────────────────────────────────────────────

    private fun getRingerMode(): Int =
        (getSystemService(AUDIO_SERVICE) as AudioManager).ringerMode

    private fun getRingerIcon(): String = when (getRingerMode()) {
        AudioManager.RINGER_MODE_SILENT -> "🔕"
        AudioManager.RINGER_MODE_VIBRATE -> "📳"
        else -> "🔔"
    }

    private fun getRingerSubtitle(): String = when (getRingerMode()) {
        AudioManager.RINGER_MODE_SILENT -> "Silent"
        AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"
        else -> "Ring"
    }

    private fun isRingerActive(): Boolean =
        getRingerMode() != AudioManager.RINGER_MODE_NORMAL

    private fun toggleRingerMode() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val next = when (am.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
            AudioManager.RINGER_MODE_VIBRATE ->
                if (nm.isNotificationPolicyAccessGranted) AudioManager.RINGER_MODE_SILENT
                else AudioManager.RINGER_MODE_NORMAL
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        try { am.ringerMode = next } catch (e: Exception) { Log.w(TAG, "Ringer toggle failed", e) }
    }

    // ── Screen timeout helpers ────────────────────────────────────────────────

    private fun isScreenTimeoutLong(): Boolean = try {
        Settings.System.getInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT) >= 300_000
    } catch (_: Settings.SettingNotFoundException) { false }

    private fun getScreenTimeoutSubtitle(): String = try {
        val ms = Settings.System.getInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT)
        if (ms < 60_000) "${ms / 1000}s" else "${ms / 60_000}min"
    } catch (_: Settings.SettingNotFoundException) { "?" }

    private fun toggleScreenTimeout() {
        if (!Settings.System.canWrite(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:$packageName"))
                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            return
        }
        val current = try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT)
        } catch (_: Settings.SettingNotFoundException) { 30_000 }
        Settings.System.putInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT,
            if (current >= 300_000) 30_000 else 1_800_000)
    }

    // ── Status bar updater ────────────────────────────────────────────────────

    private fun updateStatus() {
        val timeStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date())
        ctrlTimeView?.text = timeStr

        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        ctrlBattView?.text = if (level >= 0 && scale > 0) "${level * 100 / scale}%" else ""
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

    // ── Notification / helpers ────────────────────────────────────────────────

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
            val ch = NotificationChannel("sidebar_svc", "Sidebar", NotificationManager.IMPORTANCE_MIN)
            ch.setShowBadge(false)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, "sidebar_svc")
            .setContentTitle("Sidebar")
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
                highlighted -> Color.argb(230, 110, 110, 255)
                theme == "light" -> Color.argb(210, 220, 220, 220)
                else -> Color.argb(210, 60, 60, 190)
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
