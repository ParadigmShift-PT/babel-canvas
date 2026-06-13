package protocols.apps.canvas;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * The canvas's "application protocol" that rides <em>inside</em> the gossip
 * broadcast's opaque {@code byte[]} payload. The dissemination layer doesn't
 * know or care what these bytes mean — that's the point of layering: gossip
 * moves bytes; the canvas decides they are a {@link PaintOp}.
 *
 * <p>One paint operation travels per broadcast. We hand-roll the encoding with
 * {@link DataOutputStream} (delegating to {@link PaintOp#writeTo}) so the wire
 * format is obvious and matches the snapshot encoding used for sync.
 */
public final class CanvasPayload {

    private CanvasPayload() {
        // Static encode/decode helpers only.
    }

    /** Serialize a paint op to the bytes carried inside a broadcast message. */
    public static byte[] encode(PaintOp op) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            op.writeTo(out);
        } catch (IOException e) {
            // Writing to an in-memory buffer cannot fail in practice.
            throw new UncheckedIOException(e);
        }
        return baos.toByteArray();
    }

    /** Parse the bytes delivered by the gossip layer back into a paint op. */
    public static PaintOp decode(byte[] bytes) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            return PaintOp.readFrom(in);
        } catch (IOException | RuntimeException e) {
            throw new UncheckedIOException(new IOException("Malformed canvas payload", e));
        }
    }
}
