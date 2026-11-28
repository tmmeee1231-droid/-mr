package com.manw.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class ManwRemoteAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() { super.onServiceConnected(); active = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { if (active === this) active = null; super.onDestroy() }

    private fun performTap(nx: Float, ny: Float) {
        if (nx !in 0f..1f || ny !in 0f..1f) return
        val x = nx * resources.displayMetrics.widthPixels
        val y = ny * resources.displayMetrics.heightPixels
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 90))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long) {
        if (listOf(x1, y1, x2, y2).any { it !in 0f..1f }) return
        val w = resources.displayMetrics.widthPixels.toFloat()
        val h = resources.displayMetrics.heightPixels.toFloat()
        val path = Path().apply { moveTo(x1*w, y1*h); lineTo(x2*w, y2*h) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration.coerceIn(100L, 1500L)))
            .build()
        dispatchGesture(gesture, null, null)
    }

    companion object {
        @Volatile private var active: ManwRemoteAccessibilityService? = null
        fun tap(x: Float, y: Float) { active?.performTap(x, y) }
        fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long) {
            active?.performSwipe(x1, y1, x2, y2, duration)
        }
    }
}