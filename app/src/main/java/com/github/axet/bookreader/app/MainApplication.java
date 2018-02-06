package com.github.axet.bookreader.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.support.multidex.MultiDexApplication;
import android.support.v7.preference.PreferenceManager;

import com.github.axet.bookreader.R;

public class MainApplication extends MultiDexApplication {

    public static String PREFERENCE_THEME = "theme";
    public static String PREFERENCE_CATALOGS = "catalogs";
    public static String PREFERENCE_CATALOGS_PREFIX = "catalogs_";
    public static String PREFERENCE_CATALOGS_COUNT = "count";

    public static int getTheme(Context context, int light, int dark) {
        final SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(context);
        String theme = shared.getString(PREFERENCE_THEME, "");
        if (theme.equals(context.getString(R.string.Theme_Dark))) {
            return dark;
        } else {
            return light;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }

}
