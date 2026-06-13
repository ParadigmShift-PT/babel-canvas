package protocols.apps.canvas;

import java.util.ArrayList;
import java.util.List;

/**
 * The shared pixel grid: one {@link PaintOp} winner per cell under
 * last-writer-wins (see {@link PaintOp#isNewerThan}). All mutation goes through
 * {@link #apply}; reads ({@link #snapshotArgb}, {@link #snapshotOps},
 * {@link #digest}) are used by the web UI thread and the periodic digest timer,
 * so every method is synchronized on this instance.
 *
 * <p>{@link #digest} is the convergence oracle: a stable 64-bit hash over the
 * winning colour of every painted cell in row-major order. Two nodes that have
 * applied the same set of ops produce the same digest — the experiments harness
 * asserts all live nodes agree once gossip quiesces.
 */
public final class PixelCanvas {

    /** Colour reported for a cell nobody has painted yet (transparent). */
    public static final int EMPTY = 0x00000000;

    // 64-bit FNV-1a constants, used to fold the painted cells into the digest.
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private final int width;
    private final int height;
    private final PaintOp[] cells; // row-major; null = unpainted
    private int paintedCount;

    public PixelCanvas(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("canvas dimensions must be positive, got "
                    + width + "x" + height);
        }
        this.width = width;
        this.height = height;
        this.cells = new PaintOp[width * height];
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }

    /**
     * Apply an op under last-writer-wins. Returns {@code true} if it became (or
     * remained the basis of) the new winner for its cell — i.e. the visible
     * colour may have changed; {@code false} if an out-of-bounds op or an op
     * superseded by what the cell already holds. Idempotent and order-independent:
     * applying the same op twice, or applying ops in any order, yields the same grid.
     */
    public synchronized boolean apply(PaintOp op) {
        if (op.getX() < 0 || op.getX() >= width || op.getY() < 0 || op.getY() >= height) {
            return false;
        }
        int idx = op.getY() * width + op.getX();
        PaintOp current = cells[idx];
        if (!op.isNewerThan(current)) {
            return false;
        }
        if (current == null) {
            paintedCount++;
        }
        cells[idx] = op;
        return true;
    }

    /** Number of cells that have been painted at least once. */
    public synchronized int paintedCount() {
        return paintedCount;
    }

    /**
     * The grid as a row-major array of ARGB colours, {@link #EMPTY} for unpainted
     * cells. A copy — safe to hand to the UI thread.
     */
    public synchronized int[] snapshotArgb() {
        int[] out = new int[cells.length];
        for (int i = 0; i < cells.length; i++) {
            out[i] = cells[i] == null ? EMPTY : cells[i].getArgb();
        }
        return out;
    }

    /** All current winning ops (one per painted cell) — used to seed a joining peer. */
    public synchronized List<PaintOp> snapshotOps() {
        List<PaintOp> ops = new ArrayList<>(paintedCount);
        for (PaintOp cell : cells) {
            if (cell != null) {
                ops.add(cell);
            }
        }
        return ops;
    }

    /** Merge a peer's snapshot into this canvas under LWW. Returns how many cells changed. */
    public synchronized int mergeSnapshot(List<PaintOp> ops) {
        int changed = 0;
        for (PaintOp op : ops) {
            if (apply(op)) {
                changed++;
            }
        }
        return changed;
    }

    /**
     * Stable 64-bit digest of the visible canvas: FNV-1a over (index, argb) for
     * every painted cell in row-major order. Equal across nodes that have applied
     * the same set of ops; the convergence oracle for the experiments harness.
     */
    public synchronized long digest() {
        long h = FNV_OFFSET;
        for (int i = 0; i < cells.length; i++) {
            if (cells[i] == null) {
                continue;
            }
            h = fold(h, i);
            h = fold(h, cells[i].getArgb());
        }
        return h;
    }

    private static long fold(long h, int value) {
        for (int shift = 24; shift >= 0; shift -= 8) {
            h ^= (value >>> shift) & 0xff;
            h *= FNV_PRIME;
        }
        return h;
    }
}
