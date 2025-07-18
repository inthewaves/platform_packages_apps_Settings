package com.google.android.settings.fuelgauge.batterytip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.preference.PreferenceScreen
import com.android.settings.R
import com.android.settings.core.BasePreferenceController
import com.android.settings.widget.TipCardPreference
import com.google.android.settings.fuelgauge.Utils

class CycleCountTipPreferenceController(
    context: Context,
    key: String,
) : BasePreferenceController(context, key), LifecycleEventObserver {
    companion object {
        /**
         * An undocumented constant value for {@link BatteryManager#EXTRA_HEALTH}.
         * It's defined in
         * frameworks/native/services/batteryservice/include/batteryservice/BatteryServiceConstants.h
         * but not exposed in frameworks {@link android.os.BatteryManager}
         */
        const val BATTERY_HEALTH_FAIR = 8
        const val CYCLE_COUNT_THRESHOLD = 375
        const val CYCLE_COUNT_UNAVAILABLE = -1
        const val REQUIRED_DEVICE = "bluejay"
        private const val TAG = "CycleCountTipPreferenceController"
    }

    private var batteryBroadcastReceiver: BroadcastReceiver? = null
    private var cycleCountPreference: TipCardPreference? = null

    override fun getAvailabilityStatus(): Int {
        return if (Utils.isBarrelRequiredDevice(mContext)) {
            AVAILABLE_UNSEARCHABLE
        } else {
            CONDITIONALLY_UNAVAILABLE
        }
    }

    override fun displayPreference(screen: PreferenceScreen) {
        super.displayPreference(screen)
        val preference = screen.findPreference<TipCardPreference>(preferenceKey) ?: return
        preference.iconResId = R.drawable.ic_battery_charging_full
        cycleCountPreference = preference
        // set later in updatePreference
        preference.isVisible = false
    }

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        if (Utils.isBarrelRequiredDevice(mContext)) {
            when (event) {
                Lifecycle.Event.ON_CREATE -> {
                    batteryBroadcastReceiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context?, intent: Intent) {
                            updatePreference(intent)
                        }
                    }
                }
                Lifecycle.Event.ON_START -> {
                    batteryBroadcastReceiver?.let {
                        mContext.registerReceiver(it, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    batteryBroadcastReceiver?.let { mContext.unregisterReceiver(it) }
                }
                else -> return
            }
        }
    }

    private fun getCycleCount(intent: Intent): Int {
        val cycleCount = intent.getIntExtra(
            BatteryManager.EXTRA_CYCLE_COUNT,
            CYCLE_COUNT_UNAVAILABLE
        )
        Log.d(TAG, "cycleCount: $cycleCount")
        return cycleCount
    }

    private fun shouldShowCycleCountTip(intent: Intent, cycleCount: Int): Boolean {
        if (cycleCount < CYCLE_COUNT_THRESHOLD) {
            return false
        }

        val health = intent.getIntExtra(
            BatteryManager.EXTRA_HEALTH,
            BatteryManager.BATTERY_HEALTH_UNKNOWN
        )
        Log.d(TAG, "healthStatus: $health")

        return health == BATTERY_HEALTH_FAIR || health == BatteryManager.BATTERY_HEALTH_DEAD
    }

    private fun convertCycleCountToString(cycleCount: Int): CharSequence {
        return if (cycleCount == CYCLE_COUNT_UNAVAILABLE) {
            return mContext.getText(R.string.battery_cycle_count_not_available)
        } else {
            cycleCount.toString()
        }
    }

    private fun updatePreference(intent: Intent) {
        val cycleCount = getCycleCount(intent)
        val shouldShowCycleCountTip = shouldShowCycleCountTip(intent, cycleCount)
        Log.d(TAG, "shouldShowCycleCountTip: $shouldShowCycleCountTip")
        if (shouldShowCycleCountTip) {
            cycleCountPreference?.apply {
                summary = convertCycleCountToString(cycleCount)
                isVisible = true
            }
        }
    }
}
