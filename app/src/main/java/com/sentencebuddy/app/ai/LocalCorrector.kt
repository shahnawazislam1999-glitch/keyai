package com.sentencebuddy.app.ai

/** Simple offline grammar fixer, used only when the AI is busy so the user still gets a correction. */
object LocalCorrector {

    private val contractions = mapOf(
        "dont" to "don't", "cant" to "can't", "wont" to "won't", "doesnt" to "doesn't",
        "didnt" to "didn't", "isnt" to "isn't", "arent" to "aren't", "wasnt" to "wasn't",
        "werent" to "weren't", "couldnt" to "couldn't", "shouldnt" to "shouldn't",
        "wouldnt" to "wouldn't", "havent" to "haven't", "hasnt" to "hasn't", "hadnt" to "hadn't",
        "im" to "I'm", "ive" to "I've", "thats" to "that's", "whats" to "what's",
        "u" to "you", "ur" to "your", "r" to "are", "pls" to "please", "plz" to "please",
        "wanna" to "want to", "gonna" to "going to", "gotta" to "have to", "dunno" to "don't know",
        "teh" to "the", "recieve" to "receive", "definately" to "definitely", "thx" to "thanks"
    )
    private val questionStarts = setOf(
        "what", "when", "where", "who", "whom", "whose", "why", "how", "which", "is", "are", "am",
        "was", "were", "do", "does", "did", "can", "could", "will", "would", "should", "have", "has"
    )

    /** Returns the corrected text, or null if nothing changed. */
    fun fix(input: String): String? {
        var t = input.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return null

        t = t.replace(Regex("\\bi(?=')"), "I")
        t = t.split(" ").joinToString(" ") { word ->
            val m = Regex("^([^A-Za-z]*)([A-Za-z]+)([^A-Za-z]*)$").find(word) ?: return@joinToString word
            val (pre, core, post) = m.destructured
            val lower = core.lowercase()
            val fixed = when {
                lower == "i" -> "I"
                lower in contractions && !(core.length == 1 && lower != "u" && lower != "r") -> contractions.getValue(lower)
                else -> core
            }
            pre + fixed + post
        }
        t = t.replace(Regex("\\s+([,.!?])"), "$1")
        t = t.replace(Regex("([,.!?])(?=[A-Za-z])"), "$1 ")
        t = t.replace(Regex("([!?.])\\1{2,}"), "$1$1$1")

        // Capitalise the first letter and the first letter after . ! ?
        val sb = StringBuilder(t)
        var cap = true
        for (i in sb.indices) {
            val c = sb[i]
            if (cap && c.isLetter()) { sb.setCharAt(i, c.uppercaseChar()); cap = false }
            else if (c == '.' || c == '!' || c == '?') cap = true
            else if (!c.isWhitespace() && c != '"' && c != '\'') cap = false
        }
        t = sb.toString()

        val last = t.last()
        if (last.isLetterOrDigit() && t.split(" ").size >= 3) {
            val first = t.split(" ").first().lowercase().trim { !it.isLetter() }
            t += if (first in questionStarts) "?" else "."
        }
        return t.takeIf { it != input.trim() }
    }
}
