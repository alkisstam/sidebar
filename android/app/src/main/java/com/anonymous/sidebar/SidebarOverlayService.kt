package com.anonymous.sidebar

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
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
        runCatching { unregisterReceiver(screenReceiver) }
        Handler(Looper.getMainLooper()).post {
            handleView?.let { runCatching { wm.removeView(it) } }
            panelView?.let { runCatching { wm.removeView(it) } }
            dismissOverlay?.let { runCatching { wm.removeView(it) } }
        }
    }

    private fun refreshHandle() {
        handleView?.let { runCatching { wm.removeView(it) } }
        handleView = null
        addHandle()
    }

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    data class PillPrefs(
        val height: Int,
        val width: Int,
        val position: Float,
        val side: String,
        val opacity: Float,
        val theme: String
    )

    private fun pillPrefs(): PillPrefs {
        val p = getSharedPreferences("sidebar_prefs", MODE_PRIVATE)
        return PillPrefs(
            p.getInt("pill_height", 80),
            p.getInt("pill_width", 36),
            p.getFloat("pill_position", 0.5f),
            p.getString("pill_side", "right") ?: "right",
            p.getFloat("pill_opacity", 1.0f),
            p.getString("pill_theme", "dark") ?: "dark"
        )
    }

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
        val handle = View(this).apply {
            background = PullTabDrawable(highlighted = false, prefs.theme)
            alpha = prefs.opacity
        }
        installSwipeListener(handle)
        wm.addView(handle, handleParams(prefs))
        handleView = handle
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            handle.post {
                handle.systemGestureExclusionRects = listOf(
                    android.graphics.Rect(0, 0, handle.width, handle.height)
                )
            }
        }
    }

    private fun installSwipeListener(handle: View) {
        var downX = 0f
        var downY = 0f
        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (Math.abs(dx) >= dp(16) && Math.abs(dx) > Math.abs(dy)) {
                        toggle()
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> true
                else -> false
            }
        }
    }

    private fun toggle() {
        if (shown) hidePanel() else showPanel()
    }

    // ── Drag mode ────────────────────────────────────────────────────────────

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
                    val newPos = ((params.y + params.height / 2f) / screenHeight)
                        .coerceIn(0.05f, 0.95f)
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
        handle.background = PullTabDrawable(highlighted = false, prefs.theme)
        handle.alpha = prefs.opacity
        handle.setOnTouchListener(null)
        installSwipeListener(handle)
    }

    // ── Panel ─────────────────────────────────────────────────────────────────

    private fun showPanel() {
        if (shown) return
        shown = true
        val pkgs = loadFavorites()
        val pm = packageManager
        val prefs = pillPrefs()

        val screenHeight = resources.displayMetrics.heightPixels
        val maxPanelHeight = (screenHeight * 0.72).toInt()
        val rows = if (pkgs.isEmpty()) 1 else (pkgs.size + 1) / 2
        val contentHeight = dp(34) + dp(8) + rows * dp(82) + dp(16)
        val panelHeight = contentHeight.coerceAtMost(maxPanelHeight)

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

        val dragIndicator = View(this).apply {
            background = GradientDrawable().apply {
                setColor(indicatorColor)
                cornerRadius = dp(2).toFloat()
            }
            isLongClickable = true
            val lp = LinearLayout.LayoutParams(dp(32), dp(4))
            lp.gravity = Gravity.CENTER_HORIZONTAL
            lp.topMargin = dp(10)
            lp.bottomMargin = dp(10)
            layoutParams = lp
            setOnLongClickListener {
                hidePanel()
                Handler(Looper.getMainLooper()).postDelayed({ enterDragMode() }, 300)
                true
            }
        }
        root.addView(dragIndicator)

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(16))
        }

        val labelColor = if (light) Color.argb(255, 30, 30, 30) else Color.WHITE
        val emptyColor = if (light) Color.argb(160, 50, 50, 50) else Color.argb(160, 255, 255, 255)

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
                    row?.addView(makeAppCell(pkg, name, icon, labelColor))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load app info for $pkg", e)
                    row?.addView(View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                    })
                }
            }
            if (pkgs.size % 2 != 0) {
                row?.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
            }
        }

        scroll.addView(container)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val panelGravity = if (prefs.side == "left") Gravity.START or Gravity.CENTER_VERTICAL
                           else Gravity.END or Gravity.CENTER_VERTICAL
        val params = WindowManager.LayoutParams(
            dp(200), panelHeight,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = panelGravity
            x = dp(8)
        }

        wm.addView(root, params)
        panelView = root
        handleView?.visibility = View.INVISIBLE

        // Slide + fade in
        val slideFrom = if (prefs.side == "left") -dp(216).toFloat() else dp(216).toFloat()
        root.translationX = slideFrom
        root.alpha = 0f
        root.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator())
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
            .translationX(slideTo)
            .alpha(0f)
            .setDuration(180)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                // Zero alpha explicitly before removal to prevent the one-frame
                // flash that occurs when the animator releases its property hold
                panel.alpha = 0f
                runCatching { wm.removeViewImmediate(panel) }
                overlay?.let { runCatching { wm.removeViewImmediate(it) } }
                if (!wasDrag) handle?.visibility = View.VISIBLE
            }
            .start()
    }

    private fun makeAppCell(pkg: String, name: String, icon: Drawable, labelColor: Int = Color.WHITE): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(6), dp(10), dp(6), dp(10))
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { launch(pkg) }
        }
        val iconView = ImageView(this).apply {
            setImageDrawable(icon)
            val size = dp(48)
            layoutParams = LinearLayout.LayoutParams(size, size)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val label = TextView(this).apply {
            text = name
            setTextColor(labelColor)
            textSize = 11f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(5)
            layoutParams = lp
        }
        cell.addView(iconView)
        cell.addView(label)
        return cell
    }

    private fun launch(pkg: String) {
        hidePanel()
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
            val r = rect.width() / 2f
            canvas.drawRoundRect(rect, r, r, paint)
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
