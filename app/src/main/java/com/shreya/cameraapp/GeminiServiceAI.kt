package com.shreya.cameraapp

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import android.util.Base64
import androidx.camera.core.ImageCapture
import android.os.Looper
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy


class GeminiAIService(private val context: Context) {
    private var lastSuggestions = mutableListOf<String>()
    private var lastCameraState: DetailedCameraInfo? = null
    private var lastSuggestionTime = 0L
    private var dailyCallCount = 0
    private var lastResetDate = ""
    private val MAX_DAILY_CALLS = 40 // Leave buffer of 10
    private var lastFrameAnalysis: RealTimeVisionAnalyzer.FrameAnalysis? = null
    private val suggestionHistory = mutableListOf<String>()
    private var usedSuggestionTypes = mutableSetOf<String>()
    private var sceneChangeCounter = 0


    companion object {
        private const val TAG = "GeminiAIService"
        private const val GEMINI_API_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash-exp:generateContent"
        private const val API_KEY = "your_api_key"  //i have replaced

        private const val TIMEOUT_SECONDS = 30L
    }


    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS) // increased
        .readTimeout(60, TimeUnit.SECONDS)    // was 30, now 60
        .writeTimeout(60, TimeUnit.SECONDS)   // was 30, now 60
        .build()

    /**
     * NEW: Get smart, actionable suggestions with one-tap actions
     */
    private val suggestionCache = mutableMapOf<String, List<AIEducationHelper.ActionableSuggestion>>()

    suspend fun getSmartActionableSuggestions(
        cameraInfo: DetailedCameraInfo,
        frameAnalysis: RealTimeVisionAnalyzer.FrameAnalysis? = null
    ): List<AIEducationHelper.ActionableSuggestion> = withContext(Dispatchers.IO) {



        // ✅ Throttle: allow new Gemini call only every 3 seconds
        val now = System.currentTimeMillis()
        if (now - lastSuggestionTime < 3000) {
            Log.d(TAG, "⏳ Throttled: waiting before next AI call")
            return@withContext emptyList()
        }
        lastSuggestionTime = now

        if (!canMakeAPICall()) {
            Log.w(TAG, "Daily API quota limit reached, skipping AI call")
            return@withContext emptyList()
        }

        dailyCallCount++

        try {
            Log.d(TAG, "🎯 Getting actionable AI suggestions...")

            val prompt = createActionablePrompt(cameraInfo, frameAnalysis)
            val aiResponse = if (frameAnalysis != null) {
                val previewBitmap = withContext(Dispatchers.Main) {
                    try {
                        (context as? MainActivity)?.cameraManager?.capturePreviewBitmap()
                    } catch (e: Exception) {
                        Log.e(TAG, "Preview bitmap capture failed", e)
                        null
                    }
                }
                if (previewBitmap != null) {
                    callGeminiWithImage(prompt, previewBitmap)
                } else {
                    Log.w(TAG, "Preview bitmap is null, using text-only analysis")
                    callGeminiTextOnly(prompt)
                }
            } else {
                callGeminiTextOnly(prompt)
            }

            Log.d(TAG, "✅ AI Response received")
            val suggestions = parseActionableResponse(aiResponse, cameraInfo)
            Log.d(TAG, "📋 Got ${suggestions.size} actionable suggestions")



            return@withContext suggestions

        } catch (e: Exception) {
            Log.e(TAG, "❌ AI failed, using smart fallbacks", e)
            return@withContext getSmartFallbackActions(cameraInfo)
        }
    }
    private suspend fun callGeminiWithImage(prompt: String, bitmap: Bitmap): String {
        // RESIZE IMAGE FIRST - This is the biggest performance gain
        val resizedBitmap = resizeBitmap(bitmap, 640, 480) // Much smaller!

        val baos = ByteArrayOutputStream()
        resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 60, baos) // Lower quality
        val imageBytes = baos.toByteArray()
        val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)

        Log.d(TAG, "Image size: ${imageBytes.size / 1024}KB") // Monitor size

        val requestBody = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", prompt)
                        })
                        put(JSONObject().apply {
                            put("inline_data", JSONObject().apply {
                                put("mime_type", "image/jpeg")
                                put("data", base64Image)
                            })
                        })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.4)
                put("maxOutputTokens", 500) // Shorter responses = faster
                put("topP", 0.8)
            })
        }

        val request = Request.Builder()
            .url("$GEMINI_API_URL?key=$API_KEY")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .build()

        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw Exception("Empty response")

        if (!response.isSuccessful) {
            Log.e(TAG, "API Error: $responseBody")
            throw Exception("API call failed: ${response.code}")
        }

        return parseGeminiResponse(responseBody)
    }

    // 3. ADD IMAGE RESIZE FUNCTION - Add this new method:
    private fun resizeBitmap(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        // Calculate scale to maintain aspect ratio
        val scale = minOf(
            maxWidth.toFloat() / width,
            maxHeight.toFloat() / height,
            1.0f // Never upscale
        )

        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /**
     * Create focused prompt for actionable advice
     */
    // REPLACE the entire prompt with structured photography analysis
    private fun createActionablePrompt(
        cameraInfo: DetailedCameraInfo,
        frameAnalysis: RealTimeVisionAnalyzer.FrameAnalysis? = null
    ): String {
        val sceneType = detectSceneType(cameraInfo, frameAnalysis)
        val lightingContext = when {
            frameAnalysis?.brightness ?: 100.0 < 50 -> "Very dark scene"
            frameAnalysis?.brightness ?: 100.0 < 80 -> "Low light conditions"
            frameAnalysis?.brightness ?: 100.0 > 180 -> "Very bright scene"
            frameAnalysis?.isBacklit == true -> "Subject is backlit"
            else -> "Normal lighting"
        }
        val brightnessRecommendation = when {
            frameAnalysis?.brightness ?: 100.0 < 40 -> {
                val targetPercent = 70 // Suggest 70% for very dark scenes
                "Increase brightness to $targetPercent% to brighten the scene"
            }
            frameAnalysis?.brightness ?: 100.0 < 80 -> {
                val targetPercent = 60
                "Increase brightness to $targetPercent% for better visibility"
            }
            frameAnalysis?.brightness ?: 100.0 > 180 -> {
                val targetPercent = 40
                "Decrease brightness to $targetPercent% to avoid overexposure"
            }
            else -> null
        }

        return """
You are a master photographer with 20 years of experience teaching beginners. Look at this photo and give advice like you're standing right next to someone, helping them improve their shot.

CURRENT SCENE ANALYSIS:
- Camera: ${if (cameraInfo.isUsingFrontCamera) "Front camera (selfie)" else "Back camera"}
- Zoom: ${String.format("%.1f", cameraInfo.zoomRatio)}x
- Flash: ${getFlashStatusText(cameraInfo.flashMode)}
- Lighting: $lightingContext
- Scene brightness: ${frameAnalysis?.brightness?.toInt() ?: "unknown"}/255
- Scene type: $sceneType
- Faces detected: ${frameAnalysis?.faceCount ?: 0}
- Image sharpness: ${if (frameAnalysis?.hasMotionBlur == true) "Motion blur detected" else "Sharp"}
${if (brightnessRecommendation != null) "- RECOMMENDED: $brightnessRecommendation" else ""}

WHAT YOU CAN DO AUTOMATICALLY (Phone settings):
- Adjust flash (ON/OFF/AUTO)
- Change zoom level
- Switch camera (front/back)
- Enable night mode
- Enable portrait mode
- Enable food mode
- Enable macro mode
- Turn on grid lines
- Change aspect ratio
- **Adjust brightness (1-100%) based on scene lighting** ← IMPORTANT: Include specific percentage like "50%" or "70% but suggest that percentage after properly analysing the scene brightness like a professional photographer."

ANALYZE THE IMAGE LIKE A PRO:
Look at composition, lighting, subject placement, background, and overall photo quality. Give advice that helps create stunning photos, not just technically correct ones.

Consider these professional aspects:
- Is the subject well-positioned and engaging?
- Does the lighting flatter the subject?
- Is the background clean or distracting?
- Are there missed creative opportunities?
- What would make this photo stand out?

GIVE ADVICE IN SIMPLE WORDS:
Speak like a friendly teacher, not a technical manual. Use phrases like:
- "Try moving a bit to the left"
- "The light looks harsh - step into some shade"
- "Get lower for a more dramatic angle"
- "That background is distracting - move closer to your subject"

Return EXACTLY this JSON format with 3-4 diverse suggestions:
{
  "suggestions": [
    {
      "title": "Move to Better Light",
      "description": "The harsh sunlight is creating shadows on your face",
      "action": "MOVE_TO_SHADE",
      "action_value": "find_shade",
      "priority": 1,
      "icon": "☀️"
    },
    {
      "title": "Get Down Low",
      "description": "Crouch down and shoot upward for a more powerful angle",
      "action": "CHANGE_ANGLE",
      "action_value": "low_angle",
      "priority": 2,
      "icon": "📐"
    },
    {
      "title": "Switch to Macro Mode",
      "description": "Perfect for capturing fine details in close-up shots",
      "action": "ENABLE_MACRO",
      "action_value": "macro_mode",
      "priority": 1,
      "icon": "🔍"
    },
    {
      "title": "Try Food Mode",
      "description": "Optimized settings make food look more appetizing",
      "action": "ENABLE_FOOD", 
      "action_value": "food_mode",
      "priority": 1,
      "icon": "🍽️"
    }  
  ] 
}
MANDATORY FOR DEMO: Always include exactly ONE phone-controlled suggestion (portrait,night,flash, zoom, grid, or aspect ratio) that can be automatically applied, plus 2-3 movement suggestions. Choose the most relevant phone setting based on current conditions.
You MUST include exactly ONE phone-controlled suggestion that can be applied automatically. Choose the most relevant one from:
- Grid lines (GRID_ON) - if composition help needed
- Zoom adjustment (ZOOM_IN/ZOOM_OUT) - if framing needs improvement  
- Flash control (FLASH_ON/FLASH_OFF) - if lighting needs adjustment
- Aspect ratio (RATIO_16_9/RATIO_4_3) - if different framing would help
- Night mode (ENABLE_NIGHT) - if scene is dark
- Portrait mode (ENABLE_PORTRAIT) - if there are faces
- Macro mode (ENABLE_MACRO) - if shooting very close objects, flowers, insects, or fine details
- Food mode (ENABLE_FOOD) - if photographing food, meals, or culinary subjects



Return exactly 3-4 suggestions with ONE being a phone-controlled action:

IMPORTANT: Give varied, creative advice based on what you actually see. Don't repeat the same technical suggestions every time. Focus on making beautiful photos, not just correct camera settings.
"""
    }

    /**
     * Parse AI response into actionable suggestions
     */
    private fun parseActionableResponse(
        aiResponse: String,
        cameraInfo: DetailedCameraInfo
    ): List<AIEducationHelper.ActionableSuggestion> {

        return try {
            val jsonStart = aiResponse.indexOf("{")
            val jsonEnd = aiResponse.lastIndexOf("}") + 1

            if (jsonStart != -1 && jsonEnd > jsonStart) {
                val jsonString = aiResponse.substring(jsonStart, jsonEnd)
                val jsonObject = JSONObject(jsonString)
                val suggestionsArray = jsonObject.getJSONArray("suggestions")

                val suggestions = mutableListOf<AIEducationHelper.ActionableSuggestion>()

                for (i in 0 until minOf(suggestionsArray.length(), 4)) {

                    val suggestionObj = suggestionsArray.getJSONObject(i)


                    var suggestion = AIEducationHelper.ActionableSuggestion(

                        title = suggestionObj.getString("title"),
                        description = suggestionObj.getString("description"),
                        action = parseAction(suggestionObj.getString("action")),

                        icon = suggestionObj.getString("icon"),
                        priority = suggestionObj.optInt("priority", 1),


                        )

                    suggestions.add(suggestion)
// Extract target value from AI response
                    val actionValue = suggestionObj.optString("action_value", "")
                    if (actionValue.isNotEmpty()) {
                        suggestion = suggestion.copy(targetValue = actionValue)
                    }
                }
                // ADD this filtering after parsing suggestions
                val uniqueSuggestions = suggestions.filter { suggestion ->
                    !suggestionHistory.contains(suggestion.title)
                }.take(4)

// Update history
                suggestionHistory.addAll(uniqueSuggestions.map { it.title })
                if (suggestionHistory.size > 50) suggestionHistory.removeFirst() // Keep longer history

                return suggestions
            } else {
                return parseTextToActions(aiResponse, cameraInfo)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Parse failed - AI Response was: $aiResponse", e)
            Log.e(TAG, "Ask Gemini", e)
            return getSmartFallbackActions(cameraInfo)
        }
    }


    private fun detectSceneType(cameraInfo: DetailedCameraInfo, analysis: RealTimeVisionAnalyzer.FrameAnalysis?): String {
        return when {
            analysis?.let { it.colorfulness > 150 && it.contrast > 100 } == true -> "Food photography scene"
            cameraInfo.zoomRatio > 2.0f -> "Macro/close-up scene"
            cameraInfo.isUsingFrontCamera && analysis?.faceCount == 1 -> "Solo selfie"
            cameraInfo.isUsingFrontCamera && analysis?.faceCount ?: 0 > 1 -> "Group selfie"
            analysis?.faceCount ?: 0 > 2 -> "Group photo"
            analysis?.faceCount == 1 -> "Portrait shot"
            analysis?.brightness ?: 0.0 < 60 -> "Dark scene"
            analysis?.brightness ?: 0.0 > 160 -> "Bright outdoor scene"
            cameraInfo.zoomRatio > 2f -> "Zoomed shot"
            else -> "General photo"
        }
    }

    private fun getExposureContext(analysis: RealTimeVisionAnalyzer.FrameAnalysis?): String {
        return when {
            analysis?.isOverexposed == true -> "OVEREXPOSED (too bright)"
            analysis?.isUnderexposed == true -> "UNDEREXPOSED (too dark)"
            else -> "BALANCED"
        }
    }

    private fun parseAction(actionString: String): AIEducationHelper.QuickAction {
        return when (actionString.uppercase()) {
            "FLASH_ON" -> AIEducationHelper.QuickAction.FLASH_ON
            "FLASH_OFF" -> AIEducationHelper.QuickAction.FLASH_OFF
            "ZOOM_OUT" -> AIEducationHelper.QuickAction.ZOOM_OUT
            "ZOOM_IN" -> AIEducationHelper.QuickAction.ZOOM_IN
            "SWITCH_CAMERA" -> AIEducationHelper.QuickAction.SWITCH_CAMERA
            "ENABLE_NIGHT" -> AIEducationHelper.QuickAction.ENABLE_NIGHT
            "DISABLE_NIGHT" -> AIEducationHelper.QuickAction.DISABLE_NIGHT
            "MOVE_CLOSER" -> AIEducationHelper.QuickAction.MOVE_CLOSER
            "MOVE_BACK" -> AIEducationHelper.QuickAction.MOVE_BACK
            "PORTRAIT_MODE" -> AIEducationHelper.QuickAction.ENABLE_PORTRAIT
            "NIGHT_MODE" -> AIEducationHelper.QuickAction.ENABLE_NIGHT
            "STABILIZATION_ON" -> AIEducationHelper.QuickAction.ENABLE_STABILIZATION
            "GRID_ON" -> AIEducationHelper.QuickAction.ENABLE_GRID
            "FILTER_WARM" -> AIEducationHelper.QuickAction.APPLY_WARM_FILTER
            "FILTER_VIVID" -> AIEducationHelper.QuickAction.APPLY_VIVID_FILTER
            "RATIO_16_9" -> AIEducationHelper.QuickAction.RATIO_16_9
            "RATIO_4_3" -> AIEducationHelper.QuickAction.RATIO_4_3
            "RATIO_1_1" -> AIEducationHelper.QuickAction.RATIO_1_1
            "RATIO_FULL" -> AIEducationHelper.QuickAction.RATIO_FULL
            "MOVE_TO_SHADE" -> AIEducationHelper.QuickAction.MOVE_BACK
            "CHANGE_ANGLE" -> AIEducationHelper.QuickAction.MOVE_CLOSER
            "TRY_DIFFERENT_POSE" -> AIEducationHelper.QuickAction.HOLD_STEADY
            "CLEAN_BACKGROUND" -> AIEducationHelper.QuickAction.MOVE_BACK
            "GET_CLOSER" -> AIEducationHelper.QuickAction.MOVE_CLOSER
            "STEP_BACK" -> AIEducationHelper.QuickAction.MOVE_BACK
            "FIND_BETTER_LIGHT" -> AIEducationHelper.QuickAction.FLASH_ON
            "ENABLE_MACRO" -> AIEducationHelper.QuickAction.ENABLE_MACRO
            "ENABLE_FOOD" -> AIEducationHelper.QuickAction.ENABLE_FOOD
            "ADJUST_BRIGHTNESS" -> AIEducationHelper.QuickAction.ADJUST_BRIGHTNESS
            else -> AIEducationHelper.QuickAction.HOLD_STEADY
        }
    }

    /**
     * Parse plain text into actionable suggestions
     */
    private fun parseTextToActions(
        text: String,
        cameraInfo: DetailedCameraInfo
    ): List<AIEducationHelper.ActionableSuggestion> {

        // Simple text analysis to extract actionable advice
        val lowerText = text.lowercase()
        val suggestions = mutableListOf<AIEducationHelper.ActionableSuggestion>()

        when {
            cameraInfo.isLowLight && lowerText.contains("flash") -> {
                suggestions.add(
                    AIEducationHelper.ActionableSuggestion(
                        "Turn on Flash",
                        "Low light needs flash for clear photos",
                        AIEducationHelper.QuickAction.FLASH_ON,
                        "⚡",
                        1
                    )
                )
            }

            cameraInfo.zoomRatio > 2f && (lowerText.contains("zoom") || lowerText.contains("closer")) -> {
                suggestions.add(
                    AIEducationHelper.ActionableSuggestion(
                        "Zoom Out",
                        "High zoom causes blur - try moving closer",
                        AIEducationHelper.QuickAction.ZOOM_OUT,
                        "🔍",
                        1
                    )
                )
            }

            else -> {
                suggestions.add(
                    AIEducationHelper.ActionableSuggestion(
                        "Hold Steady",
                        "Keep phone stable for sharp photos",
                        AIEducationHelper.QuickAction.HOLD_STEADY,
                        "🎯",
                        2
                    )
                )
            }
        }

        return suggestions.take(2)
    }

    /**
     * Smart fallback suggestions based on camera conditions
     */
    private fun getSmartFallbackActions(cameraInfo: DetailedCameraInfo): List<AIEducationHelper.ActionableSuggestion> {
        val suggestions = mutableListOf<AIEducationHelper.ActionableSuggestion>()

        // FORCE these specific features for demo
        val demoFeatures = listOf(
            // Flash
            if (cameraInfo.flashMode == androidx.camera.core.ImageCapture.FLASH_MODE_OFF) {
                AIEducationHelper.ActionableSuggestion(
                    "Turn Flash ON", "Brighten your photo with flash",
                    AIEducationHelper.QuickAction.FLASH_ON, "⚡", 1
                )
            } else {
                AIEducationHelper.ActionableSuggestion(
                    "Turn Flash OFF", "Try natural lighting",
                    AIEducationHelper.QuickAction.FLASH_OFF, "⚡", 1
                )
            },

            // ALWAYS include aspect ratio
            AIEducationHelper.ActionableSuggestion(
                "Try 16:9 Ratio", "Cinematic wide format for better framing",
                AIEducationHelper.QuickAction.RATIO_16_9, "📱", 1
            ),

            // ALWAYS include zoom
            if (cameraInfo.zoomRatio > 1.5f) {
                AIEducationHelper.ActionableSuggestion(
                    "Zoom to 1x", "Reset zoom for wider view",
                    AIEducationHelper.QuickAction.ZOOM_OUT, "🔍", 1
                )
            } else {
                AIEducationHelper.ActionableSuggestion(
                    "Zoom to 2x", "Get closer to your subject",
                    AIEducationHelper.QuickAction.ZOOM_IN, "🔍", 1
                )
            },

            // Grid
            AIEducationHelper.ActionableSuggestion(
                "Enable Grid", "Use rule of thirds for composition",
                AIEducationHelper.QuickAction.ENABLE_GRID, "⊞", 2
            )
        )

        return demoFeatures.take(3)
    }

    // Keep your existing methods for backward compatibility


    private suspend fun callGeminiTextOnly(prompt: String): String {
        val requestBody = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", prompt)
                        })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.3)  // Lower temperature for more focused responses
                put("maxOutputTokens", 1000)  // Shorter responses
                put("topP", 0.8)
            })
        }
        Log.d("GeminiAIService", "Current API Key (first 8 chars): " + API_KEY.take(8))


        val request = Request.Builder()
            .url("$GEMINI_API_URL?key=$API_KEY")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .build()

        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw Exception("Empty response")

        if (!response.isSuccessful) {
            Log.e(TAG, "API Error: $responseBody")
            throw Exception("API call failed: ${response.code}")
        }

        return parseGeminiResponse(responseBody)
    }

    private fun parseGeminiResponse(responseBody: String): String {
        val jsonResponse = JSONObject(responseBody)
        val candidates = jsonResponse.getJSONArray("candidates")
        val firstCandidate = candidates.getJSONObject(0)
        val content = firstCandidate.getJSONObject("content")
        val parts = content.getJSONArray("parts")
        val text = parts.getJSONObject(0).getString("text")
        return text
    }

    private fun checkSignificantChange(
        current: DetailedCameraInfo,
        previous: DetailedCameraInfo?,
        currentAnalysis: RealTimeVisionAnalyzer.FrameAnalysis?,
        previousAnalysis: RealTimeVisionAnalyzer.FrameAnalysis?
    ): Boolean {
        if (previous == null) return true

        // INCREASED minimum wait time to reduce API calls
        val timeDiff = System.currentTimeMillis() - lastSuggestionTime
        if (timeDiff < 5000) return false  // Wait 5 seconds minimum

        // Major camera changes only
        val significantCameraChange = (
                kotlin.math.abs(current.zoomRatio - previous.zoomRatio) > 0.5f || // More threshold
                        current.isUsingFrontCamera != previous.isUsingFrontCamera ||
                        current.cameraMode != previous.cameraMode ||
                        current.flashMode != previous.flashMode
                )

        // Major scene changes only
        val significantSceneChange = if (currentAnalysis != null && previousAnalysis != null) {
            kotlin.math.abs(currentAnalysis.brightness - previousAnalysis.brightness) > 40 || // Higher threshold
                    kotlin.math.abs(currentAnalysis.faceCount - previousAnalysis.faceCount) > 0 ||
                    currentAnalysis.hasMotionBlur != previousAnalysis.hasMotionBlur
        } else true

        return significantCameraChange || significantSceneChange
    }



    private fun canMakeAPICall(): Boolean {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date())

        if (today != lastResetDate) {
            dailyCallCount = 0
            lastResetDate = today
        }

        return dailyCallCount < MAX_DAILY_CALLS
    }

    private fun getFlashStatusText(flashMode: Int): String {
        return when (flashMode) {
            ImageCapture.FLASH_MODE_ON -> "ON"
            ImageCapture.FLASH_MODE_OFF -> "OFF"
            ImageCapture.FLASH_MODE_AUTO -> "AUTO"
            else -> "OFF"
        }
    }
}