package dev.liskov.net;

import dev.liskov.core.sync.SyncWire;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Native Fabric {@link CustomPacketPayload} on {@code liskov:sync} carrying JSON control envelopes
 * and optional binary 64 KB blob chunks.
 */
public record LiskovPayload(String json, byte[] data) implements CustomPacketPayload {
    public static final Identifier CHANNEL_ID =
            Identifier.fromNamespaceAndPath(SyncWire.CHANNEL_NAMESPACE, SyncWire.CHANNEL_PATH);
    public static final Type<LiskovPayload> TYPE = new Type<>(CHANNEL_ID);
    private static final byte[] EMPTY = new byte[0];

    public static final StreamCodec<FriendlyByteBuf, LiskovPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeUtf(payload.json() == null ? "{}" : payload.json(), SyncWire.MAX_JSON_BYTES);
                byte[] bytes = payload.data() == null ? EMPTY : payload.data();
                buf.writeVarInt(bytes.length);
                if (bytes.length > 0) buf.writeBytes(bytes);
            },
            buf -> {
                String json = buf.readUtf(SyncWire.MAX_JSON_BYTES);
                int len = Math.max(0, Math.min(SyncWire.MAX_CHUNK_BYTES, buf.readVarInt()));
                byte[] bytes = len == 0 ? EMPTY : new byte[len];
                if (len > 0) buf.readBytes(bytes);
                return new LiskovPayload(json, bytes);
            }
    );

    public LiskovPayload(String json) {
        this(json, EMPTY);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
