package dev.liskov.core;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Pure-Java GZIP + NBT reader for {@code .litematic} files (zero Minecraft or Litematica imports).
 * Extracts schematic metadata, block counts, palette, and a sampled/full 3D voxel grid for previewing.
 */
public final class LitematicReader {
    public static final class MaterialCount {
        public final String blockId;
        public final int count;

        public MaterialCount(String blockId, int count) {
            this.blockId = blockId;
            this.count = count;
        }
    }

    public static final class Summary {
        public final String name;
        public final String author;
        public final String description;
        public final int sizeX;
        public final int sizeY;
        public final int sizeZ;
        public final int regionCount;
        public final int totalBlocks;
        public final List<MaterialCount> topMaterials;
        /** Sampled 3D voxel grid in [0..gridSizeX, 0..gridSizeY, 0..gridSizeZ] with blockId per non-air voxel. */
        public final int gridX, gridY, gridZ;
        public final int sampleStep;
        public final List<Voxel> voxels;

        public Summary(String name, String author, String description, int sizeX, int sizeY, int sizeZ,
                       int regionCount, int totalBlocks, List<MaterialCount> topMaterials,
                       int gridX, int gridY, int gridZ, int sampleStep, List<Voxel> voxels) {
            this.name = name;
            this.author = author;
            this.description = description;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.regionCount = regionCount;
            this.totalBlocks = totalBlocks;
            this.topMaterials = topMaterials;
            this.gridX = gridX;
            this.gridY = gridY;
            this.gridZ = gridZ;
            this.sampleStep = sampleStep;
            this.voxels = voxels;
        }
    }

    public record Voxel(int x, int y, int z, String blockId, int argbColor) {}

    private LitematicReader() {}

    public static Summary read(Path path) throws IOException {
        return read(Files.readAllBytes(path), path.getFileName().toString());
    }

    public static Summary read(byte[] rawOrGzipped, String fallbackName) throws IOException {
        NbtCompound root = parseGzipOrRawNbt(rawOrGzipped);
        NbtCompound meta = root.compound("Metadata");
        String name = meta != null ? meta.str("Name", fallbackName) : fallbackName;
        String author = meta != null ? meta.str("Author", "Unknown") : "Unknown";
        String desc = meta != null ? meta.str("Description", "") : "";

        NbtCompound enclosing = meta != null ? meta.compound("EnclosingSize") : null;
        int encX = enclosing != null ? Math.abs(enclosing.intVal("x", 0)) : 0;
        int encY = enclosing != null ? Math.abs(enclosing.intVal("y", 0)) : 0;
        int encZ = enclosing != null ? Math.abs(enclosing.intVal("z", 0)) : 0;

        NbtCompound regions = root.compound("Regions");
        int regionCount = regions != null ? regions.entries.size() : (meta != null ? meta.intVal("RegionCount", 0) : 0);

        Map<String, Integer> materialCounts = new LinkedHashMap<>();
        List<Voxel> rawVoxels = new ArrayList<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        int countedBlocks = 0;

        if (regions != null) {
            // Compute overall bounds first so we can choose an LOD step for huge schematics (> 48^3)
            for (Object val : regions.entries.values()) {
                if (!(val instanceof NbtCompound reg)) continue;
                NbtCompound pos = reg.compound("Position");
                NbtCompound size = reg.compound("Size");
                if (pos == null || size == null) continue;
                int px = pos.intVal("x", 0), py = pos.intVal("y", 0), pz = pos.intVal("z", 0);
                int sx = size.intVal("x", 0), sy = size.intVal("y", 0), sz = size.intVal("z", 0);
                int rx0 = sx >= 0 ? px : px + sx + 1;
                int rx1 = sx >= 0 ? px + sx - 1 : px;
                int ry0 = sy >= 0 ? py : py + sy + 1;
                int ry1 = sy >= 0 ? py + sy - 1 : py;
                int rz0 = sz >= 0 ? pz : pz + sz + 1;
                int rz1 = sz >= 0 ? pz + sz - 1 : pz;
                minX = Math.min(minX, rx0); maxX = Math.max(maxX, rx1);
                minY = Math.min(minY, ry0); maxY = Math.max(maxY, ry1);
                minZ = Math.min(minZ, rz0); maxZ = Math.max(maxZ, rz1);
            }
            if (minX == Integer.MAX_VALUE) {
                minX = minY = minZ = 0;
                maxX = Math.max(0, encX - 1);
                maxY = Math.max(0, encY - 1);
                maxZ = Math.max(0, encZ - 1);
            }
            int spanX = Math.max(1, maxX - minX + 1);
            int spanY = Math.max(1, maxY - minY + 1);
            int spanZ = Math.max(1, maxZ - minZ + 1);
            if (encX <= 0) encX = spanX;
            if (encY <= 0) encY = spanY;
            if (encZ <= 0) encZ = spanZ;

            int maxDim = Math.max(spanX, Math.max(spanY, spanZ));
            int step = maxDim > 96 ? Math.max(2, maxDim / 48) : (maxDim > 48 ? 2 : 1);

            for (Object val : regions.entries.values()) {
                if (!(val instanceof NbtCompound reg)) continue;
                NbtCompound pos = reg.compound("Position");
                NbtCompound size = reg.compound("Size");
                List<Object> paletteList = reg.list("BlockStatePalette");
                long[] blockStates = reg.longArray("BlockStates");
                if (pos == null || size == null || paletteList == null || paletteList.isEmpty() || blockStates == null) continue;

                String[] paletteIds = new String[paletteList.size()];
                for (int i = 0; i < paletteList.size(); i++) {
                    String bName = "minecraft:air";
                    if (paletteList.get(i) instanceof NbtCompound entry) {
                        bName = entry.str("Name", "minecraft:air");
                    }
                    paletteIds[i] = bName;
                }

                int px = pos.intVal("x", 0), py = pos.intVal("y", 0), pz = pos.intVal("z", 0);
                int sx = size.intVal("x", 0), sy = size.intVal("y", 0), sz = size.intVal("z", 0);
                int absX = Math.abs(sx), absY = Math.abs(sy), absZ = Math.abs(sz);
                int originX = sx >= 0 ? px : px + sx + 1;
                int originY = sy >= 0 ? py : py + sy + 1;
                int originZ = sz >= 0 ? pz : pz + sz + 1;

                int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, paletteIds.length - 1)));
                long mask = (1L << bits) - 1L;
                int layerSize = absX * absZ;

                for (int y = 0; y < absY; y++) {
                    for (int z = 0; z < absZ; z++) {
                        for (int x = 0; x < absX; x++) {
                            int idx = y * layerSize + z * absX + x;
                            int pIdx = readPacked(blockStates, idx, bits, mask);
                            if (pIdx < 0 || pIdx >= paletteIds.length) continue;
                            String blockId = paletteIds[pIdx];
                            if (isAir(blockId)) continue;
                            countedBlocks++;
                            materialCounts.merge(blockId, 1, Integer::sum);

                            int gx = (originX + x - minX) / step;
                            int gy = (originY + y - minY) / step;
                            int gz = (originZ + z - minZ) / step;
                            if (((originX + x - minX) % step == 0 && (originY + y - minY) % step == 0 && (originZ + z - minZ) % step == 0)
                                    || step == 1) {
                                if (rawVoxels.size() < 40_000) {
                                    rawVoxels.add(new Voxel(gx, gy, gz, blockId, colorForBlock(blockId)));
                                }
                            }
                        }
                    }
                }
            }

            List<MaterialCount> top = new ArrayList<>();
            materialCounts.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                    .limit(8)
                    .forEach(e -> top.add(new MaterialCount(e.getKey(), e.getValue())));

            int total = countedBlocks > 0 ? countedBlocks : (meta != null ? meta.intVal("TotalBlocks", 0) : 0);
            return new Summary(
                    name, author, desc, encX, encY, encZ, regionCount, total,
                    Collections.unmodifiableList(top),
                    Math.max(1, (spanX + step - 1) / step),
                    Math.max(1, (spanY + step - 1) / step),
                    Math.max(1, (spanZ + step - 1) / step),
                    step,
                    Collections.unmodifiableList(rawVoxels)
            );
        }

        return new Summary(name, author, desc, encX, encY, encZ, regionCount,
                meta != null ? meta.intVal("TotalBlocks", 0) : 0,
                List.of(), Math.max(1, encX), Math.max(1, encY), Math.max(1, encZ), 1, List.of());
    }

    private static int readPacked(long[] data, int index, int bits, long mask) {
        long startBit = (long) index * bits;
        int startLong = (int) (startBit >>> 6);
        int bitOffset = (int) (startBit & 63L);
        if (startLong >= data.length) return 0;
        long value = data[startLong] >>> bitOffset;
        int endLong = (int) ((startBit + bits - 1) >>> 6);
        if (endLong != startLong && endLong < data.length) {
            value |= data[endLong] << (64 - bitOffset);
        }
        return (int) (value & mask);
    }

    private static boolean isAir(String id) {
        return "minecraft:air".equals(id) || "minecraft:cave_air".equals(id) || "minecraft:void_air".equals(id);
    }

    /** Approximate Minecraft map-style color for fast VOX previewing without MC block registry. */
    public static int colorForBlock(String blockId) {
        String s = blockId == null ? "" : blockId.toLowerCase(Locale.ROOT);
        if (s.contains("oak") || s.contains("spruce") || s.contains("birch") || s.contains("wood") || s.contains("plank") || s.contains("log")) {
            return s.contains("spruce") || s.contains("dark_oak") ? 0xFF6E5238 : 0xFF9B7752;
        }
        if (s.contains("deepslate") || s.contains("blackstone") || s.contains("basalt") || s.contains("obsidian")) return 0xFF454C57;
        if (s.contains("quartz") || s.contains("calcite") || s.contains("white") || s.contains("iron")) return 0xFFE2E8F0;
        if (s.contains("gold") || s.contains("lantern") || s.contains("glowstone") || s.contains("torch")) return 0xFFF5B731;
        if (s.contains("copper")) return 0xFF4FD6BE;
        if (s.contains("water") || s.contains("ice") || s.contains("prismarine")) return 0xFF38BDF8;
        if (s.contains("leaves") || s.contains("grass") || s.contains("moss") || s.contains("emerald")) return 0xFF22D37E;
        if (s.contains("redstone") || s.contains("brick") || s.contains("terracotta") || s.contains("netherrack")) return 0xFFB84A39;
        if (s.contains("glass")) return 0xAA88D4F8;
        if (s.contains("sand")) return 0xFFDCC99E;
        return 0xFF7B8494;
    }

    // ------------------------------------------------------------------ minimal pure-Java NBT reader

    private static NbtCompound parseGzipOrRawNbt(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 3) throw new EOFException("Empty NBT stream");
        boolean gzip = (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B;
        InputStream in = gzip
                ? new GZIPInputStream(new ByteArrayInputStream(bytes))
                : new ByteArrayInputStream(bytes);
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(in))) {
            int rootType = dis.readUnsignedByte();
            if (rootType != 10) throw new IOException("Expected TAG_Compound root, got " + rootType);
            readString(dis); // root name
            return readCompound(dis);
        }
    }

    private static final class NbtCompound {
        final Map<String, Object> entries = new LinkedHashMap<>();

        NbtCompound compound(String k) {
            return entries.get(k) instanceof NbtCompound c ? c : null;
        }

        @SuppressWarnings("unchecked")
        List<Object> list(String k) {
            return entries.get(k) instanceof List<?> l ? (List<Object>) l : null;
        }

        long[] longArray(String k) {
            return entries.get(k) instanceof long[] a ? a : null;
        }

        String str(String k, String def) {
            return entries.get(k) instanceof String s ? s : def;
        }

        int intVal(String k, int def) {
            return entries.get(k) instanceof Number n ? n.intValue() : def;
        }
    }

    private static NbtCompound readCompound(DataInputStream dis) throws IOException {
        NbtCompound c = new NbtCompound();
        while (true) {
            int tag = dis.readUnsignedByte();
            if (tag == 0) break;
            String name = readString(dis);
            c.entries.put(name, readPayload(dis, tag));
        }
        return c;
    }

    private static Object readPayload(DataInputStream dis, int tag) throws IOException {
        return switch (tag) {
            case 1 -> dis.readByte();
            case 2 -> dis.readShort();
            case 3 -> dis.readInt();
            case 4 -> dis.readLong();
            case 5 -> dis.readFloat();
            case 6 -> dis.readDouble();
            case 7 -> {
                int len = dis.readInt();
                byte[] b = dis.readNBytes(len);
                yield b;
            }
            case 8 -> readString(dis);
            case 9 -> {
                int elemType = dis.readUnsignedByte();
                int len = dis.readInt();
                List<Object> list = new ArrayList<>(Math.max(0, Math.min(len, 4096)));
                for (int i = 0; i < len; i++) list.add(readPayload(dis, elemType));
                yield list;
            }
            case 10 -> readCompound(dis);
            case 11 -> {
                int len = dis.readInt();
                int[] arr = new int[len];
                for (int i = 0; i < len; i++) arr[i] = dis.readInt();
                yield arr;
            }
            case 12 -> {
                int len = dis.readInt();
                long[] arr = new long[len];
                for (int i = 0; i < len; i++) arr[i] = dis.readLong();
                yield arr;
            }
            default -> throw new IOException("Unsupported NBT tag type: " + tag);
        };
    }

    private static String readString(DataInputStream dis) throws IOException {
        int len = dis.readUnsignedShort();
        byte[] bytes = dis.readNBytes(len);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
