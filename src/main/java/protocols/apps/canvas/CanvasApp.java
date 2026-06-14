package protocols.apps.canvas;

import java.io.IOException;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ThreadLocalRandom;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import protocols.apps.canvas.messages.CanvasSyncMessage;
import protocols.apps.canvas.telemetry.Telemetry;
import protocols.apps.canvas.timers.DigestTimer;
import protocols.apps.canvas.timers.ReadinessTimer;
import protocols.apps.canvas.timers.WorkloadTimer;
import protocols.apps.canvas.ui.WebUi;
import pt.paradigmshift.babel.eagerpush.EagerPushGossipBroadcast;
import pt.unl.fct.di.novasys.babel.core.GenericProtocol;
import pt.unl.fct.di.novasys.babel.exceptions.HandlerRegistrationException;
import pt.unl.fct.di.novasys.babel.generic.ProtoMessage;
import pt.unl.fct.di.novasys.babel.generic.ProtoTimer;
import pt.unl.fct.di.novasys.babel.protocols.dissemination.notifications.BroadcastDelivery;
import pt.unl.fct.di.novasys.babel.protocols.dissemination.requests.BroadcastRequest;
import pt.unl.fct.di.novasys.babel.protocols.general.notifications.ChannelAvailableNotification;
import pt.unl.fct.di.novasys.babel.protocols.membership.notifications.NeighborDown;
import pt.unl.fct.di.novasys.babel.protocols.membership.notifications.NeighborUp;
import pt.unl.fct.di.novasys.network.data.Host;

/**
 * The collaborative-canvas application — the top of the stack, and the only
 * protocol a user (or experiment) drives directly. It ties the overlay layers
 * together:
 * <ul>
 *   <li><b>Paint operations</b> go out as a {@link BroadcastRequest} to the
 *       eager-push gossip protocol and come back — to everyone, including us —
 *       as {@link BroadcastDelivery} notifications. Both are the shared
 *       {@code babel-protocols-common} dissemination types. Each op is applied
 *       to a {@link PixelCanvas} under last-writer-wins, so all nodes converge.</li>
 *   <li><b>Snapshot sync</b> ({@link CanvasSyncMessage}) is point-to-point over
 *       HyParView's channel: on joining, a node asks one neighbour for the current
 *       canvas so it sees existing art immediately rather than waiting for new
 *       gossip.</li>
 *   <li><b>Presence</b> — this node's HyParView active view — is tracked from
 *       {@link NeighborUp}/{@link NeighborDown} and surfaced in the web UI.</li>
 * </ul>
 *
 * <h2>Two faces: interactive and headless</h2>
 * With {@code canvas.ui.enabled} (default) an embedded {@link WebUi} lets a human
 * paint and watch the canvas + neighbours. With {@code canvas.workload.enabled}
 * the node instead paints random cells at a fixed rate from a Babel timer — no UI,
 * no human — which is how {@code babel-canvas-experiments} drives load. Both faces
 * funnel through {@link #doPaint}; structured {@link Telemetry} is emitted either way.
 *
 * <h2>Threading note</h2>
 * The web server runs on its own threads; {@link #paintFromUi} and
 * {@link #stateJson} are called from there. {@code paintFromUi} only calls
 * {@code sendRequest} (which enqueues onto Babel's event loop) and the
 * thread-safe {@link Telemetry}; it never touches the canvas directly — the op is
 * applied when it is delivered back through {@link #uponDeliver} on the event loop.
 * {@code stateJson} reads the {@link PixelCanvas} (synchronized) and the
 * {@code neighbours} set (a {@link CopyOnWriteArraySet}). All other state is
 * event-loop-only.
 */
public class CanvasApp extends GenericProtocol {

    private static final Logger logger = LogManager.getLogger(CanvasApp.class);

    public static final short PROTO_ID = 300;
    public static final String PROTO_NAME = "CanvasApp";

    /** Property key — shared grid width in cells. Must match across all nodes. */
    public static final String PAR_WIDTH = "canvas.width";
    /** Default canvas width: {@value}. */
    public static final String DEFAULT_WIDTH = "48";
    /** Property key — shared grid height in cells. Must match across all nodes. */
    public static final String PAR_HEIGHT = "canvas.height";
    /** Default canvas height: {@value}. */
    public static final String DEFAULT_HEIGHT = "48";

    /** Property key — enable the embedded web UI. */
    public static final String PAR_UI_ENABLED = "canvas.ui.enabled";
    /** Default for {@link #PAR_UI_ENABLED}: {@value}. */
    public static final String DEFAULT_UI_ENABLED = "true";
    /** Property key — web UI port. Defaults to {@code babel.port + 2000} when unset. */
    public static final String PAR_UI_PORT = "canvas.ui.port";
    /** Offset added to the bind port to derive the default UI port. */
    public static final int DEFAULT_UI_PORT_OFFSET = 2000;
    /** Property key — open the system browser at the UI on startup (best-effort). */
    public static final String PAR_UI_OPEN = "canvas.ui.open";
    /** Default for {@link #PAR_UI_OPEN}: {@value}. */
    public static final String DEFAULT_UI_OPEN = "true";

    /** Property key — request a canvas snapshot from a neighbour on join. */
    public static final String PAR_SNAPSHOT_SYNC = "canvas.snapshot.sync";
    /** Default for {@link #PAR_SNAPSHOT_SYNC}: {@value}. */
    public static final String DEFAULT_SNAPSHOT_SYNC = "true";

    /** Property key — period (ms) between convergence-digest telemetry lines; {@code <= 0} disables. */
    public static final String PAR_DIGEST_INTERVAL = "canvas.digest.interval";
    /** Default for {@link #PAR_DIGEST_INTERVAL}: {@value} ms. */
    public static final String DEFAULT_DIGEST_INTERVAL = "5000";

    /** Property key — enable the headless random-paint workload driver. */
    public static final String PAR_WORKLOAD_ENABLED = "canvas.workload.enabled";
    /** Default for {@link #PAR_WORKLOAD_ENABLED}: {@value}. */
    public static final String DEFAULT_WORKLOAD_ENABLED = "false";
    /** Property key — workload paint operations per second. */
    public static final String PAR_WORKLOAD_RATE = "canvas.workload.rate";
    /** Default for {@link #PAR_WORKLOAD_RATE}: {@value} ops/s. */
    public static final String DEFAULT_WORKLOAD_RATE = "2";
    /** Property key — how long (ms) to keep painting; {@code <= 0} = until stopped. */
    public static final String PAR_WORKLOAD_DURATION = "canvas.workload.duration";
    /** Default for {@link #PAR_WORKLOAD_DURATION}: {@value} ms. */
    public static final String DEFAULT_WORKLOAD_DURATION = "0";
    /** Property key — delay (ms) before the first workload paint, to let the overlay form. */
    public static final String PAR_WORKLOAD_START_DELAY = "canvas.workload.startDelay";
    /** Default for {@link #PAR_WORKLOAD_START_DELAY}: {@value} ms. */
    public static final String DEFAULT_WORKLOAD_START_DELAY = "5000";

    // ── Readiness gate ───────────────────────────────────────────────────────────
    // Past the start-delay floor, defer the first paint until the LOCAL overlay has
    // settled, so we don't broadcast onto a half-formed view and lose information that
    // only state reconciliation could later recover. The gate is OFF (legacy behaviour:
    // paint exactly at startDelay) when stabilizeView <= 0 and stabilizeQuietMs <= 0.

    /** Property key — require at least this many active-view neighbours before painting; {@code <= 0} disables. */
    public static final String PAR_WORKLOAD_STABILIZE_VIEW = "canvas.workload.stabilizeView";
    /** Default for {@link #PAR_WORKLOAD_STABILIZE_VIEW}: {@value} (gate off). */
    public static final String DEFAULT_WORKLOAD_STABILIZE_VIEW = "0";
    /** Property key — require no neighbour churn for this long (ms) before painting; {@code <= 0} disables. */
    public static final String PAR_WORKLOAD_STABILIZE_QUIET_MS = "canvas.workload.stabilizeQuietMs";
    /** Default for {@link #PAR_WORKLOAD_STABILIZE_QUIET_MS}: {@value} ms (gate off). */
    public static final String DEFAULT_WORKLOAD_STABILIZE_QUIET_MS = "0";
    /** Property key — start painting anyway after waiting this long (ms) past the floor for the gate. */
    public static final String PAR_WORKLOAD_STABILIZE_TIMEOUT_MS = "canvas.workload.stabilizeTimeoutMs";
    /** Default for {@link #PAR_WORKLOAD_STABILIZE_TIMEOUT_MS}: {@value} ms. */
    public static final String DEFAULT_WORKLOAD_STABILIZE_TIMEOUT_MS = "60000";
    /** Property key — how often (ms) to re-check readiness while gating. */
    public static final String PAR_WORKLOAD_STABILIZE_CHECK_MS = "canvas.workload.stabilizeCheckMs";
    /** Default for {@link #PAR_WORKLOAD_STABILIZE_CHECK_MS}: {@value} ms. */
    public static final String DEFAULT_WORKLOAD_STABILIZE_CHECK_MS = "500";

    private final Host myself;
    private final String nodeId;
    private final short broadcastProtoId;
    private final short membershipProtoId;

    private final PixelCanvas canvas;
    private final Telemetry telemetry;

    private final boolean snapshotSync;
    private final long digestInterval;
    private final boolean workloadEnabled;
    private final long workloadPeriodMs;
    private final long workloadDuration;
    private final long workloadStartDelay;
    private final int stabilizeView;
    private final long stabilizeQuietMs;
    private final long stabilizeTimeoutMs;
    private final long stabilizeCheckMs;
    private final boolean gateEnabled;
    private final boolean uiEnabled;
    private final int uiPort;
    private final boolean uiOpen;

    // For the telemetry START line.
    private final String resolutionMode;
    private final int fanout;
    private final boolean antiEntropy;

    /** This node's HyParView active view; read by the UI thread, so concurrent. */
    private final CopyOnWriteArraySet<Host> neighbours = new CopyOnWriteArraySet<>();

    private int channelId = -1;
    private boolean channelReady = false;
    private boolean snapshotRequested = false;
    private long digestTick = 0;
    private long bootMillis = -1;          // init() wall-clock — origin for readiness timing
    private long lastChurnMillis = -1;     // last NeighborUp/Down — feeds the readiness quiet check
    private long readinessStartMillis = -1; // first readiness poll — origin for the gate timeout
    private boolean workloadStarted = false;
    private long workloadStartMillis = -1;
    private long deliveredOps = 0; // distinct ops delivered via gossip (robust coverage signal)

    private WebUi ui;

    /**
     * @param props            protocol configuration; see the {@code PAR_*} constants
     * @param myself           this node's membership-space {@link Host} (HyParView's bind endpoint)
     * @param broadcastProtoId the eager-push gossip protocol id (paint ops are sent there)
     * @param membershipProtoId the HyParView protocol id (its channel is reused for snapshot sync)
     */
    public CanvasApp(Properties props, Host myself, short broadcastProtoId, short membershipProtoId)
            throws HandlerRegistrationException, IOException {
        super(PROTO_NAME, PROTO_ID);
        this.myself = myself;
        this.nodeId = myself.getAddress().getHostAddress() + ":" + myself.getPort();
        this.broadcastProtoId = broadcastProtoId;
        this.membershipProtoId = membershipProtoId;

        int width = readInt(props, PAR_WIDTH, DEFAULT_WIDTH);
        int height = readInt(props, PAR_HEIGHT, DEFAULT_HEIGHT);
        this.canvas = new PixelCanvas(width, height);
        this.telemetry = new Telemetry(nodeId);

        this.snapshotSync = readBool(props, PAR_SNAPSHOT_SYNC, DEFAULT_SNAPSHOT_SYNC);
        this.digestInterval = readLong(props, PAR_DIGEST_INTERVAL, DEFAULT_DIGEST_INTERVAL);
        this.uiEnabled = readBool(props, PAR_UI_ENABLED, DEFAULT_UI_ENABLED);
        this.uiPort = readInt(props, PAR_UI_PORT,
                Integer.toString(myself.getPort() + DEFAULT_UI_PORT_OFFSET));
        this.uiOpen = readBool(props, PAR_UI_OPEN, DEFAULT_UI_OPEN);

        this.workloadEnabled = readBool(props, PAR_WORKLOAD_ENABLED, DEFAULT_WORKLOAD_ENABLED);
        int rate = Math.max(1, readInt(props, PAR_WORKLOAD_RATE, DEFAULT_WORKLOAD_RATE));
        this.workloadPeriodMs = Math.max(1, 1000L / rate);
        this.workloadDuration = readLong(props, PAR_WORKLOAD_DURATION, DEFAULT_WORKLOAD_DURATION);
        this.workloadStartDelay = readLong(props, PAR_WORKLOAD_START_DELAY, DEFAULT_WORKLOAD_START_DELAY);
        this.stabilizeView = readInt(props, PAR_WORKLOAD_STABILIZE_VIEW, DEFAULT_WORKLOAD_STABILIZE_VIEW);
        this.stabilizeQuietMs = readLong(props, PAR_WORKLOAD_STABILIZE_QUIET_MS, DEFAULT_WORKLOAD_STABILIZE_QUIET_MS);
        this.stabilizeTimeoutMs = readLong(props, PAR_WORKLOAD_STABILIZE_TIMEOUT_MS, DEFAULT_WORKLOAD_STABILIZE_TIMEOUT_MS);
        this.stabilizeCheckMs = Math.max(1, readLong(props, PAR_WORKLOAD_STABILIZE_CHECK_MS, DEFAULT_WORKLOAD_STABILIZE_CHECK_MS));
        this.gateEnabled = stabilizeView > 0 || stabilizeQuietMs > 0;

        this.resolutionMode = props.getProperty(EagerPushGossipBroadcast.PAR_PEER_ADDRESS_RESOLUTION,
                EagerPushGossipBroadcast.DEFAULT_PEER_ADDRESS_RESOLUTION);
        this.fanout = readInt(props, EagerPushGossipBroadcast.PAR_FANOUT,
                EagerPushGossipBroadcast.DEFAULT_FANOUT);
        this.antiEntropy = readBool(props, EagerPushGossipBroadcast.PAR_SUPPORT_ANTIENTROPY,
                Boolean.toString(EagerPushGossipBroadcast.DEFAULT_SUPPORT_ANTIENTROPY));

        // Paint deliveries, membership changes, and the (HyParView) shared channel.
        subscribeNotification(BroadcastDelivery.NOTIFICATION_ID, this::uponDeliver);
        subscribeNotification(NeighborUp.NOTIFICATION_ID, this::uponNeighborUp);
        subscribeNotification(NeighborDown.NOTIFICATION_ID, this::uponNeighborDown);
        subscribeNotification(ChannelAvailableNotification.NOTIFICATION_ID, this::uponChannelAvailable);

        registerTimerHandler(DigestTimer.TIMER_ID, this::uponDigestTimer);
        registerTimerHandler(WorkloadTimer.TIMER_ID, this::uponWorkloadTimer);
        registerTimerHandler(ReadinessTimer.TIMER_ID, this::uponReadinessTimer);
    }

    @Override
    public void init(Properties props) {
        bootMillis = System.currentTimeMillis();
        telemetry.start(canvas.getWidth(), canvas.getHeight(), resolutionMode, fanout, antiEntropy);

        if (digestInterval > 0) {
            setupPeriodicTimer(new DigestTimer(), digestInterval, digestInterval);
        }
        if (workloadEnabled) {
            logger.info("Headless workload: {} op(s) every {} ms, start +{} ms, duration {}{}",
                    1, workloadPeriodMs, workloadStartDelay,
                    workloadDuration > 0 ? workloadDuration + " ms" : "unbounded",
                    gateEnabled ? " — gated on view>=" + stabilizeView + ", quiet " + stabilizeQuietMs
                            + "ms (cap " + stabilizeTimeoutMs + "ms)" : "");
            if (gateEnabled) {
                // Past the start-delay floor, poll readiness; uponReadinessTimer begins
                // painting once the local overlay settles, or when the cap fires.
                setupPeriodicTimer(new ReadinessTimer(), workloadStartDelay, stabilizeCheckMs);
            } else {
                // Legacy/demo path: paint exactly at the floor.
                setupTimer(new ReadinessTimer(), workloadStartDelay);
            }
        }
        if (uiEnabled) {
            ui = new WebUi(uiPort, this);
            try {
                ui.start();
                String url = "http://localhost:" + uiPort + "/";
                logger.info("Canvas web UI on {}", url);
                if (uiOpen) {
                    openInBrowser(url);
                }
            } catch (IOException e) {
                logger.error("Failed to start web UI on port {} — continuing headless", uiPort, e);
                ui = null;
            }
        }
    }

    /* ─────────── Attach our snapshot-sync handler to HyParView's channel ─────────── */

    private void uponChannelAvailable(ChannelAvailableNotification notification, short sourceProto) {
        // We reuse HyParView's channel specifically: the peers NeighborUp reports
        // live there, so a notification Host is directly reachable with no port
        // translation. eager-push also announces a channel — ignore that one.
        if (channelReady || notification.getProtoSource() != membershipProtoId) {
            return;
        }
        this.channelId = notification.getChannelID();
        registerSharedChannel(channelId);
        registerMessageSerializer(channelId, CanvasSyncMessage.MSG_ID, CanvasSyncMessage.serializer);
        try {
            registerMessageHandler(channelId, CanvasSyncMessage.MSG_ID, this::uponSync, this::uponSyncFail);
        } catch (HandlerRegistrationException e) {
            logger.error("Failed to register canvas sync handler", e);
            return;
        }
        channelReady = true;
        logger.debug("Attached snapshot sync to membership channel {}", channelId);
        maybeRequestSnapshot();
    }

    /* ───────────────────────── Membership notifications ───────────────────────── */

    private void uponNeighborUp(NeighborUp notification, short sourceProto) {
        Host h = notification.getPeer();
        neighbours.add(h);
        lastChurnMillis = System.currentTimeMillis();
        telemetry.neighborUp(hostId(h), neighbours.size());
        maybeRequestSnapshot();
    }

    private void uponNeighborDown(NeighborDown notification, short sourceProto) {
        Host h = notification.getPeer();
        neighbours.remove(h);
        lastChurnMillis = System.currentTimeMillis();
        telemetry.neighborDown(hostId(h), neighbours.size());
    }

    /** {@code ip:port} identity of a peer — matches this node's own {@link #nodeId} format. */
    private static String hostId(Host h) {
        return h.getAddress().getHostAddress() + ":" + h.getPort();
    }

    /** Active-view set as {@code ip:port} joined by {@code ;} (empty string when no neighbours). */
    private String peersString() {
        StringBuilder b = new StringBuilder();
        for (Host h : neighbours) {
            if (b.length() > 0) {
                b.append(';');
            }
            b.append(hostId(h));
        }
        return b.toString();
    }

    /* ───────────────────────── Snapshot sync (point-to-point) ──────────────────── */

    /** Ask one neighbour for the current canvas, once, after the channel is ready. */
    private void maybeRequestSnapshot() {
        if (!snapshotSync || snapshotRequested || !channelReady) {
            return;
        }
        Host peer = neighbours.stream().findFirst().orElse(null);
        if (peer == null) {
            return;
        }
        snapshotRequested = true;
        sendMessage(channelId, new CanvasSyncMessage(CanvasSyncMessage.Kind.REQUEST, null), peer);
        telemetry.syncRequest(hostId(peer));
        logger.debug("Requested canvas snapshot from {}", peer);
    }

    private void uponSync(CanvasSyncMessage msg, Host from, short sourceProto, int channelId) {
        switch (msg.getKind()) {
            case REQUEST -> {
                CanvasSyncMessage reply = new CanvasSyncMessage(CanvasSyncMessage.Kind.REPLY, canvas.snapshotOps());
                sendMessage(this.channelId, reply, from);
                telemetry.syncServe(hostId(from), reply.getOps().size());
                logger.debug("Served canvas snapshot ({} ops) to {}", reply.getOps().size(), from);
            }
            case REPLY -> {
                // mergeSnapshot applies ops via LWW WITHOUT going through uponDeliver, so
                // `applied` is state this node obtained by reconciliation, not gossip — it
                // does NOT bump deliveredOps. SYNC_MERGE is how the analyzer separates the
                // eager-push delivery share from the snapshot-repair share of convergence.
                int ops = msg.getOps().size();
                int applied = canvas.mergeSnapshot(msg.getOps());
                telemetry.syncMerge(hostId(from), ops, applied, neighbours.size());
                logger.debug("Merged snapshot from {}: {} of {} ops applied (LWW)", from, applied, ops);
            }
        }
    }

    private void uponSyncFail(ProtoMessage msg, Host host, short destProto, Throwable t, int channelId) {
        logger.warn("Canvas sync message to {} failed: {}", host, t.toString());
        // Allow a later neighbour to be asked instead.
        snapshotRequested = false;
    }

    /* ─────────────────────── Paint deliveries (broadcast) ──────────────────────── */

    private void uponDeliver(BroadcastDelivery notification, short sourceProto) {
        PaintOp op = CanvasPayload.decode(notification.getPayload());
        canvas.apply(op);
        deliveredOps++;
        telemetry.deliver(op.getOpId(), op.getOriginId());
    }

    /* ─────────────────────────────── Timers ─────────────────────────────────────── */

    private void uponDigestTimer(DigestTimer timer, long timerId) {
        digestTick++;
        telemetry.digest(digestTick, canvas.digest(), canvas.paintedCount(),
                neighbours.size(), deliveredOps, peersString());
    }

    /**
     * Readiness gate (see {@link ReadinessTimer}). When gating is on this fires
     * periodically after the start-delay floor: it begins painting as soon as the local
     * active view is filled and neighbour churn has quiesced, or when the timeout cap
     * elapses (then flagged unstable). When gating is off it is a one-shot that just
     * begins painting at the floor.
     */
    private void uponReadinessTimer(ReadinessTimer timer, long timerId) {
        if (workloadStarted) {
            cancelTimer(timerId);
            return;
        }
        if (!gateEnabled) {
            beginPainting(true);
            return;
        }
        long now = System.currentTimeMillis();
        if (readinessStartMillis < 0) {
            readinessStartMillis = now;
        }
        boolean viewReady = neighbours.size() >= stabilizeView;
        boolean quietReady = stabilizeQuietMs <= 0
                || (lastChurnMillis >= 0 && now - lastChurnMillis >= stabilizeQuietMs);
        boolean timedOut = now - readinessStartMillis >= stabilizeTimeoutMs;
        if ((viewReady && quietReady) || timedOut) {
            if (timedOut && !(viewReady && quietReady)) {
                logger.warn("Readiness gate timed out after {} ms (view={}, quiet={}) — painting anyway",
                        now - readinessStartMillis, neighbours.size(),
                        lastChurnMillis < 0 ? "n/a" : (now - lastChurnMillis) + "ms");
            }
            cancelTimer(timerId);
            beginPainting(viewReady && quietReady);
        }
    }

    /** Emit PAINT_START and kick off the paint workload (idempotent). */
    private void beginPainting(boolean stable) {
        if (workloadStarted) {
            return;
        }
        workloadStarted = true;
        long sinceBoot = bootMillis < 0 ? -1 : System.currentTimeMillis() - bootMillis;
        telemetry.paintStart(neighbours.size(), sinceBoot, stable);
        logger.info("Painting begins: view={} sinceBoot={}ms stable={}", neighbours.size(), sinceBoot, stable);
        setupPeriodicTimer(new WorkloadTimer(), 0, workloadPeriodMs);
    }

    private void uponWorkloadTimer(WorkloadTimer timer, long timerId) {
        long now = System.currentTimeMillis();
        if (workloadStartMillis < 0) {
            workloadStartMillis = now;
        }
        if (workloadDuration > 0 && now - workloadStartMillis > workloadDuration) {
            cancelTimer(timerId);
            logger.info("Workload finished after {} ms", now - workloadStartMillis);
            return;
        }
        int x = ThreadLocalRandom.current().nextInt(canvas.getWidth());
        int y = ThreadLocalRandom.current().nextInt(canvas.getHeight());
        int argb = 0xFF000000 | ThreadLocalRandom.current().nextInt(0x01000000);
        doPaint(x, y, argb);
    }

    /* ─────────────────────────── Paint path (shared) ───────────────────────────── */

    /**
     * Issue one paint op: stamp it, record the broadcast, and hand it to gossip.
     * The op is applied to our own canvas when it is delivered back to us through
     * {@link #uponDeliver} — so origin and remote nodes take the identical path.
     */
    private void doPaint(int x, int y, int argb) {
        long ts = System.currentTimeMillis();
        UUID opId = UUID.randomUUID();
        PaintOp op = new PaintOp(x, y, argb, ts, nodeId, opId);
        telemetry.broadcast(opId, x, y, argb);
        sendRequest(new BroadcastRequest(myself, CanvasPayload.encode(op), PROTO_ID), broadcastProtoId);
    }

    /* ───────────────────────────── Web UI surface ──────────────────────────────── */

    /**
     * Paint a cell from the web UI. {@code rgb} is a 24-bit colour (0xRRGGBB); we
     * force it opaque. Safe to call from the web-server thread (see the class
     * threading note).
     */
    public void paintFromUi(int x, int y, int rgb) {
        if (x < 0 || x >= canvas.getWidth() || y < 0 || y >= canvas.getHeight()) {
            return;
        }
        doPaint(x, y, 0xFF000000 | (rgb & 0xFFFFFF));
    }

    /** A JSON snapshot of canvas + neighbours for the web UI's polling loop. */
    public String stateJson() {
        int[] cells = canvas.snapshotArgb();
        StringBuilder b = new StringBuilder(cells.length * 6 + 128);
        b.append("{\"node\":\"").append(nodeId).append('"')
                .append(",\"width\":").append(canvas.getWidth())
                .append(",\"height\":").append(canvas.getHeight())
                .append(",\"painted\":").append(canvas.paintedCount())
                .append(",\"neighbours\":[");
        boolean first = true;
        for (Host h : neighbours) {
            if (!first) {
                b.append(',');
            }
            first = false;
            b.append('"').append(h.getAddress().getHostAddress()).append(':').append(h.getPort()).append('"');
        }
        b.append("],\"cells\":[");
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append(cells[i]);
        }
        b.append("]}");
        return b.toString();
    }

    public int getUiPort() {
        return uiPort;
    }

    public boolean isUiEnabled() {
        return uiEnabled;
    }

    /**
     * Best-effort: open the system's default browser at {@code url} so launching a
     * node pops up its canvas. Shells out to the platform opener
     * ({@code open} / {@code xdg-open} / {@code rundll32}) rather than touching AWT;
     * failures are logged and ignored (e.g. on a headless host — open the printed
     * URL by hand instead). The browser, for security, chooses its own window size;
     * the page is laid out to sit comfortably at a normal window.
     */
    private void openInBrowser(String url) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String[] cmd;
        if (os.contains("mac")) {
            cmd = new String[] {"open", url};
        } else if (os.contains("win")) {
            cmd = new String[] {"rundll32", "url.dll,FileProtocolHandler", url};
        } else {
            cmd = new String[] {"xdg-open", url};
        }
        try {
            new ProcessBuilder(cmd).start();
        } catch (IOException e) {
            logger.debug("Could not auto-open a browser ({}); open {} manually", e.getMessage(), url);
        }
    }

    /* ────────────────────────────── Config helpers ─────────────────────────────── */

    private static int readInt(Properties p, String key, String def) {
        return Integer.parseInt(p.getProperty(key, def).trim());
    }

    private static long readLong(Properties p, String key, String def) {
        return Long.parseLong(p.getProperty(key, def).trim());
    }

    private static boolean readBool(Properties p, String key, String def) {
        return Boolean.parseBoolean(p.getProperty(key, def).trim());
    }
}
