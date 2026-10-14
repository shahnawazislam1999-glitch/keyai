package com.sentencebuddy.app.util

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import com.sentencebuddy.app.service.SuggestionAccessibilityService
import kotlin.math.roundToInt

/** Converts dp to pixels. */
fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

fun View.show() {
    visibility = View.VISIBLE
}

fun View.hide() {
    visibility = View.GONE
}

object ServiceStatus {
    /** True when the user has switched on the Sentence Buddy accessibility service. */
    fun isAccessibilityEnabled(context: Context): Boolean {
        val expected = ComponentName(context, SuggestionAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            val component = ComponentName.unflattenFromString(splitter.next())
            if (component != null && component == expected) return true
        }
        return false
    }
}
