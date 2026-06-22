package com.abyssredemption.accounts

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.ProgressBar
import android.widget.TextView

object PinkDialogs {
    private val primary = Color.rgb(233, 130, 158)
    private val dark = Color.rgb(201, 79, 104)
    private val secondary = Color.rgb(155, 133, 140)
    private val textColor = Color.rgb(61, 52, 55)

    fun show(dialog: AlertDialog): AlertDialog {
        dialog.show()
        apply(dialog)
        return dialog
    }

    fun apply(dialog: AlertDialog) {
        val density = dialog.context.resources.displayMetrics.density
        dialog.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 26f * density
                setColor(Color.rgb(255, 249, 250))
                setStroke((1 * density).toInt().coerceAtLeast(1), Color.rgb(247, 220, 228))
            })
            attributes = attributes.apply { dimAmount = 0.32f }
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply { setTextColor(primary); isAllCaps = false }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply { setTextColor(secondary); isAllCaps = false }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.apply { setTextColor(dark); isAllCaps = false }
        dialog.findViewById<TextView>(android.R.id.message)?.apply {
            setTextColor(textColor)
            setLineSpacing(0f, 1.18f)
        }
        val titleId = dialog.context.resources.getIdentifier("alertTitle", "id", "android")
        dialog.findViewById<TextView>(titleId)?.setTextColor(textColor)
    }

    fun styleProgress(progressBar: ProgressBar) {
        progressBar.progressTintList = ColorStateList.valueOf(primary)
        progressBar.progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(247, 220, 228))
        progressBar.indeterminateTintList = ColorStateList.valueOf(primary)
    }
}
