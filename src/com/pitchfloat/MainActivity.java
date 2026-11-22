package com.pitchfloat;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

public class MainActivity extends Activity {

    private static final int REQ_CAPTURE = 1;
    private static final int REQ_PERMS = 2;

    private TextView status;
    private TextView errText;
    private Button copyErrBtn;
    private Button overlayBtn, mainBtn;
    private RadioGroup sourceGroup;
    private int captureId, micId;
    private boolean waitingPerm = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(30), dp(20), dp(24));
        scroll.addView(root);
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("悬浮音高");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        add(root, title, 0);

        TextView desc = new TextView(this);
        desc.setText("看视频时，悬浮窗实时显示视频里歌声的音高。\n"
                + "默认『内录』模式：采集正在播放的媒体声音（不录屏、不保存任何音频）。");
        desc.setTextSize(14);
        desc.setLineSpacing(dp(2), 1f);
        add(root, desc, 10);

        status = new TextView(this);
        status.setTextSize(13);
        add(root, status, 12);

        errText = new TextView(this);
        errText.setTextSize(12);
        errText.setTextColor(0xFFFF8A80);
        errText.setMovementMethod(new ScrollingMovementMethod());
        errText.setVisibility(View.GONE);
        add(root, errText, 8);

        copyErrBtn = new Button(this);
        copyErrBtn.setText("复制错误详情发给开发者");
        copyErrBtn.setVisibility(View.GONE);
        copyErrBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager)
                        getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("PitchFloat error", PitchService.lastError));
                Toast.makeText(MainActivity.this, "已复制，粘贴发送即可", Toast.LENGTH_SHORT).show();
            }
        });
        add(root, copyErrBtn, 4);

        overlayBtn = new Button(this);
        overlayBtn.setText("① 授予悬浮窗权限");
        overlayBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            }
        });
        add(root, overlayBtn, 6);

        TextView srcLabel = new TextView(this);
        srcLabel.setText("② 声音来源");
        srcLabel.setTextSize(15);
        srcLabel.setTypeface(Typeface.DEFAULT_BOLD);
        add(root, srcLabel, 14);

        sourceGroup = new RadioGroup(this);
        sourceGroup.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton rbCap = new RadioButton(this);
        rbCap.setText("内录（视频声音）");
        captureId = View.generateViewId();
        rbCap.setId(captureId);
        RadioButton rbMic = new RadioButton(this);
        rbMic.setText("麦克风（需外放）");
        micId = View.generateViewId();
        rbMic.setId(micId);
        sourceGroup.addView(rbCap);
        sourceGroup.addView(rbMic);
        sourceGroup.check(captureId);
        add(root, sourceGroup, 2);

        mainBtn = new Button(this);
        mainBtn.setTextSize(17);
        mainBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onMainClicked(); }
        });
        add(root, mainBtn, 12);

        TextView tips = new TextView(this);
        tips.setTextSize(12);
        tips.setText("说明：\n"
                + "· 点『开始』后系统会弹『立即开始/开始投射』确认框——本应用只采集音频做本地分析，"
                + "不录制屏幕、不保存声音。\n"
                + "· 悬浮窗：左半边拖动 = 移动位置；右半边上下拖 = 滚动音域"
                + "（高音超出视野时往下拖）；右上角 ✕ 关闭。\n"
                + "· 人声锁定：内置八度纠错 + 大跳过滤 + 中值平滑；复杂伴奏下仍偶有跳动属正常。\n"
                + "· 若内录模式下曲线毫无反应且底部电平条为 0，说明该视频 App 禁止了内录"
                + "（如 YouTube、部分音乐 App），请改选『麦克风』并外放。\n"
                + "· 荣耀手机保活：系统设置 → 应用 → 悬浮音高 → 启动管理 → 手动管理，"
                + "允许后台活动与关联启动。");
        add(root, tips, 10);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean hasOverlay = Settings.canDrawOverlays(this);
        status.setText(hasOverlay
                ? "悬浮窗权限：已授予 ✓" : "悬浮窗权限：未授予（点下方按钮去系统设置打开）");
        status.setTextColor(hasOverlay ? 0xFF81C784 : 0xFFFF8A80);
        overlayBtn.setVisibility(hasOverlay ? View.GONE : View.VISIBLE);
        mainBtn.setText(PitchService.running ? "■ 停止" : "③ 开始");
        String err = PitchService.lastError;
        if (err != null) {
            errText.setText("上次错误：\n" + err);
            errText.setVisibility(View.VISIBLE);
            copyErrBtn.setVisibility(View.VISIBLE);
        } else {
            errText.setVisibility(View.GONE);
            copyErrBtn.setVisibility(View.GONE);
        }
        if (waitingPerm && hasOverlay) {
            waitingPerm = false;
            Toast.makeText(this, "悬浮窗权限已授予，可以点开始了", Toast.LENGTH_SHORT).show();
        }
    }

    private void onMainClicked() {
        if (PitchService.running) {
            startService(new Intent(this, PitchService.class).setAction(PitchService.ACTION_STOP));
            mainBtn.postDelayed(new Runnable() {
                @Override public void run() { refresh(); }
            }, 400);
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            waitingPerm = true;
            Toast.makeText(this, "请先授予悬浮窗权限（系统设置里允许\"显示在其他应用上层\"）",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ArrayList<String> ps = new ArrayList<>();
            ps.add(Manifest.permission.RECORD_AUDIO);
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                       != PackageManager.PERMISSION_GRANTED) {
                ps.add(Manifest.permission.POST_NOTIFICATIONS);
            }
            requestPermissions(ps.toArray(new String[0]), REQ_PERMS);
            return;
        }
        if (sourceGroup.getCheckedRadioButtonId() == micId) {
            startWith(0, null);
        } else {
            MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_PERMS) {
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) {
                onMainClicked();
            } else {
                Toast.makeText(this, "需要录音权限（内录采集在系统里也算录音权限）",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode == RESULT_OK && data != null) {
            startWith(resultCode, data);
        } else {
            Toast.makeText(this, "未授权音频采集", Toast.LENGTH_SHORT).show();
        }
    }

    private void startWith(int code, Intent data) {
        Intent i = new Intent(this, PitchService.class)
                .setAction(PitchService.ACTION_START)
                .putExtra("mic", sourceGroup.getCheckedRadioButtonId() == micId);
        if (data != null) {
            i.putExtra("code", code);
            i.putExtra("data", (android.os.Parcelable) data);
        }
        startForegroundService(i);
        moveTaskToBack(true); // 直接去干别的，比如打开B站
    }

    private void add(LinearLayout root, View child, int marginTopDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(marginTopDp);
        root.addView(child, lp);
    }

    private int dp(float v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
