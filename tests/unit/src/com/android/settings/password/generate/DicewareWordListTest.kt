package com.android.settings.password.generate

import android.app.admin.DevicePolicyManager
import android.app.admin.PasswordMetrics
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// atest -c SettingsUnitTests:DicewareWordListTest
@RunWith(AndroidJUnit4::class)
class DicewareWordListTest {
    lateinit var mContext: Context

    @Before
    fun setUp() {
        mContext = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testBadWordList() {
        val badStream = """
                abc
                these
                words
                are
                bad
            """.trimIndent().toByteArray().inputStream()


        runBlocking {
            try {
                DicewareWordList.loadWordsInner(badStream, Dispatchers.IO)
                throw IllegalStateException("expected wordlist construction failure")
            } catch (_: DicewareWordList.LoadException) {}
        }
    }

    @Test
    fun testWordList(): Unit = runBlocking {
        val random = SecureRandom(byteArrayOf(1,2,3))

        val wordlist = DicewareWordList.loadWords(mContext, Dispatchers.IO)

        val word = wordlist.getRandomWord(random)

        val maxWords = DicewarePassphraseGenParams.MAX_WORDS
        val numSpaces = maxWords - 1
        val maxPassphraseLength = DicewareWordList.MAX_WORD_LENGTH * maxWords + numSpaces
        assertThat(maxPassphraseLength).isAtMost(DevicePolicyManager.MAX_PASSWORD_LENGTH)

        val longWords = wordlist.wordList()
            .filter {
                PasswordMetrics.maxLengthSequence(it.encodeToByteArray()) > PasswordMetrics.MAX_ALLOWED_SEQUENCE
            }
        assertThat(longWords).isEqualTo(listOf("overstuff"))
    }
}
