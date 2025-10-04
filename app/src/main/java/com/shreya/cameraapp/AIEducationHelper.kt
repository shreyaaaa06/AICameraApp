package com.shreya.cameraapp

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.*
import kotlin.random.Random
import androidx.camera.core.ImageCapture

/**
 * Enhanced AI Education Helper with actionable suggestions and one-tap actions
 */
class AIEducationHelper(

    private val context: Context,
    private val onSuggestionReady: ((AISuggestion) -> Unit)? = null


) {

    // Real AI service
    private val geminiAI = GeminiAIService(context)

    // Coroutine scope for background operations
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var suggestionJob: Job? = null
    private var isEducationActive = false
    // ADD these variables after line 15
    private var suggestionHistory = mutableSetOf<String>()
    private var lastSceneType = ""

    companion object {
        private const val TAG = "AIEducationHelper"
        private const val SUGGESTION_INTERVAL = 8000L // 8 seconds for actionable suggestions
    }
    val visionAnalyzer = RealTimeVisionAnalyzer()

    // NEW: Actionable suggestion with one-tap actions
    data class ActionableSuggestion(
        val title: String,
        val description: String,
        val action: QuickAction,
        val icon: String,
        val priority: Int = 1,
        val targetValue: String? = null, // "1.5x", "AUTO", "ON", etc.
        val currentValue: String? = null  // "3.2x", "OFF", etc.
    )
    data class ApplyAllSuggestions(
        val suggestions: List<ActionableSuggestion>,
        val canApplyAll: Boolean = true
    )

    // NEW: Quick actions that can be performed with one tap
    enum class QuickAction {
        FLASH_ON, FLASH_OFF, ZOOM_OUT, ZOOM_IN, SWITCH_CAMERA,
        ENABLE_NIGHT, DISABLE_NIGHT, HOLD_STEADY, MOVE_CLOSER, MOVE_BACK,
        ENABLE_GRID, ENABLE_PORTRAIT, ENABLE_STABILIZATION,
        APPLY_WARM_FILTER, APPLY_COOL_FILTER, APPLY_VIVID_FILTER, APPLY_BW_FILTER,RATIO_16_9, RATIO_4_3, RATIO_1_1, RATIO_FULL,
        ENABLE_MACRO, ENABLE_FOOD, MACRO_FOCUS_CLOSER, MACRO_FOCUS_FARTHER,ADJUST_BRIGHTNESS
    }

    // Enhanced suggestion data class
    data class CameraSuggestion(
        val title: String,
        val description: String,
        val type: SuggestionType,
        val icon: String,
        val priority: Int = 1 // 1 = high, 2 = medium, 3 = low
    )

    // Legacy compatibility
    data class AISuggestion(
        val title: String,
        val message: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    // Types of suggestions we can provide
    enum class SuggestionType {
        LIGHTING,
        COMPOSITION,
        SETTINGS,
        TECHNIQUE,
        GENERAL
    }

    /**
     * NEW: Get actionable suggestions that users can tap to apply immediately
     */
    suspend fun getActionableSuggestions(
        cameraInfo: DetailedCameraInfo,
        previewBitmap: Bitmap? = null
    ): List<ActionableSuggestion> {
        Log.d(TAG, "Getting REAL-TIME actionable AI suggestions...")

        return try {
            // NEW: Analyze camera frame in real-time
            val frameAnalysis = if (previewBitmap != null) {
                visionAnalyzer.analyzeFrame(previewBitmap)
            } else {
                null
            }

            // Generate suggestions based on REAL vision analysis
            val realTimeSuggestions = geminiAI.getSmartActionableSuggestions(cameraInfo, frameAnalysis)

            if (realTimeSuggestions.isNotEmpty()) {
                Log.d(TAG, "✅ Generated ${realTimeSuggestions.size} real-time suggestions!")
                realTimeSuggestions
            } else {
                Log.w(TAG, "No real-time issues found, using basic tips")
                getContextualActionableFallbacks(cameraInfo)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Real-time analysis failed, using fallbacks", e)
            getContextualActionableFallbacks(cameraInfo)
        }
    }
    suspend fun getEnhancedSuggestions(
        cameraInfo: DetailedCameraInfo,
        frameAnalysis: RealTimeVisionAnalyzer.FrameAnalysis
    ): List<ActionableSuggestion> {
        val suggestions = mutableListOf<ActionableSuggestion>()
        var priority = 1

        // Critical issues first
        if (frameAnalysis.hasMotionBlur) {
            suggestions.add(ActionableSuggestion(
                "Hold Steady",
                "Camera shake detected - brace phone",
                QuickAction.HOLD_STEADY, "📱", priority++
            ))
        }

        if (frameAnalysis.isUnderexposed && cameraInfo.flashMode == androidx.camera.core.ImageCapture.FLASH_MODE_OFF) {
            suggestions.add(ActionableSuggestion(
                "Enable Flash",
                "Image too dark - flash will help",
                QuickAction.FLASH_ON, "⚡", priority++
            ))
        }

        if (frameAnalysis.isOverexposed) {
            suggestions.add(ActionableSuggestion(
                "Reduce Exposure",
                "Image too bright - turn off flash",
                QuickAction.FLASH_OFF, "☀️", priority++
            ))
        }

        // Composition suggestions
        if (frameAnalysis.horizonTilt > 2.0) {
            suggestions.add(ActionableSuggestion(
                "Level Horizon",
                "Tilt phone ${if (frameAnalysis.horizonTilt > 0) "right" else "left"} ${kotlin.math.abs(frameAnalysis.horizonTilt).toInt()}°",
                QuickAction.HOLD_STEADY, "📐", priority++
            ))
        }

        return suggestions.take(3)
    }

    // Add this new function
    private fun generateRealTimeSuggestions(
        cameraInfo: DetailedCameraInfo,
        frameAnalysis: RealTimeVisionAnalyzer.FrameAnalysis?
    ): List<ActionableSuggestion> {
        val suggestions = mutableListOf<ActionableSuggestion>()
        var priority = 1

        frameAnalysis?.let { analysis ->
            // 1. CRITICAL: Blur detection
            if (analysis.hasMotionBlur) {
                suggestions.add(ActionableSuggestion(
                    "Hold Steady",
                    "Motion blur detected - brace your phone",
                    QuickAction.HOLD_STEADY,
                    "📱",
                    priority++
                ))
            }

            // 2. CRITICAL: Lighting issues
            if (analysis.brightness < 50 && cameraInfo.flashMode == androidx.camera.core.ImageCapture.FLASH_MODE_OFF) {
                suggestions.add(ActionableSuggestion(
                    "Turn Flash On",
                    "Too dark (${analysis.brightness.toInt()}/255) - need flash",
                    QuickAction.FLASH_ON,
                    "⚡",
                    priority++
                ))
            }

            // 3. COMPOSITION: Rule of thirds
            if (analysis.compositionScore < 0.3) {
                suggestions.add(ActionableSuggestion(
                    "Move Subject",
                    "Place subject on grid lines for better composition",
                    QuickAction.ENABLE_GRID,
                    "📐",
                    priority++
                ))
            }

            // 4. FACES: When faces detected but conditions not optimal
            if (analysis.faceCount > 0) {
                if (analysis.isBacklit) {
                    suggestions.add(ActionableSuggestion(
                        "Fix Backlight",
                        "${analysis.faceCount} face(s) backlit - move or use flash",
                        QuickAction.FLASH_ON,
                        "👤",
                        priority++
                    ))
                } else if (cameraInfo.zoomRatio < 1.2f && analysis.faceCount <= 2) {
                    suggestions.add(ActionableSuggestion(
                        "Zoom to 1.5x",
                        "Closer framing for ${analysis.faceCount} person(s)",
                        QuickAction.ZOOM_IN,
                        "🔍",
                        priority++
                    ))
                }
            }

            // 5. HIGH ZOOM warning
            if (cameraInfo.zoomRatio > 3.0f) {
                suggestions.add(ActionableSuggestion(
                    "Zoom to 2x",
                    "Current ${String.format("%.1f", cameraInfo.zoomRatio)}x too high - may blur",
                    QuickAction.ZOOM_OUT,
                    "🔍",
                    priority++
                ))
            }
        }

        return suggestions.take(4).sortedBy { it.priority }
    }
    /**
     * Apply multiple actions automatically
     */
    /**
     * FIXED: Apply multiple actions with proper error handling and logging
     */
    fun applyMultipleActions(
        suggestions: List<ActionableSuggestion>,
        cameraManager: CameraManager
    ): String {
        val appliedActions = mutableListOf<String>()
        val failedActions = mutableListOf<String>()

        Log.d("AIEducationHelper", "Applying ${suggestions.size} suggestions...")

        suggestions.forEach { suggestion ->
            Log.d("AIEducationHelper", "Applying action: ${suggestion.action}")

            val success = when (suggestion.action) {
                QuickAction.FLASH_ON -> {
                    try {
                        val result = cameraManager.setFlashOn() // Use specific function
                        if (result) {
                            appliedActions.add("Flash ON")
                            Log.d("AIEducationHelper", "✅ Flash turned ON")
                        } else {
                            failedActions.add("Flash ON failed")
                            Log.e("AIEducationHelper", "❌ Flash ON failed")
                        }
                        result
                    } catch (e: Exception) {
                        Log.e("AIEducationHelper", "Flash ON exception", e)
                        failedActions.add("Flash ON error")
                        false
                    }
                }

                QuickAction.FLASH_OFF -> {
                    try {
                        val result = cameraManager.setFlashOff() // Use specific function
                        if (result) {
                            appliedActions.add("Flash OFF")
                            Log.d("AIEducationHelper", "✅ Flash turned OFF")
                        } else {
                            failedActions.add("Flash OFF failed")
                            Log.e("AIEducationHelper", "❌ Flash OFF failed")
                        }
                        result
                    } catch (e: Exception) {
                        Log.e("AIEducationHelper", "Flash OFF exception", e)
                        failedActions.add("Flash OFF error")
                        false
                    }
                }

                QuickAction.ZOOM_IN -> {
                    try {
                        val oldZoom = cameraManager.getCurrentZoom()
                        val newZoom = cameraManager.zoomIn()
                        if (newZoom > oldZoom) {
                            appliedActions.add("Zoomed to ${String.format("%.1f", newZoom)}x")
                            Log.d("AIEducationHelper", "✅ Zoomed in: $oldZoom -> $newZoom")
                            true
                        } else {
                            failedActions.add("Zoom In failed")
                            Log.e("AIEducationHelper", "❌ Zoom in failed: $oldZoom -> $newZoom")
                            false
                        }
                    } catch (e: Exception) {
                        Log.e("AIEducationHelper", "Zoom in exception", e)
                        failedActions.add("Zoom In error")
                        false
                    }
                }

                QuickAction.ZOOM_OUT -> {
                    try {
                        val oldZoom = cameraManager.getCurrentZoom()
                        val newZoom = cameraManager.zoomOut()
                        if (newZoom < oldZoom) {
                            appliedActions.add("Zoomed to ${String.format("%.1f", newZoom)}x")
                            Log.d("AIEducationHelper", "✅ Zoomed out: $oldZoom -> $newZoom")
                            true
                        } else {
                            failedActions.add("Zoom Out failed")
                            Log.e("AIEducationHelper", "❌ Zoom out failed: $oldZoom -> $newZoom")
                            false
                        }
                    } catch (e: Exception) {
                        Log.e("AIEducationHelper", "Zoom out exception", e)
                        failedActions.add("Zoom Out error")
                        false
                    }
                }

                QuickAction.ENABLE_GRID -> {
                    try {
                        val wasEnabled = cameraManager.isGridEnabled()
                        if (!wasEnabled) {
                            val result = cameraManager.toggleGrid()
                            if (result) {
                                appliedActions.add("Grid ON")
                                Log.d("AIEducationHelper", "✅ Grid enabled")
                            } else {
                                failedActions.add("Grid failed")
                                Log.e("AIEducationHelper", "❌ Grid enable failed")
                            }
                            result
                        } else {
                            appliedActions.add("Grid already ON")
                            Log.d("AIEducationHelper", "Grid was already enabled")
                            true
                        }
                    } catch (e: Exception) {
                        Log.e("AIEducationHelper", "Grid toggle exception", e)
                        failedActions.add("Grid error")
                        false
                    }
                }

                QuickAction.ENABLE_PORTRAIT -> {
                    try {
                        val oldMode = cameraManager.getCurrentMode()
                        cameraManager.switchCameraMode(CameraManager.CameraMode.PORTRAIT)
                        // Give camera time to switch
                        Thread.sleep(300)
                        val newMode = cameraManager.getCurrentMode()
                        if (newMode == CameraManager.CameraMode.PORTRAIT) {
                            appliedActions.add("Portrait Mode")
                            Log.d("AIEducationHelper", "✅ Portrait mode enabled")
                            true
                        } else {
                            failedActions.add("Portrait mode failed")
                            Log.e("AIEducationHelper", "❌ Portrait mode failed: $oldMode -> $newMode")
                            false
                        }
                    } catch (e: Exception) {
                        Log.e("AIEducationHelper", "Portrait mode exception", e)
                        failedActions.add("Portrait error")
                        false
                    }
                }

                QuickAction.ENABLE_NIGHT -> {
                    try {
                        val oldMode = cameraManager.getCurrentMode()
                        cameraManager.switchCameraMode(CameraManager.CameraMode.NIGHT)
                        Thread.sleep(300)
                        val newMode = cameraManager.getCurrentMode()
                        if (newMode == CameraManager.CameraMode.NIGHT) {
                            appliedActions.add("Night Mode")
                            Log.d("AIEducationHelper", "✅ Night mode enabled")
                            true
                        } else {
                            failedActions.add("Night mode failed")
                            Log.e("AIEducationHelper", "❌ Night mode failed: $oldMode -> $newMode")
                            false
                        }
                    } catch (e: Exception) {
                        Log.e("AIEducationHelper", "Night mode exception", e)
                        failedActions.add("Night error")
                        false
                    }
                }

                QuickAction.SWITCH_CAMERA -> {
                    try {
                        val wasUsingFront = cameraManager.isUsingFrontCamera()
                        val result = cameraManager.flipCamera()
                        if (result) {
                            val nowUsingFront = cameraManager.isUsingFrontCamera()
                            appliedActions.add("Camera switched to ${if (nowUsingFront) "front" else "back"}")
                            Log.d("AIEducationHelper", "✅ Camera switched: front=$wasUsingFront -> front=$nowUsingFront")
                        } else {
                            failedActions.add("Camera switch failed")
                            Log.e("AIEducationHelper", "❌ Camera switch failed")
                        }
                        result
                    } catch (e: Exception) {
                        Log.e("AIEducationHelper", "Camera switch exception", e)
                        failedActions.add("Camera switch error")
                        false
                    }
                }

                QuickAction.ENABLE_STABILIZATION -> {
                    try {
                        val wasEnabled = cameraManager.isStabilizationEnabled
                        if (!wasEnabled) {
                            val result = cameraManager.toggleStabilization()
                            if (result) {
                                appliedActions.add("Stabilization ON")
                                Log.d("AIEducationHelper", "✅ Stabilization enabled")
                            } else {
                                failedActions.add("Stabilization failed")
                                Log.e("AIEducationHelper", "❌ Stabilization failed")
                            }
                            result
                        } else {
                            appliedActions.add("Stabilization already ON")
                            Log.d("AIEducationHelper", "Stabilization was already enabled")
                            true
                        }
                    } catch (e: Exception) {
                        Log.e("AIEducationHelper", "Stabilization exception", e)
                        failedActions.add("Stabilization error")
                        false
                    }
                }
                QuickAction.RATIO_16_9 -> {
                    try {
                        val result = cameraManager.setAspectRatio("9:16")
                        if (result) {
                            appliedActions.add("Aspect ratio: 9:16")
                            Log.d("AIEducationHelper", "✅ Aspect ratio set to 9:16")
                        } else {
                            failedActions.add("9:16 ratio failed")
                            Log.e("AIEducationHelper", "❌ 9:16 ratio failed")
                        }
                        result
                    } catch (e: Exception) {
                        Log.e("AIEducationHelper", "9:16 ratio exception", e)
                        failedActions.add("9:16 ratio error")
                        false
                    }
                }

                QuickAction.RATIO_4_3 -> {
                    try {
                        val result = cameraManager.setAspectRatio("3:4")
                        if (result) {
                            appliedActions.add("Aspect ratio: 3:4")
                            Log.d("AIEducationHelper", "✅ Aspect ratio set to 3:4")
                        } else {
                            failedActions.add("3:4 ratio failed")
                        }
                        result
                    } catch (e: Exception) {
                        Log.e("AIEducationHelper", "3:4 ratio exception", e)
                        failedActions.add("3:4 ratio error")
                        false
                    }
                }

                QuickAction.RATIO_1_1 -> {
                    try {
                        val result = cameraManager.setAspectRatio("1:1")
                        if (result) {
                            appliedActions.add("Aspect ratio: 1:1")
                            Log.d("AIEducationHelper", "✅ Aspect ratio set to 1:1")
                        } else {
                            failedActions.add("1:1 ratio failed")
                        }
                        result
                    } catch (e: Exception) {
                        Log.e("AIEducationHelper", "1:1 ratio exception", e)
                        failedActions.add("1:1 ratio error")
                        false
                    }
                }
                QuickAction.ENABLE_MACRO -> {
                    try {
                        val oldMode = cameraManager.getCurrentMode()
                        cameraManager.switchCameraMode(CameraManager.CameraMode.MACRO)
                        Thread.sleep(300)
                        val newMode = cameraManager.getCurrentMode()
                        if (newMode == CameraManager.CameraMode.MACRO) {
                            appliedActions.add("Macro Mode")
                            Log.d("AIEducationHelper", "✅ Macro mode enabled")
                            true
                        } else {
                            failedActions.add("Macro mode failed")
                            Log.e("AIEducationHelper", "❌ Macro mode failed: $oldMode -> $newMode")
                            false
                        }
                    } catch (e: Exception) {
                        Log.e("AIEducationHelper", "Macro mode exception", e)
                        failedActions.add("Macro error")
                        false
                    }
                }

                QuickAction.ENABLE_FOOD -> {
                    try {
                        val oldMode = cameraManager.getCurrentMode()
                        cameraManager.switchCameraMode(CameraManager.CameraMode.FOOD)
                        Thread.sleep(300)
                        val newMode = cameraManager.getCurrentMode()
                        if (newMode == CameraManager.CameraMode.FOOD) {
                            appliedActions.add("Food Mode")
                            Log.d("AIEducationHelper", "✅ Food mode enabled")
                            true
                        } else {
                            failedActions.add("Food mode failed")
                            Log.e("AIEducationHelper", "❌ Food mode failed: $oldMode -> $newMode")
                            false
                        }
                    } catch (e: Exception) {
                        Log.e("AIEducationHelper", "Food mode exception", e)
                        failedActions.add("Food error")
                        false
                    }
                }

                QuickAction.MOVE_CLOSER -> {
                    try {
                        // This is a user guidance action - just log it
                        appliedActions.add("Move closer guidance")
                        Log.d("AIEducationHelper", "✅ Move closer guidance provided")
                        true
                    } catch (e: Exception) {
                        Log.e("AIEducationHelper", "Move closer guidance exception", e)
                        failedActions.add("Move closer error")
                        false
                    }
                }

                QuickAction.MOVE_BACK -> {
                    try {
                        // This is a user guidance action - just log it
                        appliedActions.add("Move back guidance")
                        Log.d("AIEducationHelper", "✅ Move back guidance provided")
                        true
                    } catch (e: Exception) {
                        Log.e("AIEducationHelper", "Move back guidance exception", e)
                        failedActions.add("Move back error")
                        false
                    }
                }
                QuickAction.ADJUST_BRIGHTNESS -> {
                    try {
                        // Extract brightness value from suggestion title or description
                        val brightnessValue = suggestion.targetValue?.let { target ->
                            // Try to parse from targetValue first
                            val percentMatch = Regex("""(\d+)%?""").find(target)
                            percentMatch?.groupValues?.get(1)?.toIntOrNull()
                        } ?: run {
                            // Fallback: parse from title or description
                            val titleMatch = Regex("""(\d+)%""").find(suggestion.title)
                            val descMatch = Regex("""(\d+)%""").find(suggestion.description)
                            (titleMatch ?: descMatch)?.groupValues?.get(1)?.toIntOrNull()
                        }

                        if (brightnessValue != null) {
                            Log.d("AIEducationHelper", "Attempting to set brightness to $brightnessValue%")
                            val result = cameraManager.adjustBrightnessFromPercent(brightnessValue)
                            if (result) {
                                appliedActions.add("Brightness set to $brightnessValue%")
                                Log.d("AIEducationHelper", "✅ Brightness set to $brightnessValue%")
                            } else {
                                failedActions.add("Brightness adjustment to $brightnessValue% failed")
                                Log.e("AIEducationHelper", "❌ Brightness adjustment failed")
                            }
                            result
                        } else {
                            // Generic adjustment if no specific value found
                            val currentBrightness = cameraManager.getCurrentBrightness()
                            val newBrightness = if (suggestion.title.contains("increase", ignoreCase = true) ||
                                suggestion.description.contains("increase", ignoreCase = true)) {
                                (currentBrightness + 1).coerceIn(-2, 2)
                            } else {
                                (currentBrightness - 1).coerceIn(-2, 2)
                            }

                            Log.d("AIEducationHelper", "Generic brightness adjustment: $currentBrightness → $newBrightness")
                            val result = cameraManager.setBrightness(newBrightness)
                            if (result) {
                                appliedActions.add("Brightness ${if (newBrightness > currentBrightness) "increased" else "decreased"}")
                                Log.d("AIEducationHelper", "✅ Brightness adjusted generically")
                            } else {
                                failedActions.add("Brightness adjustment failed")
                            }
                            result
                        }
                    } catch (e: Exception) {
                        Log.e("AIEducationHelper", "Brightness exception", e)
                        failedActions.add("Brightness error: ${e.message}")
                        false
                    }
                }


                else -> {
                    Log.w("AIEducationHelper", "Action ${suggestion.action} not implemented")
                    failedActions.add("${suggestion.action} not implemented")
                    false
                }
            }
        }

        // Create detailed response message
        val message = when {
            appliedActions.isNotEmpty() && failedActions.isEmpty() ->
                appliedActions.joinToString(", ")
            appliedActions.isNotEmpty() && failedActions.isNotEmpty() ->
                "Applied: ${appliedActions.joinToString(", ")} | Failed: ${failedActions.joinToString(", ")}"
            else ->
                "Failed: ${failedActions.joinToString(", ")}"
        }

        Log.d("AIEducationHelper", "Final result: $message")
        return message
    }


    /**
     * Smart contextual fallbacks when AI fails
     */
    private fun getContextualActionableFallbacks(cameraInfo: DetailedCameraInfo): List<ActionableSuggestion> {
        val suggestions = mutableListOf<ActionableSuggestion>()

        // ADD these new mode-specific suggestions at the beginning
        when (cameraInfo.cameraMode) {
            CameraManager.CameraMode.MACRO -> {
                suggestions.add(ActionableSuggestion(
                    "Get 3-6 Inches Away",
                    "Macro works best very close to subject",
                    QuickAction.MOVE_CLOSER,
                    "🔍",
                    1
                ))
                suggestions.add(ActionableSuggestion(
                    "Use Bright Light",
                    "Macro needs lots of light for sharp details",
                    QuickAction.FLASH_ON,
                    "💡",
                    1
                ))
                return suggestions
            }

            CameraManager.CameraMode.FOOD -> {
                suggestions.add(ActionableSuggestion(
                    "Natural Light Best",
                    "Move near window for appetizing food photos",
                    QuickAction.FLASH_OFF,
                    "🪟",
                    1
                ))
                suggestions.add(ActionableSuggestion(
                    "Overhead Angle",
                    "Try shooting from directly above",
                    QuickAction.ENABLE_GRID,
                    "📐",
                    1
                ))
                return suggestions
            }

            else -> {
                // Continue with existing logic for other modes
            }
        }

        // Existing fallback logic continues here - analyze current conditions and provide specific actionable advice
        when {
            // Critical: Low light without flash
            cameraInfo.isLowLight && cameraInfo.flashMode == androidx.camera.core.ImageCapture.FLASH_MODE_OFF -> {
                suggestions.add(ActionableSuggestion(
                    "Turn on Flash",
                    "Dark scene - flash will brighten your photo",
                    QuickAction.FLASH_ON,
                    "⚡",
                    1
                ))
            }

            // Critical: High zoom causing potential blur
            cameraInfo.zoomRatio > 2.5f -> {
                suggestions.add(ActionableSuggestion(
                    "Zoom Out",
                    "High zoom (${String.format("%.1f", cameraInfo.zoomRatio)}x) may cause blur",
                    QuickAction.ZOOM_OUT,
                    "🔍",
                    1
                ))
            }

            // Suboptimal: Front camera in low light
            cameraInfo.isUsingFrontCamera && cameraInfo.isLowLight -> {
                suggestions.add(ActionableSuggestion(
                    "Switch to Back Camera",
                    "Main camera performs better in low light",
                    QuickAction.SWITCH_CAMERA,
                    "🔄",
                    1
                ))
            }

            // Composition: Grid not enabled
            !cameraInfo.gridEnabled -> {
                suggestions.add(ActionableSuggestion(
                    "Enable Grid",
                    "Grid lines help with better composition",
                    QuickAction.ENABLE_GRID,
                    "⊞",
                    2
                ))
            }

            // Good conditions - focus on technique
            else -> {
                suggestions.add(ActionableSuggestion(
                    "Hold Steady",
                    "Keep phone stable with both hands",
                    QuickAction.HOLD_STEADY,
                    "📱",
                    2
                ))
            }
        }

        // Always include a second suggestion if we only have one
        if (suggestions.size < 2) {
            val secondSuggestion = when {
                cameraInfo.cameraMode != CameraManager.CameraMode.NIGHT && cameraInfo.isLowLight -> {
                    ActionableSuggestion(
                        "Try Night Mode",
                        "Night mode brightens dark scenes",
                        QuickAction.ENABLE_NIGHT,
                        "🌙",
                        2
                    )
                }
                else -> {
                    ActionableSuggestion(
                        "Focus on Subject",
                        "Tap your main subject to focus before shooting",
                        QuickAction.HOLD_STEADY,
                        "🎯",
                        2
                    )
                }
            }
            suggestions.add(secondSuggestion)
        }

        return suggestions.take(2)
    }

    /**
     * Get smart suggestions (enhanced with AI) - Legacy compatibility
     */
    suspend fun getSmartSuggestions(
        cameraInfo: CameraInfo,
        previewBitmap: Bitmap? = null
    ): List<CameraSuggestion> {
        Log.d(TAG, "Getting smart suggestions (legacy compatibility)...")

        // Convert old CameraInfo to DetailedCameraInfo
        val detailedInfo = DetailedCameraInfo(
            hasFlash = cameraInfo.hasFlash,
            zoomRatio = cameraInfo.zoomRatio,
            isLowLight = cameraInfo.isLowLight,
            exposureState = cameraInfo.exposureState
        )

        return try {
            // Use the new actionable system but convert to old format
            val actionableSuggestions = getActionableSuggestions(detailedInfo)

            actionableSuggestions.map { actionable ->
                CameraSuggestion(
                    title = actionable.title,
                    description = actionable.description,
                    type = when (actionable.action) {
                        QuickAction.FLASH_ON, QuickAction.FLASH_OFF -> SuggestionType.LIGHTING
                        QuickAction.ENABLE_NIGHT, QuickAction.DISABLE_NIGHT -> SuggestionType.SETTINGS
                        QuickAction.ZOOM_IN, QuickAction.ZOOM_OUT -> SuggestionType.TECHNIQUE
                        QuickAction.SWITCH_CAMERA -> SuggestionType.SETTINGS
                        QuickAction.ENABLE_GRID -> SuggestionType.COMPOSITION
                        else -> SuggestionType.TECHNIQUE
                    },
                    icon = actionable.icon,
                    priority = actionable.priority
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "Smart suggestions failed, using basic fallbacks", e)
            getSmartFallbackSuggestions(cameraInfo)
        }
    }

    /**
     * Smart fallback suggestions when AI is unavailable - Legacy
     */
    private fun getSmartFallbackSuggestions(cameraInfo: CameraInfo): List<CameraSuggestion> {
        val suggestions = mutableListOf<CameraSuggestion>()

        // Context-aware fallbacks based on camera conditions
        when {
            cameraInfo.isLowLight -> {
                suggestions.add(
                    CameraSuggestion(
                        "💡 Low Light Detected",
                        "Enable Night Mode or find better lighting for clearer photos",
                        SuggestionType.LIGHTING,
                        "💡",
                        1
                    )
                )
                suggestions.add(
                    CameraSuggestion(
                        "📱 Steady Shot",
                        "Hold your phone steady - low light needs extra stability",
                        SuggestionType.TECHNIQUE,
                        "📱",
                        1
                    )
                )
            }

            cameraInfo.zoomRatio > 2.5f -> {
                suggestions.add(
                    CameraSuggestion(
                        "🔍 Zoom Warning",
                        "High zoom can blur - try moving closer to your subject",
                        SuggestionType.TECHNIQUE,
                        "🔍",
                        1
                    )
                )
            }

            else -> {
                suggestions.add(
                    CameraSuggestion(
                        "✨ Good Conditions",
                        "Great lighting! Perfect time for sharp, clear photos",
                        SuggestionType.LIGHTING,
                        "✨",
                        1
                    )
                )
            }
        }

        // Add composition tips
        suggestions.add(
            CameraSuggestion(
                "📐 Rule of Thirds",
                "Place subjects on grid lines for better composition",
                SuggestionType.COMPOSITION,
                "📐",
                2
            )
        )

        suggestions.add(
            CameraSuggestion(
                "🎯 Focus First",
                "Tap your subject to focus before taking the shot",
                SuggestionType.TECHNIQUE,
                "🎯",
                2
            )
        )

        return suggestions.take(3)
    }

    /**
     * Get beginner-friendly tips
     */
    fun getBeginnerTips(): List<CameraSuggestion> {
        return listOf(
            CameraSuggestion(
                "📚 Start Simple",
                "Great photos start with good light - look for it first!",
                SuggestionType.GENERAL,
                "📚",
                1
            ),
            CameraSuggestion(
                "📐 Use the Grid",
                "Enable grid lines and place subjects on intersection points",
                SuggestionType.COMPOSITION,
                "📐",
                1
            ),
            CameraSuggestion(
                "🎯 Focus is Key",
                "Always tap to focus on your main subject before shooting",
                SuggestionType.TECHNIQUE,
                "🎯",
                1
            )
        )
    }

    /**
     * Start education mode with actionable AI suggestions
     */
    fun startEducationMode() {
        if (isEducationActive) return

        isEducationActive = true
        Log.d(TAG, "🤖 Education mode started with actionable AI")

        // Start providing AI-powered suggestions
        startAISuggestionLoop()
    }

    /**
     * Stop education mode
     */
    fun stopEducationMode() {
        isEducationActive = false
        suggestionJob?.cancel()
        Log.d(TAG, "Education mode stopped")
    }

    /**
     * AI-powered suggestion loop
     */
    private fun startAISuggestionLoop() {
        suggestionJob = scope.launch {
            while (isEducationActive) {
                try {
                    delay(SUGGESTION_INTERVAL)

                    if (isEducationActive && onSuggestionReady != null) {
                        // Get AI-powered suggestion
                        val suggestion = generateAISuggestion()
                        onSuggestionReady.invoke(suggestion)
                    }

                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error in AI suggestion loop", e)
                }
            }
        }
    }

    /**
     * Generate AI-powered suggestion
     */
    private suspend fun generateAISuggestion(): AISuggestion {
        return try {
            // Create mock camera info for periodic suggestions
            val detailedInfo = DetailedCameraInfo(
                hasFlash = true,
                zoomRatio = 1f + Random.nextFloat() * 2f,
                isLowLight = Random.nextBoolean(),
                isUsingFrontCamera = Random.nextBoolean()
            )

            // Get actionable suggestions
            val actionableSuggestions = getActionableSuggestions(detailedInfo)
            val randomSuggestion = actionableSuggestions.randomOrNull()

            if (randomSuggestion != null) {
                AISuggestion(
                    title = "🤖 ${randomSuggestion.title}",
                    message = randomSuggestion.description
                )
            } else {
                AISuggestion(
                    title = "📚 Photography Tip",
                    message = "Focus on good lighting and composition for better photos"
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "AI suggestion failed", e)
            AISuggestion(
                title = "📚 Photography Tip",
                message = "Focus on good lighting and composition for better photos"
            )
        }
    }

    /**
     * Generate suggestion based on specific camera info (Enhanced with AI)
     */
    fun getSuggestionForCameraInfo(cameraInfo: CameraInfo): AISuggestion {
        // Launch AI suggestion in background
        scope.launch {
            try {
                val smartSuggestions = getSmartSuggestions(cameraInfo)
                val suggestion = smartSuggestions.firstOrNull()

                if (suggestion != null && onSuggestionReady != null) {
                    onSuggestionReady.invoke(
                        AISuggestion(
                            title = suggestion.title,
                            message = suggestion.description
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "AI suggestion for camera info failed", e)
            }
        }

        // Return immediate suggestion
        val fallback = getSmartFallbackSuggestions(cameraInfo).first()
        return AISuggestion(
            title = fallback.title,
            message = fallback.description
        )
    }
    /**
     * Speak suggestions using TTS
     */
    fun speakSuggestions(suggestions: List<ActionableSuggestion>) {
        val mainActivity = context as? MainActivity
        mainActivity?.voiceHelper?.let { vh ->

            // Separate phone-controlled and user-controlled suggestions
            val phoneControlled = suggestions.filter {
                it.action in listOf(
                    QuickAction.FLASH_ON, QuickAction.FLASH_OFF,
                    QuickAction.ZOOM_IN, QuickAction.ZOOM_OUT,
                    QuickAction.ENABLE_GRID, QuickAction.SWITCH_CAMERA,
                    QuickAction.ENABLE_NIGHT, QuickAction.ENABLE_PORTRAIT
                )
            }

            val userControlled = suggestions.filter {
                it.action in listOf(
                    QuickAction.MOVE_CLOSER, QuickAction.MOVE_BACK,
                    QuickAction.HOLD_STEADY
                )
            }

            if (phoneControlled.isNotEmpty()) {
                vh.speakPhoneControlledSuggestions(
                    phoneControlled.associate { it.title to it.description }
                )
            }

            if (userControlled.isNotEmpty()) {
                vh.speakUserControlledSuggestions(
                    userControlled.map { "${it.title}: ${it.description}" }
                )
            }
        }
    }



    /**
     * Clean up resources
     */
    fun cleanup() {
        stopEducationMode()
        scope.cancel()
    }

    private fun generateMacroSuggestions(
        cameraInfo: DetailedCameraInfo,
        frameAnalysis: RealTimeVisionAnalyzer.FrameAnalysis?
    ): List<ActionableSuggestion> {
        val suggestions = mutableListOf<ActionableSuggestion>()
        var priority = 1

        frameAnalysis?.let { analysis ->
            // Critical: Check focus quality for macro
            if (analysis.blurLevel < 40) { // Low sharpness
                suggestions.add(ActionableSuggestion(
                    "Get Closer & Focus",
                    "Move 2-4 inches closer for sharper macro detail",
                    QuickAction.MOVE_CLOSER,
                    "🔍",
                    priority++
                ))
            }

            // Critical: Lighting for macro
            if (analysis.brightness < 120) {
                suggestions.add(ActionableSuggestion(
                    "Add More Light",
                    "Macro needs bright, even lighting",
                    QuickAction.FLASH_ON,
                    "💡",
                    priority++
                ))
            }

            // Zoom optimization for macro
            if (cameraInfo.zoomRatio < 1.5f) {
                suggestions.add(ActionableSuggestion(
                    "Zoom to 2x",
                    "Better magnification for macro details",
                    QuickAction.ZOOM_IN,
                    "🔍",
                    priority++
                ))
            }

            // Stability is crucial for macro
            if (analysis.hasMotionBlur) {
                suggestions.add(ActionableSuggestion(
                    "Use Both Hands",
                    "Macro photography needs rock-steady hands",
                    QuickAction.ENABLE_STABILIZATION,
                    "✋",
                    priority++
                ))
            }
        }

        return suggestions.take(3)
    }

    private fun generateFoodSuggestions(
        cameraInfo: DetailedCameraInfo,
        frameAnalysis: RealTimeVisionAnalyzer.FrameAnalysis?
    ): List<ActionableSuggestion> {
        val suggestions = mutableListOf<ActionableSuggestion>()
        var priority = 1

        frameAnalysis?.let { analysis ->
            // Lighting for appetizing food
            if (analysis.brightness < 100) {
                suggestions.add(ActionableSuggestion(
                    "Brighter Lighting",
                    "Food looks more appetizing with good light",
                    QuickAction.FLASH_ON,
                    "🍽️",
                    priority++
                ))
            }

            // Color vibrancy for food appeal
            if (analysis.colorBalance == "COOL") {
                suggestions.add(ActionableSuggestion(
                    "Warm Up Colors",
                    "Food looks better with warmer tones",
                    QuickAction.APPLY_WARM_FILTER,
                    "🌅",
                    priority++
                ))
            }

            // Composition for food photography
            if (analysis.compositionScore < 0.4) {
                suggestions.add(ActionableSuggestion(
                    "Shoot from Above",
                    "Overhead angle often works best for food",
                    QuickAction.ENABLE_GRID,
                    "📐",
                    priority++
                ))
            }

            // Distance optimization
            if (cameraInfo.zoomRatio > 2.0f) {
                suggestions.add(ActionableSuggestion(
                    "Get Closer Instead",
                    "Move closer rather than zoom for food photos",
                    QuickAction.ZOOM_OUT,
                    "📱",
                    priority++
                ))
            }
        }

        return suggestions.take(3)
    }

}
