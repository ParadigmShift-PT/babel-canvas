package utils;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import pt.unl.fct.di.novasys.babel.core.Babel;

/**
 * Resolves the process-wide bind address ({@code babel.address}) the node binds
 * and announces on.
 *
 * <p>Why this matters for the demo: for multicast auto-discovery (and for peers
 * to reach each other at all) every node must advertise a <em>reachable</em>
 * address — not {@code 127.0.0.1} and not {@code 0.0.0.0}. So the demo never
 * silently defaults to loopback. Instead {@link #resolveBindAddress(Properties)}
 * picks an address in this order:
 * <ol>
 *   <li>an explicit {@code babel.address} (the user's override always wins —
 *       including loopback, e.g. {@code babel.address=127.0.0.1} for running
 *       several nodes on one disconnected machine);</li>
 *   <li>the IPv4 address of the interface named in {@code babel.interface}
 *       (e.g. {@code babel.interface=en0} on macOS, {@code eth0} on Linux);</li>
 *   <li><b>experimental</b> auto-detection: the routable IPv4 of the <em>sole</em>
 *       real interface — up, non-loopback, non-point-to-point, non-virtual, with a
 *       non-link-local IPv4, and whose name is not a bridge / VM / container / VPN
 *       adapter ({@link #isLikelyNonPhysical}). If several real interfaces qualify
 *       it refuses to guess and lists them; if only bridges/VPNs (or nothing) exist
 *       it asks the operator to choose.</li>
 * </ol>
 * In the refuse/none cases it fails loudly and tells the operator to pass
 * {@code babel.interface} or {@code babel.address} explicitly. The auto-detection
 * heuristic is name-based and best-effort — an explicit {@code babel.interface} /
 * {@code babel.address} always wins and bypasses it.
 *
 * <p>The parameter keys {@code babel.address} / {@code babel.interface} /
 * {@code babel.port} are owned by babel-core ({@link Babel#PAR_DEFAULT_ADDRESS} /
 * {@link Babel#PAR_DEFAULT_INTERFACE} / {@link Babel#PAR_DEFAULT_PORT}); this
 * class references those constants rather than re-declaring the literals.
 */
public class InterfaceToIp {

    private static final Logger logger = LogManager.getLogger(InterfaceToIp.class);

    private InterfaceToIp() {
        // Utility class — not instantiable.
    }

    /** Returns the first non-loopback IPv4 address of {@code interfaceName}, or null if it has none / does not exist. */
    public static String getIpOfInterface(String interfaceName) throws SocketException {
        NetworkInterface networkInterface = NetworkInterface.getByName(interfaceName);
        if (networkInterface == null) {
            return null;
        }
        return firstNonLoopbackIpv4(networkInterface);
    }

    /** First non-loopback IPv4 address bound to {@code networkInterface}, or null. */
    private static String firstNonLoopbackIpv4(NetworkInterface networkInterface) {
        Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
        while (inetAddresses.hasMoreElements()) {
            InetAddress currentAddress = inetAddresses.nextElement();
            if (currentAddress instanceof Inet4Address && !currentAddress.isLoopbackAddress()) {
                return currentAddress.getHostAddress();
            }
        }
        return null;
    }

    /** First non-loopback, non-link-local (i.e. routable) IPv4 address of {@code n}, or null. */
    private static String firstReachableIpv4(NetworkInterface networkInterface) {
        Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
        while (inetAddresses.hasMoreElements()) {
            InetAddress a = inetAddresses.nextElement();
            if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) {
                return a.getHostAddress();
            }
        }
        return null;
    }

    /**
     * Name prefixes of interfaces that are <em>not</em> real, LAN-connected NICs:
     * VM/container bridges, hypervisor adapters, VPN tunnels, and Apple-internal
     * links. These routinely sit alongside the real NIC on a developer machine
     * (e.g. {@code bridge101}, {@code vmenet0}, {@code docker0}) and must not be
     * auto-selected — binding one announces an address peers can't reach. They can
     * still be used explicitly via {@code babel.interface}. This is a heuristic
     * (Java exposes no reliable "is this a physical NIC" flag); extend as needed.
     */
    private static final String[] NON_PHYSICAL_NAME_PREFIXES = {
            "bridge", "vmenet", "vnic",                 // macOS bridges / VM ethernet
            "docker", "br-", "virbr", "veth",           // Linux container / VM bridges
            "vboxnet", "vmnet",                         // VirtualBox / VMware
            "utun", "tun", "tap", "wg", "ppp", "ipsec", // VPN / tunnels
            "awdl", "llw", "anpi", "ap"                 // macOS Apple-internal links
    };

    /** True if {@code n}'s name looks like a bridge/VM/VPN/internal interface (see {@link #NON_PHYSICAL_NAME_PREFIXES}). */
    private static boolean isLikelyNonPhysical(NetworkInterface n) {
        String name = n.getName().toLowerCase();
        for (String prefix : NON_PHYSICAL_NAME_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Auto-detect a reachable bind address: the routable IPv4 of the sole real
     * (non-loopback, up, non-virtual, non-p2p, non-bridge/VM/VPN) interface.
     * Returns null if none — or more than one — qualifies.
     */
    public static String autoDetectAddress() throws SocketException {
        List<NetworkInterface> real = realInterfaces();
        return real.size() == 1 ? firstReachableIpv4(real.get(0)) : null;
    }

    /**
     * Interfaces that are up, non-loopback, non-virtual, non-point-to-point and carry
     * a routable (non-link-local) IPv4 address. May include bridges/VM/VPN adapters
     * (those are filtered out separately by {@link #isLikelyNonPhysical}); used for
     * diagnostics when there is no clear physical NIC.
     */
    private static List<NetworkInterface> reachableInterfaces() throws SocketException {
        List<NetworkInterface> candidates = new ArrayList<>();
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface n = interfaces.nextElement();
            if (n.isLoopback() || n.isVirtual() || !n.isUp() || n.isPointToPoint()) {
                continue;
            }
            if (firstReachableIpv4(n) != null) {
                candidates.add(n);
            }
        }
        return candidates;
    }

    /** {@link #reachableInterfaces()} minus bridge/VM/VPN/internal interfaces — i.e. likely-physical LAN NICs. */
    private static List<NetworkInterface> realInterfaces() throws SocketException {
        List<NetworkInterface> real = new ArrayList<>();
        for (NetworkInterface n : reachableInterfaces()) {
            if (!isLikelyNonPhysical(n)) {
                real.add(n);
            }
        }
        return real;
    }

    /**
     * Resolve the process-wide bind address into {@code babel.address} (see the
     * class javadoc for the precedence). On return {@code babel.address} is
     * guaranteed to be set to a concrete IPv4 address.
     *
     * @return a short human-readable description of how the address was chosen
     *         (for startup logging), e.g. {@code "explicit babel.address"},
     *         {@code "interface en0"}, or {@code "auto-detected interface en0"}.
     * @throws InvalidParameterException if a named {@code babel.interface} has no
     *         usable address, or no address could be determined at all — in which
     *         case the operator must pass {@code babel.interface} or
     *         {@code babel.address} on the command line.
     */
    public static String resolveBindAddress(Properties props) throws SocketException, InvalidParameterException {
        // (1) An explicit address always wins — even loopback, if that is what the
        // user asked for (e.g. several nodes on one disconnected machine).
        if (props.getProperty(Babel.PAR_DEFAULT_ADDRESS) != null) {
            return "set via " + Babel.PAR_DEFAULT_ADDRESS;
        }

        // (2) A named interface: resolve it to its IPv4 address.
        String interfaceName = props.getProperty(Babel.PAR_DEFAULT_INTERFACE);
        if (interfaceName != null) {
            String ip = getIpOfInterface(interfaceName);
            if (ip == null) {
                throw new InvalidParameterException(
                        "Property '" + Babel.PAR_DEFAULT_INTERFACE + "' is set to '" + interfaceName
                                + "', but it has no usable (non-loopback IPv4) address.");
            }
            props.setProperty(Babel.PAR_DEFAULT_ADDRESS, ip);
            return "set via " + Babel.PAR_DEFAULT_INTERFACE + "=" + interfaceName;
        }

        // (3) Auto-detect — pick a real NIC when there is exactly one. We consider
        // only likely-physical interfaces (bridges, VM/container adapters and VPN
        // tunnels are excluded by name — see isLikelyNonPhysical), so the common
        // dev-machine case "one real NIC + a pile of bridges" resolves cleanly. We
        // only refuse when there are genuinely several real NICs (can't know which
        // LAN you mean), or none at all.
        List<NetworkInterface> real = realInterfaces();
        if (real.size() == 1) {
            NetworkInterface n = real.get(0);
            String ip = firstReachableIpv4(n);
            props.setProperty(Babel.PAR_DEFAULT_ADDRESS, ip);
            logger.info("Auto-detected sole physical interface '{}' -> {}", n.getName(), ip);
            return "auto-detected sole interface " + n.getName();
        }
        if (real.size() > 1) {
            throw new InvalidParameterException(buildPickOneMessage(
                    "Several physical network interfaces are usable — refusing to guess which to bind.", real));
        }
        // No clearly-physical NIC. Distinguish "nothing at all" from "only bridges/VPN".
        List<NetworkInterface> usable = reachableInterfaces();
        if (usable.isEmpty()) {
            throw new InvalidParameterException(
                    "Could not auto-detect a reachable network interface. Pass '"
                            + Babel.PAR_DEFAULT_INTERFACE + "=<nic>' (e.g. en0 / eth0), or '"
                            + Babel.PAR_DEFAULT_ADDRESS + "=<ip>' (e.g. 127.0.0.1 for several nodes on "
                            + "one machine) on the command line.");
        }
        throw new InvalidParameterException(buildPickOneMessage(
                "Only bridge / VM / VPN interfaces are available (no plain physical NIC) — "
                        + "not auto-selecting one. If you really mean to use one, name it explicitly.", usable));
    }

    /** Builds an InvalidParameterException message that lists {@code interfaces} as candidates to pass explicitly. */
    private static String buildPickOneMessage(String lead, List<NetworkInterface> interfaces) {
        StringBuilder sb = new StringBuilder(lead)
                .append(" Pass '").append(Babel.PAR_DEFAULT_INTERFACE).append("=<nic>' (or '")
                .append(Babel.PAR_DEFAULT_ADDRESS).append("=<ip>'). Candidates:");
        for (NetworkInterface c : interfaces) {
            sb.append(System.lineSeparator()).append("  ").append(c.getName())
                    .append("  →  ").append(firstReachableIpv4(c));
        }
        return sb.toString();
    }

    /**
     * Logs every network interface and its IPv4 addresses (with up/loopback/virtual/
     * point-to-point flags) to help diagnose a wrong auto-selection. Written to the
     * log file; never throws.
     */
    public static String describeInterfaces() {
        StringBuilder b = new StringBuilder("Available network interfaces:");
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface n = interfaces.nextElement();
                StringBuilder ips = new StringBuilder();
                Enumeration<InetAddress> addrs = n.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address) {
                        ips.append(ips.length() == 0 ? "" : ", ").append(a.getHostAddress());
                    }
                }
                b.append(System.lineSeparator())
                        .append("  ").append(n.getName())
                        .append(" ipv4=[").append(ips).append(']')
                        .append(" up=").append(n.isUp())
                        .append(" loopback=").append(n.isLoopback())
                        .append(" virtual=").append(n.isVirtual())
                        .append(" p2p=").append(n.isPointToPoint());
            }
        } catch (SocketException e) {
            b.append(" (could not enumerate: ").append(e.getMessage()).append(')');
        }
        return b.toString();
    }
}
