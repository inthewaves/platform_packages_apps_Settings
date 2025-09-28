package com.android.settings.network.telephony.carriersettingsoverride

import android.telephony.CarrierConfigManager
import com.android.settings.R
import com.android.settings.network.telephony.CarrierConfigRepository.KeyType

/**
 * This file contains all the possible carrier config override options that will appear in the UI
 * To add a new option:
 *
 * 1. Create a new object subclassing [ChangeableCarrierConfigFlag].
 *    keysWithImportance specifies the keys that will be changed by the option.
 *    allPossibleConfigStates should enumerate all possible expected states for those keys. The "use
 *    default" option is added automatically elsewhere. You can mark user-selectable states that
 *    will be shown to the user; everything is selectable by default. You should also give human-
 *    readable strings for the options and the option title.
 *
 * 2. Add the new class to the allowedUserChangeableCarrierConfigFlags list below. This will allow
 *    the UI to detect the option and render it.
 */

private val simpleUniformBoolStates: List<ConfigState> = listOf(
    ConfigState.Simple(
        CarrierConfigTypedValue.Bool(true),
        R.string.carrier_settings_override_bool_true,
        R.string.carrier_settings_default_bool_true,
    ),
    ConfigState.Simple(
        CarrierConfigTypedValue.Bool(false),
        R.string.carrier_settings_override_bool_false,
        R.string.carrier_settings_default_bool_false,
    ),
)

// Add new options here so that the UI can display it
val allowedUserChangeableCarrierConfigFlags: List<ChangeableCarrierConfigFlag> by lazy {
    listOf(

        VoLTEAvailable,
        VoNREnabled,
        Enable5G,

    ).onEach { flag ->
        flag.possibleConfigStates.forEach { state ->
            if (state is ConfigState.Complex) {
                val left = state.stateValues.size
                val right = flag.keys.size
                require(left == right) {
                    "key size for ${flag.javaClass.simpleName} mismatch ($left != $right)"
                }
            }
        }
    }
}

data object VoLTEAvailable : ChangeableCarrierConfigFlag(
    // The keys that will be edited in this option
    keysWithType = listOf(
        CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL to KeyType.BOOLEAN,
        CarrierConfigManager.KEY_HIDE_ENHANCED_4G_LTE_BOOL to KeyType.BOOLEAN,
    ),
    // All possible values for the keys. Each of these can be an option that the user can select
    allPossibleConfigStates = listOf(
        // Enabled
        ConfigState.Complex(
            listOf(
                CarrierConfigTypedValue.Bool(true), // KEY_CARRIER_VOLTE_AVAILABLE_BOOL
                CarrierConfigTypedValue.Bool(false), // KEY_HIDE_ENHANCED_4G_LTE_BOOL
            ),
            R.string.carrier_settings_override_bool_true,
            R.string.carrier_settings_default_bool_true,
        ),
        // Disabled
        ConfigState.Complex(
            listOf(
                CarrierConfigTypedValue.Bool(false),
                CarrierConfigTypedValue.Bool(true),
            ),
            R.string.carrier_settings_override_bool_false,
            R.string.carrier_settings_default_bool_false,
        ),

        // Other states not exposed for user selection just so that we have a description for them
        // Disabled
        ConfigState.Complex(
            listOf(
                CarrierConfigTypedValue.Bool(false),
                CarrierConfigTypedValue.Bool(false),
            ),
            R.string.carrier_settings_override_bool_false,
            R.string.carrier_settings_default_bool_false,
            isUserSelectable = false,
        ),
        // Disabled
        ConfigState.Complex(
            listOf(
                CarrierConfigTypedValue.Bool(true),
                CarrierConfigTypedValue.Bool(true),
            ),
            R.string.carrier_settings_override_bool_false,
            R.string.carrier_settings_default_bool_false,
            isUserSelectable = false,
        ),
    )
) {
    override val titleStringRes = R.string.carrier_settings_override_volte_availability
}

data object VoNREnabled : ChangeableCarrierConfigFlag(
    keysWithType = listOf(
        CarrierConfigManager.KEY_VONR_ENABLED_BOOL to KeyType.BOOLEAN,
        CarrierConfigManager.KEY_VONR_SETTING_VISIBILITY_BOOL to KeyType.BOOLEAN,
    ),
    // Use uniform booleans (true true, false false), then cover other states not exposed for user
    // selection just so that we have a description for them
    allPossibleConfigStates = simpleUniformBoolStates + listOf(
        // Not exposed for user
        ConfigState.Complex(
            listOf(
                CarrierConfigTypedValue.Bool(true),
                CarrierConfigTypedValue.Bool(false),
            ),
            R.string.carrier_settings_override_bool_false,
            R.string.carrier_settings_default_bool_false,
            isUserSelectable = false,
        ),
        ConfigState.Complex(
            listOf(
                CarrierConfigTypedValue.Bool(false),
                CarrierConfigTypedValue.Bool(true),
            ),
            R.string.carrier_settings_override_bool_false,
            R.string.carrier_settings_default_bool_false,
            isUserSelectable = false,
        ),
    )
) {
    override val titleStringRes = R.string.carrier_settings_override_vonr_enable
}

data object Enable5G : ChangeableCarrierConfigFlag(
    keysWithType = listOf(
        CarrierConfigManager.KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY to KeyType.INT_ARRAY,
    ),
    allPossibleConfigStates = listOf(
        // Enabled (i.e. all the 5G NR capabilities)
        ConfigState.Simple(
            CarrierConfigTypedValue.IntegerArray(
                listOf(
                    CarrierConfigManager.CARRIER_NR_AVAILABILITY_NSA,
                    CarrierConfigManager.CARRIER_NR_AVAILABILITY_SA,
                )
            ),
            R.string.carrier_settings_override_5g_enabled_all_modes,
            R.string.carrier_settings_override_5g_default_all_modes,
        ),
        // Only NSA
        ConfigState.Simple(
            CarrierConfigTypedValue.IntegerArray(
                listOf(CarrierConfigManager.CARRIER_NR_AVAILABILITY_NSA)
            ),
            R.string.carrier_settings_override_5g_enabled_nsa,
            R.string.carrier_settings_override_5g_default_nsa,
            isUserSelectable = false,
        ),
        // Only SA
        ConfigState.Simple(
            CarrierConfigTypedValue.IntegerArray(
                listOf(CarrierConfigManager.CARRIER_NR_AVAILABILITY_SA)
            ),
            R.string.carrier_settings_override_5g_enabled_sa,
            R.string.carrier_settings_override_5g_default_sa,
            isUserSelectable = false,
        ),
        // Disabled (i.e. no 5G NR capabilities)
        ConfigState.Simple(
            CarrierConfigTypedValue.IntegerArray(emptyList()),
            R.string.carrier_settings_override_5g_disabled,
            R.string.carrier_settings_default_5g_disabled,
        ),
    )
) {
    override val titleStringRes = R.string.carrier_settings_override_5g_title
}
