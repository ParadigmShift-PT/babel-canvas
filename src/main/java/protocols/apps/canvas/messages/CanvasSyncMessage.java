package protocols.apps.canvas.messages;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.netty.buffer.ByteBuf;
import protocols.apps.canvas.PaintOp;
import pt.unl.fct.di.novasys.babel.generic.ProtoMessage;
import pt.unl.fct.di.novasys.network.ISerializer;

/**
 * A point-to-point canvas snapshot exchange, sent <b>directly</b> to one peer
 * (not broadcast) over HyParView's shared channel via {@code sendMessage}. Two
 * kinds:
 * <ul>
 *   <li>{@link Kind#REQUEST} — "send me your current canvas", sent by a node
 *       once it has joined the overlay so a late joiner sees existing art
 *       immediately instead of waiting for new gossip;</li>
 *   <li>{@link Kind#REPLY} — the responding peer's full set of winning
 *       {@link PaintOp}s, which the requester merges under last-writer-wins.</li>
 * </ul>
 *
 * <p>This rides HyParView's channel specifically (not eager-push's): the peers
 * a {@code NeighborUp} reports are HyParView active-view members, reachable
 * on that channel without any port translation — so the {@code Host} from the
 * notification is exactly the {@code sendMessage} destination.
 */
public class CanvasSyncMessage extends ProtoMessage {

    // CanvasApp owns protocol id 300; its messages start at 301.
    public static final short MSG_ID = 301;

    public enum Kind { REQUEST, REPLY }

    private final Kind kind;
    private final List<PaintOp> ops; // empty for REQUEST

    public CanvasSyncMessage(Kind kind, List<PaintOp> ops) {
        super(MSG_ID);
        this.kind = kind;
        this.ops = ops == null ? List.of() : ops;
    }

    public Kind getKind() { return kind; }
    public List<PaintOp> getOps() { return ops; }

    // Netty's ByteBuf has no writeUTF, so we length-prefix UTF-8 bytes ourselves.
    private static void writeString(String s, ByteBuf out) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.writeBytes(b);
    }

    private static String readString(ByteBuf in) {
        int n = in.readInt();
        byte[] b = new byte[n];
        in.readBytes(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    private static void writeOp(PaintOp op, ByteBuf out) {
        out.writeInt(op.getX());
        out.writeInt(op.getY());
        out.writeInt(op.getArgb());
        out.writeLong(op.getTimestamp());
        writeString(op.getOriginId(), out);
        out.writeLong(op.getOpId().getMostSignificantBits());
        out.writeLong(op.getOpId().getLeastSignificantBits());
    }

    private static PaintOp readOp(ByteBuf in) {
        int x = in.readInt();
        int y = in.readInt();
        int argb = in.readInt();
        long ts = in.readLong();
        String originId = readString(in);
        long hi = in.readLong();
        long lo = in.readLong();
        return new PaintOp(x, y, argb, ts, originId, new UUID(hi, lo));
    }

    public static final ISerializer<CanvasSyncMessage> serializer = new ISerializer<>() {
        @Override
        public void serialize(CanvasSyncMessage m, ByteBuf out) throws IOException {
            out.writeByte(m.kind.ordinal());
            out.writeInt(m.ops.size());
            for (PaintOp op : m.ops) {
                writeOp(op, out);
            }
        }

        @Override
        public CanvasSyncMessage deserialize(ByteBuf in) throws IOException {
            Kind kind = Kind.values()[in.readByte()];
            int n = in.readInt();
            List<PaintOp> ops = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                ops.add(readOp(in));
            }
            return new CanvasSyncMessage(kind, ops);
        }
    };
}
