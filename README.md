# babel-canvas — a collaborative pixel canvas built on Babel

A **software-only** demo of the [Babel](https://ieeexplore.ieee.org/document/9996836)
distributed-protocols framework: a peer-to-peer **collaborative pixel canvas**
(think a tiny *r/place*) where every participant is a peer. Paint a cell in your
browser and watch it **spread to every node by gossip**; because each cell is
last-writer-wins, all nodes **converge to the identical canvas** no matter what
order the updates arrive in.

Unlike the chat demo ([`babel-demo`](https://github.com/ParadigmShift-PT/babel-demo)),
which runs on simplified teaching protocols, babel-canvas composes the **real
ParadigmShift overlay stack** — so it doubles as a live showcase of those
protocols working together:

1. **HyParView** — partial-view membership (the gossip overlay) with LAN auto-discovery;
2. **eager-push gossip broadcast** — disseminates each paint operation to the overlay;
3. **bloom-filter anti-entropy** *(optional)* — recovers paint ops that gossip missed;
4. **CanvasApp** — the canvas + web UI on top.

> **A ParadigmShift open demo.** Free for non-commercial use — see [License](#license).

---

## Quickstart

Requires **Java 17+**. Grab `babel-canvas.jar` from the
[latest release](https://github.com/ParadigmShift-PT/babel-canvas/releases/latest)
(or [build it](#building-from-source)), then run one node — it auto-opens its UI:

```bash
java -jar babel-canvas.jar          # one node; auto-opens its UI at http://localhost:8000/
```

That paints locally. To connect more nodes, bootstrap the overlay one of two ways
(a joining node only needs to reach one node that's already in it):

**Explicit contact** — works on one machine, across networks, anywhere TCP
connects; no discovery to configure:

```bash
# first node — the one others contact
java -jar babel-canvas.jar babel.address=127.0.0.1 babel.port=6000 HyParView.contact=none
# second node — dials the first to join
java -jar babel-canvas.jar babel.address=127.0.0.1 babel.port=6010 HyParView.contact=127.0.0.1:6000
```

**Multicast auto-discovery** — no addresses to type, but same-LAN only and opt-in
(name the discovery protocol on the command line):

```bash
DISC=babel.discovery=pt.unl.fct.di.novasys.babel.core.protocols.discovery.MulticastDiscoveryProtocol
java -jar babel-canvas.jar $DISC                                               # machine A
java -jar babel-canvas.jar $DISC babel.port=6010 babel.discovery.unicast.port=1027   # 2nd node, same host
```

Each node serves a web UI and opens your browser at it on startup
(`canvas.ui.open=false` suppresses that). Run several nodes on one machine with a
distinct `babel.port` **spaced by ≥ 10** (the gossip channel binds `babel.port+1`);
the web-UI port follows automatically (`babel.port + 2000`). Multicast can be
blocked locally (VPN, firewall, multiple NICs, macOS Local Network permission) — if
nodes don't find each other that way, use explicit contact.

---

## How it works

A paint is one `PaintOp` — *cell (x,y) becomes colour argb* — stamped with the
painter's wall-clock time, id, and a unique op id. The flow:

1. You click a cell (or the headless workload picks one). `CanvasApp` issues a
   `BroadcastRequest` to the eager-push gossip protocol.
2. Gossip forwards it to a random fanout of HyParView neighbours; receivers
   re-broadcast novel ops until the whole overlay has it.
3. On every node (including the origin) the op arrives as a `BroadcastDelivery`
   and is applied to the `PixelCanvas` under **last-writer-wins**: a cell keeps
   whichever op has the highest `(timestamp, originId, opId)`.

Because that order is **total and deterministic**, any two nodes that have
applied the *same set* of ops hold the identical colour in every cell —
**convergence is purely a function of dissemination completeness**, which is
exactly what makes the canvas a clean correctness oracle.

Two extras make it pleasant and robust:

- **Snapshot sync.** On joining, a node asks one neighbour (point-to-point over
  HyParView's channel) for its current canvas, so a late joiner sees existing art
  immediately instead of waiting for new paints.
- **Anti-entropy** (optional, `canvas.antientropy.enabled=true`). Nodes
  periodically exchange bloom-filter summaries and recover any ops a peer is
  missing — so the canvas converges even if some gossip messages were lost.

---

## Configuration

Every value can come from `babel_config.properties` (bundled) or be overridden on
the command line as `key=value` — which also makes the demo easy to script for
automated, headless runs.

### Process-wide & overlay

| Property | Default | Description |
|---|---|---|
| `babel.port` | `6000` | TCP port HyParView binds. Space local nodes by ≥ 10. |
| `babel.interface` / `babel.address` | auto / — | NIC to bind/announce on, or an explicit IP. No loopback default — use `babel.address=127.0.0.1` for several nodes on one disconnected machine. |
| `babel.discovery` | (unset) | Opt-in **multicast** LAN auto-discovery — set on the command line to `pt.unl.fct.di.novasys.babel.core.protocols.discovery.MulticastDiscoveryProtocol`. Off by default; bootstrap is via `HyParView.contact`. |
| `babel.discovery.unicast.port` | `1026` | Per-process discovery socket — only when multicast is enabled; **distinct per local node**. |
| `HyParView.contact` | (absent) | Bootstrap: `none` = first node; `host:port` = dial that node to join; absent = wait for discovery (only useful with multicast on). |
| `HyParView.ActiveView` / `PassiveView` / … | 4 / 7 / … | HyParView view sizes and walk lengths — see the config file. |
| `EagerPushGossipBroadcast.Fanout` | `4` | Peers each paint op is forwarded to. |
| `EagerPushGossipBroadcast.PeerAddressResolution` | `offset` | How a peer's gossip port is derived: `offset` (membership + `PortOffset`), `fixed` (`PeerPort`), or `shared` (reuse HyParView's channel). |

### Canvas application (`canvas.*`)

| Property | Default | Description |
|---|---|---|
| `canvas.width` / `canvas.height` | `48` / `48` | Grid size (must match across nodes). |
| `canvas.ui.enabled` | `true` | Serve the web UI. |
| `canvas.ui.port` | `babel.port + 2000` | Web UI port. |
| `canvas.ui.open` | `true` | Open the system browser at the UI on startup (best-effort; set `false` to suppress, e.g. when running many local nodes). |
| `canvas.snapshot.sync` | `true` | Fetch the current canvas from a neighbour on join. |
| `canvas.digest.interval` | `5000` | Period (ms) of convergence-digest telemetry; `≤ 0` disables. |
| `canvas.antientropy.enabled` | `false` | Load anti-entropy (its own channel at `babel.port + 2`). |
| `canvas.workload.enabled` | `false` | Headless random-paint driver (for experiments; no UI needed). |
| `canvas.workload.rate` / `.duration` / `.startDelay` | `2` / `0` / `5000` | Ops/sec, run length (ms; `0` = unbounded), and warm-up delay. |

---

## Telemetry & validation

Alongside the human-readable protocol log (`babel-canvas-<port>.log`), each node
writes a small machine-readable telemetry file (`babel-canvas-telemetry-<port>.log`)
— one structured event per line (`BROADCAST` / `DELIVER` / `DIGEST` / `NEIGHBOR_*`).
That's enough to measure, automatically, that the demo really does what it claims:
that every paint reaches every node (reliability), how fast (latency), and that all
nodes converge to the same image — enough for an external driver to verify,
automatically, the membership and gossip protocols underneath it.

---

## Building from source

```bash
mvn package          # → target/babel-canvas.jar (fat JAR, mainClass Main)
```

Depends on the ParadigmShift Babel libraries (`babel-core`,
`babel-protocols-common`, `hyparview`, **`eager-gossip-broadcast` ≥ 0.3.0**,
`broadcast-antientropy`) from the ParadigmShift Maven repository. The
`eager-gossip-broadcast` ≥ 0.3.0 requirement is the `PeerAddressResolution` work;
0.3.0 is published to the ParadigmShift Maven repository, so the local build and CI
resolve it directly.

## Project layout

```
src/main/java/
  Main.java                              wiring: HyParView + eager-push + (opt) anti-entropy + CanvasApp
  protocols/apps/canvas/
    CanvasApp.java                        the application protocol (slot 300)
    PaintOp.java                          one paint op + last-writer-wins order
    CanvasPayload.java                    PaintOp ⇄ broadcast bytes
    PixelCanvas.java                      LWW grid + convergence digest + snapshot
    messages/CanvasSyncMessage.java       point-to-point snapshot request/reply
    telemetry/Telemetry.java              structured experiment events
    timers/{DigestTimer,WorkloadTimer}.java
    ui/WebUi.java                         embedded HTTP server (JDK built-in)
  utils/InterfaceToIp.java                bind-address resolution (shared with babel-demo)
src/main/resources/
  babel_config.properties, log4j2.xml, web/{index.html,app.js,style.css}
```

## Distribution

babel-canvas is a **runnable demo, not a library** — it is never deployed to the
ParadigmShift Maven repository. CI builds the fat JAR and attaches it to a GitHub
Release on a `v*.*.*` tag, and publishes the API docs to GitHub Pages.

## Credits & further reading

babel-canvas is a **ParadigmShift** tech demo. The Babel framework and the
protocol implementations it builds on — Babel itself, the HyParView membership
protocol, and the eager-push gossip broadcast — were originally developed at
[NOVA LINCS](https://nova-lincs.di.fct.unl.pt), in the
[TaRDIS](https://www.project-tardis.eu) European project, by the
[Computer Systems Group](https://novasys.di.fct.unl.pt) at
[NOVA FCT](https://www.fct.unl.pt). The versions used here are ParadigmShift's
own, provided and evolved independently of that original work.

The protocols underpinning this demo are described in:

- J. Leitão, J. Pereira, and L. Rodrigues, “HyParView: A Membership Protocol for
  Reliable Gossip-Based Broadcast,” in *Proc. 37th Annual IEEE/IFIP Int'l Conf.
  on Dependable Systems and Networks (DSN'07)*, Edinburgh, UK, Jun. 2007,
  pp. 419–429. doi: [10.1109/DSN.2007.56](https://doi.org/10.1109/DSN.2007.56) ·
  [PDF](https://asc.di.fct.unl.pt/~jleitao/pdf/dsn07-leitao.pdf)
- P. Fouto, P. Á. Costa, N. Preguiça, and J. Leitão, “Babel: A Framework for
  Developing Performant and Dependable Distributed Protocols,” in *Proc. 41st
  Int'l Symp. on Reliable Distributed Systems (SRDS)*, Vienna, Austria,
  Sep. 2022, pp. 146–155.
  doi: [10.1109/SRDS55811.2022.00022](https://doi.org/10.1109/SRDS55811.2022.00022) ·
  [PDF](https://asc.di.fct.unl.pt/~jleitao/pdf/fouto-srds22.pdf)

## License

ParadigmShift Proprietary License — non-commercial use permitted; commercial use
requires a written licence from ParadigmShift, Lda. See [LICENSE](LICENSE).
