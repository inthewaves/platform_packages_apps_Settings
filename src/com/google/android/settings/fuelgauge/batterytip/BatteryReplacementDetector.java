package com.google.android.settings.fuelgauge.batterytip;

import static com.google.android.settings.fuelgauge.batterytip.CycleCountTipPreferenceController.BATTERY_HEALTH_FAIR;
import static com.google.android.settings.fuelgauge.batterytip.CycleCountTipPreferenceController.CYCLE_COUNT_THRESHOLD;
import static com.google.android.settings.fuelgauge.batterytip.CycleCountTipPreferenceController.CYCLE_COUNT_UNAVAILABLE;

import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;
import android.util.Log;
import com.android.settings.fuelgauge.BatteryUtils;
import com.android.settings.fuelgauge.batterytip.tips.BatteryTip;

public class BatteryReplacementDetector {
    private final Context mContext;
    private final static String TAG = BatteryReplacementDetector.class.getSimpleName();

    public BatteryReplacementDetector(Context context) {
        this.mContext = context;
    }

    public BatteryTip detect() {
        final Intent batteryIntent = BatteryUtils.getBatteryIntent(mContext);
        if (batteryIntent == null) {
            return new BatteryReplacementTip(BatteryTip.StateType.INVISIBLE,
                    BatteryManager.BATTERY_HEALTH_UNKNOWN);
        }
        int health = batteryIntent.getIntExtra(BatteryManager.EXTRA_HEALTH,
                BatteryManager.BATTERY_HEALTH_UNKNOWN);
        int cycleCount = batteryIntent.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT,
                CYCLE_COUNT_UNAVAILABLE);
        Log.d(TAG, "detect() - battery health: " + health + ", cycle count: " + cycleCount);

        final @BatteryTip.StateType int state;
        if (health == BatteryManager.BATTERY_HEALTH_DEAD ||
                (health == BATTERY_HEALTH_FAIR && cycleCount >= CYCLE_COUNT_THRESHOLD)) {
            state = BatteryTip.StateType.NEW;
        } else {
            state = BatteryTip.StateType.INVISIBLE;
        }
        return new BatteryReplacementTip(state, health);
    }
}
