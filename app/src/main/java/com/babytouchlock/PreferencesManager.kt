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

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Helper object for managing application preferences and persistence via [SharedPreferences].
 */
object PreferencesManager {
    private const val PREFS_NAME = "baby_touch_lock_prefs"
    const val PREF_BLOCK_VOLUME = "pref_block_volume"
    const val PREF_AUTO_WAKE_POWER = "pref_auto_wake_power"
    const val PREF_TILE_ADDED = "pref_tile_added"
    private const val PREF_PIN_SALT = "pref_pin_salt"
    private const val PREF_PIN_HASH = "pref_pin_hash"
    private const val PIN_LENGTH = 4
    private const val PBKDF2_ITERATIONS = 120_000
    private const val HASH_LENGTH_BITS = 256

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun hasPin(context: Context): Boolean = getPrefs(context).contains(PREF_PIN_HASH)

    fun setPin(context: Context, pin: String): Boolean {
        if (!pin.matches(Regex("\\d{$PIN_LENGTH}"))) return false
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = derivePinHash(pin, salt)
        getPrefs(context).edit()
            .putString(PREF_PIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(PREF_PIN_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .apply()
        return true
    }

    fun verifyPin(context: Context, pin: String): Boolean {
        if (!pin.matches(Regex("\\d{$PIN_LENGTH}"))) return false
        val prefs = getPrefs(context)
        val saltText = prefs.getString(PREF_PIN_SALT, null) ?: return false
        val hashText = prefs.getString(PREF_PIN_HASH, null) ?: return false
        val salt = Base64.decode(saltText, Base64.NO_WRAP)
        val expected = Base64.decode(hashText, Base64.NO_WRAP)
        return MessageDigest.isEqual(expected, derivePinHash(pin, salt))
    }

    private fun derivePinHash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, HASH_LENGTH_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * Checks if hardware volume key suppression is enabled.
     * Default: true (enabled).
     */
    fun isBlockVolumeEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(PREF_BLOCK_VOLUME, true)
    }

    /**
     * Sets whether hardware volume keys should be suppressed while locked.
     */
    fun setBlockVolumeEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(PREF_BLOCK_VOLUME, enabled).apply()
    }

    /**
     * Checks if automatic display wake-up on power button press is enabled.
     * Default: false (disabled).
     */
    fun isAutoWakePowerEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(PREF_AUTO_WAKE_POWER, false)
    }

    /**
     * Sets whether the display should automatically wake up when the power button is pressed.
     */
    fun setAutoWakePowerEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(PREF_AUTO_WAKE_POWER, enabled).apply()
    }

    /**
     * Checks if the Quick Settings tile was added to the active status bar tiles.
     */
    fun isTileAdded(context: Context): Boolean {
        return getPrefs(context).getBoolean(PREF_TILE_ADDED, false)
    }

    /**
     * Updates whether the Quick Settings tile was added.
     */
    fun setTileAdded(context: Context, added: Boolean) {
        getPrefs(context).edit().putBoolean(PREF_TILE_ADDED, added).apply()
    }
}

