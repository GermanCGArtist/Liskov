package dev.liskov.core.sync;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class SyncWireTest {
    private static final int MC_C2S_HARD_LIMIT = 32_767;

    @Test
    void chunkSizeFitsSafelyUnderMinecraftC2sLimit() {
        assertTrue(SyncWire.CHUNK_SIZE <= 24 * 1024, "CHUNK_SIZE must stay safely under 32 KB C2S limit");
        assertTrue(SyncWire.CHUNK_SIZE + 2048 < MC_C2S_HARD_LIMIT,
                "CHUNK_SIZE + JSON metadata headroom must be strictly < 32,767 bytes");
    }

    @Test
    void compressIfSmallerShrinksLargeJsonProjectAndRoundTrips() throws IOException {
        StringBuilder sb = new StringBuilder(120_000);
        sb.append("{\"id\":\"mega_project\",\"chunks\":[");
        for (int i = 0; i < 2000; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"chunkKey\":\"t_001/").append(i * 16).append(",0\",\"cx\":").append(i).append(",\"cz\":0}");
        }
        sb.append("]}");
        byte[] raw = sb.toString().getBytes(StandardCharsets.UTF_8);
        assertTrue(raw.length > 80_000, "Test fixture should exceed 80 KB");

        SyncWire.CompressedPayload cp = SyncWire.compressIfSmaller(raw);
        assertTrue(cp.compressed(), "Large JSON project should compress");
        assertTrue(cp.bytes().length < raw.length / 4,
                "Expected >4x compression on JSON, got " + cp.bytes().length + " / " + raw.length);

        byte[] restored = SyncWire.gunzip(cp.bytes(), SyncWire.MAX_DECOMPRESSED_BYTES);
        assertArrayEquals(raw, restored);
    }

    @Test
    void compressIfSmallerDoesNotInflateAlreadyCompressedData() throws IOException {
        byte[] random = new byte[90_000];
        new Random(42).nextBytes(random);

        SyncWire.CompressedPayload cp = SyncWire.compressIfSmaller(random);
        assertFalse(cp.compressed(), "Incompressible data should not be marked compressed");
        assertArrayEquals(random, cp.bytes());

        List<byte[]> chunks = SyncWire.splitChunks(cp.bytes(), SyncWire.CHUNK_SIZE);
        assertTrue(chunks.size() >= 4, "90 KB incompressible blob should split into multiple <= 24 KB chunks");
        ByteArrayOutputStream reassembled = new ByteArrayOutputStream();
        for (byte[] c : chunks) {
            assertTrue(c.length <= SyncWire.CHUNK_SIZE);
            assertTrue(c.length + 1024 < MC_C2S_HARD_LIMIT);
            reassembled.write(c);
        }
        assertArrayEquals(random, reassembled.toByteArray());
    }

    @Test
    void gunzipEnforcesMaxBytesLimit() throws IOException {
        byte[] zeros = new byte[100_000];
        byte[] gz = SyncWire.gzip(zeros);
        assertThrows(IOException.class, () -> SyncWire.gunzip(gz, 10_000));
    }
}
