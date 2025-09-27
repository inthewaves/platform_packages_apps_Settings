package com.android.settings.network.telephony

import android.content.Context
import android.telephony.SubscriptionManager
import androidx.lifecycle.LifecycleOwner
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import com.android.settings.R
import com.android.settings.core.BasePreferenceController
import com.android.settings.network.telephony.MobileNetworkSettingsSearchIndex.MobileNetworkSettingsSearchItem
import com.android.settings.network.telephony.MobileNetworkSettingsSearchIndex.MobileNetworkSettingsSearchResult
import com.android.settings.spa.SpaActivity.Companion.startSpaActivity
import com.android.settings.network.telephony.carriersettingsoverride.CarrierSettingsOverridesProvider

/** Preference controller for "Carrier settings overrides" */
class CarrierSettingsOverridesPreferenceController(context: Context, key: String) :
    BasePreferenceController(context, key) {

    private var subId = SubscriptionManager.INVALID_SUBSCRIPTION_ID
    private lateinit var preference: Preference

    fun init(subId: Int) {
        this.subId = subId
    }

    override fun getAvailabilityStatus() = AVAILABLE

    override fun displayPreference(screen: PreferenceScreen) {
        super.displayPreference(screen)
        preference = screen.findPreference(preferenceKey)!!
    }

    override fun onViewCreated(viewLifecycleOwner: LifecycleOwner) {
        // preference.summary = mContext.getPlaceholder()
    }

    override fun handlePreferenceTreeClick(preference: Preference): Boolean {
        if (preference.key != preferenceKey) return false
        val route = CarrierSettingsOverridesProvider.getRoute(subId)
        mContext.startSpaActivity(route)
        return true
    }

    companion object {
        class CarrierSettingsOverridesSearchItem(
            private val context: Context
        ) : MobileNetworkSettingsSearchItem {
            override fun getSearchResult(subId: Int): MobileNetworkSettingsSearchResult {
                return MobileNetworkSettingsSearchResult(
                    key = "carrier_settings_override_gos",
                    title = context.getString(R.string.carrier_settings_override_gos_title),
                )
            }
        }
    }
}
