package com.stickerbridge.app;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.stickerbridge.app.SourceLookupPlan.Pass;

public final class SourceLookupPlanProbe {
    private static final int CORPUS = 2400;
    private static final Pass[] PREVIOUS_COMMENT_ORDER = {Pass.RECENT, Pass.EXPANDED, Pass.WIDE};

    public static void main(String[] args) {
        check(Arrays.equals(SourceLookupPlan.passes(false, false),
                new Pass[]{Pass.RECENT, Pass.COMMENT_ANIMATIONS, Pass.EXPANDED, Pass.WIDE}), "comment lookup order");
        check(SourceLookupPlan.passes(true, false)[0] == Pass.ANIMATED_FIRST, "chat looks for animation first");
        for (Pass pass : SourceLookupPlan.passes(false, true)) check(pass.animatedOnly(true), "motion-only lookup");
        check(!Pass.EXPANDED.animatedOnly(false), "mixed fallback stays available");
        check(Pass.COMMENT_ANIMATIONS.deadline(1000, 12000) == 3400, "optional animation scan has a local budget");
        check(Pass.COMMENT_ANIMATIONS.deadline(11500, 12000) == 12000, "stage cannot extend the overall budget");
        check(Pass.EXPANDED.deadline(3400, 12000) == 12000, "mixed scan retains the remaining budget");
        check(SourceLookupPlan.checkpoint(8) && SourceLookupPlan.checkpoint(64)
                && SourceLookupPlan.checkpoint(128) && !SourceLookupPlan.checkpoint(72), "bounded checkpoint frequency");
        check(!Pass.COMMENT_ANIMATIONS.canFinish(0, 1), "animation-only probe waits for the mixed comparison");
        check(!SourceLookupPlan.confident(.001, .001, .045, .03), "equal scores are ambiguous");

        compare("recent still", 10, 16);
        compare("older unlabeled animation", 201, 64);
        compare("older still", 200, 400);
        Result tied = search(false, false, Map.of(201, .01, 221, .01));
        check(!tied.found && tied.visited == CORPUS, "two different equally close animations do not save");
        Result earlierStill = search(false, false, Map.of(10, .06, 201, .062));
        check(!earlierStill.found, "an earlier still remains a competitor to an animation from another pass");
        check(!search(false, true, Map.of(10, .01)).found, "verified motion cannot match a static file");
        System.out.println("Source lookup plan regression passed");
    }

    private static void compare(String scenario, int target, int maxVisited) {
        Map<Integer, Double> scores = Map.of(target, .01);
        Result previous = search(true, false, scores);
        Result candidate = search(false, false, scores);
        check(previous.found && candidate.found, scenario + " retains successful selection");
        check(candidate.visited <= previous.visited && candidate.visited <= maxVisited, scenario + " has bounded work");
        System.out.println(scenario + ": " + previous.visited + " -> " + candidate.visited + " simulated candidate loads");
    }

    private static Result search(boolean previous, boolean motionRequired, Map<Integer, Double> scores) {
        Set<Integer> visited = new HashSet<>();
        double best = Double.POSITIVE_INFINITY, next = Double.POSITIVE_INFINITY;
        Pass[] passes = previous ? PREVIOUS_COMMENT_ORDER : SourceLookupPlan.passes(false, motionRequired);
        for (Pass pass : passes) {
            int offered = 0, accepted = 0;
            for (int id = 0; id < CORPUS && offered < pass.limit; id++) {
                if (pass.animatedOnly(motionRequired) && id % 20 != 1) continue;
                offered++;
                if (!visited.add(id)) continue;
                double score = scores.getOrDefault(id, .45);
                if (score < best) {
                    next = best;
                    best = score;
                } else if (score < next) {
                    next = score;
                }
                accepted++;
                if (!previous && SourceLookupPlan.checkpoint(accepted)
                        && SourceLookupPlan.confident(best, next, .045, .03)) break;
            }
            if (pass.canFinish(best, next)) return new Result(true, visited.size());
        }
        return new Result(SourceLookupPlan.confident(best, next, .105, .02), visited.size());
    }

    private static void check(boolean condition, String failure) {
        if (!condition) throw new AssertionError(failure);
    }

    private static final class Result {
        final boolean found;
        final int visited;

        Result(boolean found, int visited) {
            this.found = found;
            this.visited = visited;
        }
    }
}
