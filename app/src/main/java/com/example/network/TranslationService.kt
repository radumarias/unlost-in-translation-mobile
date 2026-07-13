package com.example.network

import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object TranslationService {
    private const val TAG = "TranslationService"

    // Configure 30 secs timeouts for standard calls and 60 secs for Gemini
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val geminiClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Translates text using the standard free Google Translate API.
     */
    suspend fun translateGoogle(text: String, sourceLang: String, targetLang: String): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext ""
        try {
            val encodedQuery = URLEncoder.encode(text, "UTF-8")
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sourceLang&tl=$targetLang&dt=t&q=$encodedQuery"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw Exception("HTTP error code: ${response.code}")

                val responseBody = response.body?.string() ?: throw Exception("Empty response body")
                Log.d(TAG, "Google Translate Response: $responseBody")

                val outerArray = JSONArray(responseBody)
                val firstGroup = outerArray.getJSONArray(0)
                val sb = StringBuilder()
                for (i in 0 until firstGroup.length()) {
                    val part = firstGroup.getJSONArray(i)
                    if (part.length() > 0) {
                        sb.append(part.getString(0))
                    }
                }
                sb.toString()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Google Translation Error: ", e)
            throw e
        }
    }

    /**
     * Translates text using the Gemini 3.5 Flash Model.
     */
    suspend fun translateGemini(text: String, sourceLangName: String, targetLangName: String): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext ""
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            throw Exception("Gemini API key is not configured in the Secrets panel.")
        }

        val prompt = "Translate the following text from $sourceLangName to $targetLangName. Please deliver only the translated text, with no preamble, explanations, decorative markers, or extra words. Text to translate:\n$text"
        
        try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey"
            
            // Build the JSON payload for Gemini REST API as specified in gemini_api skill
            val requestBodyJson = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", prompt)
                            })
                        })
                    })
                })
                put("safetySettings", JSONArray().apply {
                    listOf("HARM_CATEGORY_HARASSMENT", "HARM_CATEGORY_HATE_SPEECH", "HARM_CATEGORY_SEXUALLY_EXPLICIT", "HARM_CATEGORY_DANGEROUS_CONTENT").forEach { cat ->
                        put(JSONObject().apply {
                            put("category", cat)
                            put("threshold", "BLOCK_NONE")
                        })
                    }
                })
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = requestBodyJson.toString().toRequestBody(mediaType)

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            geminiClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorStr = response.body?.string() ?: "Unknown error"
                    throw Exception("Gemini server returned error: ${response.code} - $errorStr")
                }

                val responseStr = response.body?.string() ?: throw Exception("Empty response")
                val jsonResponse = JSONObject(responseStr)
                
                val candidates = jsonResponse.getJSONArray("candidates")
                val candidate = candidates.getJSONObject(0)
                val content = candidate.getJSONObject("content")
                val parts = content.getJSONArray("parts")
                val translatedTextText = parts.getJSONObject(0).getString("text")
                translatedTextText.trim()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gemini Translation Error: ", e)
            throw e
        }
    }

    /**
     * Translates and analyzes text using Gemini to find cultural issues or idiom mis-translations.
     * Returns a JSON payload mapping to ValidationState.Success parameters.
     */
    suspend fun validateTranslationGemini(originalText: String, translatedText: String, sourceLangName: String, targetLangName: String): JSONObject = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            throw Exception("Gemini API key is not configured in the Secrets panel.")
        }

        val prompt = """
            Analyze the following translation for cultural appropriateness, literal mistranslation of idioms, and tone.
            Source Language: $sourceLangName
            Original Text: "$originalText"
            Target Language: $targetLangName
            Translated Text: "$translatedText"
            
            Return ONLY a valid JSON object matching this schema, with no markdown wrappers or extra text:
            {
              "isWarning": boolean,
              "explanation": "Required. Always provide context on the meaning, cultural nuance, and literal translation. If perfectly fine (isWarning: false), explain the meaning positively.",
              "suggestions": [
                 { "tone": "Polite", "text": "Alternative phrase in the Source Language ($sourceLangName)" },
                 { "tone": "Casual", "text": "Alternative phrase in the Source Language ($sourceLangName)" },
                 { "tone": "Direct", "text": "Alternative phrase in the Source Language ($sourceLangName)" }
              ] // Provide alternative translations in different tones if context helps. PLEASE NOTE: the text field MUST be in the Source Language ($sourceLangName).
            }
        """.trimIndent()
        
        try {
            // Using JSON mode logic would be ideal if supported by the model, but we can instruct via prompt
            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey"
            
            val requestBodyJson = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", prompt)
                            })
                        })
                    })
                })
                put("safetySettings", JSONArray().apply {
                    listOf("HARM_CATEGORY_HARASSMENT", "HARM_CATEGORY_HATE_SPEECH", "HARM_CATEGORY_SEXUALLY_EXPLICIT", "HARM_CATEGORY_DANGEROUS_CONTENT").forEach { cat ->
                        put(JSONObject().apply {
                            put("category", cat)
                            put("threshold", "BLOCK_NONE")
                        })
                    }
                })
                put("generationConfig", JSONObject().apply {
                    put("responseMimeType", "application/json")
                })
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = requestBodyJson.toString().toRequestBody(mediaType)

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            geminiClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("Gemini server returned error: ${response.code}")
                }
                val responseStr = response.body?.string() ?: throw Exception("Empty response")
                val jsonResponse = JSONObject(responseStr)
                val candidates = jsonResponse.getJSONArray("candidates")
                val candidate = candidates.getJSONObject(0)
                val content = candidate.getJSONObject("content")
                val parts = content.getJSONArray("parts")
                var aiResponseText = parts.getJSONObject(0).getString("text").trim()
                
                // Remove markdown json block if Gemini includes it
                aiResponseText = aiResponseText.trim()
                if (aiResponseText.startsWith("```json")) {
                    aiResponseText = aiResponseText.removePrefix("```json")
                } else if (aiResponseText.startsWith("```")) {
                    aiResponseText = aiResponseText.removePrefix("```")
                }
                if (aiResponseText.endsWith("```")) {
                    aiResponseText = aiResponseText.removeSuffix("```")
                }
                aiResponseText = aiResponseText.trim()

                try {
                    JSONObject(aiResponseText)
                } catch (e: Exception) {
                    throw Exception("JSON Parse Error: ${e.message}\nRaw text: ${aiResponseText.take(150)}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gemini Validation Error: ", e)
            throw e
        }
    }
}
