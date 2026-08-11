package com.tanasi.navigation.widget

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout

class NavigationSlideHeaderView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    private var openListener: (() -> Unit)? = null
    private var closeListener: (() -> Unit)? = null


    fun setOnOpenListener(onOpen: () -> Unit) {
        openListener = onOpen
    }

    fun setOnCloseListener(onClose: () -> Unit) {
        closeListener = onClose
    }


    fun open() {
        isFocusable = true
        isFocusableInTouchMode = true
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

        openListener?.invoke()
    }

    fun close() {
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        isFocusable = false
        isFocusableInTouchMode = false

        closeListener?.invoke()
    }
}
