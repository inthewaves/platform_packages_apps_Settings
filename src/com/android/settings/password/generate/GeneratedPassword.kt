package com.android.settings.password.generate

import com.android.internal.widget.LockscreenCredential
import java.security.SecureRandom

// Generated passwords are stored as a String, since they have to be shown to the user in the UI
// as options
sealed class GeneratedPassword {
    abstract fun toLockscreenCredential(): LockscreenCredential
}

class GeneratedPin(val pin: String) : GeneratedPassword() {
    override fun toLockscreenCredential(): LockscreenCredential {
        return LockscreenCredential.createPin(pin)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as GeneratedPin
        return areStringsEqualConstantTime(pin, other.pin)
    }

    companion object {
        fun generate(random: SecureRandom, length: Int): GeneratedPin {
            require(length > 0) { "invalid length $length" }
            // Although we could use random.nextInt(10^length) and do formatted .toString() on that,
            // the bound parameter for the nextInt method is only an integer, so lengths longer than
            // floor(log10(Integer.MAX_VALUE)) = 9 digits would overflow. This is not an issue for
            // 6-8 digit pins
            val digits = CharArray(length) { '0' + random.nextInt(10) }
            return GeneratedPin(String(digits))
        }
    }
}

class GeneratedPassphrase(val passphrase: String) : GeneratedPassword() {
    override fun toLockscreenCredential(): LockscreenCredential {
        return LockscreenCredential.createPassword(passphrase)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as GeneratedPassphrase
        return areStringsEqualConstantTime(passphrase, other.passphrase)
    }

    companion object {
        fun generate(
            random: SecureRandom,
            dicewareWordList: DicewareWordList,
            numWords: Int
        ): GeneratedPassphrase {
            require(numWords > 0) { "invalid numWords $numWords" }
            val passphrase = generateSequence { dicewareWordList.getRandomWord(random) }
                .take(numWords)
                .joinToString(separator = " ")
            return GeneratedPassphrase(passphrase)
        }

        fun calculateMaxPossibleStringLength(numWords: Int, allowResampling: Boolean = true): Int {
            require(numWords >= 0) { "invalid numWords $numWords" }
            if (numWords == 0) return 0

            val numSpaces = numWords - 1
            if (allowResampling) {
                return DicewareWordList.MAX_WORD_LENGTH * numWords + numSpaces
            }

            var currentLettersCount = 0
            var wordsRemaining = numWords
            for (currentWordLength in DicewareWordList.MAX_WORD_LENGTH downTo 0) {
                val availableWordsForLength = DicewareWordList.WORD_LENGTH_FREQUENCIES.getOrDefault(currentWordLength, 0)
                if (wordsRemaining <= availableWordsForLength) {
                    currentLettersCount += wordsRemaining * currentWordLength
                    break
                } else {
                    wordsRemaining -= availableWordsForLength
                    currentLettersCount += availableWordsForLength * currentWordLength
                }
            }
            return currentLettersCount + numSpaces
        }
    }
}

// Code from libcore/ojluni/src/main/java/java/security/MessageDigest.java#isEqual, adapted to
// Strings to avoid having to create byte array copies. Maybe this isn't necessary, since
// LockscreenCredential just uses Array.equals, and there might be TextView code using normal
// string equality anyway, since these will be in the UI
//
// Original impl note: All bytes in {@code digesta} are examined to determine equality.
// The calculation time depends only on the length of {@code digesta}.
// It does not depend on the length of {@code digestb} or the contents
// of {@code digesta} and {@code digestb}.
fun areStringsEqualConstantTime(digesta: String?, digestb: String?): Boolean {
    if (digesta === digestb) return true
    if (digesta == null || digestb == null) return false

    val lenA = digesta.length
    val lenB = digestb.length

    if (lenB == 0) {
        return lenA == 0
    }

    var result = 0
    result = result or lenA - lenB

    // time-constant comparison
    for (i in 0 until lenA) {
        // If i >= lenB, indexB is 0; otherwise, i.
        val indexB = ((i - lenB) ushr 31) * i
        result = result or (digesta[i].code xor digestb[indexB].code)
    }
    return result == 0
}
