package com.aimruler;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;
import java.nio.ByteBuffer;

public class OverlayService extends Service {
    static final int RED = 0xFFE53935, ORG = 0xFFFB8C00, YEL = 0xFFFDD835, BLU = 0xFF42A5F5, GRY = 0xFF9E9E9E;
    static final String[] NAMES = {"Средний", "Тяжёлый", "ПТ", "Лёгкий"};
    static final float[] ALPHAS = {0.9f, 0.7f, 0.5f, 0.35f};
    static final int[] LEG_C = {RED, ORG, YEL, BLU, GRY};
    static final String[] LEG_T = {"боеукладка", "двигатель", "топливо", "экипаж", "гусеницы"};
    static final int SCALE = 4;

    static class P {
        final float x, y, w, h; final int c;
        P(float x, float y, float w, float h, int c) { this.x = x; this.y = y; this.w = w; this.h = h; this.c = c; }
    }

    // Схемы в условных единицах, рамка 200 x 100, перед танка справа
    // корпус: x, y, w, h; башня или рубка: x, y, w, h; высота ствола
    static final float[][] BODY = {
        {10, 44, 180, 34, 72, 14, 72, 34, 30},
        {8, 46, 184, 34, 58, 6, 100, 44, 28},
        {8, 54, 184, 26, 40, 30, 140, 48, 46},
        {14, 48, 172, 30, 80, 18, 52, 28, 32}};
    static final P[][] PARTS = {
        {new P(14, 50, 44, 24, ORG), new P(60, 52, 18, 20, YEL), new P(82, 52, 38, 22, RED), new P(150, 52, 30, 20, BLU), new P(84, 20, 50, 24, BLU)},
        {new P(12, 52, 46, 24, ORG), new P(60, 54, 20, 22, YEL), new P(60, 24, 30, 22, RED), new P(96, 56, 30, 20, RED), new P(96, 14, 54, 28, BLU), new P(152, 54, 30, 20, BLU)},
        {new P(12, 56, 44, 22, ORG), new P(58, 58, 16, 18, YEL), new P(78, 54, 40, 22, RED), new P(118, 34, 36, 24, BLU), new P(156, 60, 24, 16, BLU)},
        {new P(16, 52, 40, 22, ORG), new P(58, 54, 16, 18, YEL), new P(80, 54, 34, 20, RED), new P(150, 54, 28, 18, BLU), new P(86, 22, 40, 18, BLU)}};

    WindowManager wm;
    SharedPreferences sp;
    final Handler hd = new Handler(Looper.getMainLooper());
    float d, sc, ox, oy;
    int type, ai;
    boolean flip, locked, shown = true;
    TankView tv;
    Ring ring;
    LinearLayout panel;
    LayoutParams tlp, rlp;

    // автоматический режим
    boolean auto;
    MediaProjection mp;
    VirtualDisplay vd;
    ImageReader reader;
    HandlerThread ht;
    Handler ch;
    int realW, realH, dpi;
    byte[] mask;
    int[] stack;
    long lastT, lastSeen, calT;
    float smx = -1, smy = -1;
    boolean lostShown;
    volatile boolean detOn = true, pickPending;
    volatile int tr = -1, tg, tb, tol = 90;
    volatile float pickX, pickY;
    volatile int ex0, ey0, ex1, ey1, px0, py0, px1, py1;

    @Override public IBinder onBind(Intent i) { return null; }

    int wtype() { return Build.VERSION.SDK_INT >= 26 ? LayoutParams.TYPE_APPLICATION_OVERLAY : LayoutParams.TYPE_PHONE; }

    @Override public int onStartCommand(Intent in, int flags, int id) {
        if (wm != null) return START_STICKY;
        boolean wantAuto = in != null && in.hasExtra("data");
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel nc = new NotificationChannel("mod", "Модули танка", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(nc);
        }
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, "mod") : new Notification.Builder(this);
        Notification n = nb.setContentTitle("Модули танка включены").setSmallIcon(android.R.drawable.ic_menu_compass).build();
        try {
            if (wantAuto && Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            else if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(1, n);
        } catch (Exception e) { stopSelf(); return START_NOT_STICKY; }
        setup(wantAuto);
        if (wantAuto) {
            try { startAuto(in.getIntExtra("code", 0), (Intent) in.getParcelableExtra("data")); }
            catch (Exception e) { toast("Не удалось запустить запись экрана"); }
        }
        return START_STICKY;
    }

    void toast(final String s) { hd.post(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show()); }

    void setup(boolean wantAuto) {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics m = getResources().getDisplayMetrics();
        d = m.density;
        sp = getSharedPreferences("t", 0);
        type = sp.getInt("type", 0) % NAMES.length;
        flip = sp.getBoolean("flip", false);
        sc = Math.min(6f * d, Math.max(0.6f * d, sp.getFloat("sc", 1.4f * d)));
        ai = sp.getInt("ai", 1) % ALPHAS.length;
        locked = wantAuto;

        tv = new TankView();
        tv.setAlpha(ALPHAS[ai]);
        int fl = LayoutParams.FLAG_NOT_FOCUSABLE | LayoutParams.FLAG_NOT_TOUCH_MODAL | LayoutParams.FLAG_LAYOUT_NO_LIMITS | LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        if (locked) fl |= LayoutParams.FLAG_NOT_TOUCHABLE;
        tlp = new LayoutParams((int) (200 * sc) + 2, (int) (130 * sc) + 2, wtype(), fl, PixelFormat.TRANSLUCENT);
        tlp.gravity = Gravity.TOP | Gravity.LEFT;
        tlp.x = sp.getInt("x", (int) (40 * d));
        tlp.y = sp.getInt("y", (int) (80 * d));
        wm.addView(tv, tlp);

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xCC101A14);
        panel.setPadding(4, 4, 4, 4);
        LinearLayout r1 = new LinearLayout(this), r2 = new LinearLayout(this);
        final Button bT = btn(NAMES[type]);
        bT.setOnClickListener(v -> { type = (type + 1) % NAMES.length; bT.setText(NAMES[type]); tv.invalidate(); save(); });
        Button bF = btn("Зеркало");
        bF.setOnClickListener(v -> { flip = !flip; tv.invalidate(); save(); });
        Button bM = btn("−");
        bM.setOnClickListener(v -> { sc = Math.max(0.6f * d, sc / 1.15f); resize(); });
        Button bP = btn("+");
        bP.setOnClickListener(v -> { sc = Math.min(6f * d, sc * 1.15f); resize(); });
        Button bA = btn("Проз");
        bA.setOnClickListener(v -> { ai = (ai + 1) % ALPHAS.length; tv.setAlpha(ALPHAS[ai]); save(); });
        r1.addView(bT); r1.addView(bF); r1.addView(bM); r1.addView(bP); r1.addView(bA);
        final Button bL = btn(locked ? "Двигать" : "Закрепить");
        bL.setOnClickListener(v -> {
            locked = !locked;
            if (locked) tlp.flags |= LayoutParams.FLAG_NOT_TOUCHABLE; else tlp.flags &= ~LayoutParams.FLAG_NOT_TOUCHABLE;
            wm.updateViewLayout(tv, tlp);
            bL.setText(locked ? "Двигать" : "Закрепить");
        });
        final Button bH = btn("Скрыть");
        bH.setOnClickListener(v -> {
            shown = !shown;
            tv.setVisibility(shown ? View.VISIBLE : View.GONE);
            bH.setText(shown ? "Скрыть" : "Показать");
        });
        Button bX = btn("Выход");
        bX.setOnClickListener(v -> stopSelf());
        r2.addView(bL); r2.addView(bH); r2.addView(bX);
        panel.addView(r1); panel.addView(r2);
        if (wantAuto) {
            LinearLayout r3 = new LinearLayout(this);
            final Button bC = btn("Цвет");
            bC.setOnClickListener(v -> onColorButton(bC));
            Button bD1 = btn("Допуск−");
            bD1.setOnClickListener(v -> { tol = Math.max(30, tol - 15); toast("Допуск " + tol); });
            Button bD2 = btn("Допуск+");
            bD2.setOnClickListener(v -> { tol = Math.min(240, tol + 15); toast("Допуск " + tol); });
            final Button bAu = btn("Авто: вкл");
            bAu.setOnClickListener(v -> { detOn = !detOn; bAu.setText(detOn ? "Авто: вкл" : "Авто: выкл"); });
            r3.addView(bC); r3.addView(bD1); r3.addView(bD2); r3.addView(bAu);
            panel.addView(r3);
        }
        LayoutParams pp = new LayoutParams(-2, -2, wtype(), LayoutParams.FLAG_NOT_FOCUSABLE | LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        pp.gravity = Gravity.BOTTOM | Gravity.LEFT;
        wm.addView(panel, pp);
        hd.postDelayed(this::fixOrigin, 500);
        hd.postDelayed(this::fixOrigin, 1500);
        hd.postDelayed(tick, 500);
    }

    Button btn(String t) {
        Button b = new Button(this);
        b.setText(t); b.setTextSize(12); b.setAllCaps(false);
        b.setMinWidth(0); b.setMinimumWidth(0); b.setMinHeight(0); b.setMinimumHeight(0);
        b.setPadding((int) (8 * d), (int) (6 * d), (int) (8 * d), (int) (6 * d));
        return b;
    }

    void resize() {
        tlp.width = (int) (200 * sc) + 2;
        tlp.height = (int) (130 * sc) + 2;
        wm.updateViewLayout(tv, tlp);
        tv.invalidate();
        save();
        hd.postDelayed(this::fixOrigin, 300);
        updateRects();
    }

    void save() {
        sp.edit().putInt("type", type).putBoolean("flip", flip).putFloat("sc", sc).putInt("ai", ai)
            .putInt("x", tlp.x).putInt("y", tlp.y).apply();
    }

    // Узнаём, на сколько окно реально смещено относительно координат экрана
    void fixOrigin() {
        if (wm == null || tv == null || !tv.isAttachedToWindow()) return;
        int[] l = new int[2];
        tv.getLocationOnScreen(l);
        ox = l[0] - tlp.x; oy = l[1] - tlp.y;
        updateRects();
    }

    // Области экрана, которые распознавание должно пропускать: сама схема и панель
    void updateRects() {
        ex0 = (int) (tlp.x + ox) - 8; ey0 = (int) (tlp.y + oy) - 8;
        ex1 = ex0 + tlp.width + 16; ey1 = ey0 + tlp.height + 16;
        if (panel != null && panel.isAttachedToWindow()) {
            int[] l = new int[2];
            panel.getLocationOnScreen(l);
            px0 = l[0] - 8; py0 = l[1] - 8; px1 = l[0] + panel.getWidth() + 8; py1 = l[1] + panel.getHeight() + 8;
        }
    }

    final Runnable tick = new Runnable() {
        @Override public void run() {
            if (wm == null) return;
            updateRects();
            hd.postDelayed(this, 400);
        }
    };

    // Калибровка цвета метки: кольцо наводится на метку, цвет берётся из центра
    void onColorButton(Button b) {
        if (ring == null) {
            ring = new Ring();
            toast("Наведите кольцо на красную метку врага и нажмите «Взять»");
            b.setText("Взять");
        } else {
            pickX = rlp.x + ox + ring.S / 2f;
            pickY = rlp.y + oy + ring.S / 2f;
            pickPending = true;
            try { wm.removeView(ring); } catch (Exception e) {}
            ring = null;
            b.setText("Цвет");
        }
    }

    DisplayMetrics real() {
        DisplayMetrics m = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(m);
        return m;
    }

    void startAuto(int code, Intent data) {
        auto = true;
        ht = new HandlerThread("cap"); ht.start();
        ch = new Handler(ht.getLooper());
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        mp = mpm.getMediaProjection(code, data);
        mp.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { hd.post(() -> stopSelf()); }
        }, ch);
        DisplayMetrics m = real();
        realW = m.widthPixels; realH = m.heightPixels; dpi = m.densityDpi;
        int cw = Math.max(16, realW / SCALE), chh = Math.max(16, realH / SCALE);
        reader = ImageReader.newInstance(cw, chh, PixelFormat.RGBA_8888, 2);
        vd = mp.createVirtualDisplay("cap", cw, chh, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, ch);
        reader.setOnImageAvailableListener(this::onFrame, ch);
    }

    // При повороте экрана пересоздаём размер записи
    @Override public void onConfigurationChanged(Configuration nc) {
        super.onConfigurationChanged(nc);
        if (auto && vd != null) hd.postDelayed(this::resizeCapture, 400);
        hd.postDelayed(this::fixOrigin, 800);
    }

    void resizeCapture() {
        try {
            DisplayMetrics m = real();
            realW = m.widthPixels; realH = m.heightPixels; dpi = m.densityDpi;
            int cw = Math.max(16, realW / SCALE), chh = Math.max(16, realH / SCALE);
            ImageReader old = reader;
            reader = ImageReader.newInstance(cw, chh, PixelFormat.RGBA_8888, 2);
            reader.setOnImageAvailableListener(this::onFrame, ch);
            vd.resize(cw, chh, dpi);
            vd.setSurface(reader.getSurface());
            if (old != null) old.close();
        } catch (Exception e) {}
    }

    void onFrame(ImageReader rd) {
        Image im = null;
        try {
            im = rd.acquireLatestImage();
            if (im == null) return;
            long t = System.currentTimeMillis();
            if (t - lastT < 120) return;
            lastT = t;
            Image.Plane pl = im.getPlanes()[0];
            ByteBuffer bb = pl.getBuffer();
            int rs = pl.getRowStride(), ps = pl.getPixelStride(), w = im.getWidth(), h = im.getHeight();
            float kx = realW / (float) w, ky = realH / (float) h;
            if (pickPending) {
                int cx = (int) (pickX / kx), cy = (int) (pickY / ky), sr = 0, sg = 0, sb = 0, n = 0;
                for (int y = Math.max(0, cy - 1); y <= Math.min(h - 1, cy + 1); y++)
                    for (int x = Math.max(0, cx - 1); x <= Math.min(w - 1, cx + 1); x++) {
                        int o = y * rs + x * ps;
                        sr += bb.get(o) & 255; sg += bb.get(o + 1) & 255; sb += bb.get(o + 2) & 255; n++;
                    }
                if (n > 0) { tg = sg / n; tb = sb / n; tr = sr / n; calT = t; lastSeen = 0; lostShown = false; }
                pickPending = false;
                toast("Цвет взят");
            } else if (detOn && tr >= 0) {
                detect(bb, rs, ps, w, h, kx, ky, t);
            }
        } catch (Exception e) {
        } finally { if (im != null) im.close(); }
    }

    // Ищем широкую тонкую полоску цвета метки, берём самую большую
    void detect(ByteBuffer bb, int rs, int ps, int w, int h, float kx, float ky, long t) {
        if (mask == null || mask.length != w * h) { mask = new byte[w * h]; stack = new int[w * h]; }
        int tol0 = tol, r0 = tr, g0 = tg, b0 = tb;
        int e0 = (int) (ex0 / kx), f0 = (int) (ey0 / ky), e1 = (int) (ex1 / kx), f1 = (int) (ey1 / ky);
        int q0 = (int) (px0 / kx), s0 = (int) (py0 / ky), q1 = (int) (px1 / kx), s1 = (int) (py1 / ky);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                byte v = 0;
                boolean inT = x >= e0 && x <= e1 && y >= f0 && y <= f1;
                boolean inP = x >= q0 && x <= q1 && y >= s0 && y <= s1;
                if (!inT && !inP) {
                    int o = y * rs + x * ps;
                    int dd = Math.abs((bb.get(o) & 255) - r0) + Math.abs((bb.get(o + 1) & 255) - g0) + Math.abs((bb.get(o + 2) & 255) - b0);
                    if (dd <= tol0) v = 1;
                }
                mask[y * w + x] = v;
            }
        }
        int minW = Math.max(6, w / 80), bestArea = 0, bx0 = 0, bx1 = 0, by1 = 0;
        boolean found = false;
        for (int y0 = 0; y0 < h; y0++) {
            for (int x0 = 0; x0 < w; x0++) {
                int i0 = y0 * w + x0;
                if (mask[i0] != 1) continue;
                int sp2 = 0, area = 0, minx = x0, maxx = x0, miny = y0, maxy = y0;
                stack[sp2++] = i0; mask[i0] = 2;
                while (sp2 > 0) {
                    int c = stack[--sp2], cx = c % w, cy = c / w;
                    area++;
                    if (cx < minx) minx = cx;
                    if (cx > maxx) maxx = cx;
                    if (cy < miny) miny = cy;
                    if (cy > maxy) maxy = cy;
                    if (cx > 0 && mask[c - 1] == 1) { mask[c - 1] = 2; stack[sp2++] = c - 1; }
                    if (cx < w - 1 && mask[c + 1] == 1) { mask[c + 1] = 2; stack[sp2++] = c + 1; }
                    if (cy > 0 && mask[c - w] == 1) { mask[c - w] = 2; stack[sp2++] = c - w; }
                    if (cy < h - 1 && mask[c + w] == 1) { mask[c + w] = 2; stack[sp2++] = c + w; }
                }
                int bw = maxx - minx + 1, bh = maxy - miny + 1;
                if (bw >= minW && bw >= 2 * bh && area >= bw * bh / 2 && area > bestArea) {
                    bestArea = area; bx0 = minx; bx1 = maxx; by1 = maxy; found = true;
                }
            }
        }
        if (found) {
            final float cxs = (bx0 + bx1 + 1) / 2f * kx, bot = (by1 + 1) * ky;
            lostShown = false; lastSeen = t;
            hd.post(() -> follow(cxs, bot));
        } else if (!lostShown && t - Math.max(lastSeen, calT) > 4000) {
            lostShown = true;
            toast("Метка не найдена: проверьте цвет и допуск");
        }
    }

    // Ставим схему под найденную метку, чуть сглаживая рывки
    void follow(float cx, float bot) {
        if (wm == null || !detOn) return;
        float tx = cx - tlp.width / 2f, ty = bot + 0.08f * tlp.height;
        if (smx < 0) { smx = tx; smy = ty; } else { smx += 0.5f * (tx - smx); smy += 0.5f * (ty - smy); }
        tlp.x = Math.round(smx - ox); tlp.y = Math.round(smy - oy);
        try { wm.updateViewLayout(tv, tlp); } catch (Exception e) {}
        updateRects();
    }

    @Override public void onDestroy() {
        super.onDestroy();
        hd.removeCallbacksAndMessages(null);
        try { if (vd != null) vd.release(); } catch (Exception e) {}
        try { if (reader != null) reader.close(); } catch (Exception e) {}
        try { if (mp != null) mp.stop(); } catch (Exception e) {}
        if (ht != null) ht.quitSafely();
        if (wm == null) return;
        try { wm.removeView(tv); } catch (Exception e) {}
        try { wm.removeView(panel); } catch (Exception e) {}
        if (ring != null) try { wm.removeView(ring); } catch (Exception e) {}
        wm = null;
    }

    // Кольцо для наведения на метку: центр прозрачный, чтобы цвет брался из игры
    class Ring extends View {
        final int S = (int) (64 * d);
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float dx, dy;
        Ring() {
            super(OverlayService.this);
            rlp = new LayoutParams(S, S, wtype(),
                LayoutParams.FLAG_NOT_FOCUSABLE | LayoutParams.FLAG_NOT_TOUCH_MODAL | LayoutParams.FLAG_LAYOUT_NO_LIMITS | LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
            rlp.gravity = Gravity.TOP | Gravity.LEFT;
            rlp.x = Math.max(0, realW / 2 - S / 2);
            rlp.y = Math.max(0, realH / 3 - S / 2);
            wm.addView(this, rlp);
        }
        @Override protected void onDraw(Canvas c) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(6 * d); p.setColor(0xFF000000);
            c.drawCircle(S / 2f, S / 2f, S / 2f - 5 * d, p);
            p.setStrokeWidth(3 * d); p.setColor(0xFFFFFFFF);
            c.drawCircle(S / 2f, S / 2f, S / 2f - 5 * d, p);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: dx = e.getRawX() - rlp.x; dy = e.getRawY() - rlp.y; return true;
                case MotionEvent.ACTION_MOVE:
                    rlp.x = (int) (e.getRawX() - dx); rlp.y = (int) (e.getRawY() - dy);
                    wm.updateViewLayout(this, rlp);
                    return true;
                default: return true;
            }
        }
    }

    class TankView extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float dx, dy;
        TankView() { super(OverlayService.this); p.setTextSize(9f); }

        void box(Canvas c, float x, float y, float w, float h, int col, boolean fill) {
            if (fill) {
                p.setStyle(Paint.Style.FILL); p.setColor((col & 0x00FFFFFF) | 0xB0000000);
                c.drawRoundRect(x, y, x + w, y + h, 3, 3, p);
            }
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(fill ? 1.2f : 1.8f); p.setColor(col);
            c.drawRoundRect(x, y, x + w, y + h, 3, 3, p);
        }

        @Override protected void onDraw(Canvas c) {
            c.save();
            c.scale(sc, sc);
            c.save();
            if (flip) { c.translate(200, 0); c.scale(-1, 1); }
            float[] b = BODY[type];
            box(c, 6, 74, 188, 20, GRY, true);
            box(c, b[0], b[1], b[2], b[3], 0xFFFFFFFF, false);
            box(c, b[4], b[5], b[6], b[7], 0xFFFFFFFF, false);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3f); p.setColor(0xFFFFFFFF);
            c.drawLine(b[4] + b[6], b[8], 200, b[8], p);
            for (P q : PARTS[type]) box(c, q.x, q.y, q.w, q.h, q.c, true);
            c.restore();
            p.setStyle(Paint.Style.FILL); p.setColor(0x88000000);
            c.drawRect(0, 100, 200, 130, p);
            float[] lx = {2, 66, 124, 2, 66};
            float[] ly = {112, 112, 112, 124, 124};
            for (int i = 0; i < 5; i++) {
                p.setStyle(Paint.Style.FILL); p.setColor(LEG_C[i]);
                c.drawRect(lx[i], ly[i] - 8, lx[i] + 8, ly[i], p);
                p.setColor(0xFFFFFFFF);
                c.drawText(LEG_T[i], lx[i] + 11, ly[i], p);
            }
            c.restore();
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dx = e.getRawX() - tlp.x; dy = e.getRawY() - tlp.y; smx = -1;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    tlp.x = (int) (e.getRawX() - dx); tlp.y = (int) (e.getRawY() - dy);
                    wm.updateViewLayout(this, tlp);
                    return true;
                case MotionEvent.ACTION_UP:
                    save(); updateRects();
                    return true;
                default:
                    return true;
            }
        }
    }
}
