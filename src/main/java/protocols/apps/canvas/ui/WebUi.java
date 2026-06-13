package protocols.apps.canvas.ui;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import protocols.apps.canvas.CanvasApp;

/**
 * A tiny embedded web UI for the canvas, built on the JDK's bundled
 * {@link HttpServer} so the demo pulls in no HTTP dependency. It serves three
 * static assets from the classpath ({@code /web/index.html}, {@code app.js},
 * {@code style.css}) and two JSON endpoints:
 * <ul>
 *   <li>{@code GET /api/state} — the current canvas grid + this node's
 *       active-view neighbours, which the page polls a few times a second;</li>
 *   <li>{@code POST /api/paint} — {@code {"x":..,"y":..,"color":0xRRGGBB}} →
 *       paints one cell (which then disseminates by gossip like any other).</li>
 * </ul>
 *
 * <p>Handlers run on the server's own small thread pool and call back into
 * {@link CanvasApp#paintFromUi} / {@link CanvasApp#stateJson}, both of which are
 * safe off the event loop (see {@code CanvasApp}'s threading note).
 */
public final class WebUi {

    private static final Logger logger = LogManager.getLogger(WebUi.class);

    private static final Pattern INT_FIELD = Pattern.compile("\"(x|y|color)\"\\s*:\\s*(-?\\d+)");

    private final int port;
    private final CanvasApp app;
    private HttpServer server;

    public WebUi(int port, CanvasApp app) {
        this.port = port;
        this.app = app;
    }

    /** Bind and start serving. Throws if the port is unavailable. */
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(2));

        server.createContext("/api/state", this::handleState);
        server.createContext("/api/paint", this::handlePaint);
        // Everything else is a static asset (or the index page at "/").
        server.createContext("/", this::handleStatic);

        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /* ───────────────────────────── Handlers ─────────────────────────────── */

    private void handleState(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "text/plain", "method not allowed".getBytes(StandardCharsets.UTF_8));
            return;
        }
        respond(exchange, 200, "application/json", app.stateJson().getBytes(StandardCharsets.UTF_8));
    }

    private void handlePaint(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "text/plain", "method not allowed".getBytes(StandardCharsets.UTF_8));
            return;
        }
        byte[] body = exchange.getRequestBody().readAllBytes();
        Integer x = null;
        Integer y = null;
        int color = 0x000000;
        Matcher m = INT_FIELD.matcher(new String(body, StandardCharsets.UTF_8));
        while (m.find()) {
            int value = Integer.parseInt(m.group(2));
            switch (m.group(1)) {
                case "x" -> x = value;
                case "y" -> y = value;
                case "color" -> color = value;
            }
        }
        if (x == null || y == null) {
            respond(exchange, 400, "text/plain", "expected {x,y,color}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        app.paintFromUi(x, y, color);
        respond(exchange, 204, "text/plain", new byte[0]);
    }

    private void handleStatic(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path == null || path.equals("/")) {
            path = "/index.html";
        }
        // Confine to the bundled /web resources; reject path traversal.
        if (path.contains("..")) {
            respond(exchange, 400, "text/plain", "bad path".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try (InputStream in = WebUi.class.getResourceAsStream("/web" + path)) {
            if (in == null) {
                respond(exchange, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            respond(exchange, 200, contentType(path), in.readAllBytes());
        }
    }

    /* ───────────────────────────── Helpers ──────────────────────────────── */

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        // The page polls; never let a stale state response be cached.
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        } else {
            exchange.close();
        }
    }

    private static String contentType(String path) {
        if (path.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (path.endsWith(".js")) {
            return "text/javascript; charset=utf-8";
        }
        if (path.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        return "application/octet-stream";
    }
}
