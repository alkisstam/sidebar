package com.alkisstam.sidebar

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class SidebarAccessibilityService : AccessibilityService() {
    companion object { var instance: SidebarAccessibilityService? = null }
    override fun onServiceConnected() { instance = this }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent) {}
    override fun onInterrupt() {}
}
