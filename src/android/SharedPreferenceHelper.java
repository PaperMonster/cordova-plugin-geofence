package com.cowbell.cordova.geofence;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

/**
 * This class is used as a substitution of the window.localstorage in Android webviews
 */
public class SharedPreferenceHelper {

    public static final String PREF_NAME = "appPreference";
    public static final String KEY_USERID = "userId";
    public static final String KEY_DEVICEID = "deviceId";
    private Context context;
    private SharedPreferences sharedPref;
    private SharedPreferences.Editor editor;

    public SharedPreferenceHelper(Context context){
        this.context = context;
        sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
        editor = sharedPref.edit();
    }
    public void putString(String key, String value){
        editor.putString(key,value);
        editor.commit();
    }
    public String getString(String key, String defaultValue){
        return sharedPref.getString(key,defaultValue);
    }
}