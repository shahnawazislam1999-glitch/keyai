package com.sentencebuddy.app.data

import android.content.Context
import android.content.SharedPreferences

/** Single place for all user settings. Backed by SharedPreferences. */
class AppPreferences private constructor(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var assistantEnabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, true)
        set(value) = sp.edit().putBoolean(KEY_ENABLED, value).apply()

    var apiKey: String
        get() = sp.getString(KEY_API_KEY, "").orEmpty().ifBlank { DEFAULT_API_KEY }.takeUnless { it.startsWith("PASTE_") }.orEmpty()
        set(value) = sp.edit().putString(KEY_API_KEY, value.trim()).apply()

    var model: String
        get() = sp.getString(KEY_MODEL, DEFAULT_MODEL).orEmpty().ifBlank { DEFAULT_MODEL }
        set(value) = sp.edit().putString(KEY_MODEL, value.trim().ifBlank { DEFAULT_MODEL }).apply()

    /** false = correct my sentence (default); true = show several alternative suggestions. */
    var suggestionMode: Boolean
        get() = sp.getBoolean(KEY_SUGGESTION_MODE, false)
        set(value) = sp.edit().putBoolean(KEY_SUGGESTION_MODE, value).apply()

    var tone: Tone
        get() = Tone.fromId(sp.getString(KEY_TONE, Tone.NATURAL.id))
        set(value) = sp.edit().putString(KEY_TONE, value.id).apply()

    /** How many alternative sentences to request (3..6). */
    var suggestionCount: Int
        get() = sp.getInt(KEY_COUNT, 6).coerceIn(3, 6)
        set(value) = sp.edit().putInt(KEY_COUNT, value.coerceIn(3, 6)).apply()

    /** Floating bubble opacity in percent (5..100). Default is a very faint 10%. */
    var bubbleOpacityPercent: Int
        get() = sp.getInt(KEY_OPACITY, 10).coerceIn(5, 100)
        set(value) = sp.edit().putInt(KEY_OPACITY, value.coerceIn(5, 100)).apply()

    /** Visible size of the bubble in dp (20..56). Default is small. */
    var bubbleSizeDp: Int
        get() = sp.getInt(KEY_SIZE, 28).coerceIn(20, 56)
        set(value) = sp.edit().putInt(KEY_SIZE, value.coerceIn(20, 56)).apply()

    /** Pause after the last keystroke before suggestions are requested. */
    var debounceMs: Int
        get() = sp.getInt(KEY_DELAY, 900).coerceIn(400, 2000)
        set(value) = sp.edit().putInt(KEY_DELAY, value.coerceIn(400, 2000)).apply()

    var autoShowPanel: Boolean
        get() = sp.getBoolean(KEY_AUTO_SHOW, true)
        set(value) = sp.edit().putBoolean(KEY_AUTO_SHOW, value).apply()

    var autoSend: Boolean
        get() = sp.getBoolean(KEY_AUTO_SEND, false)
        set(value) = sp.edit().putBoolean(KEY_AUTO_SEND, value).apply()

    var whatsappOnly: Boolean
        get() = sp.getBoolean(KEY_WHATSAPP_ONLY, false)
        set(value) = sp.edit().putBoolean(KEY_WHATSAPP_ONLY, value).apply()

    var bubbleX: Int
        get() = sp.getInt(KEY_BUBBLE_X, -1)
        set(value) = sp.edit().putInt(KEY_BUBBLE_X, value).apply()

    var bubbleY: Int
        get() = sp.getInt(KEY_BUBBLE_Y, -1)
        set(value) = sp.edit().putInt(KEY_BUBBLE_Y, value).apply()

    fun saveBubblePosition(x: Int, y: Int) {
        sp.edit().putInt(KEY_BUBBLE_X, x).putInt(KEY_BUBBLE_Y, y).apply()
    }

    fun resetBubblePosition() {
        sp.edit().putInt(KEY_BUBBLE_X, -1).putInt(KEY_BUBBLE_Y, -1).apply()
    }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sp.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sp.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        /** Paste your Gemini key here (https://aistudio.google.com/apikey) so the app works out of the box. */
        const val DEFAULT_API_KEY = "PASTE_YOUR_GEMINI_API_KEY_HERE"

        /** Google's alias that always points to the latest Flash model. */
        const val DEFAULT_MODEL = "gemini-flash-latest"

        private const val FILE_NAME = "sentence_buddy_prefs"

        const val KEY_ENABLED = "enabled"
        const val KEY_API_KEY = "api_key"
        const val KEY_MODEL = "model"
        const val KEY_TONE = "tone"
        const val KEY_SUGGESTION_MODE = "suggestion_mode"
        const val KEY_COUNT = "count"
        const val KEY_OPACITY = "bubble_opacity"
        const val KEY_SIZE = "bubble_size"
        const val KEY_DELAY = "debounce_ms"
        const val KEY_AUTO_SHOW = "auto_show"
        const val KEY_AUTO_SEND = "auto_send"
        const val KEY_WHATSAPP_ONLY = "whatsapp_only"
        const val KEY_BUBBLE_X = "bubble_x"
        const val KEY_BUBBLE_Y = "bubble_y"

        @Volatile
        private var instance: AppPreferences? = null

        fun get(context: Context): AppPreferences =
            instance ?: synchronized(this) {
                instance ?: AppPreferences(context).also { instance = it }
            }
    }
}
