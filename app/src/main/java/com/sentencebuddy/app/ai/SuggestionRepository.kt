package com.sentencebuddy.app.ai

import android.util.LruCache
import com.sentencebuddy.app.data.AppPreferences
import com.sentencebuddy.app.data.TranslateLanguage

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
        // First item is always the corrected sentence; optional extra suggestions follow it.
        val correct = true
        val count = if (prefs.suggestionMode) 1 + prefs.suggestionCount else 1

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

    private val translateCache = LruCache<String, String>(40)

    /** Translates copied text into [language] (cached). */
    suspend fun translate(text: String, language: TranslateLanguage): SuggestionResult {
        val key = prefs.apiKey
        if (key.isBlank()) {
            return SuggestionResult.Failure(SuggestionResult.Kind.NO_KEY, "Add your Gemini API key in the app first.")
        }
        val clean = text.trim()
        val cacheKey = "${language.id}|$clean"
        translateCache.get(cacheKey)?.let { return SuggestionResult.Success(listOf(it)) }

        val result = GeminiClient.translate(key, prefs.model, clean, language)
        if (result is SuggestionResult.Success) {
            translateCache.put(cacheKey, result.items.first())
        }
        return result
    }
}
