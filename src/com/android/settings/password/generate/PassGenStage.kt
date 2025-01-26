package com.android.settings.password.generate

sealed class PassGenStage(val fragmentTag: String) {
    abstract val isBackwards: Boolean
    data class ChooseGeneratedOrManual(override val isBackwards: Boolean) : PassGenStage("choose-gen-or-manual")
    data class ChooseParams(override val isBackwards: Boolean) : PassGenStage("choose-params")
    data class ShowMultiple(override val isBackwards: Boolean) : PassGenStage("show-multiple")
    sealed class Confirmation(val stageNumber: Int) : PassGenStage("confirm") {
        data object ConfirmWithVisible : Confirmation(1)
        data object ConfirmWithoutVisible : Confirmation(2)
        data object ConfirmLast : Confirmation(3)

        override val isBackwards: Boolean = false

        companion object {
            fun fromStageNumber(stageNumber: Int): Confirmation? = when (stageNumber) {
                1 -> ConfirmWithVisible
                2 -> ConfirmWithoutVisible
                3 -> ConfirmLast
                else -> null
            }
        }
    }
    data object Quit : PassGenStage("exit") {
        override val isBackwards: Boolean = true
    }
}
