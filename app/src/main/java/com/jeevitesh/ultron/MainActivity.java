package com.jeevitesh.ultron;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private EditText keyBox, modelBox;
    private CheckBox crudeBox;
    private TextView status;
    private HologramView holo;

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private Button button(String text, Runnable r) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(v -> r.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0A0000);
        root.setPadding(dp(20), dp(24), dp(20), dp(24));

        holo = new HologramView(this);
        holo.setIntro(true);
        root.addView(holo, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260)));

        TextView title = new TextView(this);
        title.setText("ULTRON");
        title.setTextColor(0xFFFF2D2D);
        title.setTextSize(26);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        keyBox = new EditText(this);
        keyBox.setHint("Groq API key (gsk_...)");
        keyBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyBox.setTextColor(0xFFFFFFFF);
        keyBox.setHintTextColor(0xFF886666);
        keyBox.setText(Prefs.key(this));
        root.addView(keyBox);

        modelBox = new EditText(this);
        modelBox.setHint("Model (default openai/gpt-oss-20b)");
        modelBox.setTextColor(0xFFFFFFFF);
        modelBox.setHintTextColor(0xFF886666);
        modelBox.setText(Prefs.model(this));
        root.addView(modelBox);

        crudeBox = new CheckBox(this);
        crudeBox.setText("Crude mode (swearing, for fun, adults only)");
        crudeBox.setTextColor(0xFFFFFFFF);
        crudeBox.setChecked(Prefs.crude(this));
        root.addView(crudeBox);

        root.addView(button("1. Save key", this::save));
        root.addView(button("2. Grant permissions", this::grant));
        root.addView(button("3. Start Ultron (always listening)", this::startIt));
        root.addView(button("Stop Ultron", () -> {
            startService(new Intent(this, UltronService.class).setAction("STOP"));
            refresh();
        }));
        root.addView(button("Battery: set to Unrestricted", this::battery));

        status = new TextView(this);
        status.setTextColor(0xFFFFC933);
        status.setPadding(0, dp(12), 0, 0);
        root.addView(status);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    @Override protected void onResume() { super.onResume(); refresh(); }

    private void refresh() {
        String k = Prefs.key(this).isEmpty() ? "no key" : "key saved on this phone";
        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        boolean ov = Settings.canDrawOverlays(this);
        boolean ct = checkSelfPermission(Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        status.setText("Key: " + k + "\nMic: " + (mic ? "ok" : "MISSING") + "\nContacts: " + (ct ? "ok" : "MISSING")
                + "\nDraw over apps: " + (ov ? "ok" : "MISSING") + "\nUltron: " + (UltronService.running ? "LISTENING" : "off"));
    }

    private void save() {
        Prefs.save(this, keyBox.getText().toString(), modelBox.getText().toString(), crudeBox.isChecked());
        Toast.makeText(this, "Saved on this phone", Toast.LENGTH_SHORT).show();
        refresh();
    }

    private void grant() {
        List<String> need = new ArrayList<>();
        need.add(Manifest.permission.RECORD_AUDIO);
        need.add(Manifest.permission.READ_CONTACTS);
        need.add(Manifest.permission.CALL_PHONE);
        need.add(Manifest.permission.SEND_SMS);
        if (Build.VERSION.SDK_INT >= 33) need.add("android.permission.POST_NOTIFICATIONS");
        requestPermissions(need.toArray(new String[0]), 7);
    }

    @Override public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Now allow 'Display over other apps' for Ultron", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
        }
        refresh();
    }

    private void startIt() {
        save();
        if (Prefs.key(this).isEmpty()) { Toast.makeText(this, "Paste your Groq key first", Toast.LENGTH_LONG).show(); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Grant permissions first", Toast.LENGTH_LONG).show();
            return;
        }
        startForegroundService(new Intent(this, UltronService.class));
        Toast.makeText(this, "Ultron is listening. Say: Ultron", Toast.LENGTH_LONG).show();
        ((android.view.View) status).postDelayed(this::refresh, 800);
    }

    private void battery() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
            } else Toast.makeText(this, "Already unrestricted", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }
}
