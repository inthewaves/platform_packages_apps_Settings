package com.android.settings.password.generate

import android.app.admin.DevicePolicyManager
import android.app.admin.PasswordMetrics
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.internal.widget.LockPatternUtils
import com.android.internal.widget.LockscreenCredential
import com.android.internal.widget.PasswordValidationError
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PasswordComplexityTest {

    private lateinit var mContext: Context

    private val passwordMetric = PasswordMetrics(LockPatternUtils.CREDENTIAL_TYPE_PASSWORD)
    private val pinMetric = PasswordMetrics(LockPatternUtils.CREDENTIAL_TYPE_PIN)

    @Before
    fun setUp() {
        mContext = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testPasswordOrdering() {
        assertThat(PasswordComplexity.MEDIUM).isLessThan(PasswordComplexity.HIGH)
    }

    @Test
    fun testFromLevel() {
        assertThat(
            PasswordComplexity.fromLevel(
                DevicePolicyManager.PASSWORD_COMPLEXITY_NONE,
                minLevel = PasswordComplexity.HIGH
            )
        ).isEqualTo(PasswordComplexity.HIGH)
        assertThat(
            PasswordComplexity.fromLevel(
                DevicePolicyManager.PASSWORD_COMPLEXITY_LOW,
                minLevel = PasswordComplexity.HIGH
            )
        ).isEqualTo(PasswordComplexity.HIGH)
        assertThat(
            PasswordComplexity.fromLevel(
                DevicePolicyManager.PASSWORD_COMPLEXITY_MEDIUM,
                minLevel = PasswordComplexity.HIGH
            )
        ).isEqualTo(PasswordComplexity.HIGH)
        assertThat(
            PasswordComplexity.fromLevel(
                DevicePolicyManager.PASSWORD_COMPLEXITY_HIGH,
                minLevel = PasswordComplexity.HIGH
            )
        ).isEqualTo(PasswordComplexity.HIGH)
        assertThat(
            PasswordComplexity.fromLevel(
                DevicePolicyManager.PASSWORD_COMPLEXITY_HIGH,
                minLevel = PasswordComplexity.MEDIUM
            )
        ).isEqualTo(PasswordComplexity.HIGH)

        assertThat(
            PasswordComplexity.fromLevel(
                DevicePolicyManager.PASSWORD_COMPLEXITY_MEDIUM,
                minLevel = PasswordComplexity.MEDIUM
            )
        ).isEqualTo(PasswordComplexity.MEDIUM)
        assertThat(
            PasswordComplexity.fromLevel(
                DevicePolicyManager.PASSWORD_COMPLEXITY_LOW,
                minLevel = PasswordComplexity.MEDIUM
            )
        ).isEqualTo(PasswordComplexity.MEDIUM)
        assertThat(
            PasswordComplexity.fromLevel(
                DevicePolicyManager.PASSWORD_COMPLEXITY_NONE,
                minLevel = PasswordComplexity.NONE
            )
        ).isEqualTo(PasswordComplexity.NONE)
    }
    
    enum class ExpectError {
        PASS, TOO_SHORT, CONTAINS_SEQUENCE
    }

    private fun validatePassword(
        minMetrics: PasswordMetrics,
        strength: PasswordComplexity,
        password: LockscreenCredential,
        expectError: ExpectError,
    ) {
        PasswordMetrics.validateCredential(minMetrics, strength.complexityValue, password)
            .let { errors ->
                when (expectError) {
                    ExpectError.TOO_SHORT -> {
                        val error = PasswordValidationError(
                            PasswordValidationError.TOO_SHORT,
                            if (password.isPin) strength.pinLength else strength.alphaNumericLength
                        )
                        // PasswordValidationError doesn't have equals method and not a data class
                        assertThat(errors.map { it.toString() }).isEqualTo(listOf(error.toString()))
                    }
                    ExpectError.CONTAINS_SEQUENCE -> {
                        val error = PasswordValidationError(
                            PasswordValidationError.CONTAINS_SEQUENCE,
                            0
                        )
                        assertThat(errors.map { it.toString() }).isEqualTo(listOf(error.toString()))
                    }
                    ExpectError.PASS -> assertThat(errors).isEmpty()
                }
            }
    }

    @Test
    fun testPinLengths() {
        val tooShort = LockscreenCredential.createPin("163")
        assertThat(tooShort.size()).isEqualTo(3)
        assertThat(tooShort.size()).isLessThan(PasswordComplexity.MEDIUM.pinLength)
        assertThat(tooShort.size()).isLessThan(PasswordComplexity.HIGH.pinLength)
        validatePassword(pinMetric, PasswordComplexity.MEDIUM, tooShort, ExpectError.TOO_SHORT)
        validatePassword(pinMetric, PasswordComplexity.HIGH, tooShort, ExpectError.TOO_SHORT)

        val short = LockscreenCredential.createPin("1631")
        assertThat(short.size()).isEqualTo(4)
        assertThat(short.size()).isEqualTo(PasswordComplexity.MEDIUM.pinLength)
        assertThat(short.size()).isLessThan(PasswordComplexity.HIGH.pinLength)
        validatePassword(pinMetric, PasswordComplexity.MEDIUM, short, ExpectError.PASS)
        validatePassword(pinMetric, PasswordComplexity.HIGH, short, ExpectError.TOO_SHORT)

        val almostLong = LockscreenCredential.createPin("1631163")
        assertThat(almostLong.size()).isGreaterThan(PasswordComplexity.MEDIUM.pinLength)
        assertThat(almostLong.size()).isLessThan(PasswordComplexity.HIGH.pinLength)
        validatePassword(pinMetric, PasswordComplexity.MEDIUM, almostLong, ExpectError.PASS)
        validatePassword(pinMetric, PasswordComplexity.HIGH, almostLong, ExpectError.TOO_SHORT)

        val long = LockscreenCredential.createPin("16311631")
        assertThat(long.size()).isGreaterThan(PasswordComplexity.MEDIUM.pinLength)
        assertThat(long.size()).isEqualTo(PasswordComplexity.HIGH.pinLength)
        validatePassword(pinMetric, PasswordComplexity.MEDIUM, long, ExpectError.PASS)
        validatePassword(pinMetric, PasswordComplexity.HIGH, long, ExpectError.PASS)
    }

    @Test
    fun testPasswordLengths() {
        val tooShort = LockscreenCredential.createPassword("and")
        assertThat(tooShort.size()).isEqualTo(3)
        assertThat(tooShort.size()).isLessThan(PasswordComplexity.MEDIUM.alphaNumericLength)
        assertThat(tooShort.size()).isLessThan(PasswordComplexity.HIGH.alphaNumericLength)
        validatePassword(passwordMetric, PasswordComplexity.MEDIUM, tooShort, ExpectError.TOO_SHORT)
        validatePassword(passwordMetric, PasswordComplexity.HIGH, tooShort, ExpectError.TOO_SHORT)

        val short = LockscreenCredential.createPassword("andr")
        assertThat(short.size()).isEqualTo(4)
        assertThat(short.size()).isEqualTo(PasswordComplexity.MEDIUM.alphaNumericLength)
        assertThat(short.size()).isLessThan(PasswordComplexity.HIGH.alphaNumericLength)
        validatePassword(passwordMetric, PasswordComplexity.MEDIUM, short, ExpectError.PASS)
        validatePassword(passwordMetric, PasswordComplexity.HIGH, short, ExpectError.TOO_SHORT)

        val almostLong = LockscreenCredential.createPassword("andro")
        assertThat(almostLong.size()).isGreaterThan(PasswordComplexity.MEDIUM.alphaNumericLength)
        assertThat(almostLong.size()).isLessThan(PasswordComplexity.HIGH.alphaNumericLength)
        validatePassword(passwordMetric, PasswordComplexity.MEDIUM, almostLong, ExpectError.PASS)
        validatePassword(passwordMetric, PasswordComplexity.HIGH, almostLong, ExpectError.TOO_SHORT)

        val long = LockscreenCredential.createPassword("androi")
        assertThat(long.size()).isGreaterThan(PasswordComplexity.MEDIUM.alphaNumericLength)
        assertThat(long.size()).isEqualTo(PasswordComplexity.HIGH.alphaNumericLength)
        validatePassword(passwordMetric, PasswordComplexity.MEDIUM, long, ExpectError.PASS)
        validatePassword(passwordMetric, PasswordComplexity.HIGH, long, ExpectError.PASS)

        val longer = LockscreenCredential.createPassword("android_")
        assertThat(longer.size()).isGreaterThan(PasswordComplexity.MEDIUM.alphaNumericLength)
        assertThat(longer.size()).isGreaterThan(PasswordComplexity.HIGH.alphaNumericLength)
        validatePassword(passwordMetric, PasswordComplexity.MEDIUM, longer, ExpectError.PASS)
        validatePassword(passwordMetric, PasswordComplexity.HIGH, longer, ExpectError.PASS)
    }

    @Test
    fun testPasswordSequence() {
        val hasSequence = LockscreenCredential.createPassword("abcdefg")
        validatePassword(passwordMetric, PasswordComplexity.LOW, hasSequence, ExpectError.PASS)
        validatePassword(passwordMetric, PasswordComplexity.MEDIUM, hasSequence, ExpectError.CONTAINS_SEQUENCE)
        validatePassword(passwordMetric, PasswordComplexity.HIGH, hasSequence, ExpectError.CONTAINS_SEQUENCE)
    }

    @Test
    fun testLongSequence() {
        val cred = LockscreenCredential.createPassword("overstuff")
        val metric = PasswordMetrics.computeForCredential(cred)
        assertThat(metric.seqLength).isEqualTo(4)
    }
}
