package com.sentencebuddy.app.ai

/** Outcome of a suggestion request. */
sealed class SuggestionResult {

    data class Success(val items: List<String>) : SuggestionResult()

    data class Failure(val kind: Kind, val message: String) : SuggestionResult()

    enum class Kind { NO_KEY, INVALID_KEY, RATE_LIMIT, NETWORK, SERVER, BAD_RESPONSE, UNKNOWN }
}
