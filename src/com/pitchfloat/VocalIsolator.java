package com.pitchfloat;

import java.util.Arrays;

/**
 * 人声分离：中置声道提取（Center Channel Extraction）。
 * Audacity『Vocal Reduction and Isolation』、Adobe Audition『Center Channel
 * Extractor』所用的经典立体声人声提取法——流行音乐人声通常混在声场正中，
 * 乐器分列左右：
 *
 *   Mid  = (L+R)/2   —— 含人声 + 中置低频
 *   Side = (L−R)/2   —— 基本只有偏离声场的乐器
 *   频域逐 bin：|C_k| = max(0, |Mid_k| − β·|Side_k|)，相位沿用 Mid_k
 *
 * 实现：STFT，N=2048，Hann 分析+合成窗，75% 重叠相加（hop=512），
 * 输出比输入延迟 N 个样本（约 46ms）。纯 float 运算，无依赖。
 */
final class VocalIsolator {

    private static final int N = 2048;
    private static final int HOP = 512;
    /** 侧声道抑制强度：0=不分离，1=完全减去 */
    private static final float BETA = 0.85f;
    /** Hann² 在 hop=N/4 下的重叠相加归一化 */
    private static final float OLA_NORM = 1.5f;

    private final float[] win = new float[N];
    private final float[] bufL = new float[N];
    private final float[] bufR = new float[N];
    private final float[] re = new float[N];
    private final float[] im = new float[N];
    private final float[] sre = new float[N];
    private final float[] sim = new float[N];
    private final float[] acc = new float[N];

    VocalIsolator() {
        for (int i = 0; i < N; i++) {
            win[i] = (float) (0.5 * (1.0 - Math.cos(2.0 * Math.PI * (i + 1) / N)));
        }
    }

    /**
     * 处理固定 HOP(512) 帧立体声（从 in[inOff] 起读交错 L,R），
     * 输出 HOP 个分离后的人声样本到 out[outOff]。
     */
    void process(short[] in, int inOff, float[] out, int outOff) {
        // 滑窗左移，尾部填入新数据
        System.arraycopy(bufL, HOP, bufL, 0, N - HOP);
        System.arraycopy(bufR, HOP, bufR, 0, N - HOP);
        for (int i = 0; i < HOP; i++) {
            bufL[N - HOP + i] = in[inOff + 2 * i] / 32768f;
            bufR[N - HOP + i] = in[inOff + 2 * i + 1] / 32768f;
        }

        // Mid / Side 加窗后各自 FFT
        for (int i = 0; i < N; i++) {
            float l = bufL[i] * win[i];
            float r = bufR[i] * win[i];
            re[i] = 0.5f * (l + r);
            sre[i] = 0.5f * (l - r);
            im[i] = 0f;
            sim[i] = 0f;
        }
        fft(re, im);
        fft(sre, sim);

        // 中置提取：缩放 Mid 幅度（保留相位）
        for (int k = 0; k < N; k++) {
            float mm = (float) Math.hypot(re[k], im[k]);
            float sm = (float) Math.hypot(sre[k], sim[k]);
            float c = mm - BETA * sm;
            if (c < 0f) c = 0f;
            float scale = mm > 1e-12f ? c / mm : 0f;
            re[k] *= scale;
            im[k] *= scale;
        }

        // 逆 FFT（交换实虚前后各一次的技巧）
        swap(re, im);
        fft(re, im);
        swap(re, im);

        // 合成窗 + 重叠相加；最早的 HOP 个样本已收敛，输出之
        float invN = 1f / N;
        for (int i = 0; i < N; i++) {
            acc[i] += re[i] * invN * win[i];
        }
        for (int i = 0; i < HOP; i++) {
            out[outOff + i] = acc[i] / OLA_NORM;
        }
        System.arraycopy(acc, HOP, acc, 0, N - HOP);
        Arrays.fill(acc, N - HOP, N, 0f);
    }

    private static void swap(float[] a, float[] b) {
        for (int i = 0; i < a.length; i++) {
            float t = a[i]; a[i] = b[i]; b[i] = t;
        }
    }

    /** 迭代 radix-2 FFT，原位计算 */
    private static void fft(float[] re, float[] im) {
        final int n = re.length;
        // 位反转置换
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j |= bit;
            if (i < j) {
                float t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        // 蝶形
        for (int len = 2; len <= n; len <<= 1) {
            final float ang = (float) (-2.0 * Math.PI / len);
            final float wpr = (float) Math.cos(ang);
            final float wpi = (float) Math.sin(ang);
            final int half = len >> 1;
            for (int i = 0; i < n; i += len) {
                float cwr = 1f, cwi = 0f;
                for (int k = 0; k < half; k++) {
                    final int x = i + k, y = x + half;
                    final float tr = re[y] * cwr - im[y] * cwi;
                    final float ti = re[y] * cwi + im[y] * cwr;
                    re[y] = re[x] - tr; im[y] = im[x] - ti;
                    re[x] += tr;        im[x] += ti;
                    final float nr = cwr * wpr - cwi * wpi;
                    cwi = cwr * wpi + cwi * wpr;
                    cwr = nr;
                }
            }
        }
    }
}
