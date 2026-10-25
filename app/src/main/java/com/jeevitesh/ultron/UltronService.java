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
    private static final long AWAKE_MS = 30000; // stays awake 30 s after the wake word and 30 s after he finishes talking

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
    private HologramServer holoServer;
    private boolean teluguMissingWarned = false;
    private int errorCount = 0;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        overlay = new OverlayController(this);
        overlay.setOnTap(this::stopSpeaking);
        tools = new PhoneTools(this, this);
        holoServer = new HologramServer(this, () -> tools.goHome());
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

    private static boolean hasTelugu(String t) {
        for (int i = 0; i < t.length(); i++) { char c = t.charAt(i); if (c >= 0x0C00 && c <= 0x0C7F) return true; }
        return false;
    }

    /** Picks a male voice if one exists, otherwise any voice for that language. */
    private void pickVoice(String lang) {
        try {
            Voice best = null;
            for (Voice v : tts.getVoices()) {
                if (!v.getLocale().getLanguage().equals(lang)) continue;
                String n = v.getName().toLowerCase(Locale.ROOT);
                if (n.contains("male") && !n.contains("female")) { best = v; break; }
                if (best == null) best = v;
                else if (lang.equals("en") && v.getLocale().getCountry().equals("IN") && !best.getLocale().getCountry().equals("IN")) best = v;
            }
            if (best != null) tts.setVoice(best);
        } catch (Exception ignored) { }
    }

    private void pickMaleVoice() { pickVoice("en"); }

    /** Sets the speaking language for this reply. Returns false if Telugu was needed but the phone has no Telugu voice. */
    private boolean applyLanguageFor(String text) {
        if (hasTelugu(text)) {
            int r = tts.setLanguage(new Locale("te", "IN"));
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(new Locale("en", "IN"));
                tts.setPitch(0.5f); tts.setSpeechRate(0.9f);
                return false;
            }
            tts.setPitch(0.85f);
            tts.setSpeechRate(0.95f);
            pickVoice("te");
            return true;
        }
        tts.setLanguage(new Locale("en", "IN"));
        tts.setPitch(0.5f);
        tts.setSpeechRate(0.9f);
        pickMaleVoice();
        return true;
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
        if (speaking) { ui.postDelayed(this::startListening, 500); return; }
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
            String rl = "te".equals(Prefs.lang(this)) ? "te-IN" : "en-IN";
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, rl);
            i.putExtra("android.speech.extra.LANGUAGE_PREFERENCE", rl);
            if (rl.equals("te-IN")) i.putExtra("android.speech.extra.ADDITIONAL_LANGUAGES", new String[]{"en-IN"});
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

    /** Telugu spellings of "Ultron" the recognizer may write: starts with a Telugu vowel and contains ల్ట్ర (e.g. అల్ట్రాన్, ఆల్ట్రాన్, ఉల్ట్రాన్). */
    private static boolean teluguWake(String x) {
        if (x.length() < 4 || x.length() > 12) return false;
        char c = x.charAt(0);
        boolean vowel = c == '\u0C05' || c == '\u0C06' || c == '\u0C09' || c == '\u0C0A' || c == '\u0C12' || c == '\u0C13';
        return vowel && x.contains("\u0C32\u0C4D\u0C1F\u0C4D\u0C30");
    }

    /** Returns the text after the wake word, "" if only the wake word, or null if no wake word. */
    private static String afterWake(String heard) {
        String[] w = heard.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\u0C00-\\u0C7F ]", " ").trim().split("\\s+");
        for (int i = 0; i < w.length; i++) {
            String x = w[i];
            boolean hit = x.equals("ultron") || x.equals("altron") || x.equals("ultra") || x.equals("oltron") || x.equals("ultran")
                    || (x.length() >= 5 && x.length() <= 8 && lev(x, "ultron") <= 2 && x.charAt(0) != 'a' || x.equals("altron"))
                    || teluguWake(x);
            if (hit) {
                StringBuilder sb = new StringBuilder();
                for (int j = i + 1; j < w.length; j++) sb.append(w[j]).append(' ');
                return sb.toString().trim();
            }
        }
        return null;
    }

    private static boolean te(String s) { return hasTelugu(s); }

    private void handleHeard(List<String> alts) {
        if (alts.isEmpty() || busy) return;
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
        final String raw = cmd;
        String low = cmd.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", "").trim();
        boolean teMode = te(raw) || "te".equals(Prefs.lang(this));

        if (pendingConfirm != null && (awake || woke)) {
            if (low.matches("(yes|yeah|yep|sure|do it|send it|confirm|ok|okay|go ahead).*") || raw.matches("(?s)^(అవును|సరే|ఓకే|పంపు|చెయ్యి|చేయి).*")) {
                Runnable r = pendingConfirm; pendingConfirm = null;
                worker.execute(r);
                say(te(raw) ? "అయిపోయింది." : "It is done.");
                return;
            }
            if (low.matches("(no|nope|cancel|stop|dont|don't|never mind|nevermind).*") || raw.matches("(?s)^(వద్దు|కాదు|క్యాన్సిల్|ఆపు|రద్దు).*")) {
                pendingConfirm = null; say(te(raw) ? "రద్దు చేశాను." : "Cancelled."); return;
            }
        }
        if (low.matches("(stop|quiet|shut up|enough|be quiet)") || raw.matches("^(ఆపు|ఆగు|చాలు|సైలెంట్|నోరు మూసుకో)[ .!]*$")) {
            stopSpeaking(); overlay.hide(); awakeUntil = 0; return;
        }
        if (woke) {
            overlay.show(HologramView.LISTEN);
            awakeUntil = now + AWAKE_MS;
            if (cmd.isEmpty()) { say(teMode ? "చెప్పండి, వింటున్నాను." : "I am listening."); return; }
        }
        if (cmd.isEmpty()) return;

        // "open hologram" is handled right here so it never depends on the AI
        if (isOpenHologram(raw)) {
            busy = true;
            overlay.show(HologramView.THINK);
            worker.execute(() -> {
                String r = tools.openHologramNow();
                final boolean ok = r.startsWith("opened") || r.startsWith("Chrome is not installed");
                ui.post(() -> {
                    busy = false;
                    if (ok) say(te(raw) ? "హోలోగ్రామ్ తెరుస్తున్నాను." : "Opening the hologram.");
                    else say(te(raw) ? "హోలోగ్రామ్ తెరవలేకపోయాను. డిస్ప్లే ఓవర్ అదర్ యాప్స్ అనుమతి ఇవ్వండి." : "I could not open the hologram. " + r);
                });
            });
            return;
        }
        think(cmd);
    }

    private static boolean isOpenHologram(String raw) {
        String l = raw.toLowerCase(Locale.ROOT);
        boolean holo = l.contains("hologram") || l.contains("holo gram") || l.contains("holagram") || raw.contains("హోలోగ్ర") || raw.contains("హొలోగ్ర");
        if (!holo) return false;
        boolean close = l.contains("close") || l.contains("exit") || l.contains("stop") || raw.contains("మూసి") || raw.contains("మూసే") || raw.contains("క్లోజ్") || raw.contains("ఆపు");
        // "generate image ... in hologram" goes to the AI so the prompt is kept
        boolean image = l.contains("image") || l.contains("picture") || l.contains("draw") || raw.contains("ఇమేజ్") || raw.contains("బొమ్మ") || raw.contains("చిత్ర");
        return !close && !image;
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
        awakeUntil = System.currentTimeMillis() + 120000; // stays awake while talking; the 30 s window starts when he finishes
        overlay.show(HologramView.SPEAK);
        if (!ttsReady) { speechFinished(); return; }
        speaking = true;
        try { if (rec != null) rec.cancel(); } catch (Exception ignored) { }
        tts.stop();
        if (!applyLanguageFor(text)) {
            if (!teluguMissingWarned) {
                teluguMissingWarned = true;
                notifyText("Telugu voice is not installed. Open Ultron and tap 'Install Telugu voice'.");
            }
            text = "My Telugu voice is not installed. Open the Ultron app and tap Install Telugu voice.";
        }
        String[] parts = text.replaceAll("[*#_`]", "").split("(?<=[.!?\u0964])\\s+");
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
        awakeUntil = System.currentTimeMillis() + AWAKE_MS;   // 30 s of waiting after he finishes talking
        overlay.setMode(HologramView.LISTEN);
        overlay.hideLater(AWAKE_MS);                           // circle stays visible while he is awake
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
    @Override public String hologramUrl(String prompt) { return holoServer.start() ? holoServer.url(prompt) : null; }
    @Override public void shutDown() {
        ui.post(() -> {
            stopped = true;
            running = false;
            try { if (rec != null) rec.destroy(); } catch (Exception ignored) { }
            try { tts.stop(); tts.shutdown(); } catch (Exception ignored) { }
            overlay.hide();
            holoServer.stop();
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
        if (holoServer != null) holoServer.stop();
        worker.shutdownNow();
        super.onDestroy();
    }
}
