package com.android.settings.password.generate

import android.app.Application
import android.app.admin.DevicePolicyManager
import android.app.admin.PasswordMetrics
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.internal.widget.LockPatternUtils
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GenerateLockPasswordTest {

    private lateinit var mContext: Context

    private val defaultMinMetrics = PasswordMetrics(LockPatternUtils.CREDENTIAL_TYPE_NONE)

    private val defaultMinComplexity = DevicePolicyManager.PASSWORD_COMPLEXITY_LOW

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mContext = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testSetupPinLengthsByComplexity(): Unit = testScope.runTest {
        GenerateLockPasswordViewModel(mContext as Application, testDispatcher, testDispatcher).let { viewModel ->
            viewModel.setup(isAlphabeticalMode = false, defaultMinMetrics, DevicePolicyManager.PASSWORD_COMPLEXITY_LOW)
            advanceUntilIdle()

            val params = requireNotNull(viewModel.genParams.filterNotNull().firstWithTimeoutOrNull())
            assertThat(viewModel.minPasswordComplexity).isEqualTo(PasswordComplexity.MEDIUM)
            assertThat(params.minSize).isEqualTo(PinGenParams.DEFAULT_MIN_DIGITS)
            assertThat(params.maxSize).isEqualTo(PinGenParams.DEFAULT_MAX_DIGITS)
        }

        GenerateLockPasswordViewModel(mContext as Application, testDispatcher, testDispatcher).let { viewModel ->
            viewModel.setup(isAlphabeticalMode = false, defaultMinMetrics, DevicePolicyManager.PASSWORD_COMPLEXITY_MEDIUM)
            advanceUntilIdle()

            val params = requireNotNull(viewModel.genParams.filterNotNull().firstWithTimeoutOrNull())
            assertThat(viewModel.minPasswordComplexity).isEqualTo(PasswordComplexity.MEDIUM)
            assertThat(params.minSize).isEqualTo(PinGenParams.DEFAULT_MIN_DIGITS)
            assertThat(params.maxSize).isEqualTo(PinGenParams.DEFAULT_MAX_DIGITS)
        }

        GenerateLockPasswordViewModel(mContext as Application, testDispatcher, testDispatcher).let { viewModel ->
            viewModel.setup(isAlphabeticalMode = false, defaultMinMetrics, DevicePolicyManager.PASSWORD_COMPLEXITY_HIGH)

            val params = requireNotNull(viewModel.genParams.filterNotNull().firstWithTimeoutOrNull())
            assertThat(viewModel.minPasswordComplexity).isEqualTo(PasswordComplexity.HIGH)
            assertThat(params.minSize).isEqualTo(PasswordComplexity.HIGH.pinLength)
            assertThat(params.maxSize).isEqualTo(PasswordComplexity.HIGH.pinLength)
        }
    }

    @Test
    fun testSetupAndGeneratePinLengthsByMetrics(): Unit = testScope.runTest {
        GenerateLockPasswordViewModel(mContext as Application, testDispatcher, testDispatcher).let { viewModel ->
            val metric = PasswordMetrics(
                /* credType = */ LockPatternUtils.CREDENTIAL_TYPE_PIN,
                /* length = */ 10,
                /* letters = */ 0,
                /* upperCase = */ 0,
                /* lowerCase = */ 0,
                /* numeric = */ 0,
                /* symbols = */ 0,
                /* nonLetter = */ 0,
                /* nonNumeric = */ 0,
                /* seqLength = */ PasswordMetrics.MAX_ALLOWED_SEQUENCE
            )
            viewModel.setup(isAlphabeticalMode = false, metric, defaultMinComplexity)
            advanceUntilIdle()

            val params = requireNotNull(viewModel.genParams.filterNotNull().firstWithTimeoutOrNull())
            assertThat(viewModel.minPasswordComplexity).isEqualTo(PasswordComplexity.MEDIUM)
            require(params is PinGenParams) { "expected PinGenParams, but got $params" }
            assertThat(params.digits).isEqualTo(10)
            assertThat(params.minSize).isEqualTo(10)
            assertThat(params.maxSize).isEqualTo(10)

            advanceAndAssertToViewOptionsStage(viewModel)

            val generatedPins = viewModel.generatedPasswords
                .filterIsInstance<GenerateLockPasswordViewModel.GenerateState.Loaded>()
                .firstWithTimeoutOrNull()
            requireNotNull(generatedPins) {
                "expected generation, but got state ${viewModel.generatedPasswords.value}"
            }

            assertThat(
                generatedPins.list.all { (it as GeneratedPin).pin.length == 10 }
            ).isTrue()
        }
    }

    @Test
    fun testSetupFailureWhenMetricsTooRestrictive() : Unit = testScope.runTest {
        val viewModel = GenerateLockPasswordViewModel(mContext as Application, testDispatcher, testDispatcher)
        val restrictiveMetric = PasswordMetrics(
            /* credType = */ LockPatternUtils.CREDENTIAL_TYPE_PASSWORD,
            /* length = */ 10,
            /* letters = */ 8,
            /* upperCase = */ 2,
            /* lowerCase = */ 0,
            /* numeric = */ 0,
            /* symbols = */ 6,
            /* nonLetter = */ 5,
            /* nonNumeric = */ 0,
            /* seqLength = */ Integer.MAX_VALUE
        )
        viewModel.setup(isAlphabeticalMode = true, restrictiveMetric, defaultMinComplexity)
        viewModel.waitUntilPrimaryBtnEnabled()
        assertThat(viewModel.isPrimaryButtonEnabled.value).isFalse()
        assertThat(viewModel.areMinMetricsRestrictive.firstWithTimeoutOrNull()).isTrue()
    }

    @Test
    fun testSetupIdempotence(): Unit = testScope.runTest {
        GenerateLockPasswordViewModel(mContext as Application, testDispatcher, testDispatcher).let { viewModel ->
            viewModel.setup(isAlphabeticalMode = false, defaultMinMetrics, defaultMinComplexity)
            advanceUntilIdle()
            assertThat(viewModel.passType.filterNotNull().firstWithTimeoutOrNull())
                .isEqualTo(GenerateLockPasswordViewModel.PassType.Pin)
            viewModel.setup(isAlphabeticalMode = true, defaultMinMetrics, defaultMinComplexity)
            advanceUntilIdle()
            assertThat(viewModel.passType.filterNotNull().firstWithTimeoutOrNull())
                .isEqualTo(GenerateLockPasswordViewModel.PassType.Pin)
        }

        GenerateLockPasswordViewModel(mContext as Application, testDispatcher, testDispatcher).let { viewModel ->
            viewModel.setup(isAlphabeticalMode = true, defaultMinMetrics, defaultMinComplexity)
            advanceUntilIdle()
            assertThat(viewModel.passType.filterNotNull().firstWithTimeoutOrNull())
                .isEqualTo(GenerateLockPasswordViewModel.PassType.Passphrase)
            viewModel.setup(isAlphabeticalMode = false, defaultMinMetrics, defaultMinComplexity)
            advanceUntilIdle()
            assertThat(viewModel.passType.filterNotNull().firstWithTimeoutOrNull())
                .isEqualTo(GenerateLockPasswordViewModel.PassType.Passphrase)
        }
    }

    @Test
    fun testTappingQuicklyOnGenerateNewButtonIsLimited(): Unit = testScope.runTest {
        val viewModel = GenerateLockPasswordViewModel(mContext as Application, testDispatcher, testDispatcher)
        viewModel.setup(isAlphabeticalMode = false, defaultMinMetrics, defaultMinComplexity)
        advanceAndAssertToViewOptionsStage(viewModel)

        val generatedPins = viewModel.generatedPasswords.firstWithTimeoutOrNull {
            it is GenerateLockPasswordViewModel.GenerateState.Loaded
        }

        assertThat(viewModel.generationCount).isEqualTo(1)
        repeat(100) {
            launch { viewModel.generateNewPasswords() }
        }
        advanceUntilIdle()

        val generatedPinsAgain = viewModel.generatedPasswords
            .firstWithTimeoutOrNull { it != generatedPins }
        assertThat(viewModel.generationCount).isEqualTo(2)
        assertThat(generatedPinsAgain).isNotNull()
        assertThat(generatedPins).isNotEqualTo(generatedPinsAgain)
        assertThat(viewModel.selectedPassword.value).isNull()
    }

    @Test
    fun testPassphrase() : Unit = testScope.runTest {
        val viewModel = GenerateLockPasswordViewModel(
            mContext as Application,
            testDispatcher,
            testDispatcher
        )
        viewModel.setup(isAlphabeticalMode = true, defaultMinMetrics, defaultMinComplexity)
        advanceAndAssertToViewOptionsStage(viewModel)
        advanceUntilIdle()
        val generatedPassphrases = viewModel.generatedPasswords
            .filterIsInstance<GenerateLockPasswordViewModel.GenerateState.Loaded>()
            .map { it.list }
            .firstWithTimeoutOrNull()

        assertThat(viewModel.generationCount).isEqualTo(1)
        assertThat(generatedPassphrases).isNotNull()
        assertThat(generatedPassphrases!!.size).isGreaterThan(0)
        assertThat(generatedPassphrases.all { it is GeneratedPassphrase }).isTrue()
    }

    @Test
    fun testPassphraseWithOkayMetrics() : Unit = testScope.runTest {
        val viewModel = GenerateLockPasswordViewModel(
            mContext as Application,
            testDispatcher,
            testDispatcher
        )
        // should be okay because spaces are symbols and nonLetter
        val okayMetric = PasswordMetrics(
            /* credType = */ LockPatternUtils.CREDENTIAL_TYPE_PASSWORD,
            /* length = */ 10,
            /* letters = */ 10,
            /* upperCase = */ 0,
            /* lowerCase = */ 0,
            /* numeric = */ 0,
            /* symbols = */ 2,
            /* nonLetter = */ 2,
            /* nonNumeric = */ 2,
            /* seqLength = */ Integer.MAX_VALUE
        )
        viewModel.setup(isAlphabeticalMode = true, okayMetric, defaultMinComplexity)
        advanceUntilIdle()
        viewModel.waitUntilPrimaryBtnEnabled()
        assertThat(viewModel.areMinMetricsRestrictive.firstWithTimeoutOrNull()).isFalse()

        advanceAndAssertToViewOptionsStage(viewModel)
        advanceUntilIdle()

        val generatedPassphrases = viewModel.generatedPasswords
            .filterIsInstance<GenerateLockPasswordViewModel.GenerateState.Loaded>()
            .map { it.list }
            .firstWithTimeoutOrNull()

        assertThat(viewModel.generationCount).isEqualTo(1)
        requireNotNull(generatedPassphrases) {
            "expected generation, but got state ${viewModel.generatedPasswords.value}"
        }
        assertThat(generatedPassphrases.size).isGreaterThan(0)
        assertThat(generatedPassphrases.all { it is GeneratedPassphrase }).isTrue()
    }

    @Test
    fun testFullRun(): Unit = testScope.runTest {
        val viewModel = GenerateLockPasswordViewModel(
            application = mContext as Application,
            backgroundDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
        )
        viewModel.setup(isAlphabeticalMode = false, defaultMinMetrics, defaultMinComplexity)
        advanceAndAssertToViewOptionsStage(viewModel)

        val generatedPins = viewModel.generatedPasswords
            .filterIsInstance<GenerateLockPasswordViewModel.GenerateState.Loaded>()
            .map { it.list }
            .firstWithTimeoutOrNull()

        assertThat(viewModel.generationCount).isEqualTo(1)
        assertThat(generatedPins).isNotNull()
        assertThat(generatedPins!!.size).isGreaterThan(0)
        assertThat(generatedPins.all { it is GeneratedPin }).isTrue()
        delay(50L)
        assertThat(viewModel.isPrimaryButtonEnabled.value).isFalse()
        assertThat(viewModel.selectedPassword.value).isNull()
        val oldFirst = generatedPins[0] as GeneratedPin
        viewModel.setSelectedPassword(0)
        advanceUntilIdle()
        val selection = viewModel.selectedPassword.firstWithTimeoutOrNull { it != null }
        assertThat(selection).isNotNull()
        assertThat(selection!!)
            .isInstanceOf(GenerateLockPasswordViewModel.Selection.IndexOnly::class.java)
        assertThat(selection.index).isEqualTo(0)
        assertThat(viewModel.getPassword(selection)).isEqualTo(oldFirst)

        viewModel.waitUntilPrimaryBtnEnabled()
        assertThat(viewModel.isPrimaryButtonEnabled.value).isTrue()
        viewModel.generateNewPasswords()
        advanceUntilIdle()

        val generatedPinsAgain = viewModel.generatedPasswords
            .filterIsInstance<GenerateLockPasswordViewModel.GenerateState.Loaded>()
            .map { it.list }
            .firstWithTimeoutOrNull { it != generatedPins }
        assertThat(viewModel.generationCount).isEqualTo(2)
        assertThat(generatedPinsAgain).isNotNull()
        assertThat(generatedPins).isNotEqualTo(generatedPinsAgain)
        assertThat(viewModel.selectedPassword.value).isNull()

        viewModel.waitUntilPrimaryBtnEnabled()
        viewModel.primaryButtonClicked()
        advanceUntilIdle()
        assertThat(viewModel.stage.value).isInstanceOf(PassGenStage.ShowMultiple::class.java)
        viewModel.setSelectedPassword(0)
        advanceUntilIdle()

        val actualSelection = viewModel.getPassword(
            requireNotNull(viewModel.selectedPassword.value)
        )
        require(actualSelection is GeneratedPin)

        viewModel.primaryButtonClicked()
        advanceUntilIdle()
        assertThat(viewModel.stage.value).isEqualTo(PassGenStage.Confirmation.ConfirmWithVisible)
        assertThat(viewModel.confirmError.value).isNull()
        assertThat(viewModel.isPrimaryButtonEnabled.value).isFalse()
        val wrongInput = actualSelection.pin + "1"
        viewModel.setInputLength(wrongInput.length)
        advanceUntilIdle()
        assertThat(viewModel.isPrimaryButtonEnabled.value).isTrue()
        viewModel.primaryButtonClicked(wrongInput)
        advanceUntilIdle()
        assertThat(viewModel.stage.value).isEqualTo(PassGenStage.Confirmation.ConfirmWithVisible)
        assertThat(viewModel.confirmError.value).isEqualTo(GenerateLockPasswordViewModel.ConfirmError.DOESNT_MATCH)
        assertThat(viewModel.isPrimaryButtonEnabled.value).isTrue()

        viewModel.setInputLength(actualSelection.pin.length)
        advanceUntilIdle()
        viewModel.primaryButtonClicked(actualSelection.pin)
        advanceUntilIdle()
        assertThat(viewModel.confirmError.value).isNull()
        assertThat(viewModel.stage.value).isEqualTo(PassGenStage.Confirmation.ConfirmWithoutVisible)
        assertThat(viewModel.isPrimaryButtonEnabled.value).isFalse()

        viewModel.setInputLength(actualSelection.pin.length)
        advanceUntilIdle()
        assertThat(viewModel.isPrimaryButtonEnabled.value).isTrue()
        viewModel.primaryButtonClicked(actualSelection.pin)
        advanceUntilIdle()

        assertThat(viewModel.stage.value).isEqualTo(PassGenStage.Confirmation.ConfirmLast)
        assertThat(viewModel.saveRequest.value).isEqualTo(GenerateLockPasswordViewModel.SaveRequest.Inactive)
        assertThat(viewModel.isPrimaryButtonEnabled.value).isFalse()

        viewModel.setInputLength(actualSelection.pin.length)
        advanceUntilIdle()
        assertThat(viewModel.isPrimaryButtonEnabled.value).isTrue()
        viewModel.primaryButtonClicked(actualSelection.pin)
        advanceUntilIdle()
        val request = viewModel.saveRequest.value
        require(request is GenerateLockPasswordViewModel.SaveRequest.Requested) {
            "save not requested"
        }
        assertThat(request.autoPinConfirm).isFalse()
    }
}

private suspend fun GenerateLockPasswordViewModel.waitUntilPrimaryBtnEnabled() {
    isPrimaryButtonEnabled.firstWithTimeoutOrNull(timeMillis = 1000) { it }
}

private suspend fun GenerateLockPasswordViewModel.waitUntilDesiredStage(
    desiredStage: PassGenStage
): PassGenStage? = stage.firstWithTimeoutOrNull(timeMillis = 1000) { it == desiredStage } ?: stage.value

private suspend fun TestScope.advanceAndAssertToViewOptionsStage(
    viewModel: GenerateLockPasswordViewModel
): Unit = with(viewModel) {
    advanceUntilIdle()
    assertThat(viewModel.stage.value).isInstanceOf(PassGenStage.ChooseGeneratedOrManual::class.java)
    waitUntilPrimaryBtnEnabled()
    primaryButtonClicked()
    advanceUntilIdle()
    assertThat(viewModel.stage.value).isInstanceOf(PassGenStage.ChooseParams::class.java)


    waitUntilPrimaryBtnEnabled()
    primaryButtonClicked()
    advanceUntilIdle()
    assertThat(viewModel.stage.value).isInstanceOf(PassGenStage.ShowMultiple::class.java)
    advanceUntilIdle()
}

suspend fun <T> Flow<T>.firstWithTimeoutOrNull(
    timeMillis: Long = 500,
    predicate: suspend (T) -> Boolean
): T? =
    withTimeoutOrNull(timeMillis) {
        first(predicate)
    }

suspend fun <T> Flow<T>.firstWithTimeoutOrNull(timeMillis: Long = 500): T? =
    withTimeoutOrNull(timeMillis) {
        first()
    }
