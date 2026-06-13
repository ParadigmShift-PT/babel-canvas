package protocols.apps.canvas.telemetry;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Structured, machine-readable event emitter — the data contract between the
 * canvas demo and the {@code babel-canvas-experiments} correctness harness.
 *
 * <p>Each method writes exactly one line to the dedicated {@code canvas.telemetry}
 * logger, which log4j2 routes to a per-node telemetry file with an epoch-millis
 * prefix ({@code %d{UNIX_MILLIS}}, see {@code log4j2.xml}). So the event's time is
 * the line prefix — methods here never stamp their own. The line body is
 * {@code EVENT key=value key=value …} with no spaces inside values, which the
 * harness parses by splitting on whitespace then on {@code =}.
 *
 * <p>What the harness derives from these events:
 * <ul>
 *   <li><b>reliability</b> — {@code DELIVER} count per {@code op} across nodes ÷ live nodes;</li>
 *   <li><b>latency</b> — a node's {@code DELIVER} prefix-time minus the op's {@code BROADCAST} prefix-time;</li>
 *   <li><b>convergence</b> — all live nodes' final {@code DIGEST hash} agree;</li>
 *   <li><b>overlay health</b> — {@code VIEW size} stays within HyParView's active-view bound; {@code NEIGHBOR_*} churn.</li>
 * </ul>
 */
public final class Telemetry {

    private static final Logger log = LogManager.getLogger("canvas.telemetry");

    private final String nodeId;

    public Telemetry(String nodeId) {
        this.nodeId = nodeId;
    }

    /** Once at startup: records this node's identity and the run's key parameters. */
    public void start(int width, int height, String resolution, int fanout, boolean antiEntropy) {
        log.info("START node={} width={} height={} resolution={} fanout={} antientropy={}",
                nodeId, width, height, resolution, fanout, antiEntropy);
    }

    /** Emitted by the origin node when it issues a paint op. */
    public void broadcast(java.util.UUID opId, int x, int y, int argb) {
        log.info("BROADCAST node={} op={} x={} y={} argb={}", nodeId, opId, x, y, toHex(argb));
    }

    /** Emitted by every node (including the origin) when an op is delivered locally. */
    public void deliver(java.util.UUID opId, String originId) {
        log.info("DELIVER node={} op={} origin={}", nodeId, opId, originId);
    }

    /**
     * Periodic convergence digest of the local canvas. {@code delivered} is this
     * node's in-process count of distinct ops delivered — a robust coverage signal
     * the analyzer cross-checks against the (higher-volume, loss-prone) DELIVER lines.
     */
    public void digest(long tick, long hash, int paintedCells, int activeView, long delivered) {
        log.info("DIGEST node={} tick={} hash={} painted={} view={} delivered={}",
                nodeId, tick, Long.toHexString(hash), paintedCells, activeView, delivered);
    }

    public void neighborUp(String peer, int activeView) {
        log.info("NEIGHBOR_UP node={} peer={} view={}", nodeId, peer, activeView);
    }

    public void neighborDown(String peer, int activeView) {
        log.info("NEIGHBOR_DOWN node={} peer={} view={}", nodeId, peer, activeView);
    }

    private static String toHex(int argb) {
        return String.format("%08x", argb);
    }
}
