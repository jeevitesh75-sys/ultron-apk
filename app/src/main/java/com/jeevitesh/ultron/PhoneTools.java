package com.jeevitesh.ultron;

import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.AlarmClock;
import android.provider.ContactsContract;
import android.telephony.SmsManager;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** What Ultron can do on the phone. Runs on a background thread. Returns a plain sentence the AI reads back. */
final class PhoneTools {
    interface Host {
        void confirm(String label, Runnable action);
        void flash();
        void shutDown();
        /** Starts the local hologram page server and returns the URL to open (with optional image prompt), or null. */
        String hologramUrl(String prompt);
    }

    private final Context ctx;
    private final Host host;

    PhoneTools(Context ctx, Host host) { this.ctx = ctx; this.host = host; }

    String run(String name, JSONObject a) throws Exception {
        switch (name) {
            case "open_app": return openApp(a.optString("name"));
            case "open_url": return openUrl(a.optString("url"));
            case "play_youtube": return openUrl("https://www.youtube.com/results?search_query=" + Uri.encode(a.optString("query")));
            case "web_search": return openUrl("https://www.google.com/search?q=" + Uri.encode(a.optString("query")));
            case "call_contact": return callContact(a.optString("name"));
            case "call_number": return call(a.optString("number").replaceAll("[ -]", ""), a.optString("number"));
            case "send_sms": return sms(a.optString("to"), a.optString("message"));
            case "torch": torch(a.optBoolean("on")); return "torch " + (a.optBoolean("on") ? "on" : "off");
            case "vibrate": vibrate(400); return "vibrating";
            case "set_volume": return volume(a.optInt("percent", 50));
            case "set_timer": return timer(a.optInt("seconds", 60), a.optString("label", "Ultron timer"));
            case "repulsor_blast": blast(); return "repulsor fired";
            case "open_hologram": return hologram("");
            case "generate_image": return hologram(a.optString("prompt"));
            case "close_app": return closeApp(a.optString("name"));
            case "close_all_apps": return closeAll();
            case "go_home": return goHome();
            case "open_ultron": start(new Intent(ctx, MainActivity.class)); return "opened the Ultron screen";
            case "stop_listening": host.shutDown(); return "shutting down";
            default: return "unknown tool";
        }
    }

    private void start(Intent i) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(i); }

    private static String norm(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""); }

    /** Finds the installed launcher app whose name best matches, or null. */
    private ResolveInfo findApp(String name) {
        String q = norm(name);
        if (q.equals("insta") || q.equals("ig")) q = "instagram";
        if (q.equals("gpay")) q = "googlepay";
        if (q.equals("phonepay") || q.equals("phonepe")) q = "phonepe";
        if (q.equals("mail") || q.equals("email")) q = "gmail";
        if (q.equals("chrome") || q.equals("browser")) q = "chrome";
        if (q.isEmpty()) return null;
        PackageManager pm = ctx.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(main, 0);
        ResolveInfo best = null;
        int bestScore = -1;
        for (ResolveInfo ri : list) {
            String label = norm(String.valueOf(ri.loadLabel(pm)));
            if (label.isEmpty()) continue;
            int sc = label.equals(q) ? 100 : label.startsWith(q) ? 80 : label.contains(q) ? 60 : (q.contains(label) && label.length() >= 4) ? 40 : -1;
            if (sc > bestScore) { bestScore = sc; best = ri; }
        }
        return best;
    }

    private String openApp(String name) {
        if (norm(name).isEmpty()) return "no app name given. App names must be in English letters";
        ResolveInfo best = findApp(name);
        if (best == null) return "no installed app matches " + name;
        PackageManager pm = ctx.getPackageManager();
        Intent launch = pm.getLaunchIntentForPackage(best.activityInfo.packageName);
        if (launch == null) return "cannot launch " + name;
        try { start(launch); } catch (Exception e) { return "Android blocked opening it. Give Ultron the 'display over other apps' permission."; }
        return "opened " + best.loadLabel(pm);
    }

    // ---- hologram (opens a local page in Chrome) ----
    String openHologramNow() { return hologram(""); }

    private String hologram(String prompt) {
        String url = host.hologramUrl(prompt == null ? "" : prompt.trim());
        if (url == null) return "could not start the hologram page on this phone";
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE);
        i.setPackage("com.android.chrome");
        try { start(i); return (prompt == null || prompt.trim().isEmpty()) ? "opened the hologram in Chrome" : "opened the hologram in Chrome and it is generating the image"; }
        catch (ActivityNotFoundException e) { /* no Chrome, try the default browser */ }
        catch (Exception e) { return "Android blocked opening Chrome. Give Ultron the 'display over other apps' permission."; }
        i.setPackage(null);
        try { start(i); return "Chrome is not installed, so the hologram opened in the default browser"; }
        catch (Exception e) { return "no browser could open the hologram"; }
    }

    String goHome() {
        UltronAccess acc = UltronAccess.instance;
        if (acc != null) { acc.goHome(); return "went to the home screen"; }
        try { start(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)); } catch (Exception e) { return "could not go home"; }
        return "went to the home screen";
    }

    // ---- really closing apps ----
    private String closeApp(String name) {
        UltronAccess acc = UltronAccess.instance;
        String n = norm(name);
        String pkg, label;
        if (n.isEmpty() || n.equals("this") || n.equals("thisapp") || n.equals("current") || n.equals("currentapp") || n.equals("it")) {
            if (acc == null) return "FAILED: I cannot tell which app is open because Accessibility is not enabled for Ultron. Tell the user to enable it in the Ultron app, or to name the app";
            pkg = acc.foregroundPackage();
            if (pkg == null) return "FAILED: I could not detect the open app";
            label = pkg;
        } else {
            ResolveInfo ri = findApp(name);
            if (ri == null) return "FAILED: no installed app matches " + name + ". App names must be in English letters";
            pkg = ri.activityInfo.packageName;
            label = String.valueOf(ri.loadLabel(ctx.getPackageManager()));
        }
        if (pkg.equals(ctx.getPackageName())) return "that is Ultron himself. To shut him down the user says stop listening";
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            am.killBackgroundProcesses(pkg);
        } catch (Exception ignored) { }
        if (acc == null) {
            return "PARTLY: I only cleared " + label + " from the background. An app that is open on screen cannot be closed without Accessibility, "
                    + "so it may still be running. Tell the user honestly and ask them to enable Accessibility for Ultron in the Ultron app";
        }
        String r = acc.forceStop(pkg);
        if (r.equals("closed")) return "closed " + label + " (force stopped, verified the confirm button was pressed)";
        if (r.equals("not_running")) return label + " was not running, nothing to close";
        return "FAILED: I tried to force stop " + label + " but could not find or press the Force stop button on this phone. Do not say it is closed";
    }

    private String closeAll() {
        UltronAccess acc = UltronAccess.instance;
        if (acc == null) return "FAILED: Accessibility is not enabled for Ultron, so I cannot clear all apps. Tell the user to enable it in the Ultron app";
        boolean ok = acc.clearRecents();
        return ok ? "pressed Clear all in the recent apps screen" : "FAILED: I opened recent apps but could not find the Clear all button. Do not say the apps are closed";
    }

    private String openUrl(String url) {
        if (!url.matches("(?i)^https?://.*")) return "only web links starting with http or https";
        try { start(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception e) { return "could not open the link"; }
        return "opened";
    }

    /** Finds a contact's number by name. Returns {displayName, number} or null. */
    private String[] findContact(String name) {
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return new String[]{"", ""};
        ContentResolver cr = ctx.getContentResolver();
        Cursor c = cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER},
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?", new String[]{"%" + name.trim() + "%"}, null);
        if (c == null) return null;
        List<String[]> rows = new ArrayList<>();
        try { while (c.moveToNext()) rows.add(new String[]{c.getString(0), c.getString(1)}); } finally { c.close(); }
        if (rows.isEmpty()) return null;
        String q = name.trim().toLowerCase(Locale.ROOT);
        for (String[] r : rows) if (r[0] != null && r[0].toLowerCase(Locale.ROOT).equals(q)) return r;
        for (String[] r : rows) if (r[0] != null && r[0].toLowerCase(Locale.ROOT).startsWith(q)) return r;
        return rows.get(0);
    }

    private String callContact(String name) {
        String[] r = findContact(name);
        if (r != null && r[0].isEmpty() && r[1].isEmpty()) return "Ultron has no permission to read contacts. Tell the user to grant the contacts permission in the Ultron app";
        if (r == null) return "there is no contact named " + name + ". If the contact may be saved in Telugu, try again with the Telugu spelling";
        return call(r[1].replaceAll("[ -]", ""), r[0]);
    }

    private String call(String number, String who) {
        if (!number.matches("^\\+?[0-9]{3,15}$")) return "that phone number looks invalid";
        if (ctx.checkSelfPermission(android.Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) return "no permission to place calls. Tell the user to grant the phone permission in the Ultron app";
        try { start(new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number)))); } catch (Exception e) { return "the call could not start"; }
        return "calling " + who;
    }

    private String sms(String to, String message) {
        String number = to.replaceAll("[ -]", ""), who = to;
        if (!number.matches("^\\+?[0-9]{3,15}$")) {
            String[] r = findContact(to);
            if (r == null) return "there is no contact named " + to + ". If the contact may be saved in Telugu, try again with the Telugu spelling";
            if (r[1].isEmpty()) return "no permission to read contacts";
            number = r[1].replaceAll("[ -]", ""); who = r[0];
        }
        if (ctx.checkSelfPermission(android.Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return "no permission to send SMS. Tell the user to grant it in the Ultron app";
        final String num = number, msg = message;
        host.confirm("Text " + who + ": " + message, () -> {
            try {
                SmsManager sm = SmsManager.getDefault();
                sm.sendMultipartTextMessage(num, null, sm.divideMessage(msg), null, null);
            } catch (Exception ignored) { }
        });
        return "NOT sent yet. Waiting for the user to say yes or no. Ask them to confirm.";
    }

    private void torch(boolean on) throws Exception {
        CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        for (String id : cm.getCameraIdList()) {
            Boolean flash = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            if (flash != null && flash) { cm.setTorchMode(id, on); return; }
        }
        throw new Exception("this phone has no flashlight");
    }

    private void vibrate(long ms) {
        Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
    }

    private String volume(int percent) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int p = Math.max(0, Math.min(100, percent));
        am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(p / 100f * max), 0);
        return "volume set to " + p + " percent";
    }

    private String timer(int seconds, String label) {
        int s = Math.max(1, Math.min(86400, seconds));
        try {
            start(new Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, s)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, label).putExtra(AlarmClock.EXTRA_SKIP_UI, true));
        } catch (Exception e) { return "the clock app refused the timer"; }
        return "timer set for " + s + " seconds";
    }

    private void blast() {
        host.flash();
        vibrate(700);
        for (int i = 0; i < 3; i++) {
            try { torch(true); Thread.sleep(130); torch(false); Thread.sleep(130); } catch (Exception ignored) { }
        }
    }

    static String battery(Context ctx) {
        BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
        return bm == null ? "unknown" : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) + " percent";
    }
}
