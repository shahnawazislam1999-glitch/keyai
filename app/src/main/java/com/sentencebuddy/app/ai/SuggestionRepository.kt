package com.sentencebuddy.app.ai

import android.util.LruCache
import com.sentencebuddy.app.data.AppPreferences

/** Adds settings lookup and a small in-memory cache on top of [GeminiClient]. */
class SuggestionRepository(private val prefs: AppPreferences) {

    private val cache = LruCache<String, List<String>>(40)

    suspend fun suggest(text: String): SuggestionResult {
        val key = prefs.apiKey
        if (key.isBlank()) {
            return SuggestionResult.Failure(SuggestionResult.Kind.NO_KEY, "Add your Gemini API key in the app first.")
        }
        val model = prefs.model
        val tone = prefs.tone
        val correct = !prefs.suggestionMode
        val count = if (correct) 1 else prefs.suggestionCount

        val cacheKey = "$model|${tone.id}|$count|$correct|${text.trim().lowercase()}"
        cache.get(cacheKey)?.let { return SuggestionResult.Success(it) }

        val result = GeminiClient.suggest(key, model, text.trim(), tone, count, correct)
        if (result is SuggestionResult.Success) {
            cache.put(cacheKey, result.items)
            return result
        }
        // AI busy / offline: in correction mode still give a basic offline fix instead of an error.
        val kind = (result as? SuggestionResult.Failure)?.kind
        if (correct && (kind == SuggestionResult.Kind.RATE_LIMIT || kind == SuggestionResult.Kind.SERVER ||
                kind == SuggestionResult.Kind.NETWORK || kind == SuggestionResult.Kind.UNKNOWN)) {
            LocalCorrector.fix(text)?.let { return SuggestionResult.Success(listOf(it)) }
        }
        return result
    }
}
