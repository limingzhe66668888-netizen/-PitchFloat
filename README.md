# 悬浮音高 PitchFloat

看视频（B站等）时，以悬浮窗实时显示视频里**歌声的音高**：当前音符、频率、
音分偏差、滚动的音高曲线。适合跟唱练习、视唱训练、"这个音到底多高"的即时解答。

## ⬇️ 直接下载安装（不想折腾开发环境）

**[点此下载 PitchFloat.apk](../../releases/latest)**（约 37KB，Android 10 及以上）

安装步骤：

1. 手机上打开上面的下载链接（或先下到电脑再传到手机）
2. 点击 APK 安装 → 若提示"禁止安装未知应用"，在弹窗里选"设置"并允许本来源
3. 打开「悬浮音高」App
4. 点 **① 授予悬浮窗权限** → 系统设置里允许"显示在其他应用上层"
5. 声音来源选 **内录（视频声音）** → 点 **③ 开始** → 系统弹窗选 **立即开始**
   （只采集音频做分析，不录屏、不保存任何声音）
6. 切到 B站 播放视频，悬浮窗就会出现音高曲线
7. 悬浮窗操作：**左半边拖动**=移动位置；**右半边上下拖**=滚动音域；**右上角 ✕**=关闭

> 曲线不动且底部电平条为 0？说明该视频 App 禁止了内录（YouTube、部分音乐 App），
> 请改选「麦克风」模式并**外放**声音，任何 App 都适用（戴耳机时无效）。

## 自己构建

需要 JDK 17 + Android SDK（`platforms;android-35`、`build-tools;35.0.0`），
**不需要** Android Studio 或 Gradle：

```bash
ANDROID_SDK=/path/to/sdk ./build.sh      # 产物 PitchFloat.apk
```

详见下方「构建」章节。

---


## 特性

- **内部录音**（AudioPlaybackCapture）：直接采集正在播放的媒体声音——不录屏、
  不保存任何音频。适用于允许内录的 App（B站等）；YouTube 等禁止内录的 App
  可切换**麦克风模式**（外放）兜底
- **立体声中置人声分离**：流行乐人声混在声场正中、乐器分列左右——频域逐 bin
  做 `|C| = max(0, |Mid| − β|Side|)`（Audacity 人声提取同源算法），先剥掉大部分伴奏
- **YIN 音高检测 + 八度仲裁**：全域两级倍频折叠，对付"检测值低一个八度"的顽疾
- **人声轨迹稳定器**：滑率约束（人声不可能瞬移八度）、大跳持续性门控 +
  置信度双门槛、5 帧中值滤波、气口保持——抑制"混音里检测在声源间乱跳"
- **悬浮窗**：左半边拖动移动位置，右半边上下拖滚动音域（高音超出视野时用），
  底部输入电平条用于诊断
- **无 Gradle / 无 Android Studio 构建**：aapt2 + javac + d8 + zipalign + apksigner，
  全部代码自研，零第三方依赖，APK 约 37KB

## 流水线

```
立体声内录(AudioPlaybackCapture)
  → 中置人声分离 (STFT 2048 / Hann / hop 512 / β=0.85, 延迟 ~46ms)
  → 105Hz 高通 → YIN 基频检测 (90–1000Hz, 亚八度折叠 + 倍频仲裁)
  → 轨迹稳定器 (滑率 ±2.5 半音/帧, ±12 八度纠错与恢复, 中值滤波)
  → 悬浮窗绘制 (~43fps)
```

麦克风模式为单声道直通（无人声分离），其余相同。

## 构建

前置：JDK 17、Android SDK（`platforms;android-35`、`build-tools;35.0.0`）、Python 3。

```bash
ANDROID_SDK=/path/to/sdk VER_CODE=1 VER_NAME=1.0 ./build.sh
```

## 使用

1. 安装 `PitchFloat.apk`，打开 App：① 授予悬浮窗权限（系统设置 → 该应用 →
   显示在其他应用上层）
2. 声音来源选"内录（视频声音）"→ ③ 开始 → 系统弹窗选"立即开始"
3. 切到视频 App 播放，悬浮窗出现音高曲线；拖动移动，右上角 ✕ 关闭
4. 曲线无反应且电平条为 0 → 该 App 禁止内录，改选"麦克风"并外放

## PC 端算法测试

```bash
javac -d build/test src/com/pitchfloat/{VocalIsolator,Yin,VocalTracker}.java \
      test/com/pitchfloat/TestIsolator.java
java -cp build/test com.pitchfloat.TestIsolator
```

合成"中置人声 + 立体声乐器"信号，验证分离器无越界、人声保留率、乐器抑制倍数。

## 已知限制

- 分离依赖立体声混音；单声道内容退化为 YIN + 启发式
- 复杂混音下偶有跟丢/跳八度（单 F0 检测的固有限制）
- 麦克风模式必须外放，戴耳机收不到

## 许可

MIT（见 LICENSE）。测试用的合成信号与算法实现均为本项目原创。

---

## English quick start

**PitchFloat** shows the pitch of the singing voice in the video you're watching,
in a small floating overlay — note name, frequency, cents deviation, and a rolling
pitch curve. Built for singing practice and ear training on Android 10+.

**Install (no build needed):** download `PitchFloat.apk` from
[Releases](../../releases/latest), install it, grant the "display over other apps"
permission, choose **Internal audio capture**, tap **Start**, and approve the
system dialog (audio only — nothing is recorded or saved). Then play a video in
any capture-allowed app (e.g. Bilibili).

**Notes**
- Apps that block playback capture (YouTube, most music apps) show a flat meter —
  switch to **Microphone** mode and play the audio out loud instead.
- Overlay controls: drag the **left half** to move it; drag the **right half**
  up/down to scroll the pitch range; tap **✕** to close.

**Pipeline:** stereo playback capture → center-channel vocal isolation
(`|C| = max(0, |Mid| − β|Side|)`, the Audacity-style approach) → 105 Hz high-pass
→ YIN with sub-octave arbitration → vocal trajectory tracker (slew-rate limiting,
jump gating, median filtering) → overlay at ~43 fps.

**Build:** JDK 17 + Android SDK (`platforms;android-35`, `build-tools;35.0.0`),
no Gradle or Android Studio needed: `ANDROID_SDK=/path/to/sdk ./build.sh`.

MIT licensed.
