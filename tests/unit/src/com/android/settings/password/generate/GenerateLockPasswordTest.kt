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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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

            val params = requireNotNull(viewModel.genParams.value)
            assertThat(viewModel.minPasswordComplexity).isEqualTo(PasswordComplexity.MEDIUM)
            assertThat(params.minSize).isEqualTo(PinGenParams.DEFAULT_MIN_DIGITS)
            assertThat(params.maxSize).isEqualTo(PinGenParams.DEFAULT_MAX_DIGITS)
        }

        GenerateLockPasswordViewModel(mContext as Application, testDispatcher, testDispatcher).let { viewModel ->
            viewModel.setup(isAlphabeticalMode = false, defaultMinMetrics, DevicePolicyManager.PASSWORD_COMPLEXITY_MEDIUM)
            advanceUntilIdle()
            val params = requireNotNull(viewModel.genParams.value)
            assertThat(viewModel.minPasswordComplexity).isEqualTo(PasswordComplexity.MEDIUM)
            assertThat(params.minSize).isEqualTo(PinGenParams.DEFAULT_MIN_DIGITS)
            assertThat(params.maxSize).isEqualTo(PinGenParams.DEFAULT_MAX_DIGITS)
        }

        GenerateLockPasswordViewModel(mContext as Application, testDispatcher, testDispatcher).let { viewModel ->
            viewModel.setup(isAlphabeticalMode = false, defaultMinMetrics, DevicePolicyManager.PASSWORD_COMPLEXITY_HIGH)
            advanceUntilIdle()
            val params = requireNotNull(viewModel.genParams.value)
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
            val params = requireNotNull(viewModel.genParams.value)
            assertThat(viewModel.minPasswordComplexity).isEqualTo(PasswordComplexity.MEDIUM)
            require(params is PinGenParams) { "expected PinGenParams, but got $params" }
            assertThat(params.digits).isEqualTo(10)
            assertThat(params.minSize).isEqualTo(10)
            assertThat(params.maxSize).isEqualTo(10)

            advanceAndAssertToViewOptionsStage(viewModel)

            val generatedPins = viewModel.generatedPasswords.value
            require(generatedPins is GenerateLockPasswordViewModel.GenerateState.Loaded) {
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
        advanceUntilIdle()
        assertThat(viewModel.isPrimaryButtonEnabled.value).isFalse()
        assertThat(viewModel.areMinMetricsRestrictive.first()).isTrue()
    }

    @Test
    fun testSetupIdempotence(): Unit = testScope.runTest {
        GenerateLockPasswordViewModel(mContext as Application, testDispatcher, testDispatcher).let { viewModel ->
            viewModel.setup(isAlphabeticalMode = false, defaultMinMetrics, defaultMinComplexity)
            advanceUntilIdle()
            assertThat(viewModel.passType.value)
                .isEqualTo(GenerateLockPasswordViewModel.PassType.Pin)
            viewModel.setup(isAlphabeticalMode = true, defaultMinMetrics, defaultMinComplexity)
            advanceUntilIdle()
            assertThat(viewModel.passType.value)
                .isEqualTo(GenerateLockPasswordViewModel.PassType.Pin)
        }

        GenerateLockPasswordViewModel(mContext as Application, testDispatcher, testDispatcher).let { viewModel ->
            viewModel.setup(isAlphabeticalMode = true, defaultMinMetrics, defaultMinComplexity)
            advanceUntilIdle()
            assertThat(viewModel.passType.value)
                .isEqualTo(GenerateLockPasswordViewModel.PassType.Passphrase)
            viewModel.setup(isAlphabeticalMode = false, defaultMinMetrics, defaultMinComplexity)
            advanceUntilIdle()
            assertThat(viewModel.passType.value)
                .isEqualTo(GenerateLockPasswordViewModel.PassType.Passphrase)
        }
    }

    @Test
    fun testTappingQuicklyOnGenerateNewButtonIsLimited(): Unit = testScope.runTest {
        val viewModel = GenerateLockPasswordViewModel(mContext as Application, testDispatcher, testDispatcher)
        viewModel.setup(isAlphabeticalMode = false, defaultMinMetrics, defaultMinComplexity)
        advanceAndAssertToViewOptionsStage(viewModel)

        val generatedPins = viewModel.generatedPasswords.value
        require(generatedPins is GenerateLockPasswordViewModel.GenerateState.Loaded) {
            "expected generation, but got $generatedPins"
        }

        assertThat(viewModel.generationCount).isEqualTo(1)
        repeat(100) {
            launch { viewModel.generateNewPasswords() }
        }
        advanceUntilIdle()

        val generatedPinsAgain = viewModel.generatedPasswords.value
        assertThat(generatedPinsAgain).isNotEqualTo(generatedPins)
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
        val generatedPassphrases = viewModel.generatedPasswords.value
        require(generatedPassphrases is GenerateLockPasswordViewModel.GenerateState.Loaded)

        assertThat(viewModel.generationCount).isEqualTo(1)
        assertThat(generatedPassphrases.size).isGreaterThan(0)
        assertThat(generatedPassphrases.size).isEqualTo(generatedPassphrases.list.size)
        assertThat(generatedPassphrases.list.all { it is GeneratedPassphrase }).isTrue()
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
        assertThat(viewModel.areMinMetricsRestrictive.first()).isFalse()

        advanceAndAssertToViewOptionsStage(viewModel)
        advanceUntilIdle()

        val generatedPassphrases = viewModel.generatedPasswords.value
        require(generatedPassphrases is GenerateLockPasswordViewModel.GenerateState.Loaded) {
            "expected generation, but got state $generatedPassphrases"
        }

        assertThat(viewModel.generationCount).isEqualTo(1)
        assertThat(generatedPassphrases.size).isGreaterThan(0)
        assertThat(generatedPassphrases.list.all { it is GeneratedPassphrase }).isTrue()
    }

    @Test
    fun testFullRunOfAllTypesAndSize() = testScope.runTest {
        // In JUnit4, parametrized tests aren't easy to run
        val wordList: Set<String> =
            DicewareWordList.loadWords(mContext, testDispatcher).wordList().toSet()

        for (passType in GenerateLockPasswordViewModel.PassType.entries) {
            val (minSize, maxSize) = when (passType) {
                GenerateLockPasswordViewModel.PassType.Pin -> {
                    PinGenParams.DEFAULT_MIN_DIGITS to PinGenParams.DEFAULT_MAX_DIGITS
                }
                GenerateLockPasswordViewModel.PassType.Passphrase -> {
                    DicewarePassphraseGenParams.MIN_WORDS to DicewarePassphraseGenParams.MAX_WORDS
                }
            }

            for (size in minSize..maxSize) {
                try {
                    doFullRun(wordList, passType, size)
                } catch (e: Exception) {
                    throw AssertionError("failed with $passType, size $size", e)
                }
            }
        }
    }

    private fun TestScope.doFullRun(
        wordList: Set<String>,
        passType: GenerateLockPasswordViewModel.PassType,
        passwordSize: Int
    ) {
        val viewModel = GenerateLockPasswordViewModel(
            application = mContext as Application,
            backgroundDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
        )
        val isPassphrase = passType == GenerateLockPasswordViewModel.PassType.Passphrase

        viewModel.setup(isAlphabeticalMode = isPassphrase, defaultMinMetrics, defaultMinComplexity)
        advanceUntilIdle()
        assertThat(viewModel.stage.value).isInstanceOf(PassGenStage.ChooseGeneratedOrManual::class.java)

        viewModel.primaryButtonClicked()
        advanceUntilIdle()
        assertThat(viewModel.stage.value).isInstanceOf(PassGenStage.ChooseParams::class.java)

        viewModel.setNewLength(passwordSize)
        advanceUntilIdle()

        viewModel.primaryButtonClicked()
        advanceUntilIdle()
        assertThat(viewModel.stage.value).isInstanceOf(PassGenStage.ShowMultiple::class.java)

        var previousList: List<GeneratedPassword>? = null

        repeat(50) { iteration ->
            if (previousList != null) {
                viewModel.generateNewPasswords()
                advanceUntilIdle()
            }

            val generated = viewModel.generatedPasswords.value
            require(generated is GenerateLockPasswordViewModel.GenerateState.Loaded) {
                "expected generation, but got state $generated"
            }

            assertThat(viewModel.generationCount).isEqualTo(iteration + 1)
            assertThat(generated).isNotNull()
            if (previousList != null) {
                assertThat(previousList).isNotEqualTo(generated.list)
            }
            previousList = generated.listOrNull()
            assertThat(viewModel.isPrimaryButtonEnabled.value).isFalse()
            assertThat(viewModel.selectedPassword.value).isNull()
            if (isPassphrase) {
                for (genPassphrase in generated.list) {
                    check(genPassphrase is GeneratedPassphrase) { "expected passphrase" }
                    val wordCount = genPassphrase.passphrase
                        .splitToSequence(' ')
                        .count()
                    assertThat(wordCount).isEqualTo(passwordSize)

                    for (word in genPassphrase.passphrase.splitToSequence(' ')) {
                        assertThat(wordList).contains(word)
                    }
                }
            } else {
                assertThat(generated.list.all { it is GeneratedPin }).isTrue()
                assertThat(generated.list.all { (it as GeneratedPin).pin.length == passwordSize }).isTrue()
            }

            for (i in generated.list.indices) {
                val selected = generated.list[i]
                viewModel.setSelectedPassword(i)
                advanceUntilIdle()
                val selection = requireNotNull(viewModel.selectedPassword.value)
                assertThat(selection).isInstanceOf(GenerateLockPasswordViewModel.Selection.IndexOnly::class.java)
                assertThat(selection.index).isEqualTo(i)
                assertThat(viewModel.getPassword(selection)).isEqualTo(selected)
                assertThat(viewModel.isPrimaryButtonEnabled.value).isTrue()
            }
        }

        viewModel.generateNewPasswords()
        advanceUntilIdle()

        val finalGenerated = viewModel.generatedPasswords.value
        require(finalGenerated is GenerateLockPasswordViewModel.GenerateState.Loaded) {
            "expected generation, but got state $finalGenerated"
        }
        assertThat(finalGenerated).isNotNull()
        assertThat(viewModel.selectedPassword.value).isNull()

        viewModel.primaryButtonClicked()
        advanceUntilIdle()
        assertThat(viewModel.stage.value).isInstanceOf(PassGenStage.ShowMultiple::class.java)
        viewModel.setSelectedPassword(0)
        advanceUntilIdle()

        val actualSelection = viewModel.getPassword(
            requireNotNull(viewModel.selectedPassword.value)
        )
        requireNotNull(actualSelection)

        viewModel.primaryButtonClicked()
        advanceUntilIdle()
        assertThat(viewModel.stage.value).isEqualTo(PassGenStage.Confirmation.ConfirmWithVisible)
        assertThat(viewModel.confirmError.value).isNull()
        assertThat(viewModel.isPrimaryButtonEnabled.value).isFalse()
        val actualInput = when (actualSelection) {
            is GeneratedPassphrase -> actualSelection.passphrase
            is GeneratedPin -> actualSelection.pin
            else -> error("unreachable")
        }
        val wrongInput = actualInput + "1"
        viewModel.setInputLength(wrongInput.length)
        advanceUntilIdle()
        assertThat(viewModel.isPrimaryButtonEnabled.value).isTrue()
        viewModel.primaryButtonClicked(wrongInput)
        advanceUntilIdle()
        assertThat(viewModel.stage.value).isEqualTo(PassGenStage.Confirmation.ConfirmWithVisible)
        assertThat(viewModel.confirmError.value).isEqualTo(GenerateLockPasswordViewModel.ConfirmError.DOESNT_MATCH)
        assertThat(viewModel.isPrimaryButtonEnabled.value).isTrue()

        viewModel.setInputLength(actualInput.length)
        advanceUntilIdle()
        viewModel.primaryButtonClicked(actualInput)
        advanceUntilIdle()
        assertThat(viewModel.confirmError.value).isNull()
        assertThat(viewModel.stage.value).isEqualTo(PassGenStage.Confirmation.ConfirmWithoutVisible)
        assertThat(viewModel.isPrimaryButtonEnabled.value).isFalse()

        viewModel.setInputLength(actualInput.length)
        advanceUntilIdle()
        assertThat(viewModel.isPrimaryButtonEnabled.value).isTrue()
        viewModel.primaryButtonClicked(actualInput)
        advanceUntilIdle()

        assertThat(viewModel.stage.value).isEqualTo(PassGenStage.Confirmation.ConfirmLast)
        assertThat(viewModel.saveRequest.value).isEqualTo(GenerateLockPasswordViewModel.SaveRequest.Inactive)
        assertThat(viewModel.isPrimaryButtonEnabled.value).isFalse()

        viewModel.setInputLength(actualInput.length)
        advanceUntilIdle()
        assertThat(viewModel.isPrimaryButtonEnabled.value).isTrue()
        viewModel.primaryButtonClicked(actualInput)
        advanceUntilIdle()
        val request = viewModel.saveRequest.value
        require(request is GenerateLockPasswordViewModel.SaveRequest.Requested) {
            "save not requested"
        }
        assertThat(request.autoPinConfirm).isFalse()
    }
}

private fun TestScope.advanceAndAssertToViewOptionsStage(
    viewModel: GenerateLockPasswordViewModel
): Unit = with(viewModel) {
    advanceUntilIdle()
    assertThat(viewModel.stage.value).isInstanceOf(PassGenStage.ChooseGeneratedOrManual::class.java)
    primaryButtonClicked()
    advanceUntilIdle()
    assertThat(viewModel.stage.value).isInstanceOf(PassGenStage.ChooseParams::class.java)
    primaryButtonClicked()
    advanceUntilIdle()
    assertThat(viewModel.stage.value).isInstanceOf(PassGenStage.ShowMultiple::class.java)
}