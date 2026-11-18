package com.stickerbridge.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
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
    private static final int PICK_FILE = 7;
    private static final int PICK_CROP_VIDEO = 8;
    private static final int EDIT_CROP_VIDEO = 9;
    private static final java.util.concurrent.Executor PREVIEW_IO = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final int BG = 0xff111816, CARD = 0xff1b2722, FG = 0xffeff7f2;
    private static final int MUTED = 0xffa7bbb0, ACCENT = 0xff8cf0b8, NOTICE = 0xffebcf91;
    private LinearLayout content, taskCard, lastCard;
    private TextView status, detail, primaryHint, lastTitle, lastFormat, previewPlaceholder;
    private Button primary, more, preparation, viewLast;
    private ProgressBar progress;
    private ImageView preview;
    private boolean resumed, helper, helperChecked, checkingConnection;
    private String renderedLast = "";
    private Uri cropSource;
    private AnimatedImageDrawable animation;
    private final Runnable stateListener = this::refresh;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null) cropSource = saved.getParcelable("cropSource");
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
        checkConnection();
        State.MAIN.postDelayed(stateListener, 350);
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

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putParcelable("cropSource", cropSource);
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

    private void restorePicker() {
        if (!canAct()) return;
        State.prefs(this).edit().putBoolean("enabled", true).apply();
        BridgeAccessibility service = BridgeAccessibility.live;
        if (service == null) {
            PickerSettings.open(this);
            return;
        }
        service.rebuildBubble();
        State.changed();
        Jobs.toast(this, "选取已恢复，打开抖音后会显示绿色“选”");
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
        menu.add(0, 1, 0, "恢复悬浮按钮");
        if (BridgeAccessibility.live != null && State.prefs(this).getBoolean("enabled", true))
            menu.add(0, 2, 1, "隐藏悬浮按钮");
        menu.add(1, 3, 2, "导入图片或视频");
        menu.add(1, 8, 3, "从视频裁剪表情");
        menu.add(2, 4, 4, "无障碍设置");
        menu.add(2, 6, 5, "检查更新");
        menu.add(2, 7, 6, "复制诊断信息");
        menu.add(2, 5, 7, "使用帮助");
        menu.setGroupDividerEnabled(true);
        popup.setOnMenuItemClickListener(item -> {
            if (!canAct()) return true;
            switch (item.getItemId()) {
                case 1: restorePicker(); break;
                case 2:
                    State.prefs(this).edit().putBoolean("enabled", false).apply();
                    if (BridgeAccessibility.live != null) BridgeAccessibility.live.refreshBubble();
                    State.changed();
                    Jobs.toast(this, "已隐藏，可在“更多”中恢复");
                    break;
                case 3: pickFile(); break;
                case 4: PickerSettings.open(this); break;
                case 5: showHelp(); break;
                case 6: AppUpdates.show(this); break;
                case 7: showDiagnostics(); break;
                case 8: pickCropVideo(); break;
                default: return false;
            }
            return true;
        });
        popup.show();
    }

    private void showPreparation() {
        boolean phoneOnly = State.prefs(this).getBoolean("force_single_frame", false);
        String current = !phoneOnly && helper ? "原件桥已连接，框选优先查找原件。"
                : "手机模式可直接框选保存静态 PNG，也可导入原文件转 GIF。";
        new AlertDialog.Builder(this)
                .setTitle("保存方式")
                .setMessage(current + "\n\n框选需要开启系统无障碍。静态框选保存选区当前画面，不用电脑。"
                        + "\n\n要保留动画，请将抖音能保存或分享的原文件导入表情直达。")
                .setPositiveButton(phoneOnly ? "恢复自动选择" : "仅用手机模式", (dialog, which) -> {
                    State.prefs(this).edit().putBoolean("force_single_frame", !phoneOnly).apply();
                    updateStatus();
                })
                .setNeutralButton("导入文件", (dialog, which) -> pickFile())
                .setNegativeButton("关闭", null)
                .show();
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
                .setMessage("1. 点首页按钮，在系统无障碍设置中开启“表情直达 · 框选保存”。"
                        + "\n\n2. 打开抖音，找到评论图片、动图或小视频。点绿色“选”，再点目标；也可以把按钮拖到目标内松手，边框会自动贴合并保存。"
                        + "\n\n3. 保存完成后，打开 QQ 收藏，从最近照片添加。"
                        + "\n\n按钮不见了\n绿色“选”在本应用内不显示。打开抖音后仍未出现时，点“更多 → 恢复悬浮按钮”，再返回抖音。仍无法使用时，在系统无障碍设置中找到“表情直达 · 框选保存”，关闭再开启。不同手机的菜单名称可能不同。"
                        + "\n\n不用电脑\n开启无障碍后可直接把选区画面存为静态 PNG。要保留动画，请导入能分享或保存的原文件。"
                        + "\n\n支持范围\n评论图片和静态表情保存为 PNG；动图、短视频和有完整片段的实况照片转为 GIF。原文件最大 32 MB，其中动态 WebP 最大 12 MB。重新生成的动画最长 60 秒，原文件必须完整且可读。视频的声音不写入 GIF。"
                        + "\n\n保存位置\n手机相册的 StickerBridge 文件夹。")
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
        more.setContentDescription("更多设置与帮助");
        header.addView(more, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(-1, -2);
        headerParams.bottomMargin = dp(26);
        content.addView(header, headerParams);

        label(content, "把表情存进相册", 28, FG, true, 12);
        label(content, "抖音评论图片 · 动图 · 小视频", 14, MUTED, false, 12);
        primary = addButton(content, "开启选取", true, this::openDouyin);
        primaryHint = label(content, "", 13, MUTED, false, 0);
        primaryHint.setPadding(dp(2), dp(12), dp(2), dp(2));
        addButton(content, "导入图片或视频", false, this::pickFile);
        preparation = button("", false, this::showPreparation);
        preparation.setTypeface(Typeface.DEFAULT);
        preparation.setTextSize(13);
        preparation.setTextColor(MUTED);
        preparation.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        preparation.setPadding(dp(2), dp(10), dp(2), dp(10));
        preparation.setBackground(new RippleDrawable(ColorStateList.valueOf(0x228cf0b8), rounded(BG, 10), null));
        content.addView(preparation, new LinearLayout.LayoutParams(-1, -2));

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
        TextView instruction = label(lastCard, "在 QQ 收藏中，从最近照片添加。", 13, MUTED, false, 0);
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

    private void pickFile() {
        if (!canAct()) return;
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                    .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"})
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false), PICK_FILE);
        } catch (Exception error) {
            Jobs.toast(this, "可以将原表情图片分享给表情直达");
        }
    }

    private void pickCropVideo() {
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE).setType("video/*"), PICK_CROP_VIDEO);
        } catch (android.content.ActivityNotFoundException error) {
            Jobs.toast(this, "这台手机没有可用的文件选择器");
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == EDIT_CROP_VIDEO) {
            Uri source = cropSource;
            cropSource = null;
            if (result == RESULT_OK && data != null && source != null) {
                float[] crop = data.getFloatArrayExtra(CropVideoActivity.EXTRA_CROP);
                if (crop != null && crop.length == 4) Jobs.convertCroppedVideo(this, source,
                        new android.graphics.RectF(crop[0], crop[1], crop[2], crop[3]));
            }
            return;
        }
        if (request == PICK_CROP_VIDEO && result == RESULT_OK && data != null && !State.busy) {
            Uri source = data.getData();
            if (source != null && "content".equals(source.getScheme())) {
                cropSource = source;
                startActivityForResult(new Intent(Intent.ACTION_VIEW, source, this, CropVideoActivity.class)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), EDIT_CROP_VIDEO);
            }
            return;
        }
        if (request == PICK_FILE && result == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) Jobs.convertShared(this, uri);
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

        boolean originals = helper && !State.prefs(this).getBoolean("force_single_frame", false);
        setText(preparation, originals ? "原件优先 · 保存方式  ›" : "无需电脑 · 保存方式  ›");
        preparation.setTextColor(MUTED);
        available(primary, !busy);
        available(more, !busy);
        available(preparation, !busy);
        available(viewLast, !busy);

        JSONObject last = State.last;
        boolean hasLast = last.length() > 0;
        boolean hasMessage = busy || (!State.savedResult
                && (State.jobId != 0 || !"准备好保存到相册".equals(State.stage)))
                || (State.savedResult && !hasLast);
        taskCard.setVisibility(hasMessage ? View.VISIBLE : View.GONE);
        setText(status, State.stage + ((busy || State.savedResult) && State.progress >= 0 && !State.lookingForOriginal
                ? "  " + State.progress + "%" : ""));
        setText(detail, State.detail);
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        progress.setIndeterminate(State.progress < 0 || State.lookingForOriginal);
        if (State.progress >= 0) progress.setProgress(State.progress);

        lastCard.setVisibility(hasLast ? View.VISIBLE : View.GONE);
        bindLast(last);
        setText(lastTitle, State.savedResult ? "已保存到相册" : "上次保存的表情");
        setText(lastFormat, (last.optInt("frames") > 1 ? "动态表情"
                : "selection_frame".equals(last.optString("inputMethod")) ? "静态截图" : "静态表情")
                + (State.savedResult && State.progress == 100 ? " · 100%" : ""));
    }

    private void refresh() {
        // State notifications and delayed callbacks must never rebuild a paused Activity.
        if (!resumed) return;
        updateStatus();
        if (!State.busy && !helperChecked && !checkingConnection) checkConnection();
    }

    private void checkConnection() {
        if (checkingConnection || State.busy) return;
        checkingConnection = true;
        updateStatus();
        Context app = getApplicationContext();
        // A slow preparation check must not block local imports on the single job executor.
        Thread check = new Thread(() -> {
            boolean connected;
            try { connected = new SourceClient(app).supportsOriginals(); }
            catch (Exception error) { connected = false; }
            final boolean result = connected;
            State.MAIN.post(() -> {
                if (isDestroyed()) return;
                checkingConnection = false;
                helperChecked = true;
                helper = result;
                if (resumed) updateStatus();
            });
        }, "sticker-preparation");
        check.setDaemon(true);
        check.start();
    }
}
