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
 *   <li><b>overlay health</b> — {@code DIGEST view}/{@code peers} stay within HyParView's
 *       active-view bound; {@code NEIGHBOR_*} churn; {@code peers} gives a time-aligned
 *       active-view set for symmetry;</li>
 *   <li><b>gossip vs. repair</b> — {@code SYNC_MERGE applied} counts ops a node obtained from a
 *       snapshot (and AE) rather than gossip {@code DELIVER}, so coverage can be split into the
 *       eager-push share and the state-reconciliation share;</li>
 *   <li><b>readiness</b> — {@code PAINT_START stable} says whether the overlay was settled when a
 *       node began broadcasting (an unsettled start loses gossip information).</li>
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
     * node's in-process count of distinct ops delivered via gossip — a robust coverage
     * signal the analyzer cross-checks against the (higher-volume, loss-prone) DELIVER
     * lines. {@code peers} is the active-view set ({@code ip:port} joined by {@code ;},
     * empty when the view is empty): a time-stamped membership snapshot from which the
     * analyzer reconstructs active-view symmetry at a common instant, robust to churn.
     */
    public void digest(long tick, long hash, int paintedCells, int activeView, long delivered, String peers) {
        log.info("DIGEST node={} tick={} hash={} painted={} view={} delivered={} peers={}",
                nodeId, tick, Long.toHexString(hash), paintedCells, activeView, delivered, peers);
    }

    public void neighborUp(String peer, int activeView) {
        log.info("NEIGHBOR_UP node={} peer={} view={}", nodeId, peer, activeView);
    }

    public void neighborDown(String peer, int activeView) {
        log.info("NEIGHBOR_DOWN node={} peer={} view={}", nodeId, peer, activeView);
    }

    /** The local node began its paint workload. {@code stable} is whether the overlay
     *  met the readiness gate (view filled, churn quiesced) or the wait timed out first. */
    public void paintStart(int activeView, long sinceBootMs, boolean stable) {
        log.info("PAINT_START node={} view={} sinceBootMs={} stable={}",
                nodeId, activeView, sinceBootMs, stable);
    }

    /** This node asked {@code peer} for a canvas snapshot (point-to-point, once it has a neighbour). */
    public void syncRequest(String peer) {
        log.info("SYNC_REQUEST node={} peer={}", nodeId, peer);
    }

    /** This node served its canvas snapshot ({@code ops} winning ops) to {@code to}. */
    public void syncServe(String to, int ops) {
        log.info("SYNC_SERVE node={} to={} ops={}", nodeId, to, ops);
    }

    /**
     * This node merged a snapshot reply from {@code from}: {@code ops} ops received,
     * {@code applied} of them won LWW (state this node obtained by reconciliation rather
     * than gossip — invisible to the {@code delivered} counter and DELIVER lines).
     */
    public void syncMerge(String from, int ops, int applied, int activeView) {
        log.info("SYNC_MERGE node={} from={} ops={} applied={} view={}",
                nodeId, from, ops, applied, activeView);
    }

    private static String toHex(int argb) {
        return String.format("%08x", argb);
    }
}
