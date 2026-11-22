package com.pitchfloat;

/**
 * 人声音高稳定器：对 YIN 的原始输出做后处理，抑制"混音里人声+伴奏导致
 * 检测在声源间乱跳"的问题：
 *
 * ① 八度纠错——跳了约 ±12 半音视为八度误差，折回当前八度
 * ② 大跳过滤——偏离轨迹 >6 半音的新值须连续出现 4 帧才切换（伴奏串扰多为瞬时）
 * ③ 中值滤波——5 帧中值，杀单帧毛刺
 * ④ 短暂保持——丢失后维持最后音高约 0.28 秒（气口/换气不断线）
 */
final class VocalTracker {

    private static final float MIN_CLARITY = 0.50f;
    private static final int MEDIAN_N = 5;
    private static final int JUMP_PERSIST = 4;
    private static final int HOLD_FRAMES = 12;
    /** 保持期/等待期输出的合成置信度（视图里显示为黄色"不太可靠"） */
    private static final float HOLD_CLARITY = 0.62f;

    private final float[] med = new float[MEDIAN_N];
    private int medFill = 0;
    private float trackSt = Float.NaN;   // 当前锁定轨迹（半音）
    private float candSt = Float.NaN;    // 大跳候选
    private int candCount = 0;
    private float candClaritySum = 0f;   // 候选帧的置信度累计
    private int foldUpCount = 0;         // 连续"+12半音被折回"的帧数
    private int hold = 0;

    private float outClarity = 0f;

    /** 输入一帧原始检测，返回稳定后的 f0（-1 = 无声） */
    float push(float f0, float clarity) {
        outClarity = 0f;
        boolean voiced = f0 > 0f && clarity >= MIN_CLARITY;

        if (!voiced) {
            if (!Float.isNaN(trackSt) && hold < HOLD_FRAMES) {
                hold++;
                outClarity = HOLD_CLARITY;
                return medianF0();          // 气口：维持
            }
            trackSt = Float.NaN;
            candCount = 0;
            foldUpCount = 0;
            medFill = 0;
            return -1f;
        }

        float st = 69f + 12f * (float) (Math.log(f0 / 440.0) / Math.log(2.0));

        if (Float.isNaN(trackSt)) {
            // 长休止后重新起步：置信度门槛略高于常规，防止伴奏抢走新轨迹
            // （A2~A3 频段伴奏密集、置信度偏低，门槛不宜过高）
            if (clarity < 0.56f) return -1f;
            trackSt = st;
        } else {
            float d = st - trackSt;
            // ① 八度误差
            if (Math.abs(d) > 10.5f && Math.abs(d) < 14.5f) {
                if (d > 0f) {
                    // 原始读数高八度：若持续 6 帧都这样，说明轨迹整体低了八度，
                    // 整体上移（也覆盖旋律中真实的八度大跳）；偶发则折回
                    foldUpCount++;
                    if (foldUpCount >= 6) {
                        trackSt += 12f;
                        foldUpCount = 0;
                    } else {
                        st -= 12f;
                    }
                } else {
                    st += 12f;   // 原始读数低八度：折回（罕见，保守处理）
                    foldUpCount = 0;
                }
                d = st - trackSt;
                candCount = 0;
                candClaritySum = 0f;
            }
            // ② 大跳：持续性 + 平均置信度双门槛（伴奏串扰置信度普遍偏低）
            if (Math.abs(d) > 6f) {
                if (candCount > 0 && Math.abs(st - candSt) < 1.5f) {
                    candCount++;
                    candClaritySum += clarity;
                } else {
                    candSt = st;
                    candCount = 1;
                    candClaritySum = clarity;
                }
                float candAvg = candClaritySum / Math.max(1, candCount);
                if (candCount < JUMP_PERSIST || candAvg < 0.68f) {
                    if (hold < HOLD_FRAMES) {
                        hold++;
                        outClarity = HOLD_CLARITY;
                        return medianF0();  // 证据不足：维持原轨迹
                    }
                    trackSt = Float.NaN;    // 维持超时：宁可重找，不跟弱证据
                    candCount = 0;
                    candClaritySum = 0f;
                    return -1f;
                } else {
                    trackSt = candSt;       // 持续且置信度够：真的换音区了
                    candCount = 0;
                    candClaritySum = 0f;
                    medFill = 0;
                }
            } else {
                candCount = 0;
                candClaritySum = 0f;
                foldUpCount = 0;
                // ③ 滑率约束：人声持续发声时不可能瞬移大跨度（每秒极限也就十几
                // 半音）。每帧限速 ±2.5 半音 ≈ 108 半音/秒——远宽于人声真实能力，
                // 但足以把瞬时八度/大跳类误差物理性地压住；真实的旋律大跳会以
                // 3~4 帧平滑跟上（约 70~90ms，视觉无感）。
                float slew = d < -2.5f ? -2.5f : (d > 2.5f ? 2.5f : d);
                st = trackSt + slew;
                trackSt += (st - trackSt) * 0.5f;  // 平滑滑音（只用于跳变判定）
            }
        }

        pushMedian(st);
        hold = 0;
        outClarity = clarity;
        return medianF0();                  // ③ 中值输出
    }

    float getOutClarity() { return outClarity; }

    private void pushMedian(float v) {
        if (medFill < MEDIAN_N) {
            med[medFill++] = v;
        } else {
            System.arraycopy(med, 1, med, 0, MEDIAN_N - 1);
            med[MEDIAN_N - 1] = v;
        }
    }

    private float median() {
        if (medFill == 0) return Float.isNaN(trackSt) ? 55f : trackSt;
        float[] c = new float[medFill];
        System.arraycopy(med, 0, c, 0, medFill);
        java.util.Arrays.sort(c);
        return c[medFill / 2];
    }

    private float medianF0() {
        return 440f * (float) Math.pow(2, (median() - 69) / 12);
    }
}
