package com.stickerbridge.app;

/** Search order and confidence rules for finding a selected original. */
final class SourceLookupPlan {
    enum Pass {
        ANIMATED_FIRST(96, 5, 16, 5_000, true, .075, .025),
        RECENT(16, 5, 16, 0, false, .045, .03),
        COMMENT_ANIMATIONS(48, 12, 20, 2_400, true, 0, 0),
        EXPANDED(64, 16, 24, 0, false, .075, .025),
        WIDE(2400, 24, 32, 0, false, 0, 0);

        final int limit, progressFrom, progressTo;
        final long budgetMs;
        final boolean animationPriority;
        private final double score, gap;

        Pass(int limit, int progressFrom, int progressTo, long budgetMs, boolean animationPriority, double score, double gap) {
            this.limit = limit;
            this.progressFrom = progressFrom;
            this.progressTo = progressTo;
            this.budgetMs = budgetMs;
            this.animationPriority = animationPriority;
            this.score = score;
            this.gap = gap;
        }

        long deadline(long now, long overall) {
            return budgetMs == 0 ? overall : Math.min(overall, now + budgetMs);
        }

        boolean animatedOnly(boolean motionRequired) {
            return animationPriority || motionRequired;
        }

        boolean canFinish(double best, double next) {
            return score > 0 && confident(best, next, score, gap);
        }
    }

    private SourceLookupPlan() {}

    static Pass[] passes(boolean message, boolean motionRequired) {
        if (message || motionRequired) return new Pass[]{Pass.ANIMATED_FIRST, Pass.RECENT, Pass.EXPANDED, Pass.WIDE};
        return new Pass[]{Pass.RECENT, Pass.COMMENT_ANIMATIONS, Pass.EXPANDED, Pass.WIDE};
    }

    static boolean checkpoint(int accepted) {
        // Score early in small batches, then space checkpoints out to avoid repeatedly scoring a large pool.
        return accepted > 0 && (accepted <= 64 ? accepted % 8 == 0 : (accepted & (accepted - 1)) == 0);
    }

    static boolean confident(double best, double next, double score, double gap) {
        return best < score && next - best > gap;
    }
}
