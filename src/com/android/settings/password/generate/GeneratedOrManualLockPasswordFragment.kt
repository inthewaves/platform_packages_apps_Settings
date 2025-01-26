package com.android.settings.password.generate

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.preference.Preference
import com.android.settings.R
import com.android.settings.Utils
import com.android.settings.password.ChooseLockPassword
import com.android.settings.password.ChooseLockPassword.ChooseLockPasswordFragment
import com.android.settings.password.ChooseLockSettingsHelper
import com.android.settingslib.widget.FooterPreference
import com.google.android.setupdesign.GlifPreferenceLayout
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.combine

private const val KEY_USE_GENERATED_CREDENTIAL = "use_generated_credential"
private const val KEY_USE_OWN_CREDENTIAL = "use_own_credential"
private const val KEY_FOOTER = "footer_screen_lock_creation_choice"

class GeneratedOrManualLockPasswordFragment : BaseLockPasswordGenerationPreferenceFragment(
    prefResId = R.xml.screen_lock_creation_choice,
    shouldGcOnDestroy = false,
) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val layout = view as GlifPreferenceLayout

        val intent = activity!!.intent
        val footer = findPreference<FooterPreference>(KEY_FOOTER)!!
        val useGenerated = findPreference<Preference>(KEY_USE_GENERATED_CREDENTIAL)!!
        val useOwn = findPreference<Preference>(KEY_USE_OWN_CREDENTIAL)!!

        viewLifecycleOwner.repeatCollectOnLifecycle(viewModel.isPrimaryButtonEnabled) { on ->
            useGenerated.isEnabled = on
        }

        viewLifecycleOwner.repeatCollectOnLifecycle(
            viewModel.passType.filterNotNull()
                .combine(viewModel.areMinMetricsRestrictive) { type, restrict -> type to restrict }
        ) { (passType, minMetricsRestrictive) ->
            layout.apply {
                when (passType) {
                    GenerateLockPasswordViewModel.PassType.Pin -> {
                        icon = activity!!.getDrawable(R.drawable.ic_lock_pin)
                        activity!!.setTitle(R.string.unlock_set_unlock_pin_title)
                        setHeaderText(R.string.unlock_set_unlock_pin_title)
                    }
                    GenerateLockPasswordViewModel.PassType.Passphrase -> {
                        icon = activity!!.getDrawable(R.drawable.ic_password)
                        activity!!.setTitle(R.string.unlock_set_unlock_password_title)
                        setHeaderText(R.string.unlock_set_unlock_password_title)
                    }
                }
            }

            val isAlphaMode = passType == GenerateLockPasswordViewModel.PassType.Passphrase

            val topIntroBuilder = StringBuilder()
            addHintIfNeeded(intent, topIntroBuilder, isAlphaMode)

            if (isAlphaMode) {
                setupPrefIntroAndButtons(
                    topIntroBuilder = topIntroBuilder,
                    footer = footer,
                    useGenerated = useGenerated,
                    useOwn = useOwn,
                    introInfoTextId = R.string.lock_screen_generate_passphrase_info,
                    footerTextId = R.string.lock_screen_generate_choice_footer_password,
                    useGeneratedDrawableId = R.drawable.ic_shuffle,
                    useGeneratedTitleTextId = R.string.lock_screen_choice_generate_pref_passphrase_title,
                    useGeneratedSummaryTextId = R.string.lock_screen_choice_generate_pref_passphrase_summary_d_to_d_words,
                    minMetricsTooRestrictive = minMetricsRestrictive,
                    minSize = DicewarePassphraseGenParams.MIN_WORDS,
                    maxSize = DicewarePassphraseGenParams.MAX_WORDS,
                    useOwnDrawableId = R.drawable.ic_settings_keyboards,
                    useOwnTitleId = R.string.lock_screen_choice_manual_pref_passphrase
                )
            } else {
                setupPrefIntroAndButtons(
                    topIntroBuilder = topIntroBuilder,
                    footer = footer,
                    useGenerated = useGenerated,
                    useOwn = useOwn,
                    introInfoTextId = R.string.lock_screen_generate_pin_info,
                    footerTextId = R.string.lock_screen_generate_choice_footer_pin,
                    useGeneratedDrawableId = R.drawable.ic_shuffle,
                    useGeneratedTitleTextId = R.string.lock_screen_choice_generate_pref_pin_title,
                    useGeneratedSummaryTextId = R.string.lock_screen_choice_generate_pref_pin_summary_d_to_d_digits,
                    minMetricsTooRestrictive = minMetricsRestrictive,
                    minSize = PinGenParams.DEFAULT_MIN_DIGITS,
                    maxSize = PinGenParams.DEFAULT_MAX_DIGITS,
                    useOwnDrawableId = R.drawable.ic_lock_pin,
                    useOwnTitleId = R.string.lock_screen_choice_manual_pref_pin
                )
            }

            layout.descriptionText = topIntroBuilder.toString()

            footer.setLearnMoreText(
                getString(R.string.lock_screen_generate_choice_learn_more_link)
            )
            footer.setLearnMoreAction { _ ->
                SecureElementInfoDialog().show(childFragmentManager, "secure-element-dialog")
            }
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        val key = preference.key

        if (KEY_USE_GENERATED_CREDENTIAL == key) {
            viewModel.primaryButtonClicked()
            return true
        }
        if (KEY_USE_OWN_CREDENTIAL == key) {
            // Launch the original PIN/password input activity
            val intent = ChooseLockPassword.IntentBuilder(context).build()
            // Allow ChooseLockGeneric to get the original extras
            intent.putExtras(activity!!.intent)
            // ChooseLockPassword was the original activity and has its own result codes that it
            // wants to send back to ChooseLockGeneric
            intent.addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT)

            activity!!.startActivity(intent)
            activity!!.finish()
            return true
        }
        return false
    }

    private fun setupPrefIntroAndButtons(
        topIntroBuilder: StringBuilder,
        footer: FooterPreference,
        useGenerated: Preference,
        useOwn: Preference,
        @StringRes introInfoTextId: Int,
        @StringRes footerTextId: Int,
        @DrawableRes useGeneratedDrawableId: Int,
        @StringRes useGeneratedTitleTextId: Int,
        @StringRes useGeneratedSummaryTextId: Int,
        minMetricsTooRestrictive: Boolean,
        minSize: Int,
        maxSize: Int,
        @DrawableRes useOwnDrawableId: Int,
        @StringRes useOwnTitleId: Int,
    ) {
        topIntroBuilder.append(getString(introInfoTextId))
        footer.setTitle(footerTextId)

        useGenerated.setIcon(useGeneratedDrawableId)
        useGenerated.setTitle(useGeneratedTitleTextId)
        useGenerated.summary = if (!minMetricsTooRestrictive) {
            getString(useGeneratedSummaryTextId, minSize, maxSize)
        } else {
            getString(R.string.lock_screen_choice_disabled_due_to_device_policy_summary)
        }

        useOwn.setIcon(useOwnDrawableId)
        useOwn.setTitle(useOwnTitleId)
    }

    private fun addHintIfNeeded(
        intent: Intent,
        topIntroBuilder: StringBuilder,
        isAlphaMode: Boolean
    ) {
        val stageType = if (
            intent.getBooleanExtra(ChooseLockSettingsHelper.EXTRA_KEY_FOR_FINGERPRINT, false)
        ) {
            ChooseLockPasswordFragment.Stage.TYPE_FINGERPRINT
        } else if (
            intent.getBooleanExtra(ChooseLockSettingsHelper.EXTRA_KEY_FOR_FACE, false)
        ) {
            ChooseLockPasswordFragment.Stage.TYPE_FACE
        } else if (
            intent.getBooleanExtra(ChooseLockSettingsHelper.EXTRA_KEY_FOR_BIOMETRICS, false)
        ) {
            ChooseLockPasswordFragment.Stage.TYPE_BIOMETRIC
        } else {
            ChooseLockPasswordFragment.Stage.TYPE_NONE
        }

        val userId = Utils.getUserIdFromBundle(activity, intent.extras)
        val profileType = ChooseLockPasswordFragment.getProfileType(
            context, userId
        )
        val hint = ChooseLockPasswordFragment.Stage.Introduction.getHint(
            context, isAlphaMode, stageType, profileType
        )

        // if not under a context like a profile or setting up biometrics, the hint will be
        // redundant
        val defaultPinHint =
            getString(ChooseLockPasswordFragment.Stage.Introduction.numericHint)
        val defaultPasswordHint =
            getString(ChooseLockPasswordFragment.Stage.Introduction.alphaHint)
        if (defaultPinHint != hint && defaultPasswordHint != hint) {
            topIntroBuilder.append(hint)
            topIntroBuilder.append("\n\n")
        }
    }

    class SecureElementInfoDialog : DialogFragment() {
        override fun show(manager: FragmentManager, tag: String?) {
            if (manager.findFragmentByTag(tag) == null) {
                // Prevent opening multiple dialogs if tapped on button quickly
                super.show(manager, tag)
            }
        }

        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            return AlertDialog.Builder(activity!!)
                .setTitle(R.string.lock_screen_generate_learn_more_dialog_title)
                .setMessage(R.string.lock_screen_generate_learn_more_dialog_body)
                .setPositiveButton(android.R.string.ok) { _, _ -> dismiss() }
                .create()
        }
    }
}
