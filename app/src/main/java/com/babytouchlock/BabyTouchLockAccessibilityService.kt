/*
 * Baby Touch Lock
 * Copyright (C) 2026 Davide Micieli
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.babytouchlock

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.util.Log
import android.widget.Toast

import androidx.core.content.ContextCompat

/**
 * Core Accessibility Service providing touch and hardware key interception.
 *
 * Manages two overlay windows:
 * 1. Full-screen transparent barrier ([BabyTouchLockBackgroundView]) that blocks touches and system gestures.
 * 2. Floating unlock widget ([BabyTouchLockUnlockView]) with a four-digit PIN keypad.
 */
class BabyTouchLockAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "BabyTouchLock"
        var instance: BabyTouchLockAccessibilityService? = null
        var isLocked = false
    }
    private var windowManager: WindowManager? = null

    // Window 1: Full-screen background barrier (blocks touch and edge navigation gestures)
    private var backgroundView: BabyTouchLockBackgroundView? = null
    private var backgroundViewLayoutParams: WindowManager.LayoutParams? = null

    // Window 2: Floating unlock widget with four-digit PIN keypad
    private var unlockView: BabyTouchLockUnlockView? = null
    private var unlockViewLayoutParams: WindowManager.LayoutParams? = null

    private val handler = Handler(Looper.getMainLooper())

    private var pendingLockRunnable: Runnable? = null

    val isPendingLockActive: Boolean
        get() = pendingLockRunnable != null

    /**
     * BroadcastReceiver listening for screen-off events to immediately wake the display if enabled.
     */
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF && isLocked) {
                if (PreferencesManager.isAutoWakePowerEnabled(this@BabyTouchLockAccessibilityService)) {
                    // Screen turned off: trigger immediate wake-up via WakeUpActivity
                    wakeScreenUp()
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        Log.i(TAG, "service connected")

        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        ContextCompat.registerReceiver(this, screenStateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        BabyTouchLockTileService.updateTile(this, isLocked)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        pendingLockRunnable?.let { handler.removeCallbacks(it) }
        pendingLockRunnable = null
        if (isLocked) {
            unlock()
        }
        BabyTouchLockTileService.updateTile(this, false)
        instance = null
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Zero overhead (no global accessibility event dispatching)
    }

    override fun onDestroy() {
        pendingLockRunnable?.let { handler.removeCallbacks(it) }
        pendingLockRunnable = null
        if (isLocked) {
            unlock()
        }
        BabyTouchLockTileService.updateTile(this, false)
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (_: Exception) {}
        instance = null
        super.onDestroy()
    }

    /**
     * Activates the touch lock overlay windows, optionally after a specified delay.
     *
     * @param delayMs Delay in milliseconds before showing the lock windows.
     *                Useful when triggered from Quick Settings to allow the notification shade
     *                to finish its collapse animation smoothly.
     */
    fun lock(delayMs: Long = 0L) {
        if (!PreferencesManager.hasPin(this)) {
            Toast.makeText(this, R.string.pin_required_before_lock, Toast.LENGTH_LONG).show()
            return
        }
        pendingLockRunnable?.let { handler.removeCallbacks(it) }
        pendingLockRunnable = null

        if (delayMs <= 0L) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                showLockWindows()
            } else {
                handler.post { showLockWindows() }
            }
        } else {
            val runnable = Runnable {
                pendingLockRunnable = null
                showLockWindows()
            }
            pendingLockRunnable = runnable
            handler.postDelayed(runnable, delayMs)
        }
    }

    /**
     * Deactivates the touch lock overlay windows and provides haptic feedback.
     */
    fun unlock() {
        pendingLockRunnable?.let { handler.removeCallbacks(it) }
        pendingLockRunnable = null

        if (Looper.myLooper() == Looper.getMainLooper()) {
            hideLockWindows()
        } else {
            handler.post { hideLockWindows() }
        }
    }

    /**
     * Closes the notification shade / Quick Settings drawer across all Android versions.
     * Uses Android 12+ (API 31+) Global Action, or ACTION_CLOSE_SYSTEM_DIALOGS broadcast on Android 11 and below.
     */
    fun collapseSystemUI() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            } else {
                @Suppress("DEPRECATION")
                val closeIntent = Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
                sendBroadcast(closeIntent)
            }
        } catch (_: Exception) {}
    }

    @SuppressLint("InflateParams")
    private fun showLockWindows() {
        if (isLocked) return
        isLocked = true
        BabyTouchLockTileService.updateTile(this, true)

        val wm = windowManager ?: (getSystemService(WINDOW_SERVICE) as WindowManager).also { windowManager = it }

        // Proactively collapse notification shade if opened from Quick Settings tile
        collapseSystemUI()
        val shortDelay = resources.getInteger(R.integer.collapse_delay_short_ms).toLong()
        val longDelay = resources.getInteger(R.integer.collapse_delay_long_ms).toLong()
        handler.postDelayed({ collapseSystemUI() }, shortDelay)
        handler.postDelayed({ collapseSystemUI() }, longDelay)

        // Calculate exact display bounds
        val width: Int
        val height: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            width = bounds.width()
            height = bounds.height()
        } else {
            val display = wm.defaultDisplay
            val displaySize = Point()
            @Suppress("DEPRECATION")
            display.getRealSize(displaySize)
            width = displaySize.x
            height = displaySize.y
        }

        // ==========================================
        // Window 1: Full-screen background barrier
        // ==========================================
        backgroundViewLayoutParams = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )

        backgroundView = BabyTouchLockBackgroundView(this) {
            collapseSystemUI()
        }

        // ==========================================
        // Window 2: Floating unlock slider widget
        // ==========================================
        val inflater = LayoutInflater.from(this)
        unlockView = inflater.inflate(R.layout.view_baby_touch_lock_unlock, null) as BabyTouchLockUnlockView
        unlockView?.setOnUnlockConfirmedListener {
            unlock()
        }
        unlockView?.resetToInitialState()

        unlockViewLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = resources.getDimensionPixelSize(R.dimen.floating_unlock_margin_x)
            y = resources.getDimensionPixelSize(R.dimen.floating_unlock_margin_y)
        }

        if (wm != null) {
            try {
                wm.addView(backgroundView, backgroundViewLayoutParams)
                wm.addView(unlockView, unlockViewLayoutParams)
                backgroundView?.requestApplyInsets()
                Log.i(TAG, "lock windows added")
            } catch (e: Exception) {
                isLocked = false
                BabyTouchLockTileService.updateTile(this, false)
                Log.e(TAG, "could not add lock windows", e)
                backgroundView = null
                unlockView = null
                backgroundViewLayoutParams = null
                unlockViewLayoutParams = null
                Toast.makeText(this, R.string.lock_failed_toast, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun hideLockWindows() {
        if (!isLocked) return
        isLocked = false
        BabyTouchLockTileService.updateTile(this, false)

        val wm = windowManager
        if (wm != null) {
            if (unlockView != null) {
                try {
                    wm.removeView(unlockView)
                } catch (_: Exception) {}
                unlockView = null
                unlockViewLayoutParams = null
            }
            if (backgroundView != null) {
                try {
                    wm.removeView(backgroundView)
                } catch (_: Exception) {}
                backgroundView = null
                backgroundViewLayoutParams = null
            }
        }

        vibrateFeedback()
        Toast.makeText(applicationContext, R.string.unlocked_toast, Toast.LENGTH_SHORT).show()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val wm = windowManager ?: return
        val bg = backgroundView ?: return
        val bgParams = backgroundViewLayoutParams ?: return

        val width: Int
        val height: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            width = bounds.width()
            height = bounds.height()
        } else {
            val display = wm.defaultDisplay
            val displaySize = Point()
            @Suppress("DEPRECATION")
            display.getRealSize(displaySize)
            width = displaySize.x
            height = displaySize.y
        }

        bgParams.width = width
        bgParams.height = height

        if (isLocked) {
            try {
                wm.updateViewLayout(bg, bgParams)
                if (unlockView != null && unlockViewLayoutParams != null) {
                    wm.updateViewLayout(unlockView, unlockViewLayoutParams)
                }
                bg.requestApplyInsets()
            } catch (_: Exception) {}
        }
    }

    private fun wakeScreenUp() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            val timeout = resources.getInteger(R.integer.screen_wake_lock_timeout_ms).toLong()
            val tag = getString(R.string.wake_lock_service_tag)
            @Suppress("DEPRECATION")
            val wakeLock = powerManager?.newWakeLock(
                android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        android.os.PowerManager.ON_AFTER_RELEASE,
                tag
            )
            wakeLock?.acquire(timeout)
        } catch (_: Exception) {}

        try {
            val wakeIntent = Intent(this, WakeUpActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            }
            startActivity(wakeIntent)
        } catch (_: Exception) {}
    }

    private fun vibrateFeedback() {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
        if (vibrator != null && vibrator.hasVibrator()) {
            val duration = resources.getInteger(R.integer.vibration_feedback_duration_ms).toLong()
            vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!isLocked) {
            return super.onKeyEvent(event)
        }

        val blockVolume = PreferencesManager.isBlockVolumeEnabled(this)

        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_MUTE -> {
                return if (blockVolume) true else super.onKeyEvent(event)
            }

            KeyEvent.KEYCODE_POWER -> {
                if (PreferencesManager.isAutoWakePowerEnabled(this)) {
                    wakeScreenUp()
                    return true
                }
                return false
            }

            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_APP_SWITCH -> {
                return true
            }
        }

        return super.onKeyEvent(event)
    }

}
