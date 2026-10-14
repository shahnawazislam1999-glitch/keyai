package com.sentencebuddy.app.data

/** Writing style used when asking the AI for alternative sentences. */
enum class Tone(val id: String, val label: String, val instruction: String) {
    NATURAL("natural", "Natural", "natural, everyday conversational English"),
    PROFESSIONAL("professional", "Professional", "polished, professional English suitable for work"),
    FRIENDLY("friendly", "Friendly", "warm, friendly and casual English"),
    POLITE("polite", "Polite", "very polite and respectful English"),
    SHORT("short", "Short & simple", "short, simple and direct English");

    /** Next tone in the list (wraps around). Used by the tone button on the floating panel. */
    fun next(): Tone {
        val all = values()
        return all[(ordinal + 1) % all.size]
    }

    companion object {
        fun fromId(id: String?): Tone = values().firstOrNull { it.id == id } ?: NATURAL
    }
}
