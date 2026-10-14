package com.sentencebuddy.app.ai

import com.sentencebuddy.app.ai.SuggestionResult.Failure
import com.sentencebuddy.app.ai.SuggestionResult.Kind
import com.sentencebuddy.app.ai.SuggestionResult.Success
import com.sentencebuddy.app.data.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal client for the Google Gemini REST API (generateContent).
 * Uses only the Android framework (HttpURLConnection + org.json), so there are no extra dependencies.
 */
object GeminiClient {

    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 20_000

    suspend fun suggest(
        apiKey: String,
        model: String,
        text: String,
        tone: Tone,
        count: Int
    ): SuggestionResult = withContext(Dispatchers.IO) {
        val url = "$BASE_URL$model:generateContent"
        val body = buildRequest(model, text, tone, count)

        var result: SuggestionResult = Failure(Kind.UNKNOWN, "Something went wrong. Please try again.")
        for (attempt in 0..1) {
            result = try {
                val (code, response) = post(url, apiKey, body)
                if (code in 200..299) parseSuccess(response, text, count) else mapHttpError(code, response)
            } catch (e: IOException) {
                Failure(Kind.NETWORK, "No internet connection. Check your network and try again.")
            } catch (e: Exception) {
                Failure(Kind.UNKNOWN, "Unexpected error: ${e.message ?: e.javaClass.simpleName}")
            }
            val kind = (result as? Failure)?.kind
            val retryable = kind == Kind.NETWORK || kind == Kind.SERVER
            if (!retryable) break
            if (attempt == 0) delay(500)
        }
        result
    }

    // ---------------------------------------------------------------------------------------------
    // Request
    // ---------------------------------------------------------------------------------------------

    private fun buildSystemPrompt(tone: Tone, count: Int): String = """
        You are a writing assistant inside a phone keyboard. The user is typing a chat message and wants better ways to say it.
        Rewrite the user's text as exactly $count different English alternatives.
        Rules:
        - Keep the original meaning and intent. Do NOT answer the text, do not add new facts.
        - Fix grammar and spelling. Style: ${tone.instruction}.
        - If the text is not English (for example Hindi or Hinglish), convert it into natural English.
        - Every alternative must be a complete message the user could send as it is, with clearly different wording from the others.
        - Output only a JSON array of $count strings and nothing else.
    """.trimIndent()

    private fun buildRequest(model: String, text: String, tone: Tone, count: Int): String {
        val generationConfig = JSONObject().apply {
            put("temperature", 0.9)
            put("responseMimeType", "application/json")
            put(
                "responseSchema",
                JSONObject()
                    .put("type", "ARRAY")
                    .put("items", JSONObject().put("type", "STRING"))
            )
            // Gemini 2.5 Flash / Flash-Lite can skip "thinking" for much faster replies.
            if (model.contains("2.5") && !model.contains("pro", ignoreCase = true)) {
                put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            }
        }
        return JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", buildSystemPrompt(tone, count))))
            )
            put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", text)))
                )
            )
            put("generationConfig", generationConfig)
        }.toString()
    }

    private fun post(url: String, apiKey: String, body: String): Pair<Int, String> {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-goog-api-key", apiKey)
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return code to text
        } finally {
            connection.disconnect()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Response
    // ---------------------------------------------------------------------------------------------

    private fun parseSuccess(response: String, original: String, count: Int): SuggestionResult {
        val output = try {
            JSONObject(response)
                .optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text")
                .orEmpty()
        } catch (e: Exception) {
            ""
        }
        if (output.isBlank()) {
            return Failure(Kind.BAD_RESPONSE, "The AI returned no suggestions for this text.")
        }

        val originalNorm = original.trim().lowercase()
        val items = parseList(output)
            .map { it.trim().trim('"').trim() }
            .filter { it.isNotEmpty() && it.lowercase() != originalNorm }
            .distinctBy { it.lowercase() }
            .take(count)

        return if (items.isEmpty()) {
            Failure(Kind.BAD_RESPONSE, "No new suggestions for this text. Try writing a bit more.")
        } else {
            Success(items)
        }
    }

    /** Parses the JSON array; falls back to line-by-line parsing if the model ignored the schema. */
    private fun parseList(raw: String): List<String> {
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```")
            .trim()
        try {
            val array = JSONArray(cleaned)
            return (0 until array.length()).map { array.optString(it) }
        } catch (_: Exception) {
            // fall through
        }
        return cleaned.lines()
            .map { it.trim().trimStart('-', '*', '•').replace(Regex("^\\d+[.)]\\s*"), "").trim() }
            .filter { it.isNotEmpty() && it != "[" && it != "]" }
    }

    private fun mapHttpError(code: Int, body: String): SuggestionResult {
        val apiMessage = runCatching {
            JSONObject(body).getJSONObject("error").getString("message")
        }.getOrNull()

        return when {
            code == 400 && apiMessage?.contains("API key", ignoreCase = true) == true ->
                Failure(Kind.INVALID_KEY, "Your Gemini API key is not valid. Check it in the app.")
            code == 401 || code == 403 ->
                Failure(Kind.INVALID_KEY, "Your Gemini API key was rejected. Check it in the app.")
            code == 404 ->
                Failure(Kind.BAD_RESPONSE, "Model not found. Check the model name in the app.")
            code == 429 ->
                Failure(Kind.RATE_LIMIT, "Free limit reached. Wait a minute and try again.")
            code >= 500 ->
                Failure(Kind.SERVER, "Gemini is busy right now. Try again in a moment.")
            else ->
                Failure(Kind.BAD_RESPONSE, apiMessage?.take(160) ?: "Request failed (error $code).")
        }
    }
}
