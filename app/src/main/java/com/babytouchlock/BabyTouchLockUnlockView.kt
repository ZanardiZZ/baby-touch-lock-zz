package com.babytouchlock

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/** Floating unlock widget with a four-digit PIN keypad. */
class BabyTouchLockUnlockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    private var btnFloatingUnlock: TextView? = null
    private var pinContainer: FrameLayout? = null
    private var pinDisplay: TextView? = null
    private var pin = ""
    private var onUnlockConfirmed: (() -> Unit)? = null

    init {
        isFocusable = false
        isFocusableInTouchMode = false
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        btnFloatingUnlock = findViewById(R.id.btn_floating_unlock)
        pinContainer = findViewById(R.id.pin_container)
        pinDisplay = findViewById(R.id.pin_display)
        btnFloatingUnlock?.setOnClickListener {
            btnFloatingUnlock?.visibility = View.GONE
            pinContainer?.visibility = View.VISIBLE
        }
        listOf(
            R.id.pin_0, R.id.pin_1, R.id.pin_2, R.id.pin_3, R.id.pin_4,
            R.id.pin_5, R.id.pin_6, R.id.pin_7, R.id.pin_8, R.id.pin_9
        ).forEach { id ->
            findViewById<TextView>(id)?.setOnClickListener {
                appendDigit((it as TextView).text.toString())
            }
        }
        findViewById<TextView>(R.id.pin_backspace)?.setOnClickListener { backspace() }
        findViewById<TextView>(R.id.pin_clear)?.setOnClickListener { clearPin() }
        findViewById<TextView>(R.id.pin_submit)?.setOnClickListener {
            if (pin.length == 4 && PreferencesManager.verifyPin(context, pin)) onUnlockConfirmed?.invoke()
            else clearPin()
        }
    }

    fun setOnUnlockConfirmedListener(listener: () -> Unit) {
        onUnlockConfirmed = listener
    }

    fun resetToInitialState() {
        btnFloatingUnlock?.visibility = View.VISIBLE
        btnFloatingUnlock?.alpha = 0.5f
        pinContainer?.visibility = View.GONE
        clearPin()
    }

    private fun appendDigit(digit: String) {
        if (pin.length < 4) pin += digit
        updatePinDisplay()
    }

    private fun backspace() {
        if (pin.isNotEmpty()) pin = pin.dropLast(1)
        updatePinDisplay()
    }

    private fun clearPin() {
        pin = ""
        updatePinDisplay()
    }

    private fun updatePinDisplay() {
        pinDisplay?.text = if (pin.isEmpty()) context.getString(R.string.pin_enter_prompt)
        else "• ".repeat(pin.length).trim()
    }
}
