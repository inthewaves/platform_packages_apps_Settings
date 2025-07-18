package com.google.android.settings.fuelgauge;

import android.content.Context;
import android.os.Build;
import android.text.TextUtils;

public class Utils {
    public static boolean isBarrelRequiredDevice(Context context) {
        // Removed a check for Settings.Secure.getInt(context.getContentResolver(), "barrel_forcibly_disabled", 0) == 1;
        return TextUtils.equals("bluejay", Build.DEVICE); // && !isBarrelForciblyDisabled;
    }
}
