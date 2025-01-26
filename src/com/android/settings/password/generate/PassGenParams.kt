package com.android.settings.password.generate

sealed class PassGenParams {
    abstract val minSize: Int
    abstract val maxSize: Int
    abstract val numberToGenerate: Int
}

data class PinGenParams(
    val digits: Int,
    override val numberToGenerate: Int,
    override val minSize: Int,
    override val maxSize: Int,
    val autoPinConfirm: Boolean = false,
): PassGenParams() {
    companion object {
        const val DEFAULT_MIN_DIGITS = 6
        const val DEFAULT_MAX_DIGITS = 8
    }
}

data class DicewarePassphraseGenParams(
    val words: Int,
    override val numberToGenerate: Int,
    override val minSize: Int,
    override val maxSize: Int,
): PassGenParams() {

    val minWords: Int get() = minSize

    companion object {
        const val MIN_WORDS = 4
        const val MAX_WORDS = 8
    }
}
