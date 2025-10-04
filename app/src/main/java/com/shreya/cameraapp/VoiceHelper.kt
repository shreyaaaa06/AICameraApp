package com.shreya.cameraapp

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.*
import kotlin.collections.HashMap
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import android.os.Handler
import android.os.Looper

class VoiceHelper(private val context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "VoiceHelper"

        // Language codes for supported languages
        const val LANG_ENGLISH = "en"
        const val LANG_HINDI = "hi"
        const val LANG_KANNADA = "kn"
        const val LANG_TAMIL = "ta"
        const val LANG_TELUGU = "te"

        // Default TTS settings
        private const val DEFAULT_SPEECH_RATE = 0.9f
        private const val DEFAULT_PITCH = 1.0f
        private val lastSpokenSet = mutableSetOf<String>()
    }

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var currentLanguage = LANG_ENGLISH
    private var speechRate = DEFAULT_SPEECH_RATE
    private var pitch = DEFAULT_PITCH

    private var onInitializationListener: ((Boolean) -> Unit)? = null
    private var suggestionQueue = mutableListOf<String>()
    private var isCurrentlySpeaking = false
    private var currentTranslator: Translator? = null
    private val translationCache = mutableMapOf<String, String>()

    init {
        initializeTTS()
    }

    /**
     * Initialize TextToSpeech engine
     */
    private fun initializeTTS() {
        try {
            tts = TextToSpeech(context, this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TTS", e)
            onInitializationListener?.invoke(false)
        }
    }

    /**
     * Called when TTS initialization is complete
     */
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.let { textToSpeech ->
                // Set default language
                val result = textToSpeech.setLanguage(Locale(currentLanguage))

                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "Language not supported, falling back to English")
                    textToSpeech.setLanguage(Locale.ENGLISH)
                    currentLanguage = LANG_ENGLISH
                }

                // Set speech rate and pitch
                textToSpeech.setSpeechRate(speechRate)
                textToSpeech.setPitch(pitch)

                // Set utterance progress listener
                textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        isCurrentlySpeaking = true
                    }

                    override fun onDone(utteranceId: String?) {
                        isCurrentlySpeaking = false
                        // Process next suggestion in queue
                        processNextSuggestion()
                    }

                    override fun onError(utteranceId: String?) {
                        isCurrentlySpeaking = false
                        Log.e(TAG, "TTS Error for utterance: $utteranceId")
                        // Continue with next suggestion even if current one failed
                        processNextSuggestion()
                    }
                })

                isInitialized = true
                Log.i(TAG, "TTS initialized successfully")
                onInitializationListener?.invoke(true)
            }
        } else {
            Log.e(TAG, "TTS initialization failed")
            isInitialized = false
            onInitializationListener?.invoke(false)
        }
    }

    /**
     * Set the language for TTS
     * @param languageCode Language code (en, hi, kn, ta, te)
     * @return true if language was set successfully
     */
    fun setLanguage(languageCode: String): Boolean {
        if (!isInitialized || tts == null) {
            Log.w(TAG, "TTS not initialized")
            return false
        }

        val locale = when (languageCode) {
            LANG_ENGLISH -> Locale.ENGLISH
            LANG_HINDI -> Locale("hi", "IN")
            LANG_KANNADA -> Locale("kn", "IN")
            LANG_TAMIL -> Locale("ta", "IN")
            LANG_TELUGU -> Locale("te", "IN")
            else -> Locale.ENGLISH
        }

        val result = tts?.setLanguage(locale)

        return if (result == TextToSpeech.LANG_MISSING_DATA ||
            result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Language $languageCode not supported")
            false
        } else {
            currentLanguage = languageCode
            Log.i(TAG, "Language set to: $languageCode")
            true
        }
    }


    fun speakSuggestions(suggestions: List<String>) {
        if (suggestions.isEmpty()) {
            Log.w(TAG, "No suggestions to speak")
            return
        }

        // Filter for new suggestions only (keep your similarity logic)
        val newSuggestions = suggestions.filter { it.isNotBlank() && it !in lastSpokenSet }

        if (newSuggestions.isEmpty()) {
            Log.d(TAG, "All suggestions already spoken recently")
            return
        }

        if (isCurrentlySpeaking) {
            // Don't interrupt current sentence, but replace remaining queue with new ones
            suggestionQueue.clear() // Clear old pending suggestions
            suggestionQueue.addAll(newSuggestions) // Add only new suggestions
            Log.d(TAG, "Replaced pending suggestions with ${newSuggestions.size} new ones")
            return
        }

        // Not currently speaking - start fresh
        suggestionQueue.clear()
        suggestionQueue.addAll(newSuggestions)

        if (suggestionQueue.isNotEmpty()) {
            processNextSuggestion()
        }
    }
    private var pendingSuggestions: List<String>? = null
    private var pendingSuggestionTitles: Set<String>? = null

    private fun processNextSuggestion() {
        if (suggestionQueue.isNotEmpty() && !isCurrentlySpeaking) {
            val nextSuggestion = suggestionQueue.removeAt(0)
            lastSpokenSet.add(nextSuggestion)
            speak(nextSuggestion, TextToSpeech.QUEUE_ADD)
        }
    }
    fun queueNewSuggestions(newSuggestions: List<String>, newTitles: Set<String>) {
        pendingSuggestions = newSuggestions
        pendingSuggestionTitles = newTitles
    }

    fun speak(text: String, queueMode: Int = TextToSpeech.QUEUE_ADD) {
        if (!isInitialized || tts == null) {
            Log.w(TAG, "TTS not initialized, cannot speak: $text")
            return
        }
        if (text.isBlank()) return

        val utteranceId = "suggestion_${System.currentTimeMillis()}"
        tts?.speak(text, queueMode, null, utteranceId)  // modern API (avoids deprecated HashMap)
        Log.i(TAG, "Speaking: $text")
    }

    /**
     * Speak phone-controlled suggestions with appropriate formatting
     */
    fun speakPhoneControlledSuggestions(suggestions: Map<String, Any>) {
        val spokenSuggestions = mutableListOf<String>()

        suggestions.forEach { (key, value) ->
            val spokenText = when (key) {
                "exposure" -> translateSuggestion("Adjust exposure to $value", currentLanguage)
                "mode" -> translateSuggestion("Switch to $value mode", currentLanguage)
                "filter" -> translateSuggestion("Apply $value filter", currentLanguage)
                "zoom" -> translateSuggestion("Set zoom to $value", currentLanguage)
                "flash" -> translateSuggestion("${if (value as Boolean) "Enable" else "Disable"} flash", currentLanguage)
                "stabilization" -> translateSuggestion("Enable AI stabilization", currentLanguage)
                else -> translateSuggestion("$key: $value", currentLanguage)
            }
            spokenSuggestions.add(spokenText)
        }

        if (spokenSuggestions.isNotEmpty()) {
            speakSuggestions(spokenSuggestions)
        }
    }

    /**
     * Speak user-controlled suggestions
     */
    fun speakUserControlledSuggestions(suggestions: List<String>) {
        val translatedSuggestions = suggestions.map { suggestion ->
            translateSuggestion(suggestion, currentLanguage)
        }

        speakSuggestions(translatedSuggestions)
    }

    /**
     * Translate suggestion to selected language
     * Note: For production, integrate with proper translation service
     * This is a basic implementation for common photography terms
     */
    private fun translateSuggestion(text: String, languageCode: String): String {
        if (languageCode == LANG_ENGLISH) return text

        // Check cache first
        val cacheKey = "${languageCode}_$text"
        translationCache[cacheKey]?.let { return it }

        // For immediate response, return original text
        // Translation will happen asynchronously
        translateAsync(text, languageCode) { translated ->
            translationCache[cacheKey] = translated
        }

        return text // Return original until translation completes
    }

    // Add this new method
    private fun translateAsync(text: String, languageCode: String, onComplete: (String) -> Unit) {
        val targetLanguage = when (languageCode) {
            LANG_HINDI -> TranslateLanguage.HINDI
            LANG_KANNADA -> TranslateLanguage.KANNADA
            LANG_TAMIL -> TranslateLanguage.TAMIL
            LANG_TELUGU -> TranslateLanguage.TELUGU
            else -> return onComplete(text)
        }

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(targetLanguage)
            .build()

        currentTranslator?.close()
        currentTranslator = Translation.getClient(options)

        val conditions = DownloadConditions.Builder()
            .requireWifi()
            .build()

        currentTranslator?.downloadModelIfNeeded(conditions)
            ?.addOnSuccessListener {
                currentTranslator?.translate(text)
                    ?.addOnSuccessListener { translated ->
                        onComplete(translated)
                    }
                    ?.addOnFailureListener {
                        Log.e(TAG, "Translation failed: ${it.message}")
                        onComplete(text)
                    }
            }
            ?.addOnFailureListener {
                Log.e(TAG, "Model download failed: ${it.message}")
                onComplete(text)
            }
    }

    // Basic translation methods - integrate with proper translation service in production
    private fun translateToHindi(text: String): String {
        // Basic keyword replacement for Hindi
        return text.replace("Adjust exposure", "एक्सपोज़र समायोजित करें")
            .replace("Switch to", "स्विच करें")
            .replace("mode", "मोड")
            .replace("Apply", "लगाएं")
            .replace("filter", "फिल्टर")
            .replace("Enable", "सक्षम करें")
            .replace("Disable", "अक्षम करें")
            .replace("flash", "फ्लैश")
    }

    private fun translateToKannada(text: String): String {
        // Basic keyword replacement for Kannada
        return text.replace("Adjust exposure", "ಎಕ್ಸ್‌ಪೋಶರ್ ಹೊಂದಿಸಿ")
            .replace("Switch to", "ಬದಲಿಸಿ")
            .replace("mode", "ಮೋಡ್")
            .replace("Apply", "ಅನ್ವಯಿಸಿ")
            .replace("filter", "ಫಿಲ್ಟರ್")
    }

    private fun translateToTamil(text: String): String {
        // Basic keyword replacement for Tamil
        return text.replace("Adjust exposure", "எக்ஸ்போஷரை சரிசெய்யவும்")
            .replace("Switch to", "மாற்றவும்")
            .replace("mode", "முறை")
            .replace("Apply", "பயன்படுத்தவும்")
            .replace("filter", "வடிகட்டி")
    }

    private fun translateToTelugu(text: String): String {
        // Basic keyword replacement for Telugu
        return text.replace("Adjust exposure", "ఎక్స్‌పోజర్‌ని సర్దుబాటు చేయండి")
            .replace("Switch to", "మార్చండి")
            .replace("mode", "మోడ్")
            .replace("Apply", "వర్తింపజేయండి")
            .replace("filter", "ఫిల్టర్")
    }

    /**
     * Stop current speech and clear queue
     */
    fun stopSpeaking() {
        tts?.stop()
        suggestionQueue.clear()
        isCurrentlySpeaking = false
        Log.i(TAG, "Stopped speaking and cleared queue")
    }

    /**
     * Check if TTS is currently speaking
     */
    fun isSpeaking(): Boolean {
        return tts?.isSpeaking == true
    }

    /**
     * Check if TTS is initialized and ready
     */
    fun isReady(): Boolean {
        return isInitialized && tts != null
    }

    /**
     * Get current language
     */
    fun getCurrentLanguage(): String {
        return currentLanguage
    }

    /**
     * Set initialization listener
     */
    fun setOnInitializationListener(listener: (Boolean) -> Unit) {
        onInitializationListener = listener
    }

    /**
     * Release TTS resources
     */
    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
            currentTranslator?.close()
            isInitialized = false
            suggestionQueue.clear()
            translationCache.clear()
            Log.i(TAG, "TTS shutdown completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error during TTS shutdown", e)
        }
    }
    // Add this method to VoiceHelper
    fun preloadCommonTranslations(languageCode: String) {
        if (languageCode == LANG_ENGLISH) return

        val commonTerms = listOf(
            "Adjust exposure", "Switch to portrait mode", "Apply vivid filter",
            "Enable flash", "Disable flash", "Set zoom", "Hold steady",
            "Move closer", "Step back", "Tilt phone", "Better lighting needed"
        )

        commonTerms.forEach { term ->
            translateAsync(term, languageCode) { translated ->
                Log.d(TAG, "Preloaded: $term -> $translated")
            }
        }
    }


}