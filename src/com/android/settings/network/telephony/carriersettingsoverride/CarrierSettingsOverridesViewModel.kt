package com.android.settings.network.telephony.carriersettingsoverride

import android.app.Application
import android.os.PersistableBundle
import android.os.RemoteException
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyFrameworkInitializer
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import com.android.internal.telephony.ICarrierConfigLoader
import com.android.settings.R
import com.android.settings.network.telephony.CarrierConfigRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "CarriSettOverridVM"

private const val KEY_VERSION = "__carrier_config_package_version__"

class CarrierSettingsOverridesViewModel(application: Application) : AndroidViewModel(application) {
    private val carrierConfigLoader = ICarrierConfigLoader.Stub.asInterface(
        TelephonyFrameworkInitializer.getTelephonyServiceManager()
            .carrierConfigServiceRegisterer
            .get()
    )
    private val carrierConfigRepo = CarrierConfigRepository(application)
    private val carrierConfigManager: CarrierConfigManager? =
        application.getSystemService(CarrierConfigManager::class.java)

    private fun carrierConfigChanges(subId: Int): Flow<Unit> = callbackFlow {
        val manager = carrierConfigManager
        if (manager == null) {
            close()
            return@callbackFlow
        }

        val executor = Dispatchers.Default.asExecutor()
        val listener = CarrierConfigManager.CarrierConfigChangeListener { _, subscriptionId, _, _ ->
            if (subscriptionId == subId) {
                trySend(Unit)
            }
        }

        Log.d(TAG, "registering config listener")
        manager.registerCarrierConfigChangeListener(executor, listener)
        awaitClose {
            Log.d(TAG, "unregistering config listener")
            manager.unregisterCarrierConfigChangeListener(listener)
        }
    }.conflate()

    private val subId = MutableStateFlow(SubscriptionManager.INVALID_SUBSCRIPTION_ID)

    private val carrierConfigUpdatePing: SharedFlow<Unit> =
        subId
            .filter { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
            .flatMapLatest { sid -> carrierConfigChanges(sid) }
            .shareIn(
                scope = viewModelScope,
                started = SharingStarted.Lazily,
                replay = 0
            )

    private val _overrideStates = mutableStateListOf<CarrierConfigState>()
    val overrideStates: List<CarrierConfigState>
        get() = _overrideStates

    private val _isAnOverrideActive = MutableStateFlow(false)
    val isAnOverrideActive: StateFlow<Boolean> = _isAnOverrideActive

    private var isInitialized = false

    fun init(subId: Int) {
        this.subId.update { oldSubId ->
            if (oldSubId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) subId else oldSubId
        }
        viewModelScope.launch(Dispatchers.Default) {
            reloadFromCarrierConfig()
            isInitialized = true
        }
    }

    private suspend fun reloadFromCarrierConfig() {
        // Query telephony for current active overrides. This is a new method added to
        // CarrierConfigLoader
        val activeOverrides: PersistableBundle = carrierConfigLoader
            .getOverrideConfigForSubIdWithFeature(
                subId.value,
                application.opPackageName,
                application.attributionTag,
                true
            )
        activeOverrides.remove(KEY_VERSION)

        if (activeOverrides.isEmpty) {
            _isAnOverrideActive.update { false }
        }

        Log.d(TAG, "activeOverrides: $activeOverrides")

        // Construct states
        val configList = allowedUserChangeableCarrierConfigOptions.map { flagKey ->
            val state = CarrierConfigState.createState(
                subId.value,
                carrierConfigRepo,
                flagKey,
                activeOverrides
            )
            if (state.isOverriddenBefore.value && !_isAnOverrideActive.value) {
                _isAnOverrideActive.update { true }
            }
            state
        }

        Log.d(TAG, "new configList is $configList")
        withContext(Dispatchers.Main) {
            if (_overrideStates.isEmpty()) {
                _overrideStates.addAll(configList)
            } else {
                // preserve the selections
                _overrideStates.indices.forEach { index ->
                    val newState = configList[index]
                    _overrideStates[index] = _overrideStates[index].copy(
                        indexOfValueBefore = newState.indexOfValueBefore,
                        isOverriddenBefore = newState.isOverriddenBefore
                    )
                }
            }
        }
    }

    private val _isOverriding = MutableStateFlow(false)
    val isOverrideInProgress: StateFlow<Boolean> = _isOverriding

    sealed class MessageType {
        data class ErrorMessage(val msg: String) : MessageType()
        data object TurnOffToEdit : MessageType()
    }

    private val _errorMessage = MutableStateFlow<String?>(null)
    val message: Flow<MessageType?> =
        combine(_errorMessage, _isAnOverrideActive) { errorMsg, active ->
            if (errorMsg != null) {
                MessageType.ErrorMessage(errorMsg)
            } else if (active) {
                MessageType.TurnOffToEdit
            } else {
                null
            }
        }.distinctUntilChanged()

    private val gate = Mutex()
    fun submitOverrides(clearOverrides: Boolean): Unit = viewModelScope.launch {
        if (!isInitialized) return@launch
        if (!gate.tryLock()) return@launch
        _isOverriding.update { true }
        try {
            val overrides: PersistableBundle? = if (clearOverrides) {
                null
            } else {
                val bundle = PersistableBundle()
                for (state in _overrideStates) {
                    // null index means disabled. But the disabled option at the end could be
                    // selected as well resulting in non-null configState.
                    val configState = state.getConfigStateFromIndex() ?: continue
                    configState.insertIntoBundle(state.key.keys, bundle)
                }

                if (bundle.isEmpty) {
                    Log.d(TAG, "attempting to submit no overrides but not disabling")
                    _errorMessage.value = application.getString(
                        R.string.carrier_settings_override_no_override_selected_message
                    )
                    return@launch
                }

                bundle
            }
            Log.d(TAG, "submitting overrides $overrides")

            // overrideConfig does the update asynchronously by posting to a handler, so the config
            // is not guaranteed to updated immediately after this Binder call. Wait until an update
            // is broadcast after making the override call.
            //
            // Start waiting here to avoid missing very a fast update.
            val waiter = async { carrierConfigUpdatePing.first() }
            try {
                carrierConfigLoader.overrideConfig(subId.value, overrides, true)
                withTimeoutOrNull(2000L) {
                    waiter.await()
                    Log.d(TAG, "proceeding after carrier config update")
                }
                _errorMessage.update { null }
            } catch (e: RemoteException) {
                Log.e(TAG, "error while overriding config", e)
                _errorMessage.update { "RemoteException: ${e.message}" }
            } finally {
                waiter.cancel()
            }
            reloadFromCarrierConfig()
            delay(250L)
        } finally {
            gate.unlock()
            _isOverriding.update { false }
        }
    }.let { }
}
