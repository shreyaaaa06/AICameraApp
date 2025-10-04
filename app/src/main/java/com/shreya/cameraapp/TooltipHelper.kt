import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat

class TooltipHelper {
    companion object {
        private var currentTooltip: PopupWindow? = null

        fun showTooltip(context: Context, anchorView: View, message: String) {
            // Dismiss any existing tooltip
            dismissCurrentTooltip()

            // Create tooltip layout
            val inflater = LayoutInflater.from(context)
            val tooltipView = createTooltipView(context, message)

            // Create PopupWindow
            val popupWindow = PopupWindow(
                tooltipView,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                false
            ).apply {
                // Make it look like a tooltip
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                elevation = 8f
                isOutsideTouchable = true
                isFocusable = false
            }

            // Calculate position
            val location = IntArray(2)
            anchorView.getLocationOnScreen(location)

            val anchorX = location[0]
            val anchorY = location[1]
            val anchorWidth = anchorView.width
            val anchorHeight = anchorView.height

            // Measure tooltip dimensions
            tooltipView.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val tooltipWidth = tooltipView.measuredWidth
            val tooltipHeight = tooltipView.measuredHeight

            // Position tooltip above the button (with some offset)
            val xOffset = anchorX + (anchorWidth / 2) - (tooltipWidth / 2)
            val yOffset = anchorY - tooltipHeight - 16 // 16dp above the button

            // Show the tooltip
            try {
                popupWindow.showAtLocation(anchorView, Gravity.NO_GRAVITY, xOffset, yOffset)
                currentTooltip = popupWindow
            } catch (e: Exception) {
                // Fallback to showing below if above fails
                val yOffsetBelow = anchorY + anchorHeight + 16
                popupWindow.showAtLocation(anchorView, Gravity.NO_GRAVITY, xOffset, yOffsetBelow)
                currentTooltip = popupWindow
            }

            // Auto-dismiss after 2.5 seconds
            Handler(Looper.getMainLooper()).postDelayed({
                dismissCurrentTooltip()
            }, 2500)
        }

        private fun createTooltipView(context: Context, message: String): View {
            val textView = TextView(context).apply {
                text = message
                setTextColor(Color.WHITE)
                textSize = 12f
                setPadding(24, 16, 24, 16)

                // Create rounded background
                background = ContextCompat.getDrawable(context, android.R.drawable.editbox_background)?.apply {
                    setTint(Color.parseColor("#DD000000")) // Semi-transparent black
                }

                // Set max width to prevent very wide tooltips
                maxWidth = (context.resources.displayMetrics.widthPixels * 0.8).toInt()
            }

            return textView
        }

        private fun dismissCurrentTooltip() {
            currentTooltip?.let { popup ->
                if (popup.isShowing) {
                    try {
                        popup.dismiss()
                    } catch (e: Exception) {
                        // Ignore dismiss errors
                    }
                }
            }
            currentTooltip = null
        }
    }
}