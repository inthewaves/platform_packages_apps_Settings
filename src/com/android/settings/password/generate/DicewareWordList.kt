package com.android.settings.password.generate

import android.app.admin.PasswordMetrics
import android.content.Context
import androidx.annotation.Keep
import androidx.annotation.OpenForTesting
import com.android.settings.password.generate.DicewareWordList.LoadException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import libcore.util.HexEncoding
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.security.DigestInputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.TreeMap
import kotlin.jvm.Throws
import kotlinx.coroutines.CoroutineDispatcher

// https://www.eff.org/files/2016/07/18/eff_large_wordlist.txt
// remove numbers:
//     sed -r -i 's/^[0-9]{5}\t(.*)$/\1/g' eff_large_wordlist.txt
const val WORDLIST_ASSET_FILENAME = "eff_large_wordlist.txt"
// a digest to verify contents and sanity check any changes
//     sha256sum eff_large_wordlist.txt
private const val WORDLIST_DIGEST = "6d557f0693958fb5e650b68b5bee585eb82cf4da32965505c789e924743bc522"
private const val WORDLIST_NUM_WORDS = 7776

// In-memory storage of wordlist for passphrase generation
class DicewareWordList private constructor(
    private val words: Array<String>
) {
    fun getRandomWord(random: SecureRandom): String {
        // if wordlist size is a power of 2, could just use SecureRandom#next directly
        val index = random.nextInt(WORDLIST_NUM_WORDS)
        return words[index]
    }

    @OpenForTesting
    @Keep
    fun wordList() = words.asList()

    companion object {
        // these values need to be updated if the wordlist is changed, as they will let us
        // access these properties without needing to construct and calculate it from the wordlist
        const val MAX_SEQUENCE_LENGTH = 4
        val WORD_LENGTH_FREQUENCIES by lazy {
            mapOf(
                3 to 82,
                4 to 467,
                5 to 928,
                6 to 1372,
                7 to 1591,
                8 to 1779,
                9 to 1557
            )
        }
        val MAX_WORD_LENGTH by lazy {
            WORD_LENGTH_FREQUENCIES.keys.asSequence().max()
        }

        suspend fun loadWords(
            context: Context,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        ): DicewareWordList {
            return loadWordsInner(
                context.applicationContext.assets.open(WORDLIST_ASSET_FILENAME),
                ioDispatcher
            )
        }

        // DCL is disabled for system apps, so can't use Mockito. Expose a method to allow the
        // InputStream to be chosen
        @OpenForTesting
        @Throws(LoadException::class)
        suspend fun loadWordsInner(
            consumedStream: InputStream,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        ): DicewareWordList = withContext(ioDispatcher) {
            val checkDupesSet = HashSet<String>()
            val frequencies = TreeMap<Int, Int>()
            var maxSequenceLength = 0
            val msgDigest = MessageDigest.getInstance("SHA-256")
            val words: Array<String> = try {
                val fileStream = DigestInputStream(consumedStream, msgDigest)
                BufferedReader(InputStreamReader(fileStream)).use { reader ->
                    Array(WORDLIST_NUM_WORDS) { index ->
                        ensureActive()

                        val line: String = reader.readLine()
                            ?: throw LoadException(
                                "expected $WORDLIST_NUM_WORDS words; actual number $index"
                            )

                        line.trim()
                            .onEach { c ->
                                validate(c.isLetter() || c == '-') {
                                    "found word with non-letters [$line]"
                                }
                            }
                            .also {
                                val frequencyForLength = frequencies.getOrDefault(it.length, 0)
                                frequencies[it.length] = frequencyForLength + 1
                                maxSequenceLength = maxOf(
                                    maxSequenceLength,
                                    PasswordMetrics.maxLengthSequence(it.encodeToByteArray())
                                )
                                checkDupesSet.add(it)
                            }
                    }.also {
                        validate(reader.readLine() == null) { "expected EOF but more text found" }
                    }
                }
            } catch (e: IOException) {
                throw LoadException("failed to load/read words", e)
            }
            val digest = HexEncoding.encodeToString(msgDigest.digest(), false)
            validate(digest == WORDLIST_DIGEST) { "sha256 digest of wordlist mismatch" }
            validate(checkDupesSet.size == WORDLIST_NUM_WORDS) { "duplicate words detected" }
            val updatableErrors = buildList {
                if (frequencies != WORD_LENGTH_FREQUENCIES) {
                    add("word frequencies: expected $WORD_LENGTH_FREQUENCIES, got $frequencies")
                }
                if (maxSequenceLength != MAX_SEQUENCE_LENGTH) {
                    add("maxSequenceLength: expected $MAX_SEQUENCE_LENGTH, got $maxSequenceLength")
                }
            }
            validate(updatableErrors.isEmpty()) { updatableErrors.joinToString(separator = "; ") }

            DicewareWordList(words)
        }
    }

    class LoadException(msg: String, cause: Throwable? = null) : Exception(msg, cause)
}

private inline fun validate(value: Boolean, lazyMessage: () -> Any) {
    if (!value) {
        val message = lazyMessage()
        throw LoadException(message.toString())
    }
}
