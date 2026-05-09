package com.anonymous.sidebar

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
        return iconCache.get(pkg) ?: drawableToBase64(app.loadIcon(pm)).also { iconCache.put(pkg, it) }
    }

    @ReactMethod
    fun saveFavorites(packages: ReadableArray, promise: Promise) {
        val arr = JSONArray()
        for (i in 0 until packages.size()) arr.put(packages.getString(i))
        reactContext.getSharedPreferences("sidebar_prefs", Context.MODE_PRIVATE)
            .edit().putString("favorites", arr.toString()).apply()
        promise.resolve(null)
    }

    @ReactMethod
    fun getFavorites(promise: Promise) {
        val json = reactContext.getSharedPreferences("sidebar_prefs", Context.MODE_PRIVATE)
            .getString("favorites", "[]")
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
        reactContext.getSharedPreferences("sidebar_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("service_enabled", true).apply()
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
        reactContext.getSharedPreferences("sidebar_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("service_enabled", false).apply()
        reactContext.stopService(Intent(reactContext, SidebarOverlayService::class.java))
        promise.resolve(null)
    }

    @ReactMethod
    fun isServiceEnabled(promise: Promise) {
        val enabled = reactContext.getSharedPreferences("sidebar_prefs", Context.MODE_PRIVATE)
            .getBoolean("service_enabled", false)
        promise.resolve(enabled)
    }

    @ReactMethod
    fun savePillSettings(settings: ReadableMap, promise: Promise) {
        val prefs = reactContext.getSharedPreferences("sidebar_prefs", Context.MODE_PRIVATE).edit()
        if (settings.hasKey("height")) prefs.putInt("pill_height", settings.getInt("height"))
        if (settings.hasKey("width")) prefs.putInt("pill_width", settings.getInt("width"))
        if (settings.hasKey("position")) prefs.putFloat("pill_position", settings.getDouble("position").toFloat())
        if (settings.hasKey("side")) prefs.putString("pill_side", settings.getString("side"))
        if (settings.hasKey("opacity")) prefs.putFloat("pill_opacity", settings.getDouble("opacity").toFloat())
        if (settings.hasKey("theme")) prefs.putString("pill_theme", settings.getString("theme"))
        prefs.apply()
        val intent = Intent(reactContext, SidebarOverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            reactContext.startForegroundService(intent)
        } else {
            reactContext.startService(intent)
        }
        promise.resolve(null)
    }

    @ReactMethod
    fun getPillSettings(promise: Promise) {
        val prefs = reactContext.getSharedPreferences("sidebar_prefs", Context.MODE_PRIVATE)
        val map = WritableNativeMap()
        map.putInt("height", prefs.getInt("pill_height", 80))
        map.putInt("width", prefs.getInt("pill_width", 36))
        map.putDouble("position", prefs.getFloat("pill_position", 0.5f).toDouble())
        map.putString("side", prefs.getString("pill_side", "right") ?: "right")
        map.putDouble("opacity", prefs.getFloat("pill_opacity", 1.0f).toDouble())
        map.putString("theme", prefs.getString("pill_theme", "dark") ?: "dark")
        promise.resolve(map)
    }

    @ReactMethod
    fun getOverlaySettings(promise: Promise) {
        val prefs = reactContext.getSharedPreferences("sidebar_prefs", Context.MODE_PRIVATE)
        val map = WritableNativeMap()
        map.putBoolean("autoHideFullscreen", prefs.getBoolean("auto_hide_fullscreen", false))
        map.putBoolean("showLabels", prefs.getBoolean("show_labels", true))
        map.putBoolean("vibration", prefs.getBoolean("vibration", true))
        map.putInt("sensitivity", prefs.getInt("swipe_sensitivity", 16))
        map.putBoolean("quickControlsEnabled", prefs.getBoolean("quick_controls_enabled", true))
        map.putBoolean("showTorch", prefs.getBoolean("show_torch", true))
        map.putBoolean("showAutoRotate", prefs.getBoolean("show_auto_rotate", true))
        map.putBoolean("showAutoBrightness", prefs.getBoolean("show_auto_brightness", true))
        map.putBoolean("showRingerMode", prefs.getBoolean("show_ringer_mode", true))
        promise.resolve(map)
    }

    @ReactMethod
    fun saveOverlaySettings(settings: ReadableMap, promise: Promise) {
        val prefs = reactContext.getSharedPreferences("sidebar_prefs", Context.MODE_PRIVATE).edit()
        if (settings.hasKey("autoHideFullscreen")) prefs.putBoolean("auto_hide_fullscreen", settings.getBoolean("autoHideFullscreen"))
        if (settings.hasKey("showLabels")) prefs.putBoolean("show_labels", settings.getBoolean("showLabels"))
        if (settings.hasKey("vibration")) prefs.putBoolean("vibration", settings.getBoolean("vibration"))
        if (settings.hasKey("sensitivity")) prefs.putInt("swipe_sensitivity", settings.getInt("sensitivity"))
        if (settings.hasKey("quickControlsEnabled")) prefs.putBoolean("quick_controls_enabled", settings.getBoolean("quickControlsEnabled"))
        if (settings.hasKey("showTorch")) prefs.putBoolean("show_torch", settings.getBoolean("showTorch"))
        if (settings.hasKey("showAutoRotate")) prefs.putBoolean("show_auto_rotate", settings.getBoolean("showAutoRotate"))
        if (settings.hasKey("showAutoBrightness")) prefs.putBoolean("show_auto_brightness", settings.getBoolean("showAutoBrightness"))
        if (settings.hasKey("showRingerMode")) prefs.putBoolean("show_ringer_mode", settings.getBoolean("showRingerMode"))
        prefs.apply()
        val intent = Intent(reactContext, SidebarOverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            reactContext.startForegroundService(intent)
        } else {
            reactContext.startService(intent)
        }
        promise.resolve(null)
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

    companion object {
        private const val TAG = "SidebarModule"
    }
}
