/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License.
 */

package com.android.launcher3.settings;

import static com.android.launcher3.SessionCommitReceiver.ADD_ICON_PREFERENCE_KEY;
import static com.android.launcher3.states.RotationHelper.ALLOW_ROTATION_PREFERENCE_KEY;
import static com.android.launcher3.states.RotationHelper.getAllowRotationDefaultValue;
import static com.android.launcher3.util.SecureSettingsObserver.newNotificationSettingsObserver;

import android.app.Activity;
import android.app.DialogFragment;
import android.app.Fragment;
import android.content.Context;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;

import androidx.preference.Preference;
import androidx.preference.Preference.OnPreferenceChangeListener;
import androidx.preference.PreferenceFragment;
import androidx.preference.PreferenceFragment.OnPreferenceStartFragmentCallback;
import androidx.preference.PreferenceFragment.OnPreferenceStartScreenCallback;
import androidx.preference.PreferenceGroup.PreferencePositionCallback;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreference;
import androidx.recyclerview.widget.RecyclerView;

import com.android.internal.util.derp.derpUtils;

import com.android.launcher3.customization.IconDatabase;
import com.android.launcher3.LauncherAppState;
import com.android.launcher3.LauncherFiles;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.config.FeatureFlags;
import com.android.launcher3.settings.preferences.CustomSeekBarPreference;
import com.android.launcher3.settings.preferences.IconPackPrefSetter;
import com.android.launcher3.settings.preferences.ReloadingListPreference;
import com.android.launcher3.trust.TrustAppsActivity;
import com.android.launcher3.util.AppReloader;
import com.android.launcher3.uioverrides.plugins.PluginManagerWrapper;
import com.android.launcher3.util.SecureSettingsObserver;

import androidx.preference.Preference;
import androidx.preference.PreferenceFragment;
import androidx.preference.PreferenceFragment.OnPreferenceStartFragmentCallback;
import androidx.preference.PreferenceFragment.OnPreferenceStartScreenCallback;
import androidx.preference.PreferenceGroup.PreferencePositionCallback;
import androidx.preference.PreferenceScreen;
import androidx.recyclerview.widget.RecyclerView;

import static com.android.launcher3.OverlayCallbackImpl.KEY_ENABLE_MINUS_ONE;

/**
 * Settings activity for Launcher.
 */
public class SettingsActivity extends Activity
        implements OnPreferenceStartFragmentCallback, OnPreferenceStartScreenCallback,
        SharedPreferences.OnSharedPreferenceChangeListener{

    private static final String DEVELOPER_OPTIONS_KEY = "pref_developer_options";
    private static final String FLAGS_PREFERENCE_KEY = "flag_toggler";

    private static final String NOTIFICATION_DOTS_PREFERENCE_KEY = "pref_icon_badging";
    /** Hidden field Settings.Secure.ENABLED_NOTIFICATION_LISTENERS */
    private static final String NOTIFICATION_ENABLED_LISTENERS = "enabled_notification_listeners";
    private static final String SUGGESTIONS_KEY = "pref_suggestions";
    protected static final String DPS_PACKAGE = "com.google.android.as";
    protected static final String GSA_PACKAGE = "com.google.android.googlequicksearchbox";
    private static final String KEY_ICON_PACK = "pref_icon_pack";

    public static final String EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key";
    public static final String EXTRA_SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args";
    private static final int DELAY_HIGHLIGHT_DURATION_MILLIS = 600;
    public static final String SAVE_HIGHLIGHTED_KEY = "android:preference_highlighted";

    private static Context mContext;

    public static final String KEY_MINUS_ONE = "pref_enable_minus_one";
    public static final String KEY_TRUST_APPS = "pref_trust_apps";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState == null) {
            Bundle args = new Bundle();
            String prefKey = getIntent().getStringExtra(EXTRA_FRAGMENT_ARG_KEY);
            if (!TextUtils.isEmpty(prefKey)) {
                args.putString(EXTRA_FRAGMENT_ARG_KEY, prefKey);
            }

            Fragment f = Fragment.instantiate(
                    this, getString(R.string.settings_fragment_name), args);
            // Display the fragment as the main content.
            getFragmentManager().beginTransaction()
                    .replace(android.R.id.content, f)
                    .commit();
        }
        Utilities.getPrefs(getApplicationContext()).registerOnSharedPreferenceChangeListener(this);
        mContext = getApplicationContext();
    }
    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (Utilities.DESKTOP_SHOW_QUICKSPACE.equals(key)) {
            LauncherAppState.getInstanceNoCreate().setNeedsRestart();
        }
        if (Utilities.ICON_SIZE.equals(key)) {
                LauncherAppState.getInstanceNoCreate().setNeedsRestart();
        }
    }

    public interface OnResumePreferenceCallback {
        void onResume();
    }

    private boolean startFragment(String fragment, Bundle args, String key) {
        if (Utilities.ATLEAST_P && getFragmentManager().isStateSaved()) {
            // Sometimes onClick can come after onPause because of being posted on the handler.
            // Skip starting new fragments in that case.
            return false;
        }
        Fragment f = Fragment.instantiate(this, fragment, args);
        if (f instanceof DialogFragment) {
            ((DialogFragment) f).show(getFragmentManager(), key);
        } else {
            getFragmentManager()
                    .beginTransaction()
                    .replace(android.R.id.content, f)
                    .addToBackStack(key)
                    .commit();
        }
        return true;
    }

    @Override
    public boolean onPreferenceStartFragment(
            PreferenceFragment preferenceFragment, Preference pref) {
        return startFragment(pref.getFragment(), pref.getExtras(), pref.getKey());
    }

    @Override
    public boolean onPreferenceStartScreen(PreferenceFragment caller, PreferenceScreen pref) {
        Bundle args = new Bundle();
        args.putString(PreferenceFragment.ARG_PREFERENCE_ROOT, pref.getKey());
        return startFragment(getString(R.string.settings_fragment_name), args, pref.getKey());
    }

    /**
     * This fragment shows the launcher preferences.
     */
    public static class LauncherSettingsFragment extends PreferenceFragment {

        private SecureSettingsObserver mNotificationDotsObserver;

        private String mHighLightKey;
        private boolean mPreferenceHighlighted = false;

        private Preference mShowGoogleAppPref;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            final Bundle args = getArguments();
            mHighLightKey = args == null ? null : args.getString(EXTRA_FRAGMENT_ARG_KEY);
            if (rootKey == null && !TextUtils.isEmpty(mHighLightKey)) {
                rootKey = getParentKeyForPref(mHighLightKey);
            }

            if (savedInstanceState != null) {
                mPreferenceHighlighted = savedInstanceState.getBoolean(SAVE_HIGHLIGHTED_KEY);
            }

            getPreferenceManager().setSharedPreferencesName(LauncherFiles.SHARED_PREFERENCES_KEY);
            setPreferencesFromResource(R.xml.launcher_preferences, rootKey);

            PreferenceScreen screen = getPreferenceScreen();
            for (int i = screen.getPreferenceCount() - 1; i >= 0; i--) {
                Preference preference = screen.getPreference(i);
                if (!initPreference(preference)) {
                    screen.removePreference(preference);
                }
            }
        }

        @Override
        public void onSaveInstanceState(Bundle outState) {
            super.onSaveInstanceState(outState);
            outState.putBoolean(SAVE_HIGHLIGHTED_KEY, mPreferenceHighlighted);
        }

        protected String getParentKeyForPref(String key) {
            return null;
        }

        /**
         * Initializes a preference. This is called for every preference. Returning false here
         * will remove that preference from the list.
         */
        protected boolean initPreference(Preference preference) {
            switch (preference.getKey()) {
                case NOTIFICATION_DOTS_PREFERENCE_KEY:
                    if (!Utilities.ATLEAST_OREO ||
                            !getResources().getBoolean(R.bool.notification_dots_enabled)) {
                        return false;
                    }

                    // Listen to system notification dot settings while this UI is active.
                    mNotificationDotsObserver = newNotificationSettingsObserver(
                            getActivity(), (NotificationDotsPreference) preference);
                    mNotificationDotsObserver.register();
                    // Also listen if notification permission changes
                    mNotificationDotsObserver.getResolver().registerContentObserver(
                            Settings.Secure.getUriFor(NOTIFICATION_ENABLED_LISTENERS), false,
                            mNotificationDotsObserver);
                    mNotificationDotsObserver.dispatchOnChange();
                    return true;

                case ADD_ICON_PREFERENCE_KEY:
                    return Utilities.ATLEAST_OREO;

                case ALLOW_ROTATION_PREFERENCE_KEY:
                    if (getResources().getBoolean(R.bool.allow_rotation)) {
                        // Launcher supports rotation by default. No need to show this setting.
                        return false;
                    }
                    // Initialize the UI once
                    preference.setDefaultValue(getAllowRotationDefaultValue());
                    return true;

                case FLAGS_PREFERENCE_KEY:
                    // Only show flag toggler UI if this build variant implements that.
                    return FeatureFlags.showFlagTogglerUi(getContext());

                case Utilities.KEY_ALL_APPS_BACKGROUND_ALPHA:
                    CustomSeekBarPreference allAppsAlpha = 
                            (CustomSeekBarPreference) findPreference(Utilities.KEY_ALL_APPS_BACKGROUND_ALPHA);
                    allAppsAlpha.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
                        public boolean onPreferenceChange(Preference preference, Object newValue) {
                            LauncherAppState.getInstanceNoCreate().setNeedsRestart();
                            return true;
                        }
                    });
                    return true;

                case SUGGESTIONS_KEY:
                    // Show if Device Personalization Services is present.
                    return isDPSEnabled(getContext());

                case Utilities.KEY_ALLOW_ALL_APPS_BLUR:
                case Utilities.KEY_ALL_APPS_BLUR:
                case Utilities.KEY_ALLOW_OVERVIEW_BLUR:
                case Utilities.KEY_OVERVIEW_BLUR:
                    return derpUtils.supportsBlur();

                case KEY_TRUST_APPS:
                    preference.setOnPreferenceClickListener(p -> {
                        Intent intent = new Intent(getActivity(), TrustAppsActivity.class);
                        startActivity(intent);
                        return true;
                    });
                    return true;

                case KEY_ENABLE_MINUS_ONE:
                    mShowGoogleAppPref = preference;
                    updateIsGoogleAppEnabled();
                    return true;

                case Utilities.KEY_SHOW_QUICKSPACE_NOWPLAYING:
                    SwitchPreference quickspaceNowPlaying =
                        (SwitchPreference) findPreference(Utilities.KEY_SHOW_QUICKSPACE_NOWPLAYING);
                    quickspaceNowPlaying.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
                        public boolean onPreferenceChange(Preference preference, Object newValue) {
                            LauncherAppState.getInstanceNoCreate().setNeedsRestart();
                            return true;
                        }
                    });
                    return true;

                case KEY_ICON_PACK:
                    ReloadingListPreference icons = (ReloadingListPreference) findPreference(KEY_ICON_PACK);
                    icons.setValue(IconDatabase.getGlobal(mContext));
                    icons.setOnReloadListener(IconPackPrefSetter::new);
                    icons.setOnPreferenceChangeListener((pref, val) -> {
                        IconDatabase.clearAll(mContext);
                        IconDatabase.setGlobal(mContext, (String) val);
                        AppReloader.get(mContext).reload();
                        return true;
                    });
                    return true;
            }
            return true;
        }

        @Override
        public void onResume() {
            super.onResume();

            if (isAdded() && !mPreferenceHighlighted) {
                PreferenceHighlighter highlighter = createHighlighter();
                if (highlighter != null) {
                    getView().postDelayed(highlighter, DELAY_HIGHLIGHT_DURATION_MILLIS);
                    mPreferenceHighlighted = true;
                }
            }
        }

        public static boolean isDPSEnabled(Context context) {
            try {
                return context.getPackageManager().getApplicationInfo(DPS_PACKAGE, 0).enabled;
            } catch (PackageManager.NameNotFoundException e) {
                return false;
            }
        }

        public static boolean isGSAEnabled(Context context) {
            try {
                return context.getPackageManager().getApplicationInfo(GSA_PACKAGE, 0).enabled;
            } catch (PackageManager.NameNotFoundException e) {
                return false;
            }
        }

        private void updateIsGoogleAppEnabled() {
            if (mShowGoogleAppPref != null) {
                mShowGoogleAppPref.setEnabled(isGSAEnabled(getContext()));
            }
        }

        private PreferenceHighlighter createHighlighter() {
            if (TextUtils.isEmpty(mHighLightKey)) {
                return null;
            }

            PreferenceScreen screen = getPreferenceScreen();
            if (screen == null) {
                return null;
            }

            RecyclerView list = getListView();
            PreferencePositionCallback callback = (PreferencePositionCallback) list.getAdapter();
            int position = callback.getPreferenceAdapterPosition(mHighLightKey);
            return position >= 0 ? new PreferenceHighlighter(list, position) : null;
        }

        @Override
        public void onDestroy() {
            if (mNotificationDotsObserver != null) {
                mNotificationDotsObserver.unregister();
                mNotificationDotsObserver = null;
            }
            // if we don't press the home button but the back button to close Settings,
            // then we must force a restart because the home button watcher wouldn't trigger it
            LauncherAppState.getInstanceNoCreate().checkIfRestartNeeded();
            super.onDestroy();
        }
    }
}
