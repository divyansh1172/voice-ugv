package com.zoro.ugv;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * DTWMatcher — Java port of esp32_side/src/dtw.cpp
 *
 * Uses Best-of-N strategy: for each command, score = min DTW across all stored templates.
 * The command with the lowest score (below THRESHOLD) wins.
 *
 * Optimized version:
 *   - Parallel execution across templates
 *   - Sakoe-Chiba band (pruning)
 *   - Early exit (if row minimum > best_so_far)
 *   - Reduced allocations via ThreadLocal buffers
 */
public class DTWMatcher {

    public static final float THRESHOLD = 12.0f;
    private static final int   WINDOW    = 35; // Increased window for better alignment flexibility

    // Thread pool for parallel matching
    private final ExecutorService executor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors());

    // Reusable buffers to avoid GC pressure (ThreadLocal for thread-safety)
    private static class Buffers {
        float[] prev     = new float[MFCCProcessor.MAX_FRAMES + 10];
        float[] curr     = new float[MFCCProcessor.MAX_FRAMES + 10];
        float[] prevPath = new float[MFCCProcessor.MAX_FRAMES + 10];
        float[] currPath = new float[MFCCProcessor.MAX_FRAMES + 10];
    }
    private final ThreadLocal<Buffers> localBuffers = ThreadLocal.withInitial(Buffers::new);

    // ── Public result type ────────────────────────────────────────────────────

    public static class MatchResult {
        public final String  command;
        public final float   score;
        public final boolean matched;
        public final Map<String, Float> allScores;

        MatchResult(String command, float score, boolean matched,
                    Map<String, Float> allScores) {
            this.command   = command;
            this.score     = score;
            this.matched   = matched;
            this.allScores = allScores;
        }

        @Override
        public String toString() {
            return matched
                    ? String.format("Match: %s (%.2f)", command, score)
                    : String.format("NoMatch (best=%.2f)", score);
        }
    }

    // ── Core DTW ─────────────────────────────────────────────────────────────

    private float euclidean(float[] a, float[] b) {
        float sum = 0.0f;
        // Handle compatibility: use the minimum number of coefficients available in both arrays
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            float d = a[i] - b[i];
            sum += d * d;
        }
        // Square root is expensive, but Euclidean is standard. 
        // For DTW, sometimes squared Euclidean is used to save time, but scores change.
        return (float) Math.sqrt(sum);
    }

    /**
     * Optimized DTW with Sakoe-Chiba band and early exit.
     * @param limit If the score exceeds this, we can return early.
     */
    private float dtwDistance(float[][] seq1, float[][] seq2, float limit) {
        int len1 = seq1.length;
        int len2 = seq2.length;
        
        // Relaxed length check
        if (Math.abs(len1 - len2) > WINDOW) return Float.MAX_VALUE;

        Buffers b = localBuffers.get();
        float[] prev = b.prev;
        float[] curr = b.curr;
        float[] prevPath = b.prevPath;
        float[] currPath = b.currPath;

        // Initialize first row
        for (int j = 0; j < len2; j++) prev[j] = Float.MAX_VALUE;

        prev[0]     = euclidean(seq1[0], seq2[0]);
        prevPath[0] = 1.0f;
        
        int firstRowMax = Math.min(len2, WINDOW + 1);
        for (int j = 1; j < firstRowMax; j++) {
            prev[j]     = prev[j - 1] + euclidean(seq1[0], seq2[j]);
            prevPath[j] = prevPath[j - 1] + 1.0f;
        }

        for (int i = 1; i < len1; i++) {
            int jStart = Math.max(0, i - WINDOW);
            int jEnd   = Math.min(len2, i + WINDOW + 1);

            float rowMin = Float.MAX_VALUE;

            for (int j = jStart; j < jEnd; j++) {
                float cost = euclidean(seq1[i], seq2[j]);
                
                float diag = (j > 0) ? prev[j - 1] : Float.MAX_VALUE;
                float up   = prev[j];
                float left = (j > jStart) ? curr[j - 1] : Float.MAX_VALUE;

                float diagP = (j > 0) ? prevPath[j - 1] : 0;
                float upP   = prevPath[j];
                float leftP = (j > jStart) ? currPath[j - 1] : 0;

                if (diag <= up && diag <= left) {
                    curr[j] = cost + diag;  currPath[j] = diagP + 1.0f;
                } else if (left <= up) {
                    curr[j] = cost + left;  currPath[j] = leftP + 1.0f;
                } else {
                    curr[j] = cost + up;    currPath[j] = upP   + 1.0f;
                }

                if (curr[j] < rowMin) rowMin = curr[j];
            }

            // Early exit: if the best possible score in this row (normalized)
            // is already significantly worse than our limit.
            if (rowMin / (i + 1) > limit * 1.5f) return Float.MAX_VALUE;

            // Swap rows
            float[] tmp; 
            tmp = prev; prev = curr; curr = tmp;
            tmp = prevPath; prevPath = currPath; currPath = tmp;
            
            // Clear current row for next iteration (important since we only fill the window)
            for (int j = 0; j < len2; j++) curr[j] = Float.MAX_VALUE;
        }
        
        float finalScore = prev[len2 - 1] / prevPath[len2 - 1];
        return (finalScore > limit) ? Float.MAX_VALUE : finalScore;
    }

    // ── Best-of-N ─────────────────────────────────────────────────────────────

    public float bestScore(float[][] query, List<float[][]> templates, float currentBest) {
        float best = currentBest;
        for (float[][] tmpl : templates) {
            float s = dtwDistance(query, tmpl, best);
            if (s < best) best = s;
        }
        return best;
    }

    /**
     * Parallelized findBestCommand.
     */
    public MatchResult findBestCommand(final float[][] query,
                                       final Map<String, List<float[][]>> allTemplates) {
        
        List<Future<CommandScore>> futures = new ArrayList<>();
        
        for (final Map.Entry<String, List<float[][]>> entry : allTemplates.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            
            futures.add(executor.submit(() -> {
                float s = bestScore(query, entry.getValue(), THRESHOLD);
                return new CommandScore(entry.getKey(), s);
            }));
        }

        Map<String, Float> scores = new LinkedHashMap<>();
        float  bestScore   = THRESHOLD;
        String bestCommand = null;

        for (Future<CommandScore> f : futures) {
            try {
                CommandScore cs = f.get();
                scores.put(cs.command, cs.score);
                if (cs.score < bestScore) {
                    bestScore   = cs.score;
                    bestCommand = cs.command;
                }
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }

        for (String cmd : TemplateStore.COMMANDS) {
            scores.putIfAbsent(cmd, Float.MAX_VALUE);
        }

        return new MatchResult(bestCommand, bestScore, bestCommand != null, scores);
    }

    private static class CommandScore {
        final String command;
        final float score;
        CommandScore(String c, float s) { this.command = c; this.score = s; }
    }
}
