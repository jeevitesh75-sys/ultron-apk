package com.jeevitesh.ultron;

import android.content.Context;
import android.content.SharedPreferences;

/** Everything is stored privately on this phone (the API key never leaves it except to talk to Groq). */
final class Prefs {
    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("ultron", Context.MODE_PRIVATE); }
    static String key(Context c) { return sp(c).getString("groq_key", "").trim(); }
    static String model(Context c) { String m = sp(c).getString("model", "").trim(); return m.isEmpty() ? "openai/gpt-oss-120b" : m; }
    static boolean crude(Context c) { return sp(c).getBoolean("crude", false); }
    static String callMe(Context c) { String n = sp(c).getString("call_me", "").trim(); return n.isEmpty() ? "Creator" : n; }
    /** "te" = Telugu (also understands English words), "en" = English. */
    static String lang(Context c) { return "en".equals(sp(c).getString("lang", "te")) ? "en" : "te"; }
    static void setLang(Context c, String lang) { sp(c).edit().putString("lang", "en".equals(lang) ? "en" : "te").apply(); }
    static void save(Context c, String key, String model, boolean crude) {
        sp(c).edit().putString("groq_key", key.trim()).putString("model", model.trim()).putBoolean("crude", crude).apply();
    }
    private Prefs() {}
}
