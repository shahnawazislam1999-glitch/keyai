package com.sentencebuddy.app.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.ScrollView

/** ScrollView that never grows taller than [maxHeightPx] (0 = no limit). */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ScrollView(context, attrs) {

    var maxHeightPx: Int = 0
        set(value) {
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val spec = if (maxHeightPx > 0) {
            View.MeasureSpec.makeMeasureSpec(maxHeightPx, View.MeasureSpec.AT_MOST)
        } else {
            heightMeasureSpec
        }
        super.onMeasure(widthMeasureSpec, spec)
    }
}
