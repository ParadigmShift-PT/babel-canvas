package protocols.apps.canvas;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

/**
 * A single paint operation: "cell ({@code x},{@code y}) becomes colour
 * {@code argb}". This is the atomic unit the canvas disseminates by gossip and
 * the unit it stores per cell.
 *
 * <h2>Last-writer-wins (why the canvas converges)</h2>
 * Each op carries a {@code timestamp} (the painter's wall clock at paint time)
 * and the painter's {@code originId}. A cell keeps whichever op is the
 * <em>winner</em> under the total order in {@link #isNewerThan}: higher
 * timestamp first, ties broken by {@code originId} then {@code opId}. Because
 * that order is total and deterministic, any two nodes that have applied the
 * <em>same set</em> of ops hold the identical winner in every cell —
 * independent of the order the gossip waves delivered them. Convergence is
 * therefore a property of dissemination completeness alone, which is exactly
 * what the experiments harness measures.
 *
 * <p>{@code opId} is a globally-unique identifier for the op, assigned once at
 * the origin. It is the final tie-breaker, and the stable key the telemetry
 * uses to correlate "broadcast on node A" with "delivered on node B".
 */
public final class PaintOp {

    private final int x;
    private final int y;
    private final int argb;
    private final long timestamp;
    private final String originId;
    private final UUID opId;

    public PaintOp(int x, int y, int argb, long timestamp, String originId, UUID opId) {
        this.x = x;
        this.y = y;
        this.argb = argb;
        this.timestamp = timestamp;
        this.originId = originId;
        this.opId = opId;
    }

    public int getX() { return x; }
    public int getY() { return y; }
    public int getArgb() { return argb; }
    public long getTimestamp() { return timestamp; }
    public String getOriginId() { return originId; }
    public UUID getOpId() { return opId; }

    /**
     * Total, deterministic last-writer-wins order: this op beats {@code other}
     * iff it has a strictly higher timestamp, or an equal timestamp and a
     * greater {@code originId}, or equal on both and a greater {@code opId}.
     * {@code other} may be {@code null} (an empty cell), which this always wins.
     */
    public boolean isNewerThan(PaintOp other) {
        if (other == null) {
            return true;
        }
        if (timestamp != other.timestamp) {
            return timestamp > other.timestamp;
        }
        int byOrigin = originId.compareTo(other.originId);
        if (byOrigin != 0) {
            return byOrigin > 0;
        }
        return opId.compareTo(other.opId) > 0;
    }

    /** Append this op to {@code out} (see {@link #readFrom} for the inverse). */
    public void writeTo(DataOutputStream out) throws IOException {
        out.writeInt(x);
        out.writeInt(y);
        out.writeInt(argb);
        out.writeLong(timestamp);
        out.writeUTF(originId);
        out.writeLong(opId.getMostSignificantBits());
        out.writeLong(opId.getLeastSignificantBits());
    }

    /** Read one op previously written by {@link #writeTo}. */
    public static PaintOp readFrom(DataInputStream in) throws IOException {
        int x = in.readInt();
        int y = in.readInt();
        int argb = in.readInt();
        long timestamp = in.readLong();
        String originId = in.readUTF();
        long hi = in.readLong();
        long lo = in.readLong();
        return new PaintOp(x, y, argb, timestamp, originId, new UUID(hi, lo));
    }

    @Override
    public String toString() {
        return "PaintOp{(" + x + "," + y + ") argb=" + Integer.toHexString(argb)
                + " ts=" + timestamp + " origin=" + originId + " op=" + opId + '}';
    }
}
