package com.android.settings.network.telephony.carriersettingsoverride

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.PersistableBundle
import android.os.RemoteException
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyFrameworkInitializer
import android.util.ArrayMap
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import com.android.internal.telephony.ICarrierConfigLoader
import com.android.settings.R
import com.android.settings.network.telephony.CarrierConfigRepository
import app.grapheneos.carrierconfig2.ICarrierConfigSettingsService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
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
import kotlinx.coroutines.flow.stateIn
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

    /**
     * Whether the SIM is currently using an active override
     */
    private val _isAnOverrideActive = MutableStateFlow(false)
    val isAnOverrideActive: StateFlow<Boolean> = _isAnOverrideActive

    private var isInitialized = false

    private val intent = Intent().apply {
        component = ComponentName(
            "app.grapheneos.carrierconfig2",
            "app.grapheneos.carrierconfig2.CarrierConfigSettings"
        )
    }

    val service: StateFlow<ICarrierConfigSettingsService?> = callbackFlow {
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                Log.d(TAG, "onServiceConnected")
                trySend(ICarrierConfigSettingsService.Stub.asInterface(service))
            }

            override fun onServiceDisconnected(name: ComponentName) {
                Log.d(TAG, "onServiceDisconnected")
                trySend(null)
            }

            override fun onBindingDied(name: ComponentName) {
                Log.d(TAG, "onBindingDied")
                trySend(null)
                // ignore failures
                runCatching {
                    application.unbindService(this)
                    application.bindService(intent, this, Context.BIND_AUTO_CREATE)
                }
            }
        }

        val bound = runCatching {
            withContext(Dispatchers.Main.immediate) {
                Log.d(TAG, "bindService called")
                application.bindService(intent, conn, Context.BIND_AUTO_CREATE)
            }
        }.getOrElse {
            close(it)
            return@callbackFlow
        }
        if (!bound) {
            close(IllegalStateException("bindService returned false"))
            return@callbackFlow
        }

        awaitClose {
            application.unbindService(conn)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = null
    )

    fun init(subId: Int) {
        if (isInitialized) return

        this.subId.update { oldSubId ->
            if (oldSubId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                subId
            } else {
                return
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            reloadFromCarrierConfig()

            isInitialized = true
        }
    }

    private val _unrecognizedOverrides =
        MutableStateFlow<ArrayMap<String, Any?>?>(null)
    val unrecognizedOverrides: StateFlow<ArrayMap<String, Any?>?> = _unrecognizedOverrides

    private suspend fun setOverrideConfig(newOverrides: PersistableBundle?): Boolean {
        val service = service.firstWithTimeoutOrNull() ?: return false
        Log.d(TAG, "calling setOverrideConfig")
        service.setOverrideConfig(subId.value, newOverrides)

        return true
    }

    private suspend fun getOverrideConfigForSubId(): PersistableBundle? {
        val service = service.firstWithTimeoutOrNull() ?: return null
        Log.d(TAG, "calling getOverrideConfigForSubId")
        return service.getOverrideConfigForSubId(subId.value)
    }

    private suspend fun reloadFromCarrierConfig() {
        // Query CarrierConfig2
        val activeOverrides = getOverrideConfigForSubId()
        if (activeOverrides == null) {
            _message.update {
                MessageType.ErrorMessage(
                    application.getString(
                        R.string.carrier_settings_override_error_unable_to_connect_to_config
                    )
                )
            }
        }
        Log.d(TAG, "activeOverrides: $activeOverrides")
        _isAnOverrideActive.update { activeOverrides?.isEmpty == false }

        // Display any overrides from AOSP. We expect these to not be set, because this is only
        // meant to be used as a test API.
        val overridesFromAosp: PersistableBundle = carrierConfigLoader
            .getOverrideConfigForSubIdWithFeature(
                subId.value,
                application.opPackageName,
                application.attributionTag
            )
        overridesFromAosp.remove(KEY_VERSION)
        if (overridesFromAosp.isEmpty) {
            _unrecognizedOverrides.update { null }
        } else {
            // every override from AOSP is unrecognized
            val newUnrecognizedOverrides = ArrayMap<String, Any?>()
            overridesFromAosp.keySet().forEach { key ->
                newUnrecognizedOverrides[key] = overridesFromAosp.get(key)
            }
            Log.d(TAG, "found newUnrecognizedOverrides: $newUnrecognizedOverrides")
            _unrecognizedOverrides.update {
                newUnrecognizedOverrides.ifEmpty { null }
            }
        }

        // Construct states
        val configList = allowedUserChangeableCarrierConfigOptions.map { flagKey ->
            val state = CarrierConfigState.createState(
                subId.value,
                carrierConfigRepo,
                flagKey,
                activeOverrides ?: PersistableBundle()
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
                // preserve the selections if just trying to reload with latest state
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
        data class ErrorMessage(val msg: String, val title: String? = null) : MessageType()
        data object TurnOffToEdit : MessageType()
    }

    private val _message = MutableStateFlow<MessageType?>(null)
    val message: Flow<MessageType?> =
        combine(_message, _isAnOverrideActive) { errorMsg, active ->
            errorMsg
                ?: if (active) {
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
                    _message.update {
                        MessageType.ErrorMessage(
                            application.getString(
                                R.string.carrier_settings_override_no_override_selected_message
                            ),
                            title = application.getString(
                                R.string.carrier_settings_override_no_override_selected_title
                            ),
                        )
                    }
                    return@launch
                }

                bundle
            }
            Log.d(TAG, "submitting overrides $overrides")


            try {
                runThenAwaitCarrierConfigUpdateIfTrue {
                    if (setOverrideConfig(overrides)) {
                        _message.update { null }
                        true
                    } else {
                        _message.update {
                            MessageType.ErrorMessage(
                                application.getString(
                                    R.string.carrier_settings_override_error_unable_to_connect_to_config
                                )
                            )
                        }
                        false
                    }
                }

                if (clearOverrides && !_unrecognizedOverrides.value.isNullOrEmpty()) {
                    Log.d(TAG, "clearing all AOSP overrides")
                    runThenAwaitCarrierConfigUpdateIfTrue {
                        carrierConfigLoader.overrideConfig(subId.value, null, true)
                        true
                    }
                }
            } catch (e: RemoteException) {
                Log.e(TAG, "error while overriding config", e)
                _message.update { MessageType.ErrorMessage("RemoteException: ${e.message}") }
            }
            reloadFromCarrierConfig()
            // delay so user can't spam quickly
            delay(250L)
        } finally {
            gate.unlock()
            _isOverriding.update { false }
        }
    }.let { }

    private suspend inline fun runThenAwaitCarrierConfigUpdateIfTrue(
        timeoutMillis: Long = 2000L,
        crossinline block: suspend () -> Boolean
    ) {
        coroutineScope {
            // setOverrideConfig, etc. do the updates asynchronously, so the config is not
            // guaranteed to update immediately after RPC Binder calls. Wait until an update is
            // broadcast after making the override call.
            //
            // Start waiting here to avoid missing very a fast update.
            val waiter = async { carrierConfigUpdatePing.first() }
            try {
                if (block()) {
                    withTimeoutOrNull(timeoutMillis) {
                        waiter.await()
                    }
                }
            } finally {
                waiter.cancel()
            }
        }
    }
}

private suspend fun <T> Flow<T>.firstWithTimeoutOrNull(timeMillis: Long = 1000): T? =
    withTimeoutOrNull(timeMillis) {
        filter { it != null }.first()
    }
