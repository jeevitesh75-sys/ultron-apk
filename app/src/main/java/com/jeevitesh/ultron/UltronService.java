package com.jeevitesh.ultron;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Always-on listener. Says "ultron" -> only the hologram circle appears over whatever screen you are on. */
public class UltronService extends Service implements PhoneTools.Host {
    static volatile boolean running = false;
    private static final String CH = "ultron_listen";
    private static final long AWAKE_MS = 15000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<String[]> history = new ArrayList<>();
    private SpeechRecognizer rec;
    private TextToSpeech tts;
    private boolean ttsReady = false, speaking = false, busy = false, stopped = false;
    private long awakeUntil = 0;
    private Runnable pendingConfirm = null;
    private OverlayController overlay;
    private PowerManager.WakeLock wl;
    private PhoneTools tools;
    private int errorCount = 0;
    private String currentSpeech = "";

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        overlay = new OverlayController(this);
        overlay.setOnTap(this::stopSpeaking);
        tools = new PhoneTools(this, this);
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(new Locale("en", "IN"));
                tts.setPitch(0.5f);
                tts.setSpeechRate(0.9f);
                pickMaleVoice();
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { }
                    @Override public void onDone(String id) { if (id.startsWith("last")) ui.post(UltronService.this::speechFinished); }
                    @Override public void onError(String id) { if (id.startsWith("last")) ui.post(UltronService.this::speechFinished); }
                });
                ttsReady = true;
            }
        });
    }

    private void pickMaleVoice() {
        try {
            Voice best = null;
            for (Voice v : tts.getVoices()) {
                String n = v.getName().toLowerCase(Locale.ROOT);
                if (!v.getLocale().getLanguage().equals("en")) continue;
                if (n.contains("male") && !n.contains("female")) { best = v; break; }
                if (best == null && v.getLocale().getCountry().equals("IN")) best = v;
            }
            if (best != null) tts.setVoice(best);
        } catch (Exception ignored) { }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) { shutDown(); return START_NOT_STICKY; }
        startForegroundNow();
        if (!running) {
            running = true;
            stopped = false;
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ultron:listen");
            wl.acquire();
            ui.post(this::startListening);
        }
        return START_STICKY;
    }

    private void startForegroundNow() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CH, "Ultron listening", NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, UltronService.class).setAction("STOP"),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent open = PendingIntent.getActivity(this, 2, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CH)
                .setContentTitle("Ultron is listening")
                .setContentText("Say \"Ultron\" to wake him")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .setOngoing(true).build();
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
    }

    // ---- listening loop ----
    private void startListening() {
        if (stopped) return;
                try {
            if (rec != null) { rec.destroy(); rec = null; }
            if (!SpeechRecognizer.isRecognitionAvailable(this)) { notifyText("Speech recognition is not available on this phone."); return; }
            rec = SpeechRecognizer.createSpeechRecognizer(this);
            rec.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle b) { }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float v) { }
                @Override public void onBufferReceived(byte[] b) { }
                @Override public void onEndOfSpeech() { }
                @Override public void onEvent(int t, Bundle b) { }
                @Override public void onPartialResults(Bundle b) { }
                @Override public void onError(int e) {
                    errorCount++;
                    long delay = (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || errorCount > 5) ? 2500 : 300;
                    if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) { notifyText("Microphone permission missing. Open Ultron and grant it."); return; }
                    ui.postDelayed(UltronService.this::startListening, delay);
                }
                @Override public void onResults(Bundle b) {
                    errorCount = 0;
                    ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    handleHeard(r == null ? new ArrayList<String>() : r);
                    if (!busy) ui.postDelayed(UltronService.this::startListening, 150);
                }
            });
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN");
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
            rec.startListening(i);
        } catch (Exception e) {
            ui.postDelayed(this::startListening, 2000);
        }
    }

    private static int lev(String a, String b) {
        int[] p = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) p[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int prev = p[0];
            p[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int t = p[j];
                p[j] = Math.min(Math.min(p[j] + 1, p[j - 1] + 1), prev + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
                prev = t;
            }
        }
        return p[b.length()];
    }

    /** Returns the text after the wake word, "" if only the wake word, or null if no wake word. */
    private static String afterWake(String heard) {
        String[] w = heard.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").trim().split("\\s+");
        for (int i = 0; i < w.length; i++) {
            String x = w[i];
            boolean hit = x.equals("ultron") || x.equals("altron") || x.equals("ultra") || x.equals("oltron") || x.equals("ultran")
                    || (x.length() >= 5 && x.length() <= 8 && lev(x, "ultron") <= 2 && x.charAt(0) != 'a' || x.equals("altron"));
            if (hit) {
                StringBuilder sb = new StringBuilder();
                for (int j = i + 1; j < w.length; j++) sb.append(w[j]).append(' ');
                return sb.toString().trim();
            }
        }
        return null;
    }

    private void handleHeard(List<String> alts) {
        if (alts.isEmpty()) return;
        if (speaking) {
            for (String a : alts) {
                String t = a.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", " ").trim();
                if (t.matches("(.*\\b)?(stop|quiet|enough|shut up|be quiet|cancel|silence)(\\b.*)?") && t.split("\\s+").length <= 4
                        && !currentSpeech.contains(t)) {
                    stopSpeaking();
                    awakeUntil = System.currentTimeMillis() + AWAKE_MS;
                    return;
                }
            }
            return;
        }
        if (busy) return;
        long now = System.currentTimeMillis();
        boolean awake = now < awakeUntil;
        String cmd = null;
        boolean woke = false;
        for (String a : alts) {
            String rest = afterWake(a);
            if (rest != null) { cmd = rest; woke = true; break; }
        }
        if (!woke) {
            if (!awake) return;
            cmd = alts.get(0).trim();
        }
        String low = cmd.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", "").trim();

        if (pendingConfirm != null && (awake || woke)) {
            if (low.matches("(yes|yeah|yep|sure|do it|send it|confirm|ok|okay|go ahead).*")) {
                Runnable r = pendingConfirm; pendingConfirm = null;
                worker.execute(r);
                say("It is done.");
                return;
            }
            if (low.matches("(no|nope|cancel|stop|dont|don't|never mind|nevermind).*")) { pendingConfirm = null; say("Cancelled."); return; }
        }
        if (low.matches("(stop|quiet|shut up|enough|be quiet)")) { stopSpeaking(); overlay.hide(); awakeUntil = 0; return; }
        if (woke) {
            overlay.show(HologramView.LISTEN);
            awakeUntil = now + AWAKE_MS;
            if (cmd.isEmpty()) {
                try { ((android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE)).vibrate(android.os.VibrationEffect.createOneShot(80, 200)); } catch (Exception ignored) { }
                overlay.hideLater(AWAKE_MS);
                return;
            }
        }
        if (cmd.isEmpty()) return;
        think(cmd);
    }

    private void think(final String text) {
        busy = true;
        overlay.show(HologramView.THINK);
        final String device = "Android phone, battery " + PhoneTools.battery(this);
        worker.execute(() -> {
            String reply;
            try {
                reply = Brain.ask(this, new ArrayList<>(history), text, device, tools::run);
                history.add(new String[]{"user", text});
                history.add(new String[]{"assistant", reply});
                while (history.size() > 24) history.remove(0);
            } catch (Exception e) {
                reply = "I cannot reach my mind. " + e.getMessage();
            }
            final String out = reply;
            ui.post(() -> { busy = false; say(out); });
        });
    }

    // ---- speaking ----
    private void say(String text) {
        awakeUntil = System.currentTimeMillis() + AWAKE_MS;
        overlay.show(HologramView.SPEAK);
        if (!ttsReady) { speechFinished(); return; }
        speaking = true;
        currentSpeech = text.toLowerCase(Locale.ROOT);
        String[] parts = text.replaceAll("[*#_`]", "").split("(?<=[.!?])\\s+");
        tts.stop();
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            if (p.isEmpty()) continue;
            String id = (i == parts.length - 1 ? "last" : "part") + i;
            tts.speak(p, TextToSpeech.QUEUE_ADD, null, id);
        }
        tts.speak(" ", TextToSpeech.QUEUE_ADD, null, "last_end");
    }

    private void speechFinished() {
        if (!speaking) return;
        speaking = false;
        overlay.setMode(HologramView.LISTEN);
        overlay.hideLater(4000);
        ui.postDelayed(this::startListening, 300);
    }

    private void stopSpeaking() {
        try { tts.stop(); } catch (Exception ignored) { }
        speaking = true;
        speechFinished();
    }

    private void notifyText(String t) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(5, new Notification.Builder(this, CH).setContentTitle("Ultron").setContentText(t)
                .setSmallIcon(android.R.drawable.stat_notify_error).build());
    }

    // ---- PhoneTools.Host ----
    @Override public void confirm(String label, Runnable action) {
        ui.post(() -> { pendingConfirm = action; awakeUntil = System.currentTimeMillis() + 30000; });
    }
    @Override public void flash() { overlay.flash(); }
    @Override public void shutDown() {
        ui.post(() -> {
            stopped = true;
            running = false;
            try { if (rec != null) rec.destroy(); } catch (Exception ignored) { }
            try { tts.stop(); tts.shutdown(); } catch (Exception ignored) { }
            overlay.hide();
            if (wl != null && wl.isHeld()) wl.release();
            stopForeground(true);
            stopSelf();
        });
    }

    @Override public void onDestroy() {
        stopped = true;
        running = false;
        try { if (rec != null) rec.destroy(); } catch (Exception ignored) { }
        try { if (tts != null) tts.shutdown(); } catch (Exception ignored) { }
        if (wl != null && wl.isHeld()) wl.release();
        worker.shutdownNow();
        super.onDestroy();
    }
}
