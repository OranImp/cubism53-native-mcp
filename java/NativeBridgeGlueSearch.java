package cubism53.mcp;

import java.util.*;

final class NativeBridgeGlueSearch {
    record Candidate(int a, int b, double distance) {}
    record Matches(List<Candidate> candidates, int[] aCounts, int[] bCounts, long comparisons) {
        boolean unique(Candidate candidate) {
            return aCounts[candidate.a] == 1 && bCounts[candidate.b] == 1;
        }
    }
    private record Cell(long x, long y) {}

    static Matches find(float[] a, float[] b, double tolerance) {
        // The original distance uses float subtraction. A cell twice as wide
        // leaves room for that rounding at the tolerance and grid boundaries.
        double width = tolerance * 2;
        var aCells = cells(a, width);
        var bCells = cells(b, width);
        if (aCells == null || bCells == null) return bruteForce(a, b, tolerance);
        var buckets = new HashMap<Cell,List<Integer>>();
        for (int j = 0; j < bCells.length; j++)
            buckets.computeIfAbsent(bCells[j], key -> new ArrayList<>()).add(j);
        var candidates = new ArrayList<Candidate>();
        int[] aCounts = new int[a.length / 2], bCounts = new int[b.length / 2];
        long comparisons = 0;
        for (int i = 0; i < aCells.length; i++) {
            Cell cell = aCells[i];
            int start = candidates.size();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    var bucket = buckets.get(new Cell(cell.x + dx, cell.y + dy));
                    if (bucket == null) continue;
                    for (int j : bucket) {
                        compare(a, b, tolerance, i, j, candidates, aCounts, bCounts);
                        comparisons++;
                    }
                }
            }
            candidates.subList(start, candidates.size()).sort(Comparator.comparingInt(Candidate::b));
        }
        return new Matches(candidates, aCounts, bCounts, comparisons);
    }

    private static Cell[] cells(float[] coordinates, double width) {
        var cells = new Cell[coordinates.length / 2];
        for (int i = 0; i < cells.length; i++) {
            double x = coordinates[i * 2] / width;
            double y = coordinates[i * 2 + 1] / width;
            if (!(Math.abs(x) < 0x1.0p51 && Math.abs(y) < 0x1.0p51)) return null;
            cells[i] = new Cell((long) Math.floor(x), (long) Math.floor(y));
        }
        return cells;
    }

    static Matches bruteForce(float[] a, float[] b, double tolerance) {
        var candidates = new ArrayList<Candidate>();
        int[] aCounts = new int[a.length / 2], bCounts = new int[b.length / 2];
        for (int i = 0; i < aCounts.length; i++)
            for (int j = 0; j < bCounts.length; j++)
                compare(a, b, tolerance, i, j, candidates, aCounts, bCounts);
        return new Matches(candidates, aCounts, bCounts, (long) aCounts.length * bCounts.length);
    }

    private static void compare(float[] a, float[] b, double tolerance, int i, int j,
                                List<Candidate> candidates, int[] aCounts, int[] bCounts) {
        double distance = Math.hypot(a[i * 2] - b[j * 2], a[i * 2 + 1] - b[j * 2 + 1]);
        if (distance <= tolerance) {
            aCounts[i]++;
            bCounts[j]++;
            candidates.add(new Candidate(i, j, distance));
        }
    }
}
