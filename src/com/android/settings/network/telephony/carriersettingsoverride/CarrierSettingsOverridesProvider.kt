package com.android.settings.network.telephony.carriersettingsoverride

import android.app.settings.SettingsEnums
import android.content.Context
import android.os.Bundle
import android.os.UserManager
import android.util.ArrayMap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.android.settings.R
import com.android.settings.spa.network.CollectAirplaneModeAndFinishIfOn
import com.android.settingslib.spa.framework.common.SettingsPageProvider
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.widget.dialog.SettingsDialog
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spa.widget.preference.SwitchPreferenceModel
import com.android.settingslib.spa.widget.preference.TopIntroPreference
import com.android.settingslib.spa.widget.preference.TopIntroPreferenceModel
import com.android.settingslib.spa.widget.scaffold.RegularScaffold
import com.android.settingslib.spa.widget.ui.Category
import com.android.settingslib.spa.widget.ui.Footer
import com.android.settingslib.spa.widget.ui.SettingsBody
import com.android.settingslib.spa.widget.ui.SettingsDialogItem
import com.android.settingslib.spa.widget.ui.SettingsIcon
import com.android.settingslib.spaprivileged.model.enterprise.Restrictions
import com.android.settingslib.spaprivileged.template.preference.RestrictedMainSwitchPreference
import com.android.settingslib.spaprivileged.template.preference.RestrictedPreference
import com.android.settings.network.telephony.carriersettingsoverride.CarrierSettingsOverridesViewModel.MessageType

private const val SUB_ID_FOR_OVERRIDE = "subId"

object CarrierSettingsOverridesProvider : SettingsPageProvider {
    override val name = "CarrierSettingsOverridesProvider"
    override val metricsCategory = SettingsEnums.MOBILE_NETWORK

    override val parameter = listOf(
        navArgument(SUB_ID_FOR_OVERRIDE) { type = NavType.IntType },
    )

    @Composable
    override fun Page(arguments: Bundle?) {
        val subId = arguments!!.getInt(com.android.settings.network.apn.SUB_ID)
        val context = LocalContext.current
        val viewModel = viewModel<CarrierSettingsOverridesViewModel>()
        LaunchedEffect(subId) {
            viewModel.init(subId)
        }

        val isOverrideInProgress by viewModel.isOverrideInProgress.collectAsStateWithLifecycle()
        val isOverrideActive by viewModel.isAnOverrideActive.collectAsStateWithLifecycle()

        CollectAirplaneModeAndFinishIfOn()

        RegularScaffold(title = stringResource(R.string.carrier_settings_override_gos_title)) {
            TopIntroPreference(model = object : TopIntroPreferenceModel {
                override val text = stringResource(R.string.carrier_settings_override_intro_text)
                override val expandText = stringResource(R.string.carrier_settings_override_intro_text_expand)
                override val collapseText = stringResource(R.string.carrier_settings_override_intro_text_collapse)
                override val alwaysExpand = true
                override val labelText: Int? = null
            })

            RestrictedMainSwitchPreference(
                model = object : SwitchPreferenceModel {
                    override val title = stringResource(R.string.carrier_settings_override_main_switch_title)
                    override val changeable = { !isOverrideInProgress }
                    override val checked = { isOverrideActive }
                    override val onCheckedChange: (Boolean) -> Unit = { _ ->
                        viewModel.submitOverrides(clearOverrides = isOverrideActive)
                    }
                },
                restrictions = Restrictions(keys = listOf(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS))
            )

            Category {
                val message by viewModel.message.collectAsStateWithLifecycle(null)
                AnimatedContent(
                    targetState = message,
                    transitionSpec = {
                        // Make it fade / slide in from top and vice versa
                        (fadeIn(tween(200)) + expandVertically()) togetherWith
                                (fadeOut(tween(150)) + shrinkVertically())
                    },
                    label = "errorPref"
                ) { msg ->
                    when (msg) {
                        is MessageType.ErrorMessage -> {
                            CompositionLocalProvider(
                                LocalContentColor provides MaterialTheme.colorScheme.error
                            ) {
                                Preference(
                                    model = object : PreferenceModel {
                                        override val title = msg.title
                                            ?: stringResource(
                                                R.string.carrier_settings_override_error_title
                                            )
                                        override val summary: () -> String = { msg.msg }
                                        override val icon = @Composable {
                                            SettingsIcon(imageVector = Icons.Outlined.Info)
                                        }
                                    }
                                )
                            }
                        }
                        MessageType.TurnOffToEdit -> {
                            Preference(
                                model = object : PreferenceModel {
                                    override val title = stringResource(
                                        R.string.carrier_settings_override_active_title
                                    )
                                    override val summary: () -> String = {
                                        context.getString(
                                            R.string.carrier_settings_override_active_summary
                                        )
                                    }
                                    override val icon = @Composable {
                                        SettingsIcon(imageVector = Icons.Outlined.Info)
                                    }
                                }
                            )
                        }
                        null -> {}
                    }
                }

                viewModel.overrideStates.forEach { flagState: CarrierConfigState ->
                    CarrierSettingOverrideOptionPreference(
                        flagState,
                        context,
                        isOverrideInProgress,
                        isOverrideActive
                    )
                }
            }

            val unrecognizedOverrides by viewModel.unrecognizedOverrides
                .collectAsStateWithLifecycle()
            if (!unrecognizedOverrides.isNullOrEmpty()) {
                Category(stringResource(R.string.carrier_settings_override_unrecognized_category_title)) {
                    CompositionLocalProvider(
                        LocalContentColor provides MaterialTheme.colorScheme.error
                    ) {
                        Preference(
                            model = object : PreferenceModel {
                                override val title = stringResource(
                                    R.string.carrier_settings_override_unrecognized_warning_title
                                )
                                override val summary: () -> String = {
                                    context.getString(
                                        R.string.carrier_settings_override_unrecognized_warning_summary
                                    )
                                }
                                override val icon = @Composable {
                                    SettingsIcon(imageVector = Icons.Outlined.Warning)
                                }
                            }
                        )
                    }

                    unrecognizedOverrides?.forEachInline { key, value ->
                        Preference(
                            model = object : PreferenceModel {
                                override val title = key
                                override val summary: () -> String = {
                                    if (value is Array<*>) {
                                        value.asList().toString()
                                    } else {
                                        value.toString()
                                    }
                                }
                                override val icon = @Composable {
                                    SettingsIcon(imageVector = Icons.Outlined.Warning)
                                }
                            }
                        )
                    }
                }
            }

            Footer(stringResource(R.string.carrier_settings_override_footer))
        }
    }

    fun getRoute(subId: Int): String = "${name}/$subId"
}

private inline fun <K, V> ArrayMap<K, V>.forEachInline(action: (K, V) -> Unit) {
    for (index in 0 until size) {
        action(keyAt(index), valueAt(index))
    }
}

@Composable
private fun CarrierSettingOverrideOptionPreference(
    flagState: CarrierConfigState,
    context: Context,
    isOverrideInProgress: Boolean,
    isOverrideActive: Boolean
) {
    val selectedIndexForThisFlag by flagState.stateIndex
    val currentState: ConfigState? = remember(selectedIndexForThisFlag) {
        selectedIndexForThisFlag?.let { flagState.key.possibleConfigStates[it] }
    }

    var dialogOpened by rememberSaveable { mutableStateOf(false) }
    if (dialogOpened) {
        SettingsDialog(
            title = stringResource(flagState.key.titleStringRes),
            onDismissRequest = { dialogOpened = false },
        ) {
            Column(
                modifier = Modifier.selectableGroup().verticalScroll(
                    rememberScrollState()
                )
            ) {
                val allStates = flagState.key.possibleConfigStates
                allStates.forEachIndexed { index, possibleState ->
                    if (!possibleState.isUserSelectable) return@forEachIndexed
                    Radio(
                        text = getSelectionText(context, flagState, possibleState),
                        selected = index == selectedIndexForThisFlag,
                        enabled = !isOverrideInProgress && !isOverrideActive,
                        onSelected = {
                            flagState.stateIndex.value = index
                            dialogOpened = false
                        }
                    )
                }
            }
        }
    }

    RestrictedPreference(
        model = object : PreferenceModel {
            override val title = context.getString(flagState.key.titleStringRes)
            override val summary = {
                getSelectionText(context, flagState, currentState)
            }
            override val icon = null
            override val enabled = { !isOverrideInProgress && !isOverrideActive }
            override val onClick = {
                if (enabled()) dialogOpened = true
            }
        },
        restrictions = Restrictions(keys = listOf(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS)),
    )
}

private fun getSelectionText(
    context: Context,
    flagState: CarrierConfigState,
    stateToDisplay: ConfigState?,
): String {
    return when (stateToDisplay) {
        is ConfigState.ActiveState -> {
            context.getString(stateToDisplay.selectionStringRes)
        }
        ConfigState.Inactive, null -> {
            val active = flagState.getConfigStateFromIndex(useIndexOfCurrentConfigValue = true)
                as? ConfigState.ActiveState
            if (flagState.isOverriddenBefore.value) {
                // Show as an overridden summary if the current state is from overridden config
                // e.g. it will show "Force enabled"
                active
                    ?.selectionStringRes
                    ?.let(context::getString)
                    ?: context.getString(R.string.carrier_settings_default_unknown)
            } else {
                // Show as a default-value summary if the current state is not from overridden config
                // e.g. it will show "Default (Enabled)"
                val existingValString = active
                    ?.existingValueStringRes
                    ?.let(context::getString)
                    ?: context.getString(R.string.carrier_settings_default_unknown)

                context.getString(R.string.carrier_settings_override_default__s, existingValString)
            }
        }
    }
}

/**
 * Adapted from com.android.settingslib.spa.widget.preference.ListPreference
 */
@Composable
private fun Radio(
    text: String,
    summary: String? = null,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onSelected: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = enabled,
                onClick = { onSelected() },
                role = Role.RadioButton,
            )
            .padding(SettingsDimension.dialogItemPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(modifier = Modifier.width(SettingsDimension.itemPaddingEnd))
        Column {
            SettingsDialogItem(text = text, enabled = enabled)
            if (summary?.isNotEmpty() == true) {
                SettingsBody(
                    body = summary,
                    maxLines = 2
                )
            }
        }
    }
}
