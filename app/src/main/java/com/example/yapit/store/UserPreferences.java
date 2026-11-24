package com.example.yapit.store;

import android.content.Context;
import android.content.SharedPreferences;

public final class UserPreferences {
    private final SharedPreferences prefs;
    public UserPreferences(Context context) { prefs = context.getSharedPreferences("yapit", Context.MODE_PRIVATE); }
    public String selectedModelKey() { return prefs.getString("selected_model_key", ""); }
    public void setSelectedModelKey(String key) { prefs.edit().putString("selected_model_key", key).apply(); }
    public String selectedProfileId() { return prefs.getString("selected_profile_id", ""); }
    public void setSelectedProfileId(String id) { prefs.edit().putString("selected_profile_id", id).apply(); }
}
