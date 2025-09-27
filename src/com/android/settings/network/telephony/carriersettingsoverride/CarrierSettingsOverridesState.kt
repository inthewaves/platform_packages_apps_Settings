package com.android.settings.network.telephony.carriersettingsoverride

import android.annotation.StringRes
import android.os.PersistableBundle
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import com.android.settings.network.telephony.CarrierConfigRepository
import com.android.settings.network.telephony.CarrierConfigRepository.KeyType

private const val TAG = "CarrierSetOverrideState"

// See CarrierConfigOverrideOptions to add more options

/**
 * A class that represents a specific state for a carrier config override option. The state may
 * be selectable or not by the user.
 *
 * e.g. for boolean flags, you would have [ConfigState] instance for "Enabled" which specifies
 * the carrier config flag values for "Enabled", and another [ConfigState] for "Disabled" with its
 * own flag values.
 */
@Immutable
sealed class ConfigState {
    /**
     * Indicates the state where the override is not active.
     */
    data object Inactive : ConfigState() {
        override val isUserSelectable: Boolean get() = true
        override fun get(index: Int): CarrierConfigTypedValue? = null
        override fun insertIntoBundle(keys: List<String>, bundle: PersistableBundle) {}
        override fun testMatching(
            key: String,
            keyIndex: Int,
            config: PersistableBundle,
            overrideConfig: PersistableBundle
        ): Boolean {
            return !overrideConfig.containsKey(key)
        }
    }

    /**
     * Indicates a state where the override is active.
     */
    sealed class ActiveState : ConfigState() {
        @get:StringRes abstract val selectionStringRes: Int
        @get:StringRes abstract val existingValueStringRes: Int
    }

    /**
     * Whether this state can be selected by the user in the UI or just something we display if
     * the default values are set to this state.
     */
    abstract val isUserSelectable: Boolean

    data class Simple(
        val valueForAllKeys: CarrierConfigTypedValue,
        @get:StringRes override val selectionStringRes: Int,
        @get:StringRes override val existingValueStringRes: Int,
        override val isUserSelectable: Boolean = true
    ) : ActiveState() {
        override fun get(index: Int): CarrierConfigTypedValue = valueForAllKeys
        override fun insertIntoBundle(keys: List<String>, bundle: PersistableBundle) {
            keys.forEach{ key ->
                doInsertion(valueForAllKeys, bundle, key)
            }
        }

        override fun testMatching(
            key: String,
            keyIndex: Int,
            config: PersistableBundle,
            overrideConfig: PersistableBundle
        ): Boolean {
            return valueForAllKeys.matchesValue(key, config)
        }
    }

    /**
     *  For when a set of flags have different values per setting. size of [stateValues] is
     *  expected to be the same as the size of the keys list.
     */
    data class Complex(
        val stateValues: List<CarrierConfigTypedValue>,
        @get:StringRes override val selectionStringRes: Int,
        @get:StringRes override val existingValueStringRes: Int,
        override val isUserSelectable: Boolean = true
    ) : ActiveState() {
        override fun get(index: Int): CarrierConfigTypedValue = stateValues[index]
        override fun insertIntoBundle(keys: List<String>, bundle: PersistableBundle) {
            keys.forEachIndexed { index, key -> doInsertion(stateValues[index], bundle, key) }
        }

        override fun testMatching(
            key: String,
            keyIndex: Int,
            config: PersistableBundle,
            overrideConfig: PersistableBundle
        ): Boolean {
            val typedValue = stateValues[keyIndex]
            return typedValue.matchesValue(key, config)
        }

    }

    abstract operator fun get(index: Int): CarrierConfigTypedValue?

    abstract fun insertIntoBundle(keys: List<String>, bundle: PersistableBundle)

    protected abstract fun testMatching(
        key: String,
        keyIndex: Int,
        config: PersistableBundle,
        overrideConfig: PersistableBundle
    ): Boolean

    /**
     * Determines whether the values in [config] and [overrideConfig] match with this particular
     * config state.
     */
    fun isMatchingConfig(
        keys: List<String>,
        subsetKeyIndices: List<Int>,
        config: PersistableBundle,
        overrideConfig: PersistableBundle
    ): Boolean {
        subsetKeyIndices.forEach { index ->
            val key = keys[index]
            if (!testMatching(key, index, config, overrideConfig)) {
                return false
            }
        }
        return true
    }

    fun doInsertion(
        stateVal: CarrierConfigTypedValue,
        bundle: PersistableBundle,
        key: String,
    ) {
        when (stateVal) {
            is CarrierConfigTypedValue.Bool ->
                stateVal.currentValue?.let { bundle.putBoolean(key, it) }
            is CarrierConfigTypedValue.Integer ->
                stateVal.currentValue?.let { bundle.putInt(key, it) }
            is CarrierConfigTypedValue.IntegerArray ->
                stateVal.currentValue?.let { bundle.putIntArray(key, it.toIntArray()) }
            is CarrierConfigTypedValue.Str ->
                stateVal.currentValue?.let { bundle.putString(key, it) }
        }
    }
}

/**
 * Specifies a user-facing option for a set of carrier config flags
 *
 * @param keysWithType specifies the keys for the config flags that will be edited by this u
 * @param allPossibleConfigStates specifies all the possible values for this option.
 * For example, for 5G, you would need to do FLAG_ENABLED and FLAG_UI_ENABLED. So the possible
 * options would be:
 *  - FLAG_ENABLED and FLAG_UI_ENABLED: "Enabled"
 *  - !FLAG_ENABLED and !FLAG_UI_ENABLED: "Disabled"
 * And we can consider all other options as disabled.
 */
@Stable
sealed class ChangeableCarrierConfigOption(
    keysWithType: List<Pair<String, KeyType>>,
    allPossibleConfigStates: List<ConfigState>,
) {
    @get:StringRes
    abstract val titleStringRes: Int

    /**
     * List of all possible config states including disabled.
     */
    val possibleConfigStates: List<ConfigState> = allPossibleConfigStates + listOf(ConfigState.Inactive)

    val keys: List<String> = keysWithType.map { it.first }
    val types: List<KeyType> = keysWithType.map { it.second }

    fun findMatchingConfigStateIndex(
        currentConfig: PersistableBundle,
        activeOverrides: PersistableBundle
    ): Int? {
        Log.d(TAG, "findMatchingConfigStateIndex for ${this.javaClass.simpleName}")
        require(possibleConfigStates.last() is ConfigState.Inactive)
        require(possibleConfigStates.isNotEmpty())

        // Note that the current config values already include the active overrides
        val indicesOfKeysInConfig: List<Int> = keys.asSequence()
            .withIndex()
            .filter { currentConfig.containsKey(it.value) }
            .map { it.index }
            .toList()

        Log.d(TAG, "indicesOfKeysInConfig: $indicesOfKeysInConfig")
        if (indicesOfKeysInConfig.isEmpty()) {
            // Match to the disabled option
            return possibleConfigStates.indices.last
        }

        return possibleConfigStates.asSequence()
            .withIndex()
            .filter {
                it.value.isMatchingConfig(
                    keys, indicesOfKeysInConfig, currentConfig, activeOverrides
                )
            }
            .map { it.index }
            .firstOrNull()
    }
}

@Immutable
sealed class CarrierConfigTypedValue {
    abstract val currentValue: Any?
    fun matchesValue(key: String, bundle: PersistableBundle): Boolean {
        if (!bundle.containsKey(key)) {
            return currentValue == null
        }
        return matchesValueInner(key, bundle)
    }
    protected abstract fun matchesValueInner(key: String, bundle: PersistableBundle): Boolean
    data class Bool(
        override val currentValue: Boolean?,
    ) : CarrierConfigTypedValue() {
        override fun matchesValueInner(key: String, bundle: PersistableBundle) =
            bundle.getBoolean(key) == currentValue
    }
    data class Str(
        override val currentValue: String?,
    ) : CarrierConfigTypedValue() {
        override fun matchesValueInner(key: String, bundle: PersistableBundle) =
            bundle.getString(key) == currentValue
    }
    data class Integer(
        override val currentValue: Int?,
    ) : CarrierConfigTypedValue() {
        override fun matchesValueInner(key: String, bundle: PersistableBundle) =
            bundle.getInt(key) == currentValue
    }
    data class IntegerArray(
        // represent as a List (zero-copy representation of array in Kotlin) for hashcode/equals
        override val currentValue: List<Int>?,
    ) : CarrierConfigTypedValue() {
        override fun matchesValueInner(key: String, bundle: PersistableBundle) =
            bundle.getIntArray(key)?.asList() == currentValue
    }
}

/**
 * UI-facing state for a specific carrier config override option. e.g., we would have an instance
 * of this to store state for VoLTE overrides, and another instance for VoNR state.
 *
 * Stores info such as which state is selected and whether this state value is from an overridden
 * value.
 */
@Stable
data class CarrierConfigState(
    /**
     * The specific option. This stores all possible states for the option, along with some string
     * resources to display in the UI, etc.
     */
    val key: ChangeableCarrierConfigOption,
    /**
     * An index to the state prior to entering the carrier config overrides screen, i.e. the
     * existing state
     */
    val indexOfValueBefore: Int?,
    /**
     * An index to the current selection for this carrier config override option
     */
    val stateIndex: MutableState<Int?> = mutableStateOf(null),
    /**
     * Whether this carrier config option is from an active override. If this is true, we expect
     * that this state can't be changed until all overrides are removed.
     */
    val isOverriddenBefore: MutableState<Boolean> = mutableStateOf(false),
) {
    fun getConfigStateFromIndex(useIndexOfCurrentConfigValue: Boolean = false): ConfigState? {
        val index = if (useIndexOfCurrentConfigValue) {
            indexOfValueBefore
        } else {
            stateIndex.value
        }
        return index?.let { key.possibleConfigStates[index] }
    }

    companion object {
        fun createState(
            subId: Int,
            repo: CarrierConfigRepository,
            flag: ChangeableCarrierConfigOption,
            activeOverride: PersistableBundle
        ): CarrierConfigState {
            val currentAsBundle: PersistableBundle = repo.transformConfig(subId) {
                val bundle = PersistableBundle()
                flag.keys.forEachIndexed { index, key ->
                    val type = flag.types[index]
                    when (type) {
                        KeyType.BOOLEAN -> bundle.putBoolean(key, getBoolean(key))
                        KeyType.INT -> bundle.putInt(key, getInt(key))
                        KeyType.INT_ARRAY -> bundle.putIntArray(key, getIntArray(key))
                        KeyType.STRING -> bundle.putString(key, getString(key))
                    }
                }
                bundle
            }

            Log.d(TAG, "currentAsBundle: $currentAsBundle")

            val indexOfClosestMatch = flag.findMatchingConfigStateIndex(currentAsBundle, activeOverride)

            val isOverridden = !activeOverride.isEmpty &&
                    flag.keys.any { key -> activeOverride.containsKey(key) }
            // Set this so that when entering back into the screen with an override active, the
            // corresponding options gets selected
            val index: Int? = if (isOverridden) indexOfClosestMatch else null
            return CarrierConfigState(
                key = flag,
                indexOfValueBefore = indexOfClosestMatch,
                stateIndex = mutableStateOf(index),
                isOverriddenBefore = mutableStateOf(isOverridden)
            )
        }
    }
}
