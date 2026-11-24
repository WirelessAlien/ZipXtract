package com.wirelessalien.zipxtract.helper

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

object HapticHelper {
    fun performConfirm(view: View?) {
        if (view == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    fun performLongPress(view: View?) {
        view?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    fun performContextClick(view: View?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            view?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        } else {
            view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }
}
