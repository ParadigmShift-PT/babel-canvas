import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.InvalidParameterException;
import java.util.Properties;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import protocols.apps.canvas.CanvasApp;
import pt.paradigmshift.babel.antientropy.AntiEntropy;
import pt.paradigmshift.babel.eagerpush.EagerPushGossipBroadcast;
import pt.paradigmshift.babel.hyparview.HyParView;
import pt.unl.fct.di.novasys.babel.core.Babel;
import pt.unl.fct.di.novasys.babel.core.protocols.discovery.MulticastDiscoveryProtocol;
import pt.unl.fct.di.novasys.channel.tcp.TCPChannel;
import pt.unl.fct.di.novasys.network.data.Host;
import utils.InterfaceToIp;

/**
 * Entry point for babel-canvas — a peer-to-peer collaborative pixel canvas.
 *
 * <p>The demo composes the real ParadigmShift overlay stack and lets the Babel
 * runtime wire it together through asynchronous events:
 * <ol>
 *   <li>{@link HyParView} — partial-view membership (the gossip overlay), which
 *       auto-discovers peers on the LAN (it's a {@code DiscoverableProtocol});</li>
 *   <li>{@link EagerPushGossipBroadcast} — disseminates paint operations to the overlay;</li>
 *   <li>{@link AntiEntropy} — optional bloom-filter reconciliation that recovers
 *       missed paint ops (enabled with {@code canvas.antientropy.enabled});</li>
 *   <li>{@link CanvasApp} — the collaborative canvas + web UI on top.</li>
 * </ol>
 *
 * <p>Launch: {@code java -jar babel-canvas.jar [babel.port=<port>]
 * [babel.interface=<nic>] [babel.address=<ip>] [HyParView.contact=<host>:<port>]
 * [canvas.ui.port=<port>]}. To run several nodes on one machine, give each a
 * distinct {@code babel.port} spaced by &ge; 10 (eager-push binds
 * {@code babel.port+1} in the default offset mode) and a distinct
 * {@code babel.discovery.unicast.port}.
 */
public class Main {

    // Point log4j at our bundled configuration before any logger is created.
    static {
        System.setProperty("log4j.configurationFile", "log4j2.xml");
    }

    /** Default Babel configuration file (overridable with the "-config" launch arg). */
    private static final String DEFAULT_CONF = "babel_config.properties";

    /**
     * Default value for the TCP port ({@link Babel#PAR_DEFAULT_PORT}, i.e.
     * {@code babel.port}) HyParView binds. babel-core owns the key but defines no
     * default value, so the demo supplies one here.
     */
    public static final String PAR_DEFAULT_BABEL_PORT = "6000";

    /**
     * Property key — load and wire the anti-entropy reconciliation protocol. When
     * {@code true}, Main turns on {@code EagerPushGossipBroadcast.SupportAntiEntropy}
     * and gives {@link AntiEntropy} its own channel at {@code babel.port + 2}.
     */
    public static final String PAR_ANTIENTROPY_ENABLED = "canvas.antientropy.enabled";

    /** Port offset (relative to {@code babel.port}) for anti-entropy's own channel. */
    private static final int ANTIENTROPY_PORT_OFFSET = 2;

    public static void main(String[] args) throws Exception {

        // Give each node its own log files (so two nodes on one machine don't write
        // to the same file). This must happen BEFORE any logger is created.
        String port = argValue(args, Babel.PAR_DEFAULT_PORT, PAR_DEFAULT_BABEL_PORT);
        System.setProperty("babelcanvas.logfile", "babel-canvas-" + port + ".log");
        System.setProperty("babelcanvas.telemetryfile", "babel-canvas-telemetry-" + port + ".log");

        Logger logger = LogManager.getLogger(Main.class);

        Babel babel = Babel.getInstance();
        Properties props = Babel.loadConfig(args, DEFAULT_CONF);

        // Resolve a reachable bind address into babel.address (explicit babel.address
        // wins, else babel.interface, else auto-detect the sole physical NIC). We
        // never default to loopback silently — discovery and peers need a reachable
        // address. On failure, tell the operator how to fix it and exit cleanly.
        String addressSource;
        try {
            addressSource = InterfaceToIp.resolveBindAddress(props);
        } catch (InvalidParameterException e) {
            System.err.println("babel-canvas — cannot determine a bind address.\n");
            System.err.println(e.getMessage());
            System.exit(1);
            return; // unreachable; keeps the compiler happy
        }

        String bindAddress = props.getProperty(Babel.PAR_DEFAULT_ADDRESS);
        int bindPort = Integer.parseInt(props.getProperty(Babel.PAR_DEFAULT_PORT, PAR_DEFAULT_BABEL_PORT));
        Host myself = new Host(InetAddress.getByName(bindAddress), bindPort);

        boolean antiEntropy = Boolean.parseBoolean(props.getProperty(PAR_ANTIENTROPY_ENABLED, "false"));
        if (antiEntropy) {
            // Turn on the gossip protocol's anti-entropy hooks and give the AntiEntropy
            // protocol its own channel (babel.port+2) so it doesn't collide with
            // HyParView (babel.port) or eager-push (babel.port+1, offset mode).
            props.setProperty(EagerPushGossipBroadcast.PAR_SUPPORT_ANTIENTROPY, "true");
            props.setProperty("AntiEntropy.Channel.Address", bindAddress);
            props.setProperty("AntiEntropy.Channel.Port", Integer.toString(bindPort + ANTIENTROPY_PORT_OFFSET));
        }

        logger.info("babel-canvas starting — host={}, antiEntropy={}", myself, antiEntropy);
        logger.info(InterfaceToIp.describeInterfaces());
        printStartupBanner(props, myself, addressSource, antiEntropy);

        // Build the protocol stack. HyParView owns the membership channel and (when
        // discovery is enabled) auto-finds peers; eager-push owns the dissemination
        // channel; the canvas rides on top.
        HyParView membership = new HyParView(TCPChannel.NAME, props, myself);
        EagerPushGossipBroadcast broadcast = new EagerPushGossipBroadcast(TCPChannel.NAME, props, myself);
        AntiEntropy reconciliation = antiEntropy ? new AntiEntropy(props, myself) : null;
        CanvasApp canvas = new CanvasApp(props, myself,
                EagerPushGossipBroadcast.PROTOCOL_ID, HyParView.PROTOCOL_ID);

        babel.registerProtocol(membership);
        babel.registerProtocol(broadcast);
        if (reconciliation != null) {
            babel.registerProtocol(reconciliation);
        }
        babel.registerProtocol(canvas);

        membership.init(props);
        broadcast.init(props);
        if (reconciliation != null) {
            reconciliation.init(props);
        }
        canvas.init(props);

        babel.start();

        logger.info("babel-canvas up ({})", myself);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> logger.info("babel-canvas shutting down")));
    }

    /**
     * Print a concise startup summary to stdout: the address/port we bound and how
     * it was chosen, the gossip/UI endpoints, the discovery configuration, and how
     * we bootstrap. Makes it obvious at a glance which interface/IP a node uses and
     * whether auto-discovery is engaged.
     */
    private static void printStartupBanner(Properties props, Host myself, String addressSource, boolean antiEntropy) {
        String iface;
        try {
            NetworkInterface nif = NetworkInterface.getByInetAddress(myself.getAddress());
            iface = (nif != null) ? nif.getName() : "?";
        } catch (Exception e) {
            iface = "?";
        }

        String resolution = props.getProperty(EagerPushGossipBroadcast.PAR_PEER_ADDRESS_RESOLUTION,
                EagerPushGossipBroadcast.DEFAULT_PEER_ADDRESS_RESOLUTION);
        int offset = Integer.parseInt(props.getProperty(EagerPushGossipBroadcast.PAR_PORT_OFFSET,
                EagerPushGossipBroadcast.DEFAULT_PORT_OFFSET));
        String gossipEndpoint = switch (resolution) {
            case EagerPushGossipBroadcast.RESOLUTION_OFFSET ->
                    myself.getAddress().getHostAddress() + ":" + (myself.getPort() + offset) + "  (offset +" + offset + ")";
            case EagerPushGossipBroadcast.RESOLUTION_SHARED -> "shared with membership channel";
            default -> "fixed peer port (see EagerPushGossipBroadcast.PeerPort)";
        };

        int uiPort = Integer.parseInt(props.getProperty(CanvasApp.PAR_UI_PORT,
                Integer.toString(myself.getPort() + CanvasApp.DEFAULT_UI_PORT_OFFSET)));
        boolean uiEnabled = Boolean.parseBoolean(props.getProperty(CanvasApp.PAR_UI_ENABLED, CanvasApp.DEFAULT_UI_ENABLED));

        StringBuilder b = new StringBuilder(System.lineSeparator());
        b.append("  babel-canvas").append(System.lineSeparator());
        b.append("  network     : ").append(iface).append("  →  ").append(myself.getAddress().getHostAddress())
                .append("   (").append(addressSource).append(')').append(System.lineSeparator());
        b.append("  membership  : ").append(myself.getAddress().getHostAddress()).append(':').append(myself.getPort())
                .append("  (HyParView, TCP)").append(System.lineSeparator());
        b.append("  gossip      : ").append(gossipEndpoint).append(System.lineSeparator());
        if (antiEntropy) {
            b.append("  anti-entropy: ").append(myself.getAddress().getHostAddress()).append(':')
                    .append(myself.getPort() + ANTIENTROPY_PORT_OFFSET).append(System.lineSeparator());
        }
        b.append("  web UI      : ").append(uiEnabled ? "http://localhost:" + uiPort + "/" : "disabled")
                .append(System.lineSeparator());

        boolean discoveryOn = props.getProperty(Babel.PAR_DISCOVERY_PROTOCOL) != null;
        if (discoveryOn) {
            String group = props.getProperty(MulticastDiscoveryProtocol.PAR_DISCOVERY_MULTICAST_ADDRESS,
                    MulticastDiscoveryProtocol.MULTICAST_ADDRESS);
            b.append("  discovery   : multicast ").append(group).append(System.lineSeparator());
        } else {
            b.append("  discovery   : off (pass babel.discovery=… to enable multicast)")
                    .append(System.lineSeparator());
        }

        String contact = props.getProperty(HyParView.PAR_CONTACT);
        String bootstrap;
        if (contact != null && !contact.isBlank() && !contact.trim().equalsIgnoreCase("none")) {
            bootstrap = "seed from contact " + contact.trim();
        } else if (contact != null && contact.trim().equalsIgnoreCase("none")) {
            bootstrap = "first node (HyParView.contact=none) — I don't probe, but I reply to others";
        } else if (discoveryOn) {
            bootstrap = "auto-discovery — probe the LAN and connect to whoever answers";
        } else {
            bootstrap = "none — set HyParView.contact=<host>:<port> to join, or babel.discovery=… for multicast";
        }
        b.append("  bootstrap   : ").append(bootstrap).append(System.lineSeparator());
        System.out.println(b);
    }

    /** Tiny helper: find {@code key=value} in the launch args, else return a default. */
    private static String argValue(String[] args, String key, String def) {
        String prefix = key + "=";
        for (String a : args) {
            if (a.startsWith(prefix)) {
                return a.substring(prefix.length());
            }
        }
        return def;
    }
}
