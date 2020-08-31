/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */
package com.android.settings.users;

import android.content.Context;
import android.os.UserManager;
import android.provider.Settings;

import androidx.preference.Preference;

import com.android.settings.R;
import com.android.settings.core.TogglePreferenceController;

public class LimitNumberOfBackgroundUsersPreferenceController extends TogglePreferenceController {

    private final UserCapabilities mUserCaps;

    public LimitNumberOfBackgroundUsersPreferenceController(Context context, String key) {
        super(context, key);
        mUserCaps = UserCapabilities.create(context);
    }

    @Override
    public void updateState(Preference preference) {
        super.updateState(preference);
        mUserCaps.updateAddUserCapabilities(mContext);

        preference.setSummary(getSummary());
        if (!isAvailable()) {
            preference.setVisible(false);
        } else {
            preference.setVisible(mUserCaps.mUserSwitcherEnabled);
        }
    }

    @Override
    public int getAvailabilityStatus() {
        if (!mUserCaps.isAdmin()) {
            return DISABLED_FOR_USER;
        } else {
            return mUserCaps.mUserSwitcherEnabled ? AVAILABLE : DISABLED_FOR_USER;
        }
    }

    @Override
    public boolean isChecked() {
        return Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.RUNNING_USERS_LIMIT_ENABLED, 1) == 1;
    }

    @Override
    public boolean setChecked(boolean isChecked) {
        return Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.RUNNING_USERS_LIMIT_ENABLED, isChecked ? 1 : 0);
    }

    @Override
    public CharSequence getSummary() {
        final int maxRunningUsers = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_multiuserMaxRunningUsers);
        return mContext.getString(R.string.user_settings_limit_number_background_users_summary,
                maxRunningUsers);
    }
}

