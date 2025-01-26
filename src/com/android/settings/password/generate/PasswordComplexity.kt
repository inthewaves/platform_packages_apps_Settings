package com.android.settings.password.generate

import android.app.admin.DevicePolicyManager
import androidx.annotation.Keep

// Class just to consolidate various AOSP password complexity details. Unit test against platform
// implementation with atest SettingsUnitTests:PasswordStrengthTest
enum class PasswordComplexity(
    val complexityValue: Int,
    // get these from the DevicePolicyManager documentation
    val pinLength: Int,
    @get:Keep
    val alphaNumericLength: Int
) {
    NONE(DevicePolicyManager.PASSWORD_COMPLEXITY_NONE, 0, 0),
    LOW(DevicePolicyManager.PASSWORD_COMPLEXITY_LOW, 0, 0),
    MEDIUM(DevicePolicyManager.PASSWORD_COMPLEXITY_MEDIUM, 4, 4),
    HIGH(DevicePolicyManager.PASSWORD_COMPLEXITY_HIGH, 8, 6);

    companion object {
        fun fromLevel(level: Int, minLevel: PasswordComplexity) =
            when (level) {
                DevicePolicyManager.PASSWORD_COMPLEXITY_NONE -> NONE
                DevicePolicyManager.PASSWORD_COMPLEXITY_LOW -> LOW
                DevicePolicyManager.PASSWORD_COMPLEXITY_MEDIUM -> MEDIUM
                DevicePolicyManager.PASSWORD_COMPLEXITY_HIGH -> HIGH
                else -> minLevel
            }.coerceIn(minimumValue = minLevel, maximumValue = entries.last())
    }
}
