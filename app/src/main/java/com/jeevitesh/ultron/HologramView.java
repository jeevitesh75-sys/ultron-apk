package com.jeevitesh.ultron;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import java.util.Random;

/** The red hologram. Draws a rotating point sphere with rings. Optional intro: blue, gold fights it, red takes over. */
public class HologramView extends View {
    public static final int IDLE = 0, LISTEN = 1, THINK = 2, SPEAK = 3;
    private static final int RED = 0xFFFF2D2D, GOLD = 0xFFFFC933, BLUE = 0xFF3DF2FF;
    private static final int N = 220;

    private final float[] px = new float[N], py = new float[N], pz = new float[N], r1 = new float[N], r2 = new float[N];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random rnd = new Random(7);
    private final long t0 = System.currentTimeMillis();
    private volatile int mode = IDLE;
    private volatile long flashAt = -100000L;
    private boolean intro = false;

    public HologramView(Context c) {
        super(c);
        for (int i = 0; i < N; i++) {
            float y = 1f - 2f * (i + 0.5f) / N;
            float r = (float) Math.sqrt(1 - y * y);
            float a = i * 2.399963f;
            px[i] = (float) Math.cos(a) * r;
            py[i] = y;
            pz[i] = (float) Math.sin(a) * r;
            r1[i] = rnd.nextFloat();
            r2[i] = rnd.nextFloat();
        }
    }

    public void setMode(int m) { mode = m; }
    public void flash() { flashAt = System.currentTimeMillis(); }
    public void setIntro(boolean b) { intro = b; }

    private static float clamp(double x) { return (float) Math.max(0, Math.min(1, x)); }
    private static int alpha(int color, float a) {
        int al = Math.max(0, Math.min(255, Math.round(a * 255)));
        return (color & 0x00FFFFFF) | (al << 24);
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float cx = w / 2f, cy = h / 2f, base = Math.min(w, h) * 0.3f;
        long now = System.currentTimeMillis();
        double t = (now - t0) / 1000.0;
        double bt = intro ? t : 99;
        float p1 = clamp((bt - 1.6) / 2.2), p2 = clamp((bt - 4.8) / 2.4);
        boolean boot = intro && bt < 7.4;
        float gl = boot ? 4 * p1 * (1 - p1) + 4 * p2 * (1 - p2) : 0f;
        int m = mode;
        int modeCol = m == LISTEN ? 0xFFFF7A5C : (m == THINK ? 0xFFFFB347 : RED);
        int col = boot ? (p2 > 0.5f ? RED : (p1 > 0.5f ? GOLD : BLUE)) : modeCol;
        float fl = clamp(1 - (now - flashAt) / 700.0);
        float boost;
        if (boot) boost = 0.1f + gl * 0.25f;
        else if (m == LISTEN) boost = 0.12f + 0.08f * (float) Math.sin(t * 5);
        else if (m == SPEAK) boost = 0.35f + 0.25f * (float) Math.sin(t * 14);
        else if (m == THINK) boost = 0.2f + 0.15f * (float) Math.sin(t * 6);
        else boost = 0f;
        float rad = base * (1 + boost * 0.25f + fl * 0.12f);

        c.save();
        if (fl > 0) c.translate((rnd.nextFloat() - 0.5f) * fl * w * 0.04f, (rnd.nextFloat() - 0.5f) * fl * w * 0.04f);

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(cx, cy, rad * 1.8f, alpha(col, fl > 0 ? 0.67f : 0.33f), 0x00000000, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, rad * 1.8f, paint);
        paint.setShader(null);

        double ay = t * 0.5 + (m == THINK ? t * 1.5 : 0) + gl * Math.sin(t * 30) * 0.2;
        double ax = 0.35;
        float cs = (float) Math.cos(ay), sn = (float) Math.sin(ay), cx2 = (float) Math.cos(ax), sx = (float) Math.sin(ax);
        float dot = Math.max(1.5f, w / 190f);
        for (int i = 0; i < N; i++) {
            float x = px[i] * cs - pz[i] * sn;
            float z = px[i] * sn + pz[i] * cs;
            float y = py[i] * cx2 - z * sx;
            z = py[i] * sx + z * cx2;
            float k = 1 + boost * 0.18f * (float) Math.sin(py[i] * 9 + t * 8);
            float j = gl * rad * 0.06f;
            float X = cx + x * rad * k + (rnd.nextFloat() - 0.5f) * j;
            float Y = cy + y * rad * k + (rnd.nextFloat() - 0.5f) * j;
            int pc = boot ? (r2[i] < p2 ? RED : (r1[i] < p1 ? GOLD : BLUE)) : col;
            paint.setColor(alpha(pc, 0.25f + 0.55f * (z + 1) / 2f));
            c.drawCircle(X, Y, dot * (1 + (z + 1) * 0.5f), paint);
        }

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.5f, w / 300f));
        for (int i = 0; i < 3; i++) {
            float rr = rad * (1.25f + i * 0.2f);
            double sp = (i % 2 == 1 ? -1 : 1) * (0.4 + i * 0.25) * t;
            paint.setColor(alpha(col, 0.7f - i * 0.18f));
            paint.setPathEffect(new DashPathEffect(new float[]{rr * 0.5f, rr * 0.18f, rr * 0.08f, rr * 0.2f}, (float) (-sp * rr)));
            c.save();
            c.rotate((float) Math.toDegrees(sp * 0.3 + i), cx, cy);
            float ry = rr * (0.34f + i * 0.02f);
            c.drawOval(new RectF(cx - rr, cy - ry, cx + rr, cy + ry), paint);
            c.restore();
        }
        paint.setPathEffect(null);
        paint.setColor(alpha(col, 0.9f));
        c.drawCircle(cx, cy, rad * 1.05f, paint);

        paint.setStyle(Paint.Style.FILL);
        float sy = cy - rad + (float) ((t * 0.45) % 1.0) * rad * 2f;
        paint.setColor(alpha(col, 0.5f));
        c.drawRect(cx - rad, sy, cx + rad, sy + 3f, paint);

        if (gl > 0.05f) {
            for (int i = 0; i < 4; i++) {
                if (rnd.nextFloat() < gl) {
                    paint.setColor(alpha(rnd.nextBoolean() ? BLUE : (p1 > 0.5f ? GOLD : RED), 0.55f));
                    float bx = rnd.nextFloat() * w * 0.6f, by = cy - rad * 1.3f + rnd.nextFloat() * rad * 2.6f;
                    c.drawRect(bx, by, bx + w * (0.1f + rnd.nextFloat() * 0.3f), by + 3 + rnd.nextFloat() * 4, paint);
                }
            }
        }
        if (fl > 0) { paint.setColor(alpha(0xFFFF1A1A, fl * 0.45f)); c.drawRect(0, 0, w, h, paint); }
        c.restore();
        postInvalidateOnAnimation();
    }
}
