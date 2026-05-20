package com.alkisstam.sidebar

import android.content.*
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(Prefs.SERVICE_ENABLED, false)) return
        val svc = Intent(context, SidebarOverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(svc)
        } else {
            context.startService(svc)
        }
    }
}
