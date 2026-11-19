package com.stickerbridge.app;

public final class ProgressPresentationProbe {
    public static void main(String[] args) {
        verify("正在查找原件", true, false, false, 5, 800, "正在查找原件", -1, true);
        verify("正在查找原件", true, false, false, 29, 4399, "正在查找原件 · 已用 4 秒", -1, true);
        verify("读取原文件", true, false, false, 32, 6100, "读取原文件 · 已用 6 秒", -1, true);
        verify("生成表情", true, false, true, 54, 8200, "生成表情 · 54%", 54, false);
        verify("保存到相册", true, false, true, 100, 8500, "保存到相册 · 99%", 99, false);
        verify("已保存到相册", false, true, true, 100, 8700, "已保存到相册 · 100%", 100, false);
        verify("没有读到动图原件", false, false, false, 32, 16000, "没有读到动图原件", -1, false);
    }

    private static void verify(String stage, boolean busy, boolean saved, boolean countedPhase,
                               int progress, long elapsedMs, String title, int percent, boolean indeterminate) {
        ProgressPresentation presentation = ProgressPresentation.of(stage, busy, saved, countedPhase,
                progress, elapsedMs);
        if (!title.equals(presentation.title) || percent != presentation.percent
                || indeterminate != presentation.indeterminate) {
            throw new AssertionError(presentation.title + " / " + presentation.percent + " / "
                    + presentation.indeterminate);
        }
    }
}
