package com.tamimgamer.yesminai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class YesminAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        service = this
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        }
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { service = null }
    override fun onDestroy() { if (service === this) service = null; super.onDestroy() }

    private fun clickText(label: String): String {
        val root = rootInActiveWindow ?: return "বর্তমান স্ক্রিন পড়তে পারছি না।"
        val target = root.findAccessibilityNodeInfosByText(label).firstOrNull() ?: return "'$label' লেখা/বাটন খুঁজে পাইনি।"
        var node: AccessibilityNodeInfo? = target
        while (node != null) {
            if (node.isClickable && node.isEnabled) return if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) "'$label' এ ট্যাপ করেছি।" else "এই বাটনে ট্যাপ করা যায়নি।"
            node = node.parent
        }
        return "'$label' পাওয়া গেছে, কিন্তু এটি clickable নয়।"
    }

    private fun scroll(down: Boolean): String {
        val root = rootInActiveWindow ?: return "বর্তমান স্ক্রিনে স্ক্রল করা যাচ্ছে না।"
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        fun collect(n: AccessibilityNodeInfo?) { if (n != null) { if (n.isScrollable) nodes.add(n); for (i in 0 until n.childCount) collect(n.getChild(i)) } }
        collect(root)
        val node = nodes.firstOrNull() ?: return "এই স্ক্রিনে scrollable area পাইনি।"
        val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return if (node.performAction(action)) (if (down) "স্ক্রিন নিচে স্ক্রল করেছি।" else "স্ক্রিন উপরে স্ক্রল করেছি।") else "স্ক্রল করা যায়নি।"
    }

    private fun readScreen(): String {
        val root = rootInActiveWindow ?: return "বর্তমান স্ক্রিনের তথ্য পাওয়া যাচ্ছে না।"
        val parts = mutableListOf<String>()
        fun collect(n: AccessibilityNodeInfo?) {
            if (n == null || parts.size >= 35) return
            val t = n.text?.toString()?.trim().orEmpty()
            val d = n.contentDescription?.toString()?.trim().orEmpty()
            if (t.isNotBlank()) parts.add(t) else if (d.isNotBlank()) parts.add(d)
            for (i in 0 until n.childCount) collect(n.getChild(i))
        }
        collect(root)
        return if (parts.isEmpty()) "স্ক্রিনে পড়ার মতো লেখা পাওয়া যায়নি।" else "স্ক্রিনে যা দেখছি: " + parts.distinct().joinToString(" • ").take(1400)
    }

    private fun runApproved(command: String): String = when {
        command.startsWith("tap:") -> clickText(command.removePrefix("tap:").trim())
        command == "scroll:down" -> scroll(true)
        command == "scroll:up" -> scroll(false)
        command == "back" -> if (performGlobalAction(GLOBAL_ACTION_BACK)) "Back করেছি।" else "Back কাজ করেনি।"
        command == "read" -> readScreen()
        else -> "এই screen action সমর্থিত নয়।"
    }

    companion object {
        @Volatile private var service: YesminAccessibilityService? = null
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val target = context.packageName + "/" + YesminAccessibilityService::class.java.name
            return enabled.split(':').any { it.equals(target, true) }
        }
        fun executeApproved(command: String): String = service?.runApproved(command) ?: "YESMIN Accessibility Service এখন সংযুক্ত নয়। Settings থেকে service চালু করে আবার চেষ্টা করো।"
    }
}