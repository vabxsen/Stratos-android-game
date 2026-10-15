package com.vabxsen.stratos

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.LinearLayout

/** A LinearLayout that can let touches fall through to the game while it fades out. */
class GateLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    var passThrough = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean =
        if (passThrough) false else super.dispatchTouchEvent(ev)
}
