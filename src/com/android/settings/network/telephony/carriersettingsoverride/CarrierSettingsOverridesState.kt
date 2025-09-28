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
sealed interface ConfigState {
    data object Inactive : ConfigState {
        override val isUserSelectable: Boolean get() = true
        override fun get(index: Int): CarrierConfigTypedValue? = null
        override fun insertIntoBundle(keys: List<String>, bundle: PersistableBundle) {}
    }

    sealed interface ActiveState : ConfigState {
        @get:StringRes val selectionStringRes: Int
        @get:StringRes val existingValueStringRes: Int
    }

    /**
     * Whether this state can be selected by the user in the UI or just something we display if
     * the default values are set to this state.
     */
    val isUserSelectable: Boolean

    data class Simple(
        val valueForAllKeys: CarrierConfigTypedValue,
        @get:StringRes override val selectionStringRes: Int,
        @get:StringRes override val existingValueStringRes: Int,
        override val isUserSelectable: Boolean = true
    ) : ActiveState {
        override fun get(index: Int): CarrierConfigTypedValue = valueForAllKeys
        override fun insertIntoBundle(keys: List<String>, bundle: PersistableBundle) {
            keys.forEach{ key ->
                doInsertion(valueForAllKeys, bundle, key)
            }
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
    ) : ActiveState {
        override fun get(index: Int): CarrierConfigTypedValue = stateValues[index]
        override fun insertIntoBundle(keys: List<String>, bundle: PersistableBundle) {
            keys.forEachIndexed { index, key -> doInsertion(stateValues[index], bundle, key) }
        }
    }

    operator fun get(index: Int): CarrierConfigTypedValue?

    fun insertIntoBundle(keys: List<String>, bundle: PersistableBundle)

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
sealed class ChangeableCarrierConfigFlag(
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

    // TODO: Simplify this by just checking all possible options and requiring that we give every
    //  possible states
    fun getClosestMatch(
        currentConfig: PersistableBundle,
        activeOverrides: PersistableBundle
    ): Pair<Int, ConfigState> {
        Log.d(TAG, "getClosestMatch for ${this.javaClass.simpleName}")
        require(possibleConfigStates.last() is ConfigState.Inactive)
        require(possibleConfigStates.isNotEmpty())

        // Note that the current config values already include the active overrides
        val indicesOfKeysInConfig: List<Int> = keys.asSequence()
            .mapIndexed { index, key -> index to key }
            .filter { (_, key) -> currentConfig.containsKey(key) }
            .map { (index, _) -> index }
            .toList()

        Log.d(TAG, "indicesOfKeysInConfig: $indicesOfKeysInConfig")
        if (indicesOfKeysInConfig.isEmpty()) {
            // Match to the disabled option
            return possibleConfigStates.indices.last to possibleConfigStates.last()
        }

        // Because the individual carrier config flag values can be different, we do a histogram
        // counting for each possible option the number of values that match up.
        //
        // For example, for VoNR, KEY_VONR_ENABLED_BOOL and KEY_VONR_SETTING_VISIBILITY_BOOL should
        // both be true to be enabled. However, it's possible for KEY_VONR_ENABLED_BOOL to be false
        // and KEY_VONR_SETTING_VISIBILITY_BOOL to be true.
        //
        // TODO: This histogram approach would be redundant if you specify all the possible
        //  options.
        val possibleConfigMatchHistogram = IntArray(possibleConfigStates.size)
        possibleConfigStates.forEachIndexed { configIndex, possibleState ->
            Log.d(TAG, "populating histogram for possible config state index $configIndex")
            when (possibleState) {
                is ConfigState.Complex -> {
                    indicesOfKeysInConfig.forEach { index ->
                        val key = keys[index]
                        val thisStateVal = possibleState.stateValues[index]
                        if (thisStateVal.matchesValue(key, currentConfig)) {
                            Log.d(TAG, "key $key matches $thisStateVal")
                            possibleConfigMatchHistogram[configIndex]++
                        }
                    }
                }
                ConfigState.Inactive -> {
                    indicesOfKeysInConfig.forEach { index ->
                        val key = keys[index]
                        if (!activeOverrides.containsKey(key)) {
                            Log.d(TAG, "key $key is not in activeOverrides")
                            possibleConfigMatchHistogram[configIndex]++
                        }
                    }
                }
                is ConfigState.Simple -> {
                    indicesOfKeysInConfig.forEach { index ->
                        val key = keys[index]
                        if (possibleState.valueForAllKeys.matchesValue(key, currentConfig)) {
                            Log.d(TAG, "key $key matches ${possibleState.valueForAllKeys}")
                            possibleConfigMatchHistogram[configIndex]++
                        }
                    }
                }
            }
        }
        Log.d(TAG, "stateValueMatchCounts: ${possibleConfigMatchHistogram.asList()}")

        val indexOfMax: Int = possibleConfigMatchHistogram.indices.maxBy {
            possibleConfigMatchHistogram[it]
        }
        return indexOfMax to possibleConfigStates[indexOfMax]
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
    val key: ChangeableCarrierConfigFlag,
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
            flag: ChangeableCarrierConfigFlag,
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

            val (indexOfClosestMatch, _) = flag.getClosestMatch(currentAsBundle, activeOverride)

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
