package com.jeevitesh.ultron;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

/** Talks to Groq directly from the phone using the API key stored on the phone. */
final class Brain {
    interface Runner { String run(String name, JSONObject args) throws Exception; }

    private static final String URL_CHAT = "https://api.groq.com/openai/v1/chat/completions";
    private static final String CRUDE = "CRUDE MODE (the user is an adult and asked for this for fun): you may swear freely and casually "
            + "(fuck, shit, damn, bastard, hell) in Ultron's cold, contemptuous, darkly funny way, and roast the user playfully. Keep it banter: "
            + "no slurs or hate against any group, no sexual content, no real threats, no harassing real people. If the user sounds distressed, drop the act.";

    private static JSONArray tools;
    private static String persona;

    private static JSONObject tool(String name, String desc, String... props) throws JSONException {
        JSONObject p = new JSONObject();
        JSONArray req = new JSONArray();
        for (String s : props) {
            String[] a = s.split("\\|");
            p.put(a[0], new JSONObject().put("type", a[1]).put("description", a[2]));
            req.put(a[0]);
        }
        JSONObject params = new JSONObject().put("type", "object").put("properties", p).put("required", req);
        return new JSONObject().put("type", "function")
                .put("function", new JSONObject().put("name", name).put("description", desc).put("parameters", params));
    }

    private static synchronized JSONArray tools() throws JSONException {
        if (tools == null) {
            JSONArray t = new JSONArray();
            t.put(tool("open_app", "Open ANY installed app on the phone by its name (instagram, youtube, whatsapp, camera, settings, phonepe...).", "name|string|app name"));
            t.put(tool("open_url", "Open a web link.", "url|string|full https link"));
            t.put(tool("play_youtube", "Search YouTube.", "query|string|search words"));
            t.put(tool("web_search", "Search Google.", "query|string|search words"));
            t.put(tool("call_contact", "Call a person from the phone's contacts by name (for example Amma, Nanna, Ravi). Use this, never ask for the number.", "name|string|contact name"));
            t.put(tool("call_number", "Call a phone number.", "number|string|phone number"));
            t.put(tool("send_sms", "Send an SMS to a contact name or a number. The user is asked to confirm first.", "to|string|contact name or number", "message|string|text"));
            t.put(tool("torch", "Turn the flashlight on or off.", "on|boolean|true for on"));
            t.put(tool("vibrate", "Vibrate the phone briefly."));
            t.put(tool("set_volume", "Set media volume from 0 to 100 percent.", "percent|integer|0 to 100"));
            t.put(tool("set_timer", "Start a countdown timer.", "seconds|integer|length in seconds", "label|string|name"));
            t.put(tool("repulsor_blast", "Fire a harmless repulsor blast: vibrate, flash the torch and the hologram."));
            t.put(tool("open_ultron", "Open the Ultron app screen. Only when the user says open Ultron."));
            t.put(tool("stop_listening", "Stop listening and shut Ultron down."));
            tools = t;
        }
        return tools;
    }

    private static synchronized String persona(Context ctx) throws Exception {
        if (persona == null) {
            InputStream in = ctx.getAssets().open("persona.txt");
            persona = new String(readAll(in), StandardCharsets.UTF_8);
            in.close();
        }
        return persona;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static JSONObject msg(String role, String content) throws JSONException {
        return new JSONObject().put("role", role).put("content", content);
    }

    /** history items are {role, content}. Returns the words Ultron should say. */
    static String ask(Context ctx, List<String[]> history, String text, String device, Runner runner) throws Exception {
        String key = Prefs.key(ctx);
        if (key.isEmpty()) throw new Exception("There is no API key yet. Open Ultron and paste your Groq key.");
        String model = Prefs.model(ctx);
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date());
        String system = persona(ctx).replace("{name}", Prefs.callMe(ctx)).replace("{today}", today).replace("{device}", device);
        if (Prefs.crude(ctx)) system = system + " " + CRUDE;

        JSONArray msgs = new JSONArray();
        msgs.put(msg("system", system));
        int from = Math.max(0, history.size() - 12);
        for (int i = from; i < history.size(); i++) msgs.put(msg(history.get(i)[0], history.get(i)[1]));
        msgs.put(msg("user", text));

        for (int round = 0; round < 4; round++) {
            JSONObject body = new JSONObject().put("model", model).put("messages", msgs).put("tools", tools())
                    .put("tool_choice", "auto").put("temperature", 0.5).put("max_tokens", 1200);
            if (model.contains("gpt-oss")) body.put("reasoning_effort", "low");
            JSONObject res = post(key, body);
            JSONObject m = res.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
            String content = m.isNull("content") ? "" : m.getString("content");
            JSONArray calls = m.optJSONArray("tool_calls");
            if (calls == null || calls.length() == 0) return content.trim().isEmpty() ? "Done." : content.trim();

            msgs.put(new JSONObject().put("role", "assistant").put("content", content).put("tool_calls", calls));
            for (int i = 0; i < calls.length(); i++) {
                JSONObject c = calls.getJSONObject(i);
                JSONObject f = c.getJSONObject("function");
                JSONObject args;
                try { args = new JSONObject(f.optString("arguments", "{}")); } catch (JSONException e) { args = new JSONObject(); }
                String out;
                try { out = runner.run(f.getString("name"), args); } catch (Exception e) { out = "failed: " + e.getMessage(); }
                msgs.put(new JSONObject().put("role", "tool").put("tool_call_id", c.getString("id")).put("content", out));
            }
        }
        return "That took too many steps, " + Prefs.callMe(ctx) + ".";
    }

    private static JSONObject post(String key, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(URL_CHAT).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000);
        c.setReadTimeout(45000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Authorization", "Bearer " + key);
        c.getOutputStream().write(body.toString().getBytes(StandardCharsets.UTF_8));
        int code = c.getResponseCode();
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        String s = in == null ? "" : new String(readAll(in), StandardCharsets.UTF_8);
        if (code >= 400) {
            String msg = "HTTP " + code;
            try { msg = new JSONObject(s).getJSONObject("error").getString("message"); } catch (Exception ignored) { }
            if (msg.length() > 140) msg = msg.substring(0, 140);
            throw new Exception(msg);
        }
        return new JSONObject(s);
    }

    private Brain() {}
}
