package com.pitchfloat;

/** PC 端验证 VocalIsolator（不上手机）：合成"中置人声+立体声乐器"，量测提取效果。 */
public class TestIsolator {

    static double clamp(double v) { return Math.max(-1, Math.min(1, v)); }

    /** Goertzel 测单频幅值，取信号中段 1 秒（避开 OLA 建立期） */
    static double amp(float[] x, int sr, double f) {
        double coeff = 2 * Math.cos(2 * Math.PI * f / sr);
        double s1 = 0, s2 = 0;
        int from = x.length / 3, to = from + sr;
        for (int i = from; i < to; i++) {
            double s0 = x[i] + coeff * s1 - s2;
            s2 = s1; s1 = s0;
        }
        double power = s1 * s1 + s2 * s2 - coeff * s1 * s2;
        return Math.sqrt(Math.max(0, power)) / (to - from) * 2;
    }

    public static void main(String[] args) {
        int sr = 44100, total = sr * 3;

        // 中置"人声"：220Hz 基频 + 泛音；"乐器"：330Hz，左右不同相位（立体声定位）
        short[] stereo = new short[total * 2];
        for (int i = 0; i < total; i++) {
            double t = i / (double) sr;
            double vocal = 0.5 * Math.sin(2 * Math.PI * 220 * t)
                    + 0.2 * Math.sin(2 * Math.PI * 440 * t)
                    + 0.1 * Math.sin(2 * Math.PI * 660 * t);
            double instL = 0.6 * Math.sin(2 * Math.PI * 330 * t + 0.5);
            double instR = 0.6 * Math.sin(2 * Math.PI * 330 * t + 2.1);
            stereo[2 * i] = (short) (clamp(vocal + instL) * 32767);
            stereo[2 * i + 1] = (short) (clamp(vocal + instR) * 32767);
        }
        float[] mid = new float[total];
        for (int i = 0; i < total; i++) {
            mid[i] = (stereo[2 * i] + stereo[2 * i + 1]) * (1f / 65536f);
        }

        VocalIsolator iso = new VocalIsolator();
        float[] out = new float[total];
        short[] chunk = new short[1024];
        float[] fo = new float[512];
        for (int p = 0; p * 512 + 512 <= total; p++) {
            System.arraycopy(stereo, p * 1024, chunk, 0, 1024);
            iso.process(chunk, 0, fo, 0);
            System.arraycopy(fo, 0, out, p * 512, 512);
        }
        for (int i = 0; i < total; i++) {
            if (Float.isNaN(out[i]) || Float.isInfinite(out[i])) {
                System.out.println("FAIL: NaN/Inf at " + i); return;
            }
        }

        // —— 服务层调用模式回归测试：每轮 1024 立体声帧（2048 short）分两次喂 ——
        try {
            VocalIsolator iso2 = new VocalIsolator();
            short[] big = new short[2048];
            float[] frame = new float[1024];
            for (int p = 0; p * 1024 + 1024 <= total; p++) {
                System.arraycopy(stereo, p * 2048, big, 0, 2048);
                iso2.process(big, 0, frame, 0);
                iso2.process(big, 1024, frame, 512);
            }
            System.out.println("服务层双chunk模式: OK（无越界）");
        } catch (Throwable t) {
            System.out.println("FAIL: 服务层双chunk模式异常 " + t);
            return;
        }

        double vKeep = amp(out, sr, 220) / amp(mid, sr, 220);
        double iSupp = amp(mid, sr, 330) / Math.max(1e-9, amp(out, sr, 330));
        System.out.printf("人声220Hz 保留率 = %.0f%%\n", 100 * vKeep);
        System.out.printf("乐器330Hz 抑制倍数 = %.1fx\n", iSupp);

        // 分离前后各跑一遍 YIN+Tracker
        runPipe("未分离(mid)", mid, sr);
        runPipe("分离后   ", out, sr);

        boolean pass = vKeep > 0.55 && iSupp > 5;
        System.out.println(pass ? "== PASS ==" : "== FAIL ==");
    }

    static void runPipe(String tag, float[] x, int sr) {
        Yin yin = new Yin(sr, 2048, 90f, 1000f);
        VocalTracker tr = new VocalTracker();
        int tot = 0, hit = 0;
        float[] hop = new float[512];
        for (int p = 0; p * 512 + 512 <= x.length; p++) {
            System.arraycopy(x, p * 512, hop, 0, 512);
            float f0 = tr.push(yin.push(hop, 512), yin.lastClarity);
            if (f0 > 0) {
                tot++;
                if (Math.abs(f0 - 220) < 3) hit++;
            }
        }
        System.out.printf("%s: 检出帧 %d, 其中≈220Hz 占 %.0f%%\n", tag, tot, 100.0 * hit / Math.max(1, tot));
    }
}
