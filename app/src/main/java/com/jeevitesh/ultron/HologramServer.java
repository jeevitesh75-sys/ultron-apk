package com.jeevitesh.ultron;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Serves the hologram page (assets/hologram.html) on 127.0.0.1 so Chrome can open it. Only this phone can reach it.
 * The page asks /api/chat for AI replies; this server adds the Groq key, so the key is never given to the page.
 * Every request needs a random token that is only in the URL Ultron opens, so other web pages cannot use it.
 */
final class HologramServer {
    static final int PORT = 8765;

    private final Context ctx;
    private final Runnable onHome;
    private final String token = UUID.randomUUID().toString().replace("-", "");
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private ServerSocket server;
    private volatile boolean running = false;
    private byte[] page;

    HologramServer(Context ctx, Runnable onHome) {
        this.ctx = ctx.getApplicationContext();
        this.onHome = onHome;
    }

    synchronized boolean start() {
        if (running) return true;
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT));
            server = ss;
        } catch (IOException e) { return false; }
        running = true;
        Thread t = new Thread(this::loop, "ultron-hologram-server");
        t.setDaemon(true);
        t.start();
        return true;
    }

    synchronized void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        server = null;
    }

    String url(String prompt) {
        String u = "http://127.0.0.1:" + PORT + "/?t=" + token;
        if (prompt != null && !prompt.trim().isEmpty()) u += "&p=" + Uri.encode(prompt.trim());
        return u;
    }

    private void loop() {
        while (running) {
            try {
                final Socket s = server.accept();
                pool.execute(() -> handle(s));
            } catch (Exception e) {
                if (!running) return;
            }
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) throw new IOException("line too long");
        }
        if (c == -1 && sb.length() == 0) return null;
        return sb.toString();
    }

    private void handle(Socket sock) {
        try {
            sock.setSoTimeout(20000);
            InputStream in = sock.getInputStream();
            OutputStream out = sock.getOutputStream();
            String line = readLine(in);
            if (line == null) return;
            String[] rl = line.split(" ");
            if (rl.length < 2) return;
            String method = rl[0], target = rl[1];
            int len = 0;
            String tok = "", host = "";
            String h;
            while ((h = readLine(in)) != null && !h.isEmpty()) {
                int i = h.indexOf(':');
                if (i < 0) continue;
                String k = h.substring(0, i).trim().toLowerCase(Locale.ROOT);
                String v = h.substring(i + 1).trim();
                if (k.equals("content-length")) { try { len = Integer.parseInt(v); } catch (Exception ignored) { } }
                else if (k.equals("x-t")) tok = v;
                else if (k.equals("host")) host = v.toLowerCase(Locale.ROOT);
            }
            // only accept requests addressed to the loopback name (blocks DNS-rebinding tricks)
            if (!(host.equals("127.0.0.1:" + PORT) || host.equals("localhost:" + PORT))) { reply(out, 403, "text/plain", "forbidden".getBytes(StandardCharsets.UTF_8)); return; }
            byte[] body = new byte[0];
            if (len > 0) {
                if (len > 100000) { reply(out, 413, "text/plain", "too big".getBytes(StandardCharsets.UTF_8)); return; }
                body = new byte[len];
                int got = 0;
                while (got < len) { int n = in.read(body, got, len - got); if (n < 0) break; got += n; }
            }
            Uri u = Uri.parse("http://127.0.0.1" + target);
            String path = u.getPath() == null ? "/" : u.getPath();

            if (method.equals("GET") && path.equals("/")) {
                if (!token.equals(u.getQueryParameter("t"))) { reply(out, 403, "text/plain", "forbidden".getBytes(StandardCharsets.UTF_8)); return; }
                reply(out, 200, "text/html; charset=utf-8", page());
                return;
            }
            if (!token.equals(tok)) { reply(out, 403, "text/plain", "forbidden".getBytes(StandardCharsets.UTF_8)); return; }

            if (method.equals("POST") && path.equals("/api/chat")) {
                JSONObject res = new JSONObject();
                try {
                    JSONArray in2 = new JSONObject(new String(body, StandardCharsets.UTF_8)).getJSONArray("messages");
                    JSONArray clean = new JSONArray();
                    int from = Math.max(0, in2.length() - 16);
                    for (int i = from; i < in2.length(); i++) {
                        JSONObject m = in2.getJSONObject(i);
                        String role = m.optString("role");
                        if (!(role.equals("system") || role.equals("user") || role.equals("assistant"))) continue;
                        String c = m.optString("content");
                        if (c.length() > 2000) c = c.substring(0, 2000);
                        clean.put(new JSONObject().put("role", role).put("content", c));
                    }
                    res.put("text", Brain.chat(ctx, clean, 600));
                } catch (Exception e) {
                    res.put("error", String.valueOf(e.getMessage()));
                }
                reply(out, 200, "application/json; charset=utf-8", res.toString().getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (method.equals("POST") && path.equals("/api/home")) {
                try { onHome.run(); } catch (Exception ignored) { }
                reply(out, 200, "application/json", "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            reply(out, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        } finally {
            try { sock.close(); } catch (IOException ignored) { }
        }
    }

    private synchronized byte[] page() throws IOException {
        if (page == null) {
            InputStream in = ctx.getAssets().open("hologram.html");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            page = bo.toByteArray();
        }
        return page;
    }

    private static void reply(OutputStream out, int code, String type, byte[] body) throws IOException {
        String status = code == 200 ? "OK" : code == 403 ? "Forbidden" : code == 404 ? "Not Found" : "Error";
        String head = "HTTP/1.1 " + code + " " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }
}
