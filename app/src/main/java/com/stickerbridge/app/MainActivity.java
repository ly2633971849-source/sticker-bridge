package com.stickerbridge.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.ImageDecoder;
import android.graphics.Typeface;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Menu;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private static final java.util.concurrent.Executor PREVIEW_IO = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final int BG = 0xff111816, CARD = 0xff1b2722, FG = 0xffeff7f2;
    private static final int MUTED = 0xffa7bbb0, ACCENT = 0xff8cf0b8, NOTICE = 0xffebcf91;
    private LinearLayout content, taskCard, lastCard;
    private TextView status, detail, primaryHint, lastTitle, lastFormat, previewPlaceholder;
    private Button primary, more, viewLast;
    private ProgressBar progress;
    private ImageView preview;
    private boolean resumed;
    private String renderedLast = "";
    private AnimatedImageDrawable animation;
    private final Runnable stateListener = this::refresh;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        State.restore(this);
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        render();
        if (saved == null) accept(getIntent());
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        State.listener = stateListener;
        if (BridgeAccessibility.live != null) BridgeAccessibility.live.refreshBubble();
        refresh();
        if (animation != null) animation.start();
        if (!State.busy) State.MAIN.postDelayed(stateListener, 350);
    }

    @Override protected void onPause() {
        resumed = false;
        State.MAIN.removeCallbacks(stateListener);
        if (State.listener == stateListener) State.listener = null;
        if (animation != null) animation.stop();
        super.onPause();
    }

    @Override protected void onDestroy() {
        State.MAIN.removeCallbacks(stateListener);
        if (animation != null) animation.stop();
        if (State.listener == stateListener) State.listener = null;
        super.onDestroy();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        accept(intent);
    }

    private void accept(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri == null && intent.getClipData() != null && intent.getClipData().getItemCount() > 0)
            uri = intent.getClipData().getItemAt(0).getUri();
        if (uri != null) Jobs.convertShared(this, uri);
        else State.finish(this, "没有收到原表情文件", "这个分享只带了文字或链接，请分享图片文件。");
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(dp(3), 1f);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private void setText(TextView view, String value) {
        if (!value.contentEquals(view.getText())) view.setText(value);
    }

    private TextView label(LinearLayout parent, String value, int size, int color,
                           boolean bold, int bottom) {
        TextView view = text(value, size, color, bold);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(bottom);
        parent.addView(view, params);
        return view;
    }

    private LinearLayout card() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(18), dp(18), dp(18));
        box.setBackground(rounded(CARD, 22));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(16);
        content.addView(box, params);
        return box;
    }

    private Button button(String title, boolean prominent, Runnable action) {
        Button view = new Button(this);
        view.setText(title);
        view.setAllCaps(false);
        view.setTextSize(prominent ? 17 : 15);
        view.setTextColor(prominent ? BG : FG);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setMinWidth(0);
        view.setMinimumWidth(0);
        view.setMinHeight(dp(prominent ? 56 : 48));
        view.setMinimumHeight(dp(prominent ? 56 : 48));
        view.setPadding(dp(16), dp(12), dp(16), dp(12));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x338cf0b8),
                rounded(prominent ? ACCENT : 0xff2d4035, 16), null));
        view.setOnClickListener(v -> { if (canAct()) action.run(); });
        return view;
    }

    private Button addButton(LinearLayout parent, String title, boolean prominent, Runnable action) {
        Button view = button(title, prominent, action);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(10);
        parent.addView(view, params);
        return view;
    }

    private void available(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : .5f);
    }

    private boolean canAct() {
        if (!State.busy) return true;
        Jobs.toast(this, "正在保存，请稍候");
        return false;
    }

    private void openDouyin() {
        if (!canAct()) return;
        State.prefs(this).edit().putBoolean("enabled", true).apply();
        BridgeAccessibility service = BridgeAccessibility.live;
        if (service == null) {
            PickerSettings.open(this);
            return;
        }
        service.rebuildBubble();
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage("com.ss.android.ugc.aweme");
            if (launch != null) startActivity(launch);
            else Jobs.toast(this, "这台手机还没有安装抖音");
        } catch (Exception error) {
            Jobs.toast(this, "请手动打开抖音，再点绿色“选”按钮");
        }
    }

    private void showMore() {
        PopupMenu popup = new PopupMenu(this, more);
        Menu menu = popup.getMenu();
        menu.add(0, 6, 0, "检查更新");
        menu.add(0, 7, 1, "复制诊断信息");
        menu.add(0, 5, 2, "使用帮助");
        popup.setOnMenuItemClickListener(item -> {
            if (!canAct()) return true;
            switch (item.getItemId()) {
                case 5: showHelp(); break;
                case 6: AppUpdates.show(this); break;
                case 7: showDiagnostics(); break;
                default: return false;
            }
            return true;
        });
        popup.show();
    }

    private void showDiagnostics() {
        String summary = Diagnostics.summary(this);
        new AlertDialog.Builder(this).setTitle("诊断信息")
                .setMessage(summary)
                .setNegativeButton("关闭", null)
                .setPositiveButton("复制", (dialog, button) -> {
                    android.content.ClipboardManager clipboard = getSystemService(android.content.ClipboardManager.class);
                    if (clipboard != null) clipboard.setPrimaryClip(android.content.ClipData.newPlainText("表情直达诊断", summary));
                }).show();
    }

    private void showHelp() {
        new AlertDialog.Builder(this)
                .setTitle("怎么保存表情")
                .setMessage("1. 点首页按钮，按提示开启“表情直达 · 框选保存”。"
                        + "\n\n2. 在抖音点绿色“选”，再点表情。也可以把“选”拖到目标内松手。"
                        + "\n\n3. 保存后，在 QQ 或微信的表情收藏中，从手机相册手动添加。文件在相册的 StickerBridge 文件夹。"
                        + "\n\n读不到原件时，如果出现「保存静态图」，可以保存当前这一帧。"
                        + "\n\n悬浮按钮不见时，返回首页再点一次上方按钮。")
                .setPositiveButton("知道了", null)
                .show();
    }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(16), dp(22), dp(24));
        scroll.addView(content);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout());
            content.setPadding(dp(22) + bars.left, dp(12) + bars.top,
                    dp(22) + bars.right, dp(24) + bars.bottom);
            return insets;
        });
        setContentView(scroll);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(text("表情直达", 20, FG, true), new LinearLayout.LayoutParams(0, -2, 1));
        more = button("更多", false, this::showMore);
        more.setTextSize(14);
        more.setTextColor(MUTED);
        more.setBackground(new RippleDrawable(ColorStateList.valueOf(0x338cf0b8), rounded(BG, 14), null));
        more.setContentDescription("更多更新与帮助");
        header.addView(more, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(-1, -2);
        headerParams.bottomMargin = dp(26);
        content.addView(header, headerParams);

        label(content, "把表情存进相册", 28, FG, true, 12);
        label(content, "框选抖音表情，保存后从相册添加到 QQ 或微信。", 14, MUTED, false, 12);
        primary = addButton(content, "开启选取", true, this::openDouyin);
        primaryHint = label(content, "", 13, MUTED, false, 0);
        primaryHint.setPadding(dp(2), dp(12), dp(2), dp(2));
        taskCard = card();
        status = label(taskCard, "", 18, FG, true, 6);
        detail = label(taskCard, "", 13, MUTED, false, 0);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(ColorStateList.valueOf(ACCENT));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(0xff354b3e));
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, dp(5));
        progressParams.topMargin = dp(14);
        taskCard.addView(progress, progressParams);

        lastCard = card();
        label(lastCard, "最近保存", 12, ACCENT, true, 12);
        LinearLayout lastRow = new LinearLayout(this);
        lastRow.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout thumbnail = new FrameLayout(this);
        thumbnail.setBackground(rounded(0xff14201a, 16));
        thumbnail.setClipToOutline(true);
        previewPlaceholder = text("预览\n不可用", 13, MUTED, false);
        previewPlaceholder.setGravity(Gravity.CENTER);
        thumbnail.addView(previewPlaceholder, new FrameLayout.LayoutParams(-1, -1));
        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setContentDescription("最近保存的表情预览");
        preview.setPadding(dp(5), dp(5), dp(5), dp(5));
        thumbnail.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        lastRow.addView(thumbnail, new LinearLayout.LayoutParams(dp(92), dp(92)));
        LinearLayout lastInfo = new LinearLayout(this);
        lastInfo.setOrientation(LinearLayout.VERTICAL);
        lastInfo.setPadding(dp(16), 0, 0, 0);
        lastTitle = label(lastInfo, "已保存到相册", 17, FG, true, 7);
        lastFormat = label(lastInfo, "", 13, MUTED, false, 0);
        lastRow.addView(lastInfo, new LinearLayout.LayoutParams(0, -2, 1));
        lastCard.addView(lastRow, new LinearLayout.LayoutParams(-1, -2));
        TextView instruction = label(lastCard, "去 QQ 或微信的表情收藏，从相册添加。", 13, MUTED, false, 0);
        instruction.setPadding(0, dp(14), 0, 0);
        viewLast = addButton(lastCard, "在相册中查看", false, this::viewLast);
        updateStatus();
    }

    private void bindLast(JSONObject last) {
        String identity = last.optLong("jobId") + "|" + last.optString("output") + "|" + last.optString("mediaUri");
        if (identity.equals(renderedLast)) return;
        renderedLast = identity;
        if (animation != null) {
            animation.stop();
            animation = null;
        }
        preview.setImageDrawable(null);
        previewPlaceholder.setVisibility(View.VISIBLE);
        if (last.length() == 0) return;
        final String outputPath = last.optString("output");
        final String mediaUri = last.optString("mediaUri");
        PREVIEW_IO.execute(() -> { try {
            File output = new File(outputPath);
            ImageDecoder.Source source = output.isFile()
                    ? ImageDecoder.createSource(output)
                    : ImageDecoder.createSource(getContentResolver(), Uri.parse(mediaUri));
            Drawable image = ImageDecoder.decodeDrawable(source, (decoder, info, input) -> {
                float scale = Math.min(1f, 320f / Math.max(info.getSize().getWidth(), info.getSize().getHeight()));
                decoder.setTargetSize(Math.max(1, Math.round(info.getSize().getWidth() * scale)),
                        Math.max(1, Math.round(info.getSize().getHeight() * scale)));
            });
            State.MAIN.post(() -> {
                if (isDestroyed() || !identity.equals(renderedLast)) return;
                preview.setImageDrawable(image);
                previewPlaceholder.setVisibility(View.GONE);
                if (image instanceof AnimatedImageDrawable) {
                    animation = (AnimatedImageDrawable) image;
                    if (resumed) animation.start();
                }
            });
        } catch (Exception ignored) {} });
    }

    private void viewLast() {
        if (!canAct()) return;
        JSONObject last = State.last;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.parse(last.getString("mediaUri")), last.getString("mimeType"))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (Exception error) {
            Jobs.toast(this, "请在相册的 StickerBridge 文件夹中查看");
        }
    }

    private void updateStatus() {
        if (content == null) return;
        boolean busy = State.busy;
        boolean service = BridgeAccessibility.live != null;
        boolean enabled = State.prefs(this).getBoolean("enabled", true);
        setText(primary, busy ? "正在保存…" : service ? "去抖音选表情"
                : PickerSettings.enabled(this) ? "恢复选取" : "开启选取");
        if (busy) setText(primaryHint, "完成后会自动存入手机相册，请稍候。");
        else if (!service) setText(primaryHint, PickerSettings.enabled(this)
                ? "请在系统无障碍设置中，将“表情直达 · 框选保存”关闭后重新开启。"
                : "先在系统无障碍设置中，开启“表情直达 · 框选保存”。");
        else setText(primaryHint, enabled ? "打开抖音后，点绿色“选”再点目标；也可拖到目标内松手。"
                : "点击上方按钮，打开抖音后会显示绿色“选”。");

        available(primary, !busy);
        available(more, !busy);
        available(viewLast, !busy);

        JSONObject last = State.last;
        boolean hasLast = last.length() > 0;
        boolean hasMessage = busy || (!State.savedResult
                && (State.jobId != 0 || !"准备好保存到相册".equals(State.stage)))
                || (State.savedResult && !hasLast);
        taskCard.setVisibility(hasMessage ? View.VISIBLE : View.GONE);
        ProgressPresentation display = ProgressPresentation.of(State.stage, busy, State.savedResult,
                Diagnostics.countedProgressPhase(), State.progress,
                android.os.SystemClock.elapsedRealtime() - State.jobStartedElapsed);
        setText(status, display.title);
        setText(detail, State.detail);
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        progress.setIndeterminate(display.indeterminate);
        if (display.percent >= 0) progress.setProgress(display.percent);
        State.MAIN.removeCallbacks(stateListener);
        if (resumed && display.indeterminate) State.MAIN.postDelayed(stateListener, 350);

        lastCard.setVisibility(hasLast ? View.VISIBLE : View.GONE);
        bindLast(last);
        setText(lastTitle, State.savedResult ? "已保存到相册" : "上次保存的表情");
        setText(lastFormat, (last.optInt("frames") > 1 ? "GIF 动图"
                : "selection_frame".equals(last.optString("inputMethod")) ? "PNG 静态截图" : "PNG 静态图")
                + (display.percent == 100 ? " · 100%" : ""));
    }

    private void refresh() {
        // State notifications and delayed callbacks must never rebuild a paused Activity.
        if (!resumed) return;
        updateStatus();
    }
}
