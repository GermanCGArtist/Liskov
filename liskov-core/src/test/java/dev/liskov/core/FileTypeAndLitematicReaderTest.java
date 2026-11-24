package dev.liskov.core;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class FileTypeAndLitematicReaderTest {
    @Test
    void detectsMonogramsAndLegacyAliases() {
        FileTypeRegistry reg = new FileTypeRegistry();
        assertEquals("Lm", reg.detect(Path.of("a/keep.litematic")).monogram());
        assertEquals("Vp", reg.detect(Path.of("Projects/Harbor/project.vproj")).monogram());
        assertEquals("Vp", reg.detect(Path.of("Projects/Harbor/project.json")).monogram());
        assertEquals("La", reg.detect(Path.of("crane.lasm")).monogram());
        assertEquals("Mp", reg.detect(Path.of("manhattan/projects/w/canal.mproj")).monogram());
        assertEquals("Mp", reg.detect(Path.of("manhattan/projects/w/canal.json")).monogram());
        assertEquals("Ms", reg.detect(Path.of("manhattan/scripts/loop.mscript")).monogram());
        assertEquals("Mc", reg.detect(Path.of("manhattan/capture/cap.mcap")).monogram());
        assertEquals("Hm", reg.detect(Path.of("heavymatica/presets/cut.heavymatic")).monogram());
    }

    @Test
    void readsSyntheticGzippedLitematicNbt() throws Exception {
        byte[] nbt = buildSyntheticLitematic();
        LitematicReader.Summary summary = LitematicReader.read(nbt, "fallback.litematic");
        assertEquals("Test Tower", summary.name);
        assertEquals("Julian", summary.author);
        assertEquals(2, summary.sizeX);
        assertEquals(2, summary.sizeY);
        assertEquals(2, summary.sizeZ);
        assertEquals(1, summary.regionCount);
        assertEquals(4, summary.totalBlocks);
        assertEquals(4, summary.voxels.size());
        assertFalse(summary.topMaterials.isEmpty());
        assertEquals("minecraft:stone_bricks", summary.topMaterials.get(0).blockId);
        assertEquals(4, summary.topMaterials.get(0).count);
    }

    private static byte[] buildSyntheticLitematic() throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream d = new DataOutputStream(raw)) {
            d.writeByte(10); writeStr(d, ""); // root
            // Metadata
            d.writeByte(10); writeStr(d, "Metadata");
            d.writeByte(8); writeStr(d, "Name"); writeStr(d, "Test Tower");
            d.writeByte(8); writeStr(d, "Author"); writeStr(d, "Julian");
            d.writeByte(8); writeStr(d, "Description"); writeStr(d, "Unit test");
            d.writeByte(10); writeStr(d, "EnclosingSize");
            d.writeByte(3); writeStr(d, "x"); d.writeInt(2);
            d.writeByte(3); writeStr(d, "y"); d.writeInt(2);
            d.writeByte(3); writeStr(d, "z"); d.writeInt(2);
            d.writeByte(0);
            d.writeByte(0); // end Metadata

            // Regions
            d.writeByte(10); writeStr(d, "Regions");
            d.writeByte(10); writeStr(d, "Main");
            d.writeByte(10); writeStr(d, "Position");
            d.writeByte(3); writeStr(d, "x"); d.writeInt(0);
            d.writeByte(3); writeStr(d, "y"); d.writeInt(0);
            d.writeByte(3); writeStr(d, "z"); d.writeInt(0);
            d.writeByte(0);
            d.writeByte(10); writeStr(d, "Size");
            d.writeByte(3); writeStr(d, "x"); d.writeInt(2);
            d.writeByte(3); writeStr(d, "y"); d.writeInt(2);
            d.writeByte(3); writeStr(d, "z"); d.writeInt(2);
            d.writeByte(0);

            // BlockStatePalette: [0=air, 1=stone_bricks]
            d.writeByte(9); writeStr(d, "BlockStatePalette");
            d.writeByte(10); d.writeInt(2);
            d.writeByte(8); writeStr(d, "Name"); writeStr(d, "minecraft:air"); d.writeByte(0);
            d.writeByte(8); writeStr(d, "Name"); writeStr(d, "minecraft:stone_bricks"); d.writeByte(0);

            // 8 voxels, 2 bits per entry -> bottom layer (indices 0..3) = 1 (0b01), top layer (4..7) = 0
            long packed = 0b01_01_01_01L;
            d.writeByte(12); writeStr(d, "BlockStates"); d.writeInt(1); d.writeLong(packed);

            d.writeByte(0); // end Main
            d.writeByte(0); // end Regions
            d.writeByte(0); // end root
        }

        ByteArrayOutputStream gzOut = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(gzOut)) {
            gz.write(raw.toByteArray());
        }
        return gzOut.toByteArray();
    }

    private static void writeStr(DataOutputStream d, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        d.writeShort(b.length);
        d.write(b);
    }
}
