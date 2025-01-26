package com.android.settings.password.generate

import android.app.Application
import android.app.admin.DevicePolicyManager
import android.app.admin.PasswordMetrics
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.android.internal.widget.LockPatternUtils
import com.android.internal.widget.LockscreenCredential
import com.android.internal.widget.PasswordValidationError
import com.android.settings.R
import com.android.settings.SettingsApplication
import com.android.settings.password.ChooseLockPassword.ChooseLockPasswordFragment
import java.io.Closeable
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "GenerateLockPassVM"
private const val NUM_GENERATED_PINS_TO_SHOW = 3
private const val NUM_GENERATED_PASSPHRASES_TO_SHOW = 3
const val PRIMARY_BUTTON_DELAY_MILLIS = 100L

// will be multiplied by the number of passwords to show to determine max number of generation
// retries
private const val MAX_RETRIES_MULTIPLIER = 10

// run unit tests with a device plugged / emulator running and after building with m:
//
//    atest SettingsUnitTests:GenerateLockPasswordTest
//
// all tests can be run with
//
//    atest -c SettingsUnitTests:com.android.settings.password.generate
//
// Test in SetupWizard by unsetting password and using
//
//    adb shell pm enable app.grapheneos.setupwizard
//    adb shell settings put secure user_setup_complete 0
//    adb shell am start -a android.intent.action.MAIN -n app.grapheneos.setupwizard/app.grapheneos.setupwizard.view.activity.WelcomeActivity
//
class GenerateLockPasswordViewModel(
    private val application: Application,
    private val backgroundDispatcher: CoroutineDispatcher,
    private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {
    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val settingsApplication = this[APPLICATION_KEY] as SettingsApplication
                GenerateLockPasswordViewModel(
                    settingsApplication,
                    Dispatchers.Default,
                    Dispatchers.IO
                )
            }
        }
    }

    private val random = SecureRandom()

    override fun onCleared() {
        super.onCleared()
        cleanUp()
    }

    fun cleanUp() {
        _selectedPassword.value?.close()
        // nothing else to do about an array full of Strings, since we need the Strings to show
        // to the user in the UI the choices. Just call the garbage collector in Fragment /
        // Activity's onDestroy
        val state = (_generatedPasswords.value as? GenerateState.Loaded)?.list as? ArrayList
        state?.clear()
    }

    private val _genParams = MutableStateFlow<PassGenParams?>(null)
    val genParams: StateFlow<PassGenParams?> = _genParams.asStateFlow()

    val isAutoPinConfirm: StateFlow<Boolean> = _genParams.map { params ->
        when (params) {
            is PinGenParams -> ChooseLockPasswordFragment.isAutoPinConfirmPossible(params.digits) &&
                        params.autoPinConfirm
            else -> false
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val dicewareWordList: Deferred<Result<DicewareWordList>> by lazy {
        viewModelScope.async(start = CoroutineStart.LAZY) {
            Log.d(TAG, "loading diceware words")
            try {
                Result.success(DicewareWordList.loadWords(application, ioDispatcher))
            } catch (e: DicewareWordList.LoadException) {
                Log.e(TAG, "failed to load diceware words", e)
                Result.failure(e)
            }
        }
    }

    enum class PassType {
        Pin,
        // For the first screen, this will be also the Password type if the user goes on to just
        // use their own password
        Passphrase
    }

    val passType: StateFlow<PassType?> = genParams
        .map {
            when (it) {
                is DicewarePassphraseGenParams -> PassType.Passphrase
                is PinGenParams -> PassType.Pin
                null -> null
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _stage = MutableStateFlow<PassGenStage>(PassGenStage.ChooseGeneratedOrManual(false))
    // force stage to be null for observers until the right passtype has been processed
    val stage: StateFlow<PassGenStage?> =
        combine(passType, _stage) { passType, curStage ->
            if (passType == null) null else curStage
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    var generationCount = 0L
        private set

    sealed class GenerateState {
        data object NotLoaded : GenerateState()
        data class Error(val errorMessage: String) : GenerateState()
        data class Loaded(val list: List<GeneratedPassword>) : GenerateState()

        fun listOrNull() = when (this) {
            is Loaded -> list
            else -> null
        }

        val size: Int get() = when (this) {
            is Loaded -> list.size
            else -> 0
        }
    }

    private val _generatedPasswords = MutableStateFlow<GenerateState>(GenerateState.NotLoaded)
    val generatedPasswords: StateFlow<GenerateState> = _generatedPasswords.asStateFlow()

    fun getGeneratedPasswordIdForRecyclerView(position: Int): Long {
        val base: Int = _generatedPasswords.value.size
            .takeIf { it > 0 }
            ?: minOf(NUM_GENERATED_PINS_TO_SHOW, NUM_GENERATED_PASSPHRASES_TO_SHOW)
        // The generated passwords list will never be updated partially (unless we support editing
        // passphrases). The contents will only change if an entire new set of passwords is
        // generated. Suffices to just use the generation count to get unique IDs for recyclerview
        return generationCount * base + position
    }

    /**
     * This is either an index for the list in [_generatedPasswords], or a stored index with
     * the LockscreenCredential
    */
    sealed class Selection : Closeable {
        abstract val index: Int
        data class IndexOnly(override val index: Int) : Selection() {
            override fun close() {}
        }
        class ForConfirmation(
            override val index: Int,
            // don't zeroize until Activity closes
            val credential: LockscreenCredential
        ) : Selection() {
            override fun close() {
                credential.zeroize()
            }

            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false
                other as ForConfirmation
                if (index != other.index) return false
                // constant-time comparison (is this necessary, since LockscreenCredential just uses
                // Array.equals?)
                return MessageDigest.isEqual(credential.credential, other.credential.credential)
            }
        }
    }
    private val _selectedPassword = MutableStateFlow<Selection?>(null)
    val selectedPassword = _selectedPassword.asStateFlow()

    /** Lets the UI access the password text string */
    fun getPassword(selection: Selection): GeneratedPassword? {
        return _generatedPasswords.value.listOrNull()?.getOrNull(selection.index)
    }

    fun setSelectedPassword(newIndex: Int) {
        _selectedPassword.update { current ->
            if (newIndex == current?.index && current !is Selection.ForConfirmation) return
            val validIndices = _generatedPasswords.value.listOrNull()?.indices ?: return
            if (newIndex in validIndices) {
                setAutoPinConfirm(false)
                current?.close()
                Selection.IndexOnly(index = newIndex)
            } else {
                current
            }
        }
    }

    /** Idempotent function to set new length */
    fun setNewLength(newLength: Int) {
        _genParams.update { currentOpts ->
            currentOpts ?: return
            if (newLength !in currentOpts.minSize..currentOpts.maxSize) {
                return
            }

            when (currentOpts) {
                is DicewarePassphraseGenParams -> {
                    if (currentOpts.words == newLength) return
                    currentOpts.copy(words = newLength)
                }
                is PinGenParams -> {
                    if (currentOpts.digits == newLength) return
                    currentOpts.copy(digits = newLength)
                }
            }.also { regenerateOnNav = true }
        }
    }

    fun setAutoPinConfirm(enabled: Boolean) {
        _genParams.update { currentOpts ->
            when (currentOpts) {
                is PinGenParams -> {
                    if (currentOpts.autoPinConfirm == enabled) return

                    currentOpts.copy(autoPinConfirm = enabled)
                }
                else -> return
            }
        }
    }

    private val minPasswordMetrics = MutableStateFlow(
        PasswordMetrics(LockPatternUtils.CREDENTIAL_TYPE_NONE)
    )

    var minPasswordComplexity: PasswordComplexity = PasswordComplexity.MEDIUM
        private set

    fun setup(isAlphabeticalMode: Boolean, metrics: PasswordMetrics?, complexity: Int) {
        if (passType.value != null) {
            return
        }
        _genParams.update { existingParams ->
            if (existingParams != null) {
                return
            }

            minPasswordMetrics.value = metrics ?: if (isAlphabeticalMode) {
                PasswordMetrics(LockPatternUtils.CREDENTIAL_TYPE_PASSWORD)
            } else {
                PasswordMetrics(LockPatternUtils.CREDENTIAL_TYPE_PIN)
            }
            minPasswordComplexity = PasswordComplexity.fromLevel(
                complexity,
                minLevel = if (isAlphabeticalMode) {
                    // Use a default low complexity level for passphrases, because >= MEDIUM
                    // complexity does not allow sequences that are more than 3 (i.e.
                    // PasswordMetrics.MAX_ALLOWED_SEQUENCE). This would result in some words being
                    // excluded from the wordlist and affect entropy, e.g. the word "overstuff" has
                    // sequence of length 4 because of "rstu"
                    PasswordComplexity.LOW
                } else {
                    // Note: This will avoid sequences in PINs (like 1234, 1111, etc.) by default.
                    // Reduces PIN generation entropy, but PINs already have really low entropy and
                    // are backed by secure element throttling.
                    PasswordComplexity.MEDIUM
                }
            )

            if (isAlphabeticalMode) {
                // We're not supporting extra device admin options like adding extra symbols,
                // uppercase, etc.
                DicewarePassphraseGenParams(
                    words = DicewarePassphraseGenParams.MIN_WORDS,
                    numberToGenerate = NUM_GENERATED_PASSPHRASES_TO_SHOW,
                    minSize = DicewarePassphraseGenParams.MIN_WORDS,
                    maxSize = DicewarePassphraseGenParams.MAX_WORDS
                )
            } else {
                val minDigits = maxOf(
                    minPasswordMetrics.value.length, // defaults to 0 for unmanaged users
                    minPasswordComplexity.pinLength, // defaults to 4 digits for unmanaged (MEDIUM)
                    PinGenParams.DEFAULT_MIN_DIGITS
                )
                // If, for some reason, a device admin wants PINs that are greater than
                // our default MAX digits, obey it for now
                val maxDigits = maxOf(minDigits, PinGenParams.DEFAULT_MAX_DIGITS)

                PinGenParams(
                    digits = minDigits,
                    numberToGenerate = NUM_GENERATED_PINS_TO_SHOW,
                    minSize = minDigits,
                    maxSize = maxDigits
                )
            }
        }
    }

    data class PassphraseLenWarning(val fullNumberOfWords: Int, val maxPasswordLength: Int)

    /**
     * Indicates to the user if the max word count can generated passphrases that exceed
     * [DevicePolicyManager.MAX_PASSWORD_LENGTH]
     *
     * Not expected to be reached with 4-8 words with [DevicePolicyManager.MAX_PASSWORD_LENGTH] of
     * 128. However, if max words is increased in the future for whatever reason (13-word
     * passphrases of all length-9 words can start running into this), it can cause loss of
     * passphrase entropy, since certain passphrases will exceed
     * [DevicePolicyManager.MAX_PASSWORD_LENGTH].
     *
     * e.g. EFF wordlist with b = 6^5 words can generate b^13 ~ 2^168 possible passphrases of
     * length 13. If the max password length is 128, these passphrases would have string length
     * 9 * 13 + numSpaces = 129 (numSpaces = 12), exceeding the max password length. There are 1557
     * words of length 9 in the EFF wordlist, so 1557^13 ~ 2^138 such possible passphrases would
     * be excluded. This is only 1557^13 / b^13 ~ 0.00000008316% of all possible 13-word passphrases
     * from this wordlist, but it still reduces the search space.
     */
    val passphraseMaxWordsEntropyWarning: Flow<PassphraseLenWarning?> = _genParams.map { params ->
        if (params !is DicewarePassphraseGenParams) return@map null
        val safeMaxSize = (params.maxSize downTo params.minSize)
            .firstOrNull {
                GeneratedPassphrase.calculateMaxPossibleStringLength(
                    it,
                    // Passphrase words are just selected using SecureRandom.nextInt on index;
                    // words can technically be repeated but very unlikely.
                    allowResampling = true
                ) <= DevicePolicyManager.MAX_PASSWORD_LENGTH
            }
            ?: 0
        if (safeMaxSize == params.maxSize) return@map null

        PassphraseLenWarning(safeMaxSize, DevicePolicyManager.MAX_PASSWORD_LENGTH)
    }

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating

    /**
     * We do not support the specific [DevicePolicyManager] password requirements, and we opt
     * to disable generation flow entirely if password requirements are too strict
     */
    val areMinMetricsRestrictive =
        combine(minPasswordMetrics, _genParams) { minMetrics, genParam ->
            if (genParam !is DicewarePassphraseGenParams) return@combine false

            val minimumNumberOfSpaces = genParam.minWords - 1

            // All the metrics: letters, upperCase, lowerCase, numeric, symbols, nonLetter,
            // nonNumeric, seqLength
            //
            // Note that using these requirements via DevicePolicyManager is deprecated, and
            // MDMs are not common on GrapheneOS anyway.
            minMetrics.upperCase > 0 || minMetrics.numeric > 0
                    || minMetrics.symbols > minimumNumberOfSpaces
                    || minMetrics.nonLetter > minimumNumberOfSpaces
                    // We don't want certain words to get excluded as that would reduce passphrase
                    // entropy. Note: Currently bypassing sequence length errors
                    // || minMetrics.seqLength < DicewareWordList.MAX_SEQUENCE_LENGTH
        }

    /**
     * Keeps track of user confirmation input length so that we avoid storing references to their
     * input
     */
    private val _currentInputLength = MutableStateFlow(0)
    
    fun setInputLength(length: Int) {
        _currentInputLength.value = length
    }

    enum class ConfirmError {
        DOESNT_MATCH, TOO_SHORT
    }

    private val _confirmError = MutableStateFlow<ConfirmError?>(null)
    val confirmError: StateFlow<ConfirmError?> = _confirmError.asStateFlow()

    sealed class SaveRequest {
        data object Inactive : SaveRequest()
        data class Requested(val autoPinConfirm: Boolean) : SaveRequest()
    }

    // Used to request the fragment to launch the AOSP save worker from ChooseLockPassword
    private val _saveRequest = MutableStateFlow<SaveRequest>(SaveRequest.Inactive)
    val saveRequest: StateFlow<SaveRequest> = _saveRequest.asStateFlow()

    private fun isInputLengthValid(currentLength: Int): Boolean {
        return currentLength >= LockPatternUtils.MIN_LOCK_PASSWORD_SIZE
    }

    private val _isPrimaryButtonProcessing = MutableStateFlow(false)

    val isPrimaryButtonEnabled: StateFlow<Boolean> =
        combine(
            stage, _selectedPassword, _isGenerating, _currentInputLength, _saveRequest,
            _isPrimaryButtonProcessing, areMinMetricsRestrictive
        ) { stage, selectedPass, isGenerating, inputLength, saveRequest, isPrimaryBtnProcessing,
            areMetricsRestrictive ->
            if (isPrimaryBtnProcessing) {
                return@combine false
            }

            when (stage) {
                is PassGenStage.ChooseGeneratedOrManual -> {
                    // prevent user from generating passphrase if DPM requirements too strict, since
                    // we're not accommodating them
                    !areMetricsRestrictive
                }
                is PassGenStage.ChooseParams -> true
                is PassGenStage.ShowMultiple -> !isGenerating && selectedPass != null
                is PassGenStage.Confirmation ->
                    isInputLengthValid(inputLength) && saveRequest == SaveRequest.Inactive
                PassGenStage.Quit -> false
                null -> false
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun onBackPressed() {
        _stage.update { curStage ->
            when (curStage) {
                PassGenStage.Quit -> curStage
                is PassGenStage.ChooseGeneratedOrManual -> PassGenStage.Quit
                is PassGenStage.ChooseParams -> PassGenStage.ChooseGeneratedOrManual(isBackwards = true)
                is PassGenStage.ShowMultiple -> {
                    regenerateOnNav = false
                    PassGenStage.ChooseParams(isBackwards = true)
                }
                is PassGenStage.Confirmation -> {
                    _selectedPassword.update { selected ->
                        if (selected is Selection.ForConfirmation) {
                            selected.close()
                            Selection.IndexOnly(selected.index)
                        } else {
                            selected
                        }
                    }
                    PassGenStage.ShowMultiple(isBackwards = true)
                }
            }
        }
    }

    fun primaryButtonClicked(inputPassword: CharSequence? = null) {
        if (!isPrimaryButtonEnabled.value) {
            return
        }
        // atomic update
        _isPrimaryButtonProcessing.update { isProcessing ->
            if (isProcessing) return
            true
        }
        try {
            advanceStage(inputPassword)
        } finally {
            viewModelScope.launch {
                delay(PRIMARY_BUTTON_DELAY_MILLIS)
                _isPrimaryButtonProcessing.update { false }
            }
        }
    }

    private var regenerateOnNav = false

    private fun advanceStage(inputPassword: CharSequence?) {
        _stage.update { curStage ->
            when (curStage) {
                is PassGenStage.ChooseGeneratedOrManual -> {
                    if (passType.value == PassType.Passphrase) {
                        dicewareWordList.start()
                    }
                    PassGenStage.ChooseParams(isBackwards = false)
                }
                is PassGenStage.ChooseParams -> {
                    generationRequestChannel.trySend(GenerationRequest(regenerateOnNav))

                    PassGenStage.ShowMultiple(isBackwards = false)
                }
                is PassGenStage.ShowMultiple -> {
                    _selectedPassword.update { selected ->
                        selected ?: return
                        val password = _generatedPasswords.value.listOrNull()
                            ?.getOrNull(selected.index)
                            ?: return
                        Selection.ForConfirmation(selected.index, password.toLockscreenCredential())
                    }

                    PassGenStage.Confirmation.ConfirmWithVisible
                }
                is PassGenStage.Confirmation -> {
                    _confirmError.update { null }
                    if (inputPassword == null || !isInputLengthValid(inputPassword.length)) {
                        _confirmError.update { ConfirmError.TOO_SHORT }
                        return
                    }
                    val selectionCredential =
                        (_selectedPassword.value as? Selection.ForConfirmation)?.credential
                            ?: return

                    when (passType.value) {
                        PassType.Pin -> LockscreenCredential.createPin(inputPassword)
                        PassType.Passphrase -> LockscreenCredential.createPassword(inputPassword)
                        null -> return
                    }.use { inputCredential ->
                        // again, LockscreenCredential does not do this and just uses Array.equals
                        if (
                            !MessageDigest.isEqual(
                                inputCredential.credential, selectionCredential.credential
                            )
                        ) {
                            _confirmError.update { ConfirmError.DOESNT_MATCH }
                            return
                        }
                    }

                    _confirmError.update { null }
                    _currentInputLength.update { 0 }
                    when (curStage) {
                        PassGenStage.Confirmation.ConfirmWithVisible -> {
                            PassGenStage.Confirmation.ConfirmWithoutVisible
                        }

                        PassGenStage.Confirmation.ConfirmWithoutVisible -> {
                            PassGenStage.Confirmation.ConfirmLast
                        }
                        PassGenStage.Confirmation.ConfirmLast -> {
                            val autoPinConfirm = isAutoPinConfirm.value
                            _saveRequest.update { SaveRequest.Requested(autoPinConfirm) }
                            curStage
                        }
                    }
                }
                PassGenStage.Quit -> curStage
            }
        }
    }

    @JvmInline
    value class GenerationRequest(val forceRegenerate: Boolean)

    // A Channel to handle async requests for generating passwords to deduplicate requests
    // and process requests serially
    private val generationRequestChannel = Channel<GenerationRequest>(capacity = Channel.RENDEZVOUS)

    fun generateNewPasswords() {
        generationRequestChannel.trySend(GenerationRequest(forceRegenerate = true))
    }

    init {
        viewModelScope.launch(backgroundDispatcher) {
            generationRequestChannel.consumeEach { request ->
                val isAlreadyGenerated = _generatedPasswords.value is GenerateState.Loaded
                if (isAlreadyGenerated && !request.forceRegenerate) {
                    return@consumeEach
                }
                _isGenerating.update { true }
                _selectedPassword.update { oldSelection ->
                    oldSelection?.close()
                    null
                }
                try {
                    generatePasswords()
                    if (isAlreadyGenerated) {
                        delay(100L)
                    }
                } finally {
                    _isGenerating.update { false }
                }
            }
        }
    }

    private suspend fun generatePasswords(): Unit = withContext(backgroundDispatcher) {
        generationCount++

        val params = _genParams.value ?: return@withContext
        val maxRetries = params.numberToGenerate * MAX_RETRIES_MULTIPLIER

        val newPasswords = ArrayList<GeneratedPassword>(params.numberToGenerate)
        val allErrors = hashSetOf<String>()
        // The retries variable is meant to catch policy errors, not one-off edge cases
        var retries = 0

        _generatedPasswords.update { GenerateState.NotLoaded }
        while (newPasswords.size < params.numberToGenerate && isActive) {
            val generated: GeneratedPassword = when (params) {
                is DicewarePassphraseGenParams -> {
                    val result = dicewareWordList.await()
                    val generator = result.getOrNull()
                    if (generator == null) {
                        _generatedPasswords.update {
                            GenerateState.Error(result.exceptionOrNull()?.message ?: "")
                        }
                        return@withContext
                    }

                    GeneratedPassphrase.generate(random, generator, params.words)
                }
                is PinGenParams -> {
                    GeneratedPin.generate(random, params.digits)
                }
            }

            // Only show generated passwords that would be considered by Android to satisfy
            // any policy requirements. Note that DPMs password requirements are deprecated, and the
            // intended behavior is to disable this generation flow entirely if requirements are too
            // strict.
            //
            // Note that this will include avoiding arithmetic sequences in PINs. This can reduce
            // the entropy of a generated PIN, but entropy isn't really a concern when talking about
            // PINs anyway due to the secure element.
            //
            // For passphrases, there were checks earlier to prevent the user from choosing
            // generation if device policy requires them to have uppercase, extra symbols, etc.
            // but if there were any issues, they can still be caught here.
            val errors: List<PasswordValidationError> = generated
                .toLockscreenCredential()
                .use { cred ->
                    PasswordMetrics.validateCredential(
                        minPasswordMetrics.value,
                        minPasswordComplexity.complexityValue,
                        cred
                    )
                }

            // Bypass sequence errors. This is a rather arbitrary requirement when it comes to
            // generated passphrases. The alternative is to edit the wordlist to ensure all words
            // have a max length sequence that is at most PasswordMetrics.MAX_ALLOWED_SEQUENCE,
            // which is currently 3
            val shouldBypassError = params is DicewarePassphraseGenParams &&
                    errors.size == 1 &&
                    errors.first().errorCode == PasswordValidationError.CONTAINS_SEQUENCE

            if (errors.isEmpty() || shouldBypassError) {
                newPasswords.add(generated)
            } else {
                // PasswordValidationError will only say the reason without storing the
                // password, though it might indicate length required
                Log.d(TAG, "failed to generate: $errors")
                allErrors.addAll(errors.asSequence().map { it.toString() })
                if (retries >= maxRetries) {
                    val msg = application
                        .getString(
                            R.string.lock_screen_generate_show_generated_error_device_requirements_too_strict_s,
                            // use kotlin's toString
                            allErrors.toList().toString()
                        )

                    _generatedPasswords.update { GenerateState.Error(msg) }
                    return@withContext
                } else {
                    retries++
                }
            }
        }

        Log.d(TAG, "generated ${newPasswords.size} passwords successfully")

        regenerateOnNav = false
        _generatedPasswords.update { GenerateState.Loaded(newPasswords) }
    }
}
