package com.stickerbridge.app;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CropVideoActivity extends Activity {
    public static final String EXTRA_CROP = "com.stickerbridge.app.VIDEO_CROP";
    private static final String PREVIEW_POSITION = "previewPosition";
    private static final int BG = 0xff111816;
    private static final int CARD = 0xff1b2722;
    private static final int FG = 0xffeff7f2;
    private static final int MUTED = 0xffa7bbb0;
    private static final int ACCENT = 0xff8cf0b8;
    private static final int MAX_PREVIEW_SIZE = 960;
    private static final float MIN_SIDE = .03f;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Uri source;
    private CropView cropView;
    private TextView status;
    private SeekBar seek;
    private Button save;
    private volatile int request;
    private int duration;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Intent intent = getIntent();
        source = intent.getData();
        if (!Intent.ACTION_VIEW.equals(intent.getAction()) || source == null
                || !"content".equals(source.getScheme())) {
            finish();
            return;
        }

        getWindow().setDecorFitsSystemWindows(false);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(BG);
        layout.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets safe = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                    | android.view.WindowInsets.Type.displayCutout());
            view.setPadding(safe.left + dp(16), safe.top + dp(10), safe.right + dp(16), safe.bottom + dp(10));
            return insets;
        });

        TextView title = label("裁剪视频", 22, FG);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        layout.addView(title);
        TextView instruction = label("在画面上从一角拖到另一角，框住表情。", 15, MUTED);
        instruction.setPadding(0, dp(5), 0, dp(12));
        layout.addView(instruction);
        cropView = new CropView();
        if (state != null) cropView.restore(state.getFloatArray(EXTRA_CROP));
        layout.addView(cropView, new LinearLayout.LayoutParams(-1, 0, 1f));

        status = label("正在打开视频…", 13, MUTED);
        status.setPadding(0, dp(8), 0, 0);
        layout.addView(status);
        seek = new SeekBar(this);
        seek.setMax(1000);
        seek.setContentDescription("选择预览位置");
        seek.setProgressTintList(ColorStateList.valueOf(ACCENT));
        seek.setThumbTintList(ColorStateList.valueOf(ACCENT));
        seek.setEnabled(false);
        seek.setProgress(state == null ? 0 : state.getInt(PREVIEW_POSITION));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            private boolean dragging;

            @Override public void onStartTrackingTouch(SeekBar bar) { dragging = true; }
            @Override public void onProgressChanged(SeekBar bar, int position, boolean fromUser) {
                if (!fromUser) return;
                if (dragging) status.setText(timeLabel(position, duration));
                else loadFrame(position);
            }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                loadFrame(bar.getProgress());
            }
        });
        layout.addView(seek, new LinearLayout.LayoutParams(-1, dp(40)));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END);
        actions.setPadding(0, dp(6), 0, 0);
        Button cancel = button("取消", false);
        cancel.setOnClickListener(view -> finish());
        actions.addView(cancel);
        save = button("转换成 GIF", true);
        save.setEnabled(false);
        save.setOnClickListener(view -> {
            float[] crop = cropView.getCrop();
            if (crop == null) return;
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_CROP, crop));
            finish();
        });
        LinearLayout.LayoutParams saveLayout = new LinearLayout.LayoutParams(-2, -2);
        saveLayout.setMarginStart(dp(8));
        actions.addView(save, saveLayout);
        layout.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        setContentView(layout);
        loadFrame(seek.getProgress());
    }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private Button button(String text, boolean primary) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextColor(primary ? BG : FG);
        button.setMinHeight(dp(48));
        button.setPadding(dp(16), 0, dp(16), 0);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(12));
        shape.setColor(primary ? ACCENT : CARD);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x30636363), shape, null));
        return button;
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void loadFrame(int position) {
        int current = ++request;
        worker.execute(() -> {
            if (current != request) return;
            Bitmap frame = null;
            int length = 0;
            String error = null;
            try (MediaMetadataRetriever reader = new MediaMetadataRetriever()) {
                reader.setDataSource(getApplicationContext(), source);
                int width = metadata(reader, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                int height = metadata(reader, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                length = metadata(reader, MediaMetadataRetriever.METADATA_KEY_DURATION);
                int rotation = metadata(reader, MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                if (width <= 0 || height <= 0 || length <= 0)
                    throw new IllegalArgumentException("没有读取到视频画面，请换一个视频。");
                if (width > 4096 || height > 4096)
                    throw new IllegalArgumentException("视频宽或高超过 4096 像素，请先缩小视频。");
                if (length > 60_000)
                    throw new IllegalArgumentException("视频超过 60 秒，请先截取一段再导入。");
                if (rotation == 90 || rotation == 270) {
                    int originalWidth = width;
                    width = height;
                    height = originalWidth;
                }
                float scale = Math.min(1f, (float) MAX_PREVIEW_SIZE / Math.max(width, height));
                long atUs = Math.min((length - 1L) * 1000L, (long) length * position);
                frame = reader.getScaledFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST,
                        Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale)));
                if (frame == null) throw new IllegalArgumentException("这段视频的预览无法读取，请换一个文件。");
                Bitmap oriented = VideoFrames.orientCropFrame(frame, rotation, width, height);
                if (oriented != frame) frame.recycle();
                frame = oriented;
            } catch (IllegalArgumentException problem) {
                error = problem.getMessage();
            } catch (Exception problem) {
                error = "打开视频失败，请重新选择手机里的文件。";
            }
            Bitmap preview = frame;
            int videoLength = length;
            String message = error;
            runOnUiThread(() -> {
                if (current != request || isDestroyed() || isFinishing()) {
                    if (preview != null) preview.recycle();
                    return;
                }
                if (preview == null) {
                    status.setText(message == null ? "未能读取视频预览。" : message);
                    updateSave();
                    return;
                }
                duration = videoLength;
                seek.setEnabled(duration > 1);
                status.setText(timeLabel(position, duration));
                cropView.setPreview(preview);
                updateSave();
            });
        });
    }

    private static int metadata(MediaMetadataRetriever reader, int key) {
        return MediaFiles.integer(reader.extractMetadata(key), 0);
    }

    private static String timeLabel(int position, int duration) {
        return String.format(Locale.ROOT, "预览 %.1f / %.1f 秒 · 滑动只改变预览画面",
                duration * position / 1_000_000d, duration / 1000d);
    }

    private void updateSave() {
        save.setEnabled(cropView.preview != null && cropView.getCrop() != null);
        save.setAlpha(save.isEnabled() ? 1f : .45f);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt(PREVIEW_POSITION, seek == null ? 0 : seek.getProgress());
        if (cropView != null) state.putFloatArray(EXTRA_CROP, cropView.getCrop());
    }

    @Override protected void onDestroy() {
        request++;
        worker.shutdownNow();
        super.onDestroy();
    }

    private final class CropView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF display = new RectF();
        private final RectF crop = new RectF();
        private final RectF gesture = new RectF();
        private Bitmap preview;
        private boolean dragging;
        private float startX, startY;

        CropView() {
            super(CropVideoActivity.this);
            setContentDescription("视频预览，拖动选取表情区域");
        }

        void restore(float[] values) {
            if (values == null || values.length != 4) return;
            for (float value : values) if (!(value >= 0f && value <= 1f)) return;
            RectF restored = new RectF(values[0], values[1], values[2], values[3]);
            if (valid(restored)) crop.set(restored);
        }

        float[] getCrop() {
            return valid(crop) ? new float[]{crop.left, crop.top, crop.right, crop.bottom} : null;
        }

        void setPreview(Bitmap bitmap) {
            preview = bitmap;
            updateDisplay();
            invalidate();
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            updateDisplay();
        }

        private void updateDisplay() {
            display.setEmpty();
            if (preview == null || getWidth() <= 0 || getHeight() <= 0) return;
            float scale = Math.min((float) getWidth() / preview.getWidth(), (float) getHeight() / preview.getHeight());
            float width = preview.getWidth() * scale;
            float height = preview.getHeight() * scale;
            float left = (getWidth() - width) * .5f;
            float top = (getHeight() - height) * .5f;
            display.set(left, top, left + width, top + height);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(0xff0a100d);
            if (preview == null || display.isEmpty()) return;
            paint.setColor(Color.WHITE);
            canvas.drawBitmap(preview, null, display, paint);
            RectF selection = dragging ? gesture : crop;
            if (selection.isEmpty()) return;
            float left = display.left + selection.left * display.width();
            float top = display.top + selection.top * display.height();
            float right = display.left + selection.right * display.width();
            float bottom = display.top + selection.bottom * display.height();
            paint.setColor(0x88000000);
            canvas.drawRect(display.left, display.top, display.right, top, paint);
            canvas.drawRect(display.left, bottom, display.right, display.bottom, paint);
            canvas.drawRect(display.left, top, left, bottom, paint);
            canvas.drawRect(right, top, display.right, bottom, paint);
            paint.setColor(ACCENT);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            canvas.drawRect(left, top, right, bottom, paint);
            paint.setStyle(Paint.Style.FILL);
            int radius = dp(3);
            canvas.drawCircle(left, top, radius, paint);
            canvas.drawCircle(right, top, radius, paint);
            canvas.drawCircle(left, bottom, radius, paint);
            canvas.drawCircle(right, bottom, radius, paint);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (preview == null || display.isEmpty()) return false;
            float positionX = clamp((event.getX() - display.left) / display.width());
            float positionY = clamp((event.getY() - display.top) / display.height());
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (!display.contains(event.getX(), event.getY())) return false;
                    startX = positionX;
                    startY = positionY;
                    gesture.setEmpty();
                    dragging = true;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                case MotionEvent.ACTION_UP:
                    if (!dragging) return false;
                    gesture.set(Math.min(startX, positionX), Math.min(startY, positionY),
                            Math.max(startX, positionX), Math.max(startY, positionY));
                    if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                        dragging = false;
                        if (valid(gesture)) crop.set(gesture);
                        else performClick();
                        updateSave();
                    }
                    invalidate();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    invalidate();
                    return true;
                default:
                    return dragging;
            }
        }

        @Override public boolean performClick() {
            super.performClick();
            return true;
        }

        private float clamp(float value) { return Math.max(0f, Math.min(value, 1f)); }
        private boolean valid(RectF value) { return value.width() >= MIN_SIDE && value.height() >= MIN_SIDE; }
    }
}
