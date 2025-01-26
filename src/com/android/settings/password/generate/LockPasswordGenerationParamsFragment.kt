package com.android.settings.password.generate

import com.android.settings.R
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import com.android.settings.widget.LabeledSeekBarPreference
import com.android.settingslib.widget.FooterPreference
import com.google.android.setupcompat.template.FooterBarMixin
import com.google.android.setupcompat.template.FooterButton
import com.google.android.setupdesign.GlifPreferenceLayout
import kotlinx.coroutines.flow.filterNotNull

private const val PREF_GEN_LENGTH_KEY = "generation_length"
private const val PREF_WARNING_FOOTER_KEY = "warning_footer"

class LockPasswordGenerationParamsFragment : BaseLockPasswordGenerationPreferenceFragment(
    prefResId = R.xml.screen_lock_generation_params,
    shouldGcOnDestroy = false
) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val glifLayout = view as GlifPreferenceLayout

        val onNextButtonClick = View.OnClickListener { viewModel.primaryButtonClicked() }
        val footerBarMixin = glifLayout.getMixin(FooterBarMixin::class.java)
        footerBarMixin.primaryButton =
            FooterButton.Builder(requireContext())
                .setText(R.string.lock_screen_generate_options_next_button)
                .setListener(onNextButtonClick)
                .setButtonType(FooterButton.ButtonType.NEXT)
                .setTheme(com.google.android.setupdesign.R.style.SudGlifButton_Primary)
                .build()

        viewLifecycleOwner.repeatCollectOnLifecycle(
            viewModel.passType.filterNotNull()
        ) { passType ->
            glifLayout.apply {
                when (passType) {
                    GenerateLockPasswordViewModel.PassType.Pin -> {
                        icon = activity!!.getDrawable(R.drawable.ic_lock_pin)
                        setHeaderText(R.string.lock_screen_generate_options_title_pin)
                        setDescriptionText(R.string.lock_screen_generate_options_desc_pin)
                    }

                    GenerateLockPasswordViewModel.PassType.Passphrase -> {
                        icon = activity!!.getDrawable(R.drawable.ic_password)
                        setHeaderText(R.string.lock_screen_generate_options_title_passphrase)
                        setDescriptionText(R.string.lock_screen_generate_options_desc_passphrase)
                    }
                }
            }
        }

        val footer = findPreference<FooterPreference>(PREF_WARNING_FOOTER_KEY)!!
        viewLifecycleOwner.repeatCollectOnLifecycle(
            viewModel.passphraseMaxWordsEntropyWarning
        ) { warning ->
            if (warning != null) {
                footer.title = getString(
                    R.string.lock_screen_generate_options_warning_footer_lose_entropy_d_to_d,
                    warning.fullNumberOfWords, warning.maxPasswordLength
                )
                footer.isVisible = true
            } else {
                footer.isVisible = false
            }
        }

        val seekbar = findPreference<LabeledSeekBarPreference>(PREF_GEN_LENGTH_KEY)!!
        seekbar.setContinuousUpdates(true)
        seekbar.setTriggerUserChangeOnIconPress(true)
        seekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    viewModel.setNewLength(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        viewLifecycleOwner.repeatCollectOnLifecycle(
            viewModel.genParams.filterNotNull()
        ) { genOpts ->
            seekbar.apply {
                // Post to the recycler view to avoid IllegalStateException: Cannot call this
                // method while RecyclerView is computing a layout or scrolling. Might not be
                // needed now that we check fromUser in the OnSeekBarChangeListener
                glifLayout.recyclerView.post {
                    min = genOpts.minSize
                    max = genOpts.maxSize
                    when (genOpts) {
                        is DicewarePassphraseGenParams -> {
                            progress = genOpts.words
                            title = getString(R.string.lock_screen_generate_options_length_slider_title_passphrase)
                            summary = getString(
                                R.string.lock_screen_generate_options_length_slider_summary_passphrase,
                                genOpts.words
                            )
                        }
                        is PinGenParams -> {
                            progress = genOpts.digits
                            title = getString(R.string.lock_screen_generate_options_length_slider_title_pin)
                            summary = getString(
                                R.string.lock_screen_generate_options_length_slider_summary_pin,
                                genOpts.digits
                            )
                        }
                    }
                }
            }
        }
    }
}
