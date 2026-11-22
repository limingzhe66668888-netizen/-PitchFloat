package com.pitchfloat;

/**
 * YIN 基频(音高)估计器 — de Cheveigné & Kawahara (2002) 的精简实现。
 * 自包含、无依赖；输入 PCM short 窗口，输出 Hz（无效时 -1）。
 *
 * 用法：每帧调用 push(newSamples, n)，内部维护滑动窗口。
 * 结果：返回值 f0（-1 = 无声/不可靠），并更新公有字段 lastClarity / lastRms。
 */
public final class Yin {

    /** cmnd' 低于该值即认为找到周期（原论文推荐 0.1~0.15） */
    private static final float THRESHOLD = 0.15f;
    /** 兜底搜索失败时，cmnd' 全局最小值高于此 → 判为不可靠 */
    private static final float FALLBACK_MAX = 0.55f;
    /** 静音门限（RMS，相对满幅） */
    private static final float SILENCE_RMS = 0.004f;

    private final float sampleRate;
    private final int w;              // 窗口长度（样本数）
    private final int tauMin, tauMax; // 滞后搜索范围
    private final float[] raw;        // 原始滑动窗口
    private final float[] buf;        // 高通滤波后的窗口
    private final float[] diff;       // d(tau)
    private final float[] cmnd;       // d'(tau)
    private float hpX = 0, hpY = 0;   // 高通滤波器状态（跨帧连续）

    public float lastClarity = 0f;
    public float lastRms = 0f;

    public Yin(float sampleRate, int window, float fmin, float fmax) {
        this.sampleRate = sampleRate;
        this.w = window;
        this.tauMin = Math.max(2, Math.round(sampleRate / fmax));
        this.tauMax = Math.min(w / 2 - 1, Math.round(sampleRate / fmin));
        raw = new float[w];
        buf = new float[w];
        diff = new float[tauMax + 2];
        cmnd = new float[tauMax + 2];
    }

    /**
     * 推入 n 个新样本（帧移 = n），对整个窗口做检测。样本为 float，范围约 ±1。
     * @return 基频 Hz；无声或不可靠时返回 -1
     */
    public float push(float[] add, int n) {
        lastClarity = 0f;
        if (n <= 0 || n > w) return -1;

        // 滑动窗口左移，尾部填入新样本
        System.arraycopy(raw, n, raw, 0, w - n);
        float sum2 = 0f;
        for (int i = 0; i < n; i++) {
            float s = add[i];
            raw[w - n + i] = s;
            sum2 += s * s;
        }

        // 一阶 RC 高通 (~105Hz)：压掉低音贝斯/底鼓的基频，保留 A2 以上的人声
        final float rc = 1f / (float) (2 * Math.PI * 105);
        final float alpha = rc / (rc + 1f / sampleRate);
        for (int i = w - n; i < w; i++) {
            float y = alpha * (hpY + raw[i] - hpX);
            hpX = raw[i];
            hpY = y;
            buf[i] = y;
        }

        // RMS（对整个原始窗口）
        float total = 0f;
        for (int i = 0; i < w - n; i++) total += raw[i] * raw[i];
        lastRms = (float) Math.sqrt((total + sum2) / w);
        if (lastRms < SILENCE_RMS) return -1;

        // 差分函数 d(tau) = sum (buf[i]-buf[i+tau])^2
        for (int tau = 1; tau <= tauMax; tau++) {
            float d = 0f;
            for (int i = 0, len = w - tau; i < len; i++) {
                float e = buf[i] - buf[i + tau];
                d += e * e;
            }
            diff[tau] = d;
        }

        // 累积均值归一化 d'(tau)
        cmnd[0] = 1f;
        float run = 0f;
        for (int tau = 1; tau <= tauMax; tau++) {
            run += diff[tau];
            cmnd[tau] = run > 0f ? diff[tau] * tau / run : 1f;
        }

        // 绝对阈值：第一个低于阈值的 tau，再滑到局部极小
        int tauStar = -1;
        for (int tau = tauMin; tau <= tauMax; tau++) {
            if (cmnd[tau] < THRESHOLD) {
                while (tau + 1 <= tauMax && cmnd[tau + 1] < cmnd[tau]) tau++;
                tauStar = tau;
                break;
            }
        }
        if (tauStar < 0) {
            float best = Float.MAX_VALUE;
            for (int tau = tauMin; tau <= tauMax; tau++) {
                if (cmnd[tau] < best) { best = cmnd[tau]; tauStar = tau; }
            }
            if (best > FALLBACK_MAX) return -1;
        }

        // —— 八度仲裁（全域两级，最多连折两次，即最多 ×4）——
        // A. 强证据：半周期(τ/2)处周期性很好(cmnd'≤0.30) → 上折，不限音域。
        // B. 中等证据 + 中低音域：cmnd'≤0.45 且目标 ≤ C5 → 上折。
        //    人声低于 ~C5 时的"低八度"误差多源于残留中置低频/贝斯（分离后仍有），
        //    证据中等即可上折；高音区维持严格门槛，防泛音鬼影。
        // 统一规则取代此前"低于C3才直达倍频"的频段补丁——每修一个八度音域
        // 就在下一档冒出同类问题（A2→A3→F4/G4 逐档上移），根因相同。
        for (int guard = 0; guard < 2; guard++) {
            int half = tauStar / 2;
            if (half < tauMin) break;
            float fUp = sampleRate / half;
            float ch = cmnd[half];
            boolean fold = ch <= 0.30f || (ch <= 0.45f && fUp <= 523f);
            if (!fold) break;
            tauStar = half;
        }

        // 抛物线插值细化
        float better = tauStar;
        if (tauStar > 1 && tauStar < tauMax) {
            float s0 = cmnd[tauStar - 1], s1 = cmnd[tauStar], s2 = cmnd[tauStar + 1];
            float denom = 2f * (2f * s1 - s2 - s0);
            if (denom != 0f) better = tauStar + (s2 - s0) / denom;
        }

        lastClarity = 1f - cmnd[tauStar];
        float f0 = sampleRate / better;
        if (f0 < 55f || f0 > 1100f) return -1;
        return f0;
    }
}
