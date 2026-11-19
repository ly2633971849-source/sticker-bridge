package com.stickerbridge.app;

/** Formats the same job progress for the floating notice and the home screen. */
final class ProgressPresentation {
    final String title;
    final int percent;
    final boolean indeterminate;

    private ProgressPresentation(String title, int percent, boolean indeterminate) {
        this.title = title;
        this.percent = percent;
        this.indeterminate = indeterminate;
    }

    static ProgressPresentation of(String stage, boolean busy, boolean saved, boolean countedPhase,
                                   int progress, long elapsedMs) {
        if (!busy) {
            return saved ? new ProgressPresentation(stage + " · 100%", 100, false)
                    : new ProgressPresentation(stage, -1, false);
        }
        if (countedPhase && progress >= 0) {
            int percent = Math.min(99, progress);
            return new ProgressPresentation(stage + " · " + percent + "%", percent, false);
        }
        long seconds = elapsedMs / 1000;
        String title = seconds > 0 ? stage + " · 已用 " + seconds + " 秒" : stage;
        return new ProgressPresentation(title, -1, true);
    }
}
