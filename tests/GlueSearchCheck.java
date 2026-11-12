package cubism53.mcp;

import java.util.Arrays;
import java.util.Random;

public final class GlueSearchCheck {
    private static long check(float[] a, float[] b, double tolerance) {
        var indexed = NativeBridgeGlueSearch.find(a, b, tolerance);
        var original = NativeBridgeGlueSearch.bruteForce(a, b, tolerance);
        if (!indexed.candidates().equals(original.candidates())
            || !Arrays.equals(indexed.aCounts(), original.aCounts())
            || !Arrays.equals(indexed.bCounts(), original.bCounts()))
            throw new AssertionError("Indexed and exhaustive Glue matches differ");
        return indexed.comparisons();
    }

    public static void main(String[] args) {
        check(new float[]{-2, 0, -1, 0, -1, 0, 1, -2},
            new float[]{-1, 0, 0, 0, 1, -1, Math.nextUp(-1f), 0}, 1);
        check(new float[]{Float.MAX_VALUE, 0, Float.NaN, 0},
            new float[]{Float.MAX_VALUE, 0, Float.MIN_VALUE, 0}, Float.MIN_VALUE);
        var random = new Random(92853);
        for (int i = 0; i < 500; i++) {
            float[] a = new float[40], b = new float[50];
            for (int j = 0; j < a.length; j++) a[j] = (random.nextFloat() - .5f) * 50;
            for (int j = 0; j < b.length; j++) b[j] = (random.nextFloat() - .5f) * 50;
            check(a, b, random.nextFloat() * 5 + .01);
        }
        int size = 4096;
        float[] a = new float[size * 2], b = new float[size * 2];
        for (int i = 0; i < size; i++) {
            a[i * 2] = i * 10 - 20480;
            b[i * 2] = a[i * 2] + .25f;
        }
        long start = System.nanoTime();
        var indexed = NativeBridgeGlueSearch.find(a, b, 1);
        long indexedMs = (System.nanoTime() - start) / 1_000_000;
        start = System.nanoTime();
        var original = NativeBridgeGlueSearch.bruteForce(a, b, 1);
        long originalMs = (System.nanoTime() - start) / 1_000_000;
        if (!indexed.candidates().equals(original.candidates()) || indexed.comparisons() != size)
            throw new AssertionError("Sparse search did not reduce comparisons");
        System.out.printf("Glue comparisons %d -> %d, elapsed %d ms -> %d ms%n",
            original.comparisons(), indexed.comparisons(), originalMs, indexedMs);
    }
}
