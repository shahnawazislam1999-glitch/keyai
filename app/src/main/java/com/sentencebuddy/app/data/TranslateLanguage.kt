package com.sentencebuddy.app.data

/** Languages copied text can be translated into. Only these three are offered. */
enum class TranslateLanguage(val id: String, val english: String, val native: String) {
    ASSAMESE("as", "Assamese", "অসমীয়া"),
    HINDI("hi", "Hindi", "हिन्दी"),
    BENGALI("bn", "Bengali", "বাংলা");

    /** e.g. "Assamese (অসমীয়া)" */
    val label: String get() = "$english ($native)"

    companion object {
        fun fromId(id: String?): TranslateLanguage = values().firstOrNull { it.id == id } ?: ASSAMESE
    }
}
