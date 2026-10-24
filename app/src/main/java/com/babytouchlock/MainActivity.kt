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

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.babytouchlock.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Main Activity providing the configuration dashboard, accessibility permission setup,
 * Quick Settings tile shortcut helper, and lock testing.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupVolumeSwitch()
        setupPowerWakeSwitch()
        setupPin()
        setupButtons()
        checkPermissionsUI()
    }

    override fun onResume() {
        super.onResume()
        checkPermissionsUI()
    }

    private fun setupVolumeSwitch() {
        val isBlockVolumeEnabled = PreferencesManager.isBlockVolumeEnabled(this)
        binding.switchBlockVolume.isChecked = isBlockVolumeEnabled

        binding.switchBlockVolume.setOnCheckedChangeListener { _, isChecked ->
            PreferencesManager.setBlockVolumeEnabled(this, isChecked)
            val state = getString(if (isChecked) R.string.state_on else R.string.state_off)
            val msg = getString(R.string.setting_state_format, getString(R.string.volume_lock_switch_label), state)
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupPowerWakeSwitch() {
        val isAutoWakeEnabled = PreferencesManager.isAutoWakePowerEnabled(this)
        binding.switchAutoWakePower.isChecked = isAutoWakeEnabled

        binding.switchAutoWakePower.setOnCheckedChangeListener { _, isChecked ->
            PreferencesManager.setAutoWakePowerEnabled(this, isChecked)
            val state = getString(if (isChecked) R.string.state_on else R.string.state_off)
            val msg = getString(R.string.setting_state_format, getString(R.string.power_lock_switch_label), state)
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupPin() {
        binding.btnSetPin.setOnClickListener {
            val input = android.widget.EditText(this).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
                filters = arrayOf(android.text.InputFilter.LengthFilter(4))
                hint = getString(R.string.pin_setup_hint)
            }
            val confirmation = android.widget.EditText(this).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
                filters = arrayOf(android.text.InputFilter.LengthFilter(4))
                hint = getString(R.string.pin_confirm_hint)
            }
            val fields = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(48, 0, 48, 0)
                addView(input)
                addView(confirmation)
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pin_setup_title)
                .setView(fields)
                .setNegativeButton(R.string.dialog_consent_cancel, null)
                .setPositiveButton(R.string.dialog_ok) { _, _ ->
                    val pin = input.text.toString()
                    if (pin == confirmation.text.toString() && PreferencesManager.setPin(this, pin)) {
                        Toast.makeText(this, R.string.pin_saved, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, R.string.pin_invalid, Toast.LENGTH_SHORT).show()
                    }
                }
                .show()
        }
    }

    private fun setupButtons() {
        // Prominent Disclosure before navigating to system accessibility settings
        binding.btnGrantAccessibility.setOnClickListener {
            showAccessibilityConsentDialog()
        }

        binding.tvPrivacyInfo.setOnClickListener {
            showPrivacyDisclosureDialog()
        }

        binding.btnAddQsTile.setOnClickListener {
            if (PreferencesManager.isTileAdded(this)) {
                Toast.makeText(this, R.string.tile_already_present, Toast.LENGTH_SHORT).show()
                updateTileButtonUI()
                return@setOnClickListener
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val statusBarManager = getSystemService(android.app.StatusBarManager::class.java)
                val componentName = android.content.ComponentName(this, BabyTouchLockTileService::class.java)
                val icon = android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_baby_touch_lock)
                statusBarManager?.requestAddTileService(
                    componentName,
                    getString(R.string.tile_label),
                    icon,
                    mainExecutor
                ) { result ->
                    when (result) {
                        android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> {
                            PreferencesManager.setTileAdded(this, true)
                            updateTileButtonUI()
                            Toast.makeText(this, R.string.tile_added_success, Toast.LENGTH_SHORT).show()
                        }
                        android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> {
                            PreferencesManager.setTileAdded(this, true)
                            updateTileButtonUI()
                            Toast.makeText(this, R.string.tile_already_present, Toast.LENGTH_SHORT).show()
                        }
                        android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> {
                            // User cancelled the prompt
                        }
                    }
                }
            } else {
                // Show step-by-step manual guide dialog for Android 12 and below
                showManualAddGuideDialog()
            }
        }

        binding.btnTestLock.setOnClickListener {
            val service = BabyTouchLockAccessibilityService.instance
            if (service == null) {
                Toast.makeText(this, R.string.accessibility_needed_toast, Toast.LENGTH_LONG).show()
                showAccessibilityConsentDialog()
                return@setOnClickListener
            }

            if (!PreferencesManager.hasPin(this)) {
                Toast.makeText(this, R.string.pin_required_before_lock, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            service.lock()
            if (BabyTouchLockAccessibilityService.isLocked) {
                moveTaskToBack(true)
            }
        }
    }

    /**
     * Prominent Disclosure Dialog compliant with Google Play Store & F-Droid policies:
     * - Discloses required permissions and privacy guarantees prior to activation.
     * - Requests explicit user consent before opening Android Accessibility Settings.
     */
    private fun showAccessibilityConsentDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.disclosure_dialog_title)
            .setIcon(R.drawable.ic_status_shield)
            .setMessage(R.string.disclosure_dialog_text)
            .setPositiveButton(R.string.dialog_consent_agree) { dialog, _ ->
                dialog.dismiss()
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            }
            .setNegativeButton(R.string.dialog_consent_cancel) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun showPrivacyDisclosureDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.disclosure_dialog_title)
            .setIcon(R.drawable.ic_status_shield)
            .setMessage(R.string.disclosure_dialog_text)
            .setPositiveButton(R.string.dialog_ok) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun showManualAddGuideDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.manual_add_dialog_title)
            .setIcon(R.drawable.ic_baby_touch_lock)
            .setMessage(R.string.manual_add_dialog_message)
            .setPositiveButton(R.string.dialog_ok) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun checkPermissionsUI() {
        val isAccessibilityActive = BabyTouchLockAccessibilityService.instance != null
        if (isAccessibilityActive) {
            binding.cardStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.status_ok_container))
            binding.cardStatus.strokeColor = ContextCompat.getColor(this, R.color.status_ok_stroke)
            binding.ivStatusIcon.setImageResource(R.drawable.ic_status_shield)
            binding.ivStatusIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_ok))
            binding.tvStatusTitle.setText(R.string.status_active_title)
            binding.tvStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.status_ok_text))
            binding.tvStatusDesc.setText(R.string.status_active_desc)
            binding.tvStatusDesc.setTextColor(ContextCompat.getColor(this, R.color.status_ok_text))
            binding.btnGrantAccessibility.visibility = View.GONE
        } else {
            binding.cardStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.status_warning_container))
            binding.cardStatus.strokeColor = ContextCompat.getColor(this, R.color.status_warning_stroke)
            binding.ivStatusIcon.setImageResource(R.drawable.ic_status_warning)
            binding.ivStatusIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_warning))
            binding.tvStatusTitle.setText(R.string.status_inactive_title)
            binding.tvStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.status_warning_text))
            binding.tvStatusDesc.setText(R.string.status_inactive_desc)
            binding.tvStatusDesc.setTextColor(ContextCompat.getColor(this, R.color.status_warning_text))
            binding.btnGrantAccessibility.visibility = View.VISIBLE
            binding.btnGrantAccessibility.text = getString(R.string.grant_permission)
            binding.btnGrantAccessibility.isEnabled = true
        }

        updateTileButtonUI()
    }

    private fun updateTileButtonUI() {
        val isAdded = PreferencesManager.isTileAdded(this)
        if (isAdded) {
            binding.btnAddQsTile.text = getString(R.string.btn_tile_added)
            binding.btnAddQsTile.isEnabled = false
            binding.btnAddQsTile.alpha = 0.6f
        } else {
            binding.btnAddQsTile.text = getString(R.string.btn_add_tile)
            binding.btnAddQsTile.isEnabled = true
            binding.btnAddQsTile.alpha = 1.0f
        }
    }
}

