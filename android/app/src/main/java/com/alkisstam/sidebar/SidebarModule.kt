package com.alkisstam.sidebar

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.util.LruCache
import com.facebook.react.bridge.*
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class SidebarModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private val iconCache = LruCache<String, String>(200)
    private val executor = Executors.newSingleThreadExecutor()

    override fun getName() = "SidebarModule"

    @ReactMethod
    fun getInstalledApps(promise: Promise) {
        executor.submit {
            try {
                val pm = reactContext.packageManager
                val intent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val apps = pm.queryIntentActivities(intent, PackageManager.GET_META_DATA)
                    .sortedBy { it.loadLabel(pm).toString().lowercase() }

                val result = WritableNativeArray()
                for (app in apps) {
                    val map = WritableNativeMap()
                    map.putString("name", app.loadLabel(pm).toString())
                    map.putString("packageName", app.activityInfo.packageName)
                    map.putString("icon", cachedIcon(pm, app))
                    result.pushMap(map)
                }
                promise.resolve(result)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get installed apps", e)
                promise.reject("ERR_GET_APPS", e.message, e)
            }
        }
    }

    private fun cachedIcon(pm: PackageManager, app: ResolveInfo): String {
        val pkg = app.activityInfo.packageName
        val lastUpdate = try { pm.getPackageInfo(pkg, 0).lastUpdateTime } catch (_: Exception) { 0L }
        val key = "$pkg@$lastUpdate"
        return iconCache.get(key) ?: drawableToBase64(app.loadIcon(pm)).also { iconCache.put(key, it) }
    }

    @ReactMethod
    fun saveFavorites(packages: ReadableArray, promise: Promise) {
        val arr = JSONArray()
        for (i in 0 until packages.size()) arr.put(packages.getString(i))
        reactContext.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
            .edit().putString(Prefs.FAVORITES, arr.toString()).apply()
        promise.resolve(null)
    }

    @ReactMethod
    fun getFavorites(promise: Promise) {
        val json = reactContext.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
            .getString(Prefs.FAVORITES, "[]")
        val arr = WritableNativeArray()
        try {
            val jArr = JSONArray(json)
            for (i in 0 until jArr.length()) arr.pushString(jArr.getString(i))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse favorites JSON", e)
        }
        promise.resolve(arr)
    }

    @ReactMethod
    fun hasOverlayPermission(promise: Promise) {
        promise.resolve(Settings.canDrawOverlays(reactContext))
    }

    @ReactMethod
    fun requestOverlayPermission(promise: Promise) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${reactContext.packageName}")
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        reactContext.startActivity(intent)
        promise.resolve(null)
    }

    @ReactMethod
    fun startService(promise: Promise) {
        reactContext.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(Prefs.SERVICE_ENABLED, true).apply()
        val intent = Intent(reactContext, SidebarOverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            reactContext.startForegroundService(intent)
        } else {
            reactContext.startService(intent)
        }
        promise.resolve(null)
    }

    @ReactMethod
    fun stopService(promise: Promise) {
        reactContext.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(Prefs.SERVICE_ENABLED, false).apply()
        reactContext.stopService(Intent(reactContext, SidebarOverlayService::class.java))
        promise.resolve(null)
    }

    @ReactMethod
    fun isServiceEnabled(promise: Promise) {
        val enabled = reactContext.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
            .getBoolean(Prefs.SERVICE_ENABLED, false)
        promise.resolve(enabled)
    }

    @ReactMethod
    fun savePillSettings(settings: ReadableMap, promise: Promise) {
        val prefs = reactContext.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE).edit()
        if (settings.hasKey("height")) prefs.putInt(Prefs.PILL_HEIGHT, settings.getInt("height"))
        if (settings.hasKey("width")) prefs.putInt(Prefs.PILL_WIDTH, settings.getInt("width"))
        if (settings.hasKey("position")) prefs.putFloat(Prefs.PILL_POSITION, settings.getDouble("position").toFloat())
        if (settings.hasKey("side")) prefs.putString(Prefs.PILL_SIDE, settings.getString("side"))
        if (settings.hasKey("opacity")) prefs.putFloat(Prefs.PILL_OPACITY, settings.getDouble("opacity").toFloat())
        if (settings.hasKey("theme")) prefs.putString(Prefs.PILL_THEME, settings.getString("theme"))
        if (settings.hasKey("themeChoice")) prefs.putString(Prefs.THEME_CHOICE, settings.getString("themeChoice"))
        if (settings.hasKey("panelColor")) prefs.putString(Prefs.PANEL_COLOR, settings.getString("panelColor") ?: "")
        prefs.apply()
        val serviceEnabled = reactContext.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
            .getBoolean(Prefs.SERVICE_ENABLED, false)
        if (serviceEnabled) {
            val intent = Intent(reactContext, SidebarOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                reactContext.startForegroundService(intent)
            } else {
                reactContext.startService(intent)
            }
        }
        promise.resolve(null)
    }

    @ReactMethod
    fun getPillSettings(promise: Promise) {
        val prefs = reactContext.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
        val map = WritableNativeMap()
        map.putInt("height", prefs.getInt(Prefs.PILL_HEIGHT, 80))
        map.putInt("width", prefs.getInt(Prefs.PILL_WIDTH, 36))
        map.putDouble("position", prefs.getFloat(Prefs.PILL_POSITION, 0.5f).toDouble())
        map.putString("side", prefs.getString(Prefs.PILL_SIDE, "right") ?: "right")
        map.putDouble("opacity", prefs.getFloat(Prefs.PILL_OPACITY, 1.0f).toDouble())
        map.putString("theme", prefs.getString(Prefs.PILL_THEME, "dark") ?: "dark")
        val savedThemeChoice = prefs.getString(Prefs.THEME_CHOICE, null)
            ?: prefs.getString(Prefs.PILL_THEME, "dark") ?: "dark"
        map.putString("themeChoice", savedThemeChoice)
        map.putString("panelColor", prefs.getString(Prefs.PANEL_COLOR, "") ?: "")
        promise.resolve(map)
    }

    @ReactMethod
    fun getOverlaySettings(promise: Promise) {
        val prefs = reactContext.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
        val map = WritableNativeMap()
        map.putBoolean("autoHideFullscreen", prefs.getBoolean(Prefs.AUTO_HIDE_FULLSCREEN, false))
        map.putBoolean("showLabels", prefs.getBoolean(Prefs.SHOW_LABELS, true))
        map.putBoolean("vibration", prefs.getBoolean(Prefs.VIBRATION, true))
        map.putInt("sensitivity", prefs.getInt(Prefs.SWIPE_SENSITIVITY, 16))
        map.putBoolean("quickControlsEnabled", prefs.getBoolean(Prefs.QUICK_CONTROLS_ENABLED, true))
        map.putBoolean("showTorch", prefs.getBoolean(Prefs.SHOW_TORCH, true))
        map.putBoolean("showAutoRotate", prefs.getBoolean(Prefs.SHOW_AUTO_ROTATE, true))
        map.putBoolean("showAutoBrightness", prefs.getBoolean(Prefs.SHOW_AUTO_BRIGHTNESS, true))
        map.putBoolean("showRingerMode", prefs.getBoolean(Prefs.SHOW_RINGER_MODE, true))
        map.putBoolean("showAllApps", prefs.getBoolean(Prefs.SHOW_ALL_APPS, true))
        map.putBoolean("showEdit", prefs.getBoolean(Prefs.SHOW_EDIT, true))
        map.putBoolean("gesturesEnabled", prefs.getBoolean(Prefs.GESTURES_ENABLED, true))
        map.putString("gestureSwipeIn", prefs.getString(Prefs.GESTURE_SWIPE_IN, "panel") ?: "panel")
        map.putString("gestureSwipeUp", prefs.getString(Prefs.GESTURE_SWIPE_UP, "none") ?: "none")
        map.putString("gestureSwipeDown", prefs.getString(Prefs.GESTURE_SWIPE_DOWN, "notifications") ?: "notifications")
        map.putString("gestureDoubleTap", prefs.getString(Prefs.GESTURE_DOUBLE_TAP, "none") ?: "none")
        map.putBoolean("showBrightnessSlider", prefs.getBoolean(Prefs.SHOW_BRIGHTNESS_SLIDER, false))
        map.putBoolean("showVolumeSlider", prefs.getBoolean(Prefs.SHOW_VOLUME_SLIDER, false))
        map.putBoolean("showQuickShare", prefs.getBoolean(Prefs.SHOW_QUICK_SHARE, false))
        map.putBoolean("showPower", prefs.getBoolean(Prefs.SHOW_POWER, false))
        map.putBoolean("showQr", prefs.getBoolean(Prefs.SHOW_QR, false))
        map.putBoolean("showDnd", prefs.getBoolean(Prefs.SHOW_DND, false))
        promise.resolve(map)
    }

    @ReactMethod
    fun saveOverlaySettings(settings: ReadableMap, promise: Promise) {
        val prefs = reactContext.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE).edit()
        if (settings.hasKey("autoHideFullscreen")) prefs.putBoolean(Prefs.AUTO_HIDE_FULLSCREEN, settings.getBoolean("autoHideFullscreen"))
        if (settings.hasKey("showLabels")) prefs.putBoolean(Prefs.SHOW_LABELS, settings.getBoolean("showLabels"))
        if (settings.hasKey("vibration")) prefs.putBoolean(Prefs.VIBRATION, settings.getBoolean("vibration"))
        if (settings.hasKey("sensitivity")) prefs.putInt(Prefs.SWIPE_SENSITIVITY, settings.getInt("sensitivity"))
        if (settings.hasKey("quickControlsEnabled")) prefs.putBoolean(Prefs.QUICK_CONTROLS_ENABLED, settings.getBoolean("quickControlsEnabled"))
        if (settings.hasKey("showTorch")) prefs.putBoolean(Prefs.SHOW_TORCH, settings.getBoolean("showTorch"))
        if (settings.hasKey("showAutoRotate")) prefs.putBoolean(Prefs.SHOW_AUTO_ROTATE, settings.getBoolean("showAutoRotate"))
        if (settings.hasKey("showAutoBrightness")) prefs.putBoolean(Prefs.SHOW_AUTO_BRIGHTNESS, settings.getBoolean("showAutoBrightness"))
        if (settings.hasKey("showRingerMode")) prefs.putBoolean(Prefs.SHOW_RINGER_MODE, settings.getBoolean("showRingerMode"))
        if (settings.hasKey("showAllApps")) prefs.putBoolean(Prefs.SHOW_ALL_APPS, settings.getBoolean("showAllApps"))
        if (settings.hasKey("showEdit")) prefs.putBoolean(Prefs.SHOW_EDIT, settings.getBoolean("showEdit"))
        if (settings.hasKey("gesturesEnabled")) prefs.putBoolean(Prefs.GESTURES_ENABLED, settings.getBoolean("gesturesEnabled"))
        if (settings.hasKey("gestureSwipeIn")) prefs.putString(Prefs.GESTURE_SWIPE_IN, settings.getString("gestureSwipeIn"))
        if (settings.hasKey("gestureSwipeUp")) prefs.putString(Prefs.GESTURE_SWIPE_UP, settings.getString("gestureSwipeUp"))
        if (settings.hasKey("gestureSwipeDown")) prefs.putString(Prefs.GESTURE_SWIPE_DOWN, settings.getString("gestureSwipeDown"))
        if (settings.hasKey("gestureDoubleTap")) prefs.putString(Prefs.GESTURE_DOUBLE_TAP, settings.getString("gestureDoubleTap"))
        if (settings.hasKey("showBrightnessSlider")) prefs.putBoolean(Prefs.SHOW_BRIGHTNESS_SLIDER, settings.getBoolean("showBrightnessSlider"))
        if (settings.hasKey("showVolumeSlider")) prefs.putBoolean(Prefs.SHOW_VOLUME_SLIDER, settings.getBoolean("showVolumeSlider"))
        if (settings.hasKey("showQuickShare")) prefs.putBoolean(Prefs.SHOW_QUICK_SHARE, settings.getBoolean("showQuickShare"))
        if (settings.hasKey("showPower")) prefs.putBoolean(Prefs.SHOW_POWER, settings.getBoolean("showPower"))
        if (settings.hasKey("showQr")) prefs.putBoolean(Prefs.SHOW_QR, settings.getBoolean("showQr"))
        if (settings.hasKey("showDnd")) prefs.putBoolean(Prefs.SHOW_DND, settings.getBoolean("showDnd"))
        prefs.apply()
        val serviceEnabled = reactContext.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
            .getBoolean(Prefs.SERVICE_ENABLED, false)
        if (serviceEnabled) {
            val intent = Intent(reactContext, SidebarOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                reactContext.startForegroundService(intent)
            } else {
                reactContext.startService(intent)
            }
        }
        promise.resolve(null)
    }

    @ReactMethod
    fun getLaunchTab(promise: Promise) {
        val prefs = reactContext.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
        val tab = prefs.getString(Prefs.LAUNCH_TAB, null)
        if (tab != null) prefs.edit().remove(Prefs.LAUNCH_TAB).apply()
        promise.resolve(tab)
    }

    @ReactMethod
    fun hasDndPermission(promise: Promise) {
        val nm = reactContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        promise.resolve(nm.isNotificationPolicyAccessGranted)
    }

    @ReactMethod
    fun requestDndPermission(promise: Promise) {
        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        reactContext.startActivity(intent)
        promise.resolve(null)
    }

    @ReactMethod
    fun hasWriteSettingsPermission(promise: Promise) {
        promise.resolve(Settings.System.canWrite(reactContext))
    }

    @ReactMethod
    fun requestWriteSettingsPermission(promise: Promise) {
        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
            Uri.parse("package:${reactContext.packageName}"))
            .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        reactContext.startActivity(intent)
        promise.resolve(null)
    }

    private fun drawableToBase64(drawable: android.graphics.drawable.Drawable): String {
        val bmp = if (drawable is BitmapDrawable && drawable.bitmap != null) {
            drawable.bitmap
        } else {
            val w = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
            val h = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
                val canvas = Canvas(b)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
            }
        }
        val scaled = Bitmap.createScaledBitmap(bmp, 96, 96, true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 85, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    override fun invalidate() {
        super.invalidate()
        executor.shutdown()
    }

    companion object {
        private const val TAG = "SidebarModule"
    }
}
