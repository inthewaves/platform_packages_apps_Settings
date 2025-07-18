package com.google.android.settings.fuelgauge.batterytip

import android.app.settings.SettingsEnums
import android.content.Context
import android.os.BatteryManager
import android.os.Parcel
import android.os.Parcelable
import android.text.Html
import android.util.Log
import androidx.preference.Preference
import com.android.settings.R
import com.android.settings.fuelgauge.batterytip.tips.BatteryTip
import com.android.settings.widget.TipCardPreference
import com.android.settingslib.HelpUtils
import com.android.settingslib.core.instrumentation.MetricsFeatureProvider

private const val TAG = "BatteryReplacementTip"

class BatteryReplacementTip : BatteryTip {
    private var mBatteryHealth = 0

    constructor(@StateType state: Int, batteryHealth: Int) : super(
        // TODO: 12 is used as the tipType param in stock OS, but TipType currently goes up to
        //  11 (BATTERY_WARNING) in AOSP (no July tags). Stock OS also has changes in TipType and
        //  adds 12 to the top of the order
        TipType.BATTERY_WARNING,
        state,
        false
    ) {
        mBatteryHealth = batteryHealth
    }

    constructor(parcel: Parcel) : super(parcel) {
        mBatteryHealth = parcel.readInt()
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        super.writeToParcel(dest, flags)
        dest.writeInt(mBatteryHealth)
    }

    override fun getTitle(context: Context): CharSequence {
        return context.getString(R.string.battery_tip_replacement_title)
    }

    override fun getSummary(context: Context): CharSequence {
        val stringRes = if (mBatteryHealth == BatteryManager.BATTERY_HEALTH_DEAD) {
            R.string.battery_tip_replacement_summary
        } else {
            R.string.battery_tip_early_replacement_summary
        }
        return Html.fromHtml(context.getString(stringRes), 0);
    }

    override fun getIconId(): Int = R.drawable.ic_battery_alert_24dp

    override fun updateState(tip: BatteryTip) {
        mState = tip.state
    }

    override fun log(context: Context?, metricsFeatureProvider: MetricsFeatureProvider?) {
        metricsFeatureProvider?.action(
            context,
            SettingsEnums.ACTION_BATTERY_HEALTH_TIP,
            mState
        )
    }

    override fun updatePreference(preference: Preference) {
        super.updatePreference(preference)
        val tipCardPref = preference as? TipCardPreference ?: return
        tipCardPref.iconResId = iconId
        tipCardPref.onClick = onClick@ {
            val context: Context = tipCardPref.context
            val helpIntent = HelpUtils.getHelpIntent(
                context,
                context.getString(R.string.help_url_battery_replacement),
                ""
            ) ?: return@onClick

            try {
                context.startActivity(helpIntent)
            } catch (e: Exception) {
                Log.e(TAG, "can't start action $helpIntent", e)
            }
        }
        tipCardPref.buildContent()
    }

    companion object CREATOR : Parcelable.Creator<BatteryReplacementTip> {
        override fun createFromParcel(parcel: Parcel): BatteryReplacementTip {
            return BatteryReplacementTip(parcel)
        }

        override fun newArray(size: Int): Array<BatteryReplacementTip?> {
            return arrayOfNulls(size)
        }
    }
}
