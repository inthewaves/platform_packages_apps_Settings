package com.android.settings.network.telephony.carriersettingsoverride

import android.app.settings.SettingsEnums
import android.content.Context
import android.os.Bundle
import android.os.UserManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.android.settingslib.spa.widget.dialog.rememberAlertDialogPresenter
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spa.widget.preference.SwitchPreferenceModel
import com.android.settingslib.spa.widget.preference.TopIntroPreference
import com.android.settingslib.spa.widget.preference.TopIntroPreferenceModel
import com.android.settingslib.spa.widget.scaffold.RegularScaffold
import com.android.settingslib.spa.widget.ui.Category
import com.android.settingslib.spa.widget.ui.SettingsIcon
import com.android.settingslib.spaprivileged.model.enterprise.Restrictions
import com.android.settingslib.spaprivileged.template.preference.RestrictedMainSwitchPreference
import com.android.settingslib.spaprivileged.template.preference.RestrictedPreference

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
                override val labelText: Int? = null
            })

            RestrictedMainSwitchPreference(
                model = object : SwitchPreferenceModel {
                    override val title = stringResource(R.string.carrier_settings_override_main_switch_title)
                    override val summary = {
                        context.getString(R.string.carrier_settings_override_clear_message)
                    }
                    override val changeable = { !isOverrideInProgress }
                    override val checked = { isOverrideActive }
                    override val onCheckedChange: (Boolean) -> Unit = { _ ->
                        viewModel.submitOverrides(clearOverrides = isOverrideActive)
                    }
                },
                restrictions = Restrictions(keys = listOf(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS))
            )

            Category {
                val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
                AnimatedContent(
                    targetState = errorMessage,
                    transitionSpec = {
                        // Make it fade / slide in from top and vice versa
                        (fadeIn(tween(200)) + expandVertically()) togetherWith
                                (fadeOut(tween(150)) + shrinkVertically())
                    },
                    label = "errorPref"
                ) { msg ->
                    if (msg != null) {
                        CompositionLocalProvider(
                            LocalContentColor provides MaterialTheme.colorScheme.error
                        ) {
                            Preference(
                                model = object : PreferenceModel {
                                    override val title =
                                        stringResource(R.string.carrier_settings_override_error_title)
                                    override val summary: () -> String = { msg }
                                    override val icon = @Composable {
                                        SettingsIcon(imageVector = Icons.Outlined.Info)
                                    }
                                }
                            )
                        }
                    }
                }

                viewModel.overrideStates.forEach { flagState: CarrierConfigState ->
                    val selectedIndexForThisFlag by flagState.stateIndex
                    val currentState: ConfigState? = remember(selectedIndexForThisFlag) {
                        selectedIndexForThisFlag?.let { flagState.key.possibleConfigStates[it] }
                    }

                    var hideDialog by remember { mutableStateOf(false) }
                    val alertDialog = rememberAlertDialogPresenter(
                        title = stringResource(flagState.key.titleStringRes),
                        text = {
                            val allStates = flagState.key.possibleConfigStates
                            allStates.forEachIndexed { index, possibleState ->
                                if (!possibleState.isUserSelectable) return@forEachIndexed

                                RadioButtonRow(
                                    text = getSelectionText(context, flagState, possibleState),
                                    selected = index == selectedIndexForThisFlag,
                                    enabled = !isOverrideInProgress && !isOverrideActive,
                                    onSelected = {
                                        flagState.stateIndex.value = index
                                        hideDialog = true
                                    }
                                )
                            }
                        },
                    )
                    LaunchedEffect(hideDialog) {
                        if (hideDialog) {
                            alertDialog.close()
                            hideDialog = false
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
                                if (enabled()) alertDialog.open()
                            }
                        },
                        restrictions = Restrictions(keys = listOf(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS)),
                    )
                }
            }
        }
    }

    fun getRoute(subId: Int): String = "${name}/$subId"
}

private fun getSelectionText(
    context: Context,
    flagState: CarrierConfigState,
    stateToDisplay: ConfigState?,
): String {
    // If state is disabled, show the Default (defaultValue) string
    return when (stateToDisplay) {
        is ConfigState.ActiveState -> {
            context.getString(stateToDisplay.selectionStringRes)
        }
        ConfigState.Inactive, null -> {
            if (flagState.isOverriddenBefore.value) {
                // Show as an overridden summary if the current state is from overridden config
                // e.g. it will show "Force enabled"
                flagState
                    .getConfigStateFromIndex(useIndexOfCurrentConfigValue = true)
                    ?.let { it as? ConfigState.ActiveState }
                    ?.selectionStringRes
                    ?.let(context::getString)
                    ?: context.getString(R.string.carrier_settings_default_unknown)
            } else {
                // Show as a default-value summary if the current state is not from overridden config
                // e.g. it will show "Default (Enabled)"
                val existingValString = flagState
                    .getConfigStateFromIndex(useIndexOfCurrentConfigValue = true)
                    ?.let { it as? ConfigState.ActiveState }
                    ?.existingValueStringRes
                    ?.let(context::getString)
                    ?: context.getString(R.string.carrier_settings_default_unknown)

                context.getString(R.string.carrier_settings_override_default__s, existingValString)
            }
        }
    }
}

@Composable
fun RadioButtonRow(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onSelected: () -> Unit,
) {
    ListItem(
        modifier = modifier.selectable(
            selected = selected,
            onClick = { if (enabled) onSelected() },
            role = Role.RadioButton,
            enabled = enabled,
        ),
        leadingContent = {
            RadioButton(
                selected = selected,
                // The ListItem handles the click events
                onClick = null,
                enabled = enabled,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(text) }
    )
}
