package com.streamflixrevanced.streamflix.utils

import android.content.Context
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.streamflixrevanced.streamflix.R

object SubtitleLanguagePriorityDialog {
    fun show(
        context: Context,
        languages: List<String>,
        displayName: (String) -> String,
        onSaved: (List<String>) -> Unit,
    ) {
        val order = languages.toMutableList()
        val rows = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 0)
        }
        var focusIndexAfterRender: Int? = null

        fun render() {
            val requestedFocusIndex = focusIndexAfterRender
            focusIndexAfterRender = null
            rows.removeAllViews()
            order.forEachIndexed { index, language ->
                val row = LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    isFocusable = true
                    isFocusableInTouchMode = true
                }
                val label = TextView(context).apply {
                    text = displayName(language)
                    textSize = 18f
                    layoutParams = LinearLayout.LayoutParams(0, 56.dp(context), 1f)
                }
                val up = Button(context).apply {
                    text = "UP"
                    isEnabled = index > 0
                    setOnClickListener {
                        val destination = index - 1
                        order.add(destination, order.removeAt(index))
                        focusIndexAfterRender = destination
                        render()
                    }
                }
                val down = Button(context).apply {
                    text = "DOWN"
                    isEnabled = index < order.lastIndex
                    setOnClickListener {
                        val destination = index + 1
                        order.add(destination, order.removeAt(index))
                        focusIndexAfterRender = destination
                        render()
                    }
                }
                row.addView(label)
                row.addView(up)
                row.addView(down)
                rows.addView(row)
            }
            requestedFocusIndex?.let { index ->
                rows.post { rows.getChildAt(index)?.requestFocus() }
            }
        }

        render()
        AlertDialog.Builder(context)
            .setTitle(R.string.settings_subtitle_language_priority_title)
            .setView(rows)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> onSaved(order.toList()) }
            .show()
    }

    private fun Int.dp(context: Context): Int =
        (this * context.resources.displayMetrics.density).toInt()
}
