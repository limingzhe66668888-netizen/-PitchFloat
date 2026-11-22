package com.pitchfloat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Toast;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 前台服务：持有内录(MediaProjection)或麦克风，跑 YIN，把结果推给悬浮窗。
 */
public class PitchService extends Service {

    public static volatile boolean running = false;
    /** 最近一次失败的完整错误（供主界面显示/复制） */
    public static volatile String lastError = null;
    public static final String ACTION_START = "com.pitchfloat.START";
    public static final String ACTION_STOP = "com.pitchfloat.STOP";
    private static final String TAG = "PitchFloat";
    private static final String CHANNEL_ID = "pitch_service";
    private static final int NOTIF_ID = 1;

    private static final int SR = 44100;   // 采样率
    private static final int WIN = 2048;   // 分析窗口 (~46ms)
    private static final int HOP = 1024;   // 帧移 (~23ms → 每秒约43次更新)

    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private final Handler main = new Handler(Looper.getMainLooper());

    private WindowManager wm;
    private OverlayView view;
    private WindowManager.LayoutParams lp;
    private MediaProjection projection;
    private AudioRecord record;
    private Thread audioThread;
    private boolean micMode = false;
    /** 实际跑起来的采集是否为立体声（决定人声分离是否启用） */
    private boolean audioStereo = false;

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) { shutdown(); return START_NOT_STICKY; }
        if (running) return START_NOT_STICKY;

        micMode = intent != null && intent.getBooleanExtra("mic", false);
        int code = intent == null ? 0 : intent.getIntExtra("code", 0);
        @SuppressWarnings("deprecation")
        Intent data = intent == null ? null : (Intent) intent.getParcelableExtra("data");

        lastError = null;
        startForeground(NOTIF_ID, buildNotification(), fgsType());

        if (!micMode) {
            if (data == null) { toast("缺少采集授权，请回到主界面重新开始"); shutdown(); return START_NOT_STICKY; }
            MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            try {
                projection = mpm.getMediaProjection(code, data);
            } catch (Throwable t) {
                Log.e(TAG, "getMediaProjection", t);
            }
            if (projection == null) { toast("未获得内录授权"); shutdown(); return START_NOT_STICKY; }
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { shutdown(); }
            }, main);
        }

        if (!setupOverlay()) { shutdown(); return START_NOT_STICKY; }
        if (!startAudio()) { shutdown(); return START_NOT_STICKY; }
        if (view != null) {
            view.setModeLabel(micMode ? "麦克风"
                    : (audioStereo ? "内录·人声分离" : "内录·单声道"));
        }

        running = true;
        return START_NOT_STICKY;
    }

    /** Android 14+ 要求按模式声明前台服务类型 */
    private int fgsType() {
        if (!micMode) return ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
        if (Build.VERSION.SDK_INT >= 30) return ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        return 0;
    }

    private boolean setupOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            lastError = "Settings.canDrawOverlays() == false：悬浮窗权限未真正生效。\n"
                    + "请在 设置→应用→悬浮音高→权限 中单独打开『悬浮窗』开关"
                    + "（部分荣耀系统里『显示在其他应用上层』与『悬浮窗』是两个开关）。";
            toast("没有悬浮窗权限：请在系统设置中允许后重试");
            return false;
        }
        // 首选 window context（Android 12+ 官方推荐）；失败自动换服务 context 再试一次
        try {
            addOverlay(Build.VERSION.SDK_INT >= 31
                    ? createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                    : this);
            return true;
        } catch (Throwable t1) {
            Log.e(TAG, "setupOverlay(windowContext)", t1);
            try {
                addOverlay(this);
                return true;
            } catch (Throwable t2) {
                Log.e(TAG, "setupOverlay(serviceContext)", t2);
                lastError = describe(t2);
                toast("悬浮窗创建失败：" + t2);
                return false;
            }
        }
    }

    private void addOverlay(Context ctx) {
        WindowManager w = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        OverlayView v = new OverlayView(ctx);
        v.setModeLabel(micMode ? "麦克风" : "内录");
        v.setListener(new OverlayView.Listener() {
            @Override public void onClose() { shutdown(); }
        });
        float density = getResources().getDisplayMetrics().density;
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        p.x = Math.round(16 * density);
        p.y = Math.round(150 * density);
        v.bind(w, p);
        w.addView(v, p);
        this.wm = w;
        this.view = v;
        this.lp = p;
    }

    /** 异常 → 可读文本（含堆栈前几帧，方便定位） */
    private static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder(t.toString());
        StackTraceElement[] st = t.getStackTrace();
        for (int i = 0; i < Math.min(st.length, 12); i++) {
            sb.append("\n    at ").append(st[i]);
        }
        Throwable c = t.getCause();
        int depth = 0;
        while (c != null && depth++ < 3) {
            sb.append("\nCaused by: ").append(c);
            StackTraceElement[] cs = c.getStackTrace();
            for (int i = 0; i < Math.min(cs.length, 8); i++) {
                sb.append("\n    at ").append(cs[i]);
            }
            c = c.getCause();
        }
        return sb.toString();
    }

    private boolean startAudio() {
        if (micMode) return startAudioChannels(false);
        // 内录用立体声采集，配合中置人声分离；失败回退单声道（无分离）
        if (startAudioChannels(true)) return true;
        toast("立体声采集不可用，已回退单声道（无人声分离）");
        return startAudioChannels(false);
    }

    private boolean startAudioChannels(final boolean stereo) {
        try {
            int mask = stereo ? AudioFormat.CHANNEL_IN_STEREO : AudioFormat.CHANNEL_IN_MONO;
            AudioFormat fmt = new AudioFormat.Builder()
                    .setSampleRate(SR)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(mask)
                    .build();
            int minBuf = AudioRecord.getMinBufferSize(SR, mask, AudioFormat.ENCODING_PCM_16BIT);
            AudioRecord.Builder b = new AudioRecord.Builder()
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(Math.max(minBuf * 2, WIN * 4));
            if (micMode) {
                b.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION);
            } else {
                AudioPlaybackCaptureConfiguration cfg =
                        new AudioPlaybackCaptureConfiguration.Builder(projection)
                                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                                .build();
                b.setAudioPlaybackCaptureConfig(cfg);
            }
            record = b.build();
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                if (!micMode && stereo) return false;   // 交由上层回退
                toast(micMode ? "麦克风初始化失败" : "内录初始化失败，可改用麦克风模式");
                return false;
            }
            record.startRecording();
            // 部分机型（荣耀等）立体声内录会"初始化成功但实际没开始录音"，
            // 之后 read() 抛异常。这里做状态检查 + 试探读取，失败自动回退单声道。
            int rstate = record.getRecordingState();
            audioStereo = stereo;
            if (rstate != AudioRecord.RECORDSTATE_RECORDING || !probeRead(record)) {
                try { record.stop(); } catch (Throwable ignored) {}
                try { record.release(); } catch (Throwable ignored) {}
                record = null;
                if (!micMode && stereo) return false;   // 交由上层回退
                lastError = "录音无法启动（state=" + rstate + "，"
                        + (rstate == AudioRecord.RECORDSTATE_RECORDING ? "试探读取无数据" : "未进入录音态") + "）";
                toast("录音启动失败");
                return false;
            }
            final AudioRecord r = record;
            audioThread = new Thread(new Runnable() {
                @Override public void run() { audioLoop(r, stereo); }
            }, "pitch-audio");
            audioThread.setPriority(Thread.MAX_PRIORITY);
            audioThread.start();
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "startAudio", t);
            if (!micMode && stereo) return false;
            toast("音频启动失败：" + t.getMessage());
            return false;
        }
    }

    /** 试探读取：非阻塞读 1 帧，400ms 内有数据即认为录音真的在跑 */
    private boolean probeRead(AudioRecord r) {
        short[] tmp = new short[2];
        long deadline = android.os.SystemClock.uptimeMillis() + 400;
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            try {
                int n = r.read(tmp, 0, 2, AudioRecord.READ_NON_BLOCKING);
                if (n == 2) return true;
                if (n < 0) return false;
                Thread.sleep(20);
            } catch (Throwable t) {
                Log.e(TAG, "probeRead", t);
                return false;
            }
        }
        return false;
    }

    private void audioLoop(AudioRecord r, boolean stereo) {
        String stage = "init";
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
            final int nch = stereo ? 2 : 1;
            short[] buf = new short[HOP * nch];
            float[] frame = new float[HOP];
            Yin yin = new Yin(SR, WIN, 90f, 1000f);
            VocalTracker tracker = new VocalTracker();
            VocalIsolator iso = stereo ? new VocalIsolator() : null;

            long count = 0;
            stage = "read";
            while (!stopping.get()) {
                int off = 0;
                while (off < buf.length) {
                    int n = r.read(buf, off, buf.length - off);
                    if (n < 0) return;
                    off += n;
                }
                float levelRms;
                if (iso != null) {
                    stage = "isolate";
                    // 隔离器内部步长 512 帧：本轮 1024 帧分两次喂。
                    // 偏移单位是 short：512 帧 = 1024 个 short。
                    iso.process(buf, 0, frame, 0);
                    iso.process(buf, 1024, frame, 512);
                    // 电平用分离前的原始混音（诊断"有没有采到声音"更直观）
                    double s2 = 0;
                    for (int i = 0; i < HOP; i++) {
                        double v = (buf[2 * i] + buf[2 * i + 1]) * (1.0 / 65536.0);
                        s2 += v * v;
                    }
                    levelRms = (float) Math.sqrt(s2 / HOP);
                } else {
                    stage = "convert";
                    double s2 = 0;
                    for (int i = 0; i < HOP; i++) {
                        float v = buf[i] / 32768f;
                        frame[i] = v;
                        s2 += v * v;
                    }
                    levelRms = (float) Math.sqrt(s2 / HOP);
                }

                stage = "detect";
                float f0raw = yin.push(frame, HOP);
                float f0 = tracker.push(f0raw, yin.lastClarity);
                count++;
                stage = "display";
                if (view != null) view.push(f0, tracker.getOutClarity(), levelRms);
                if (count % 170 == 0) {
                    Log.i(TAG, "mode=" + (micMode ? "mic" : (stereo ? "capture+iso" : "capture"))
                            + " rms=" + String.format(Locale.US, "%.4f", levelRms)
                            + " f0=" + (f0 > 0 ? String.format(Locale.US, "%.1f", f0) : "-")
                            + " clarity=" + String.format(Locale.US, "%.2f", tracker.getOutClarity()));
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "audioLoop@" + stage, t);
            lastError = "音频线程异常[" + stage + "]：" + describe(t);
            toast("音频处理出错，已停止");
            shutdown();
        }
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "音高监测", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("悬浮窗音高监测运行中");
        nm.createNotificationChannel(ch);
        PendingIntent pi = PendingIntent.getService(this, 1,
                new Intent(this, PitchService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("悬浮音高 · 运行中")
                .setContentText("点按此通知停止")
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    private void shutdown() {
        if (!stopping.compareAndSet(false, true)) return;
        running = false;
        main.post(new Runnable() {
            @Override public void run() {
                try { if (view != null && wm != null) wm.removeView(view); } catch (Throwable ignored) {}
                view = null;
                wm = null;
            }
        });
        try { if (record != null) record.stop(); } catch (Throwable ignored) {}
        try { if (record != null) record.release(); } catch (Throwable ignored) {}
        record = null;
        try { if (projection != null) projection.stop(); } catch (Throwable ignored) {}
        projection = null;
        stopForeground(Service.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void toast(String s) {
        final String msg = s;
        main.post(new Runnable() {
            @Override public void run() { Toast.makeText(PitchService.this, msg, Toast.LENGTH_LONG).show(); }
        });
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }
}
