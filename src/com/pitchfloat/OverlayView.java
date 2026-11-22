package com.pitchfloat;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * 悬浮窗视图：左侧大字显示当前音符/频率/音分偏差，右侧滚动音高曲线，
 * 底部输入电平条（用于诊断"内录被拒"）。可整体拖动，右上角 ✕ 关闭。
 */
public class OverlayView extends View {

    public interface Listener { void onClose(); }

    private static final int HIST = 240;        // 保留帧数 (~5.6 秒)
    private static final int SPAN = 26;         // 可见音域宽度（半音数）
    private static final String[] NAMES =
            {"C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B"};

    private final float density;
    private final Paint pBg, pGrid, pGridS, pGridT, pTraceGood, pTraceMid, pDot, pRing,
            pNote, pSub, pCents, pBadge, pHint, pLevelBg, pLevelFill, pClose, pGrip;

    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private Listener listener;
    private volatile String modeLabel = "内录";

    private final float[] semis = new float[HIST];
    private final float[] clars = new float[HIST];
    private final boolean[] voiced = new boolean[HIST];
    private int head = 0;
    private float latestF0 = -1f, latestClarity = 0f, latestRms = 0f;
    private float center = 55f;   // 视图中心（半音/MIDI），初始 A3 附近

    private float downX, downY, lastRawY;
    private int startX, startY;
    private boolean moved;
    /** 触摸分区：false=左半边(移动窗口) true=右半边(上下拖滚动音域) */
    private boolean scrollZone = false;
    /** 手动滚动后暂停自动跟随到这个时刻 */
    private long manualUntil = 0L;

    public OverlayView(Context c) {
        super(c);
        density = getResources().getDisplayMetrics().density;

        pBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        pBg.setColor(0xEE141A26);

        pGrid = new Paint(Paint.ANTI_ALIAS_FLAG);
        pGrid.setColor(0x1FFFFFFF);
        pGrid.setStrokeWidth(1f);

        pGridS = new Paint(Paint.ANTI_ALIAS_FLAG);
        pGridS.setColor(0x46FFFFFF);
        pGridS.setStrokeWidth(Math.max(2f, density));

        pGridT = new Paint(Paint.ANTI_ALIAS_FLAG);
        pGridT.setColor(0x66FFFFFF);
        pGridT.setTextSize(8 * density);

        pTraceGood = new Paint(Paint.ANTI_ALIAS_FLAG);
        pTraceGood.setColor(0xFF6CE5A0);
        pTraceGood.setStrokeWidth(Math.max(2.5f, 2 * density));
        pTraceGood.setStrokeCap(Paint.Cap.ROUND);

        pTraceMid = new Paint(Paint.ANTI_ALIAS_FLAG);
        pTraceMid.setColor(0xFFFFD166);
        pTraceMid.setStrokeWidth(Math.max(2.5f, 2 * density));
        pTraceMid.setStrokeCap(Paint.Cap.ROUND);

        pDot = new Paint(Paint.ANTI_ALIAS_FLAG);
        pDot.setColor(0xFF64D2FF);

        pRing = new Paint(Paint.ANTI_ALIAS_FLAG);
        pRing.setColor(0xFFFFFFFF);
        pRing.setStyle(Paint.Style.STROKE);
        pRing.setStrokeWidth(1.5f * density);

        pNote = new Paint(Paint.ANTI_ALIAS_FLAG);
        pNote.setColor(0xFFFFFFFF);
        pNote.setTextSize(32 * density);
        pNote.setTypeface(android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));

        pSub = new Paint(Paint.ANTI_ALIAS_FLAG);
        pSub.setColor(0xB8FFFFFF);
        pSub.setTextSize(11 * density);

        pCents = new Paint(Paint.ANTI_ALIAS_FLAG);
        pCents.setTextSize(13 * density);

        pBadge = new Paint(Paint.ANTI_ALIAS_FLAG);
        pBadge.setColor(0x8CFFFFFF);
        pBadge.setTextSize(9 * density);

        pHint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pHint.setColor(0x90FFFFFF);
        pHint.setTextSize(11 * density);
        pHint.setTextAlign(Paint.Align.CENTER);

        pLevelBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        pLevelBg.setColor(0x26FFFFFF);

        pLevelFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        pLevelFill.setColor(0xFF64D2FF);

        pClose = new Paint(Paint.ANTI_ALIAS_FLAG);
        pClose.setColor(0x99FFFFFF);
        pClose.setTextSize(13 * density);
        pClose.setTextAlign(Paint.Align.CENTER);

        pGrip = new Paint(Paint.ANTI_ALIAS_FLAG);
        pGrip.setColor(0x50FFFFFF);
        pGrip.setTextSize(11 * density);
        pGrip.setTextAlign(Paint.Align.RIGHT);

        for (int i = 0; i < HIST; i++) semis[i] = Float.NaN;
    }

    public void bind(WindowManager w, WindowManager.LayoutParams p) { wm = w; lp = p; }
    public void setListener(Listener l) { listener = l; }
    public void setModeLabel(String s) { modeLabel = s; }

    private int d(float v) { return Math.round(v * density); }

    /** 音频线程调用 */
    public void push(float f0, float clarity, float rms) {
        synchronized (this) {
            latestRms = rms;
            boolean ok = f0 > 0f && clarity >= 0.50f && f0 >= 70f && f0 <= 1050f;
            int idx = head;
            if (ok) {
                float st = 69f + 12f * (float) (Math.log(f0 / 440.0) / Math.log(2.0));
                semis[idx] = st;
                clars[idx] = clarity;
                voiced[idx] = true;
                if (SystemClock.uptimeMillis() >= manualUntil) {
                    float dd = st - center;
                    center += dd * (Math.abs(dd) > 11f ? 0.35f : 0.05f);
                }
                latestF0 = f0;
                latestClarity = clarity;
            } else {
                semis[idx] = Float.NaN;
                voiced[idx] = false;
                latestF0 = -1f;
            }
            head = (head + 1) % HIST;
        }
        postInvalidate();
    }

    @Override
    protected void onMeasure(int wms, int hms) {
        setMeasuredDimension(d(282), d(132));
    }

    @Override
    protected void onDraw(Canvas cv) {
        int W = getWidth(), H = getHeight();
        cv.drawRoundRect(0, 0, W, H, d(14), d(14), pBg);

        float x0 = d(104), x1 = W - d(8);
        float y0 = d(18), y1 = H - d(16);
        float cxm = (x0 + x1) / 2f, cym = (y0 + y1) / 2f;

        String noteText, hzText, centsText;
        int centsColor;

        synchronized (this) {
            // ---- 音域网格 ----
            int stLow = (int) Math.floor(center - SPAN / 2f);
            int stHigh = (int) Math.ceil(center + SPAN / 2f);
            for (int st = stLow; st <= stHigh; st++) {
                float y = yOf(st, cym, y0, y1);
                if (y < y0 || y > y1) continue;
                if (st % 12 == 0) {
                    cv.drawLine(x0, y, x1, y, pGridS);
                    String lab = name(st);
                    pGridT.setTextAlign(Paint.Align.LEFT);
                    cv.drawText(lab, x0 + d(2), y - d(2), pGridT);
                } else {
                    cv.drawLine(x0, y, x1, y, pGrid);
                }
            }

            // ---- 音高曲线（旧→新，从左到右） ----
            float step = (x1 - x0) / (HIST - 1);
            float px = 0, py = 0;
            boolean prev = false;
            for (int i = 0; i < HIST; i++) {
                int idx = (head + i) % HIST;
                float x = x0 + step * i;
                if (voiced[idx]) {
                    float y = yOf(semis[idx], cym, y0, y1);
                    if (prev) {
                        Paint tp = clars[idx] >= 0.78f ? pTraceGood : pTraceMid;
                        cv.drawLine(px, py, x, y, tp);
                    }
                    px = x; py = y; prev = true;
                } else {
                    prev = false;
                }
            }
            // 当前点
            int lastIdx = (head + HIST - 1) % HIST;
            if (voiced[lastIdx]) {
                float y = yOf(semis[lastIdx], cym, y0, y1);
                cv.drawCircle(x1 - d(1), y, d(3.5f), pDot);
                cv.drawCircle(x1 - d(1), y, d(5.5f), pRing);
            }

            // ---- 左侧文字 ----
            if (latestF0 > 0) {
                float st = 69f + 12f * (float) (Math.log(latestF0 / 440.0) / Math.log(2.0));
                int midi = Math.round(st);
                float cents = (st - midi) * 100f;
                noteText = name(midi);
                hzText = String.format(java.util.Locale.US, "%.1f Hz", latestF0);
                centsText = String.format(java.util.Locale.US, "%+.0f¢", cents);
                float ac = Math.abs(cents);
                centsColor = ac <= 15 ? 0xFF6CE5A0 : (ac <= 35 ? 0xFFFFD166 : 0xFFFF8A80);
            } else {
                noteText = "–";
                hzText = "";
                centsText = "";
                centsColor = 0x66FFFFFF;
            }
            pNote.setColor(0xFFFFFFFF);
            cv.drawText(noteText, d(14), d(52), pNote);
            cv.drawText(hzText, d(14), d(74), pSub);
            pCents.setColor(centsColor);
            cv.drawText(centsText, d(14), d(96), pCents);

            // ---- 模式徽标 ----
            pBadge.setColor(latestRms >= 0.005f ? 0xFF6CE5A0 : 0x70FF8A80);
            cv.drawCircle(d(110), d(14), d(2.6f), pBadge);
            pBadge.setColor(0x8CFFFFFF);
            cv.drawText(modeLabel, d(117), d(18), pBadge);

            // ---- 等待提示 ----
            int voicedRecent = 0;
            for (int i = HIST - 150; i < HIST; i++) {
                if (voiced[(head + i + HIST * 2) % HIST]) voicedRecent++;
            }
            if (voicedRecent == 0) {
                cv.drawText("等待声音…", cxm, cym - d(2), pHint);
                if (latestRms < 0.005f) {
                    pHint.setColor(0x60FFFFFF);
                    cv.drawText("无输入：该App可能禁止内录，试试麦克风模式", cxm, cym + d(16), pHint);
                    pHint.setColor(0x90FFFFFF);
                }
            }

            // ---- 底部电平条 ----
            float ly = H - d(9), lx0 = d(10), lx1 = W - d(10);
            cv.drawRect(lx0, ly, lx1, ly + d(3), pLevelBg);
            float lvl = Math.min(1f, latestRms / 0.12f);
            if (lvl > 0.01f) {
                pLevelFill.setColor(lvl > 0.85f ? 0xFFFF8A80
                        : (lvl > 0.45f ? 0xFFFFD166 : 0xFF64D2FF));
                cv.drawRect(lx0, ly, lx0 + (lx1 - lx0) * lvl, ly + d(3), pLevelFill);
            }
        }

        // ---- 关闭按钮（右上角） + 右缘滚动提示 ----
        cv.drawText("✕", W - d(16), d(19), pClose);
        cv.drawText("↕", W - d(3), (y0 + y1) / 2 + d(4), pGrip);
    }

    private float yOf(float st, float cym, float y0, float y1) {
        float half = SPAN / 2f;
        float t = (st - center) / half;
        if (t > 1.15f) t = 1.15f;
        if (t < -1.15f) t = -1.15f;
        return cym - t * ((y1 - y0) / 2f);
    }

    private static String name(int midi) {
        int m = ((midi % 12) + 12) % 12;
        return NAMES[m] + (midi / 12 - 1);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (wm == null || lp == null) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getRawX();
                downY = e.getRawY();
                lastRawY = downY;
                startX = lp.x;
                startY = lp.y;
                moved = false;
                scrollZone = e.getX() >= getWidth() * 0.5f;
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                if (Math.abs(dx) > d(6) || Math.abs(dy) > d(6)) moved = true;
                if (scrollZone) {
                    // 右半边：上下拖 = 滚动音域（曲线跟随手指方向移动：
                    // 往下拖→曲线下移→更高的音进入视野）
                    float edy = e.getRawY() - lastRawY;
                    lastRawY = e.getRawY();
                    float traceH = Math.max(1f, getHeight() - d(34));
                    float spp = SPAN / traceH;   // 每像素对应半音数
                    synchronized (this) { center += edy * spp; }
                    manualUntil = SystemClock.uptimeMillis() + 6000L;
                    invalidate();
                } else {
                    // 左半边：移动窗口
                    lp.x = startX + Math.round(dx);
                    lp.y = startY + Math.round(dy);
                    try { wm.updateViewLayout(this, lp); } catch (Throwable ignored) {}
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!moved && e.getX() > getWidth() - d(36) && e.getY() < d(36)
                        && listener != null) {
                    listener.onClose();
                }
                return true;
        }
        return super.onTouchEvent(e);
    }
}
