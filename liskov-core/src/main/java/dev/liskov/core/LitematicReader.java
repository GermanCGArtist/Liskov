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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Pure-Java GZIP + NBT reader for {@code .litematic} files (zero Minecraft or Litematica imports).
 * Extracts schematic metadata, exact block counts, palette, and a surface-culled 3D voxel grid
 * (1:1 up to 80,000 non-air blocks; adaptive cubic LOD beyond) for crisp 3D previewing.
 */
public final class LitematicReader {
    public static final int FACE_POS_X = 1 << 0;
    public static final int FACE_NEG_X = 1 << 1;
    public static final int FACE_POS_Y = 1 << 2;
    public static final int FACE_NEG_Y = 1 << 3;
    public static final int FACE_POS_Z = 1 << 4;
    public static final int FACE_NEG_Z = 1 << 5;
    public static final byte FACE_ALL = 0x3F;

    private static final int MAX_ONE_TO_ONE_BLOCKS = 80_000;
    private static final int TARGET_LOD_VOXELS = 60_000;
    private static final int MAX_SURFACE_VOXELS = 90_000;
    private static final long MAX_DENSE_GRID = 8_000_000L;

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
        /** Sampled 3D voxel grid in [0..gridX-1, 0..gridY-1, 0..gridZ-1] with exposed face masks. */
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

    public record Voxel(int x, int y, int z, String blockId, int argbColor, byte faceMask) {
        public Voxel(int x, int y, int z, String blockId, int argbColor) {
            this(x, y, z, blockId, argbColor, FACE_ALL);
        }
    }

    private record RawCell(int gx, int gy, int gz, String blockId, int argb, boolean opaque) {}

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

        if (regions == null) {
            return new Summary(name, author, desc, encX, encY, encZ, regionCount,
                    meta != null ? meta.intVal("TotalBlocks", 0) : 0,
                    List.of(), Math.max(1, encX), Math.max(1, encY), Math.max(1, encZ), 1, List.of());
        }

        // Pass 1: enclosing bounds + exact non-air count + material tally
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        Map<String, Integer> materialCounts = new LinkedHashMap<>();
        int countedBlocks = 0;

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

            List<Object> paletteList = reg.list("BlockStatePalette");
            long[] blockStates = reg.longArray("BlockStates");
            if (paletteList == null || paletteList.isEmpty() || blockStates == null) continue;
            String[] paletteIds = extractPalette(paletteList);
            int absX = Math.abs(sx), absY = Math.abs(sy), absZ = Math.abs(sz);
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, paletteIds.length - 1)));
            long mask = (1L << bits) - 1L;
            int totalCells = absX * absY * absZ;
            for (int idx = 0; idx < totalCells; idx++) {
                int pIdx = readPacked(blockStates, idx, bits, mask);
                if (pIdx < 0 || pIdx >= paletteIds.length) continue;
                String blockId = paletteIds[pIdx];
                if (isAir(blockId)) continue;
                countedBlocks++;
                materialCounts.merge(blockId, 1, Integer::sum);
            }
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

        // Keep 1:1 resolution whenever non-air block count is <= 80k (even if elongated like 5x1x1520)
        int step = countedBlocks <= MAX_ONE_TO_ONE_BLOCKS
                ? 1
                : Math.max(2, (int) Math.ceil(Math.cbrt((double) countedBlocks / TARGET_LOD_VOXELS)));

        int gridX = Math.max(1, (spanX + step - 1) / step);
        int gridY = Math.max(1, (spanY + step - 1) / step);
        int gridZ = Math.max(1, (spanZ + step - 1) / step);
        long denseSize = (long) gridX * gridY * gridZ;
        boolean useDense = denseSize > 0 && denseSize <= MAX_DENSE_GRID;

        // 0 = empty, 1 = transparent/non-full, 2 = opaque full block
        byte[] denseOcc = useDense ? new byte[(int) denseSize] : null;
        Map<Long, Byte> sparseOcc = useDense ? null : new HashMap<>(Math.min(countedBlocks, 120_000));
        List<RawCell> rawCells = new ArrayList<>(Math.min(countedBlocks, 100_000));

        // Pass 2: sample voxels & record occupancy for surface culling
        for (Object val : regions.entries.values()) {
            if (!(val instanceof NbtCompound reg)) continue;
            NbtCompound pos = reg.compound("Position");
            NbtCompound size = reg.compound("Size");
            List<Object> paletteList = reg.list("BlockStatePalette");
            long[] blockStates = reg.longArray("BlockStates");
            if (pos == null || size == null || paletteList == null || paletteList.isEmpty() || blockStates == null) continue;

            String[] paletteIds = extractPalette(paletteList);
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

                        int gx = (originX + x - minX) / step;
                        int gy = (originY + y - minY) / step;
                        int gz = (originZ + z - minZ) / step;
                        if (gx < 0 || gy < 0 || gz < 0 || gx >= gridX || gy >= gridY || gz >= gridZ) continue;

                        boolean opaque = isOpaqueFullBlock(blockId);
                        byte occFlag = (byte) (opaque ? 2 : 1);
                        if (useDense) {
                            int dIdx = (gy * gridZ + gz) * gridX + gx;
                            if (denseOcc[dIdx] != 0) {
                                if (opaque) denseOcc[dIdx] = 2;
                                continue;
                            }
                            denseOcc[dIdx] = occFlag;
                        } else {
                            long k = packCoord(gx, gy, gz);
                            Byte prev = sparseOcc.get(k);
                            if (prev != null) {
                                if (opaque && prev != 2) sparseOcc.put(k, (byte) 2);
                                continue;
                            }
                            sparseOcc.put(k, occFlag);
                        }
                        rawCells.add(new RawCell(gx, gy, gz, blockId, colorForBlock(blockId), opaque));
                    }
                }
            }
        }

        // Pass 3: compute 6-neighbor surface face masks and drop completely hidden interior blocks
        List<Voxel> surfaceVoxels = new ArrayList<>(Math.min(rawCells.size(), MAX_SURFACE_VOXELS));
        for (RawCell rc : rawCells) {
            byte fm = 0;
            if (!isOpaqueAt(rc.gx + 1, rc.gy, rc.gz, gridX, gridY, gridZ, denseOcc, sparseOcc)) fm |= FACE_POS_X;
            if (!isOpaqueAt(rc.gx - 1, rc.gy, rc.gz, gridX, gridY, gridZ, denseOcc, sparseOcc)) fm |= FACE_NEG_X;
            if (!isOpaqueAt(rc.gx, rc.gy + 1, rc.gz, gridX, gridY, gridZ, denseOcc, sparseOcc)) fm |= FACE_POS_Y;
            if (!isOpaqueAt(rc.gx, rc.gy - 1, rc.gz, gridX, gridY, gridZ, denseOcc, sparseOcc)) fm |= FACE_NEG_Y;
            if (!isOpaqueAt(rc.gx, rc.gy, rc.gz + 1, gridX, gridY, gridZ, denseOcc, sparseOcc)) fm |= FACE_POS_Z;
            if (!isOpaqueAt(rc.gx, rc.gy, rc.gz - 1, gridX, gridY, gridZ, denseOcc, sparseOcc)) fm |= FACE_NEG_Z;
            if (fm != 0) {
                surfaceVoxels.add(new Voxel(rc.gx, rc.gy, rc.gz, rc.blockId, rc.argb, fm));
                if (surfaceVoxels.size() >= MAX_SURFACE_VOXELS) break;
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
                gridX, gridY, gridZ, step,
                Collections.unmodifiableList(surfaceVoxels)
        );
    }

    private static String[] extractPalette(List<Object> paletteList) {
        String[] paletteIds = new String[paletteList.size()];
        for (int i = 0; i < paletteList.size(); i++) {
            String bName = "minecraft:air";
            if (paletteList.get(i) instanceof NbtCompound entry) {
                bName = entry.str("Name", "minecraft:air");
            }
            paletteIds[i] = bName;
        }
        return paletteIds;
    }

    private static long packCoord(int x, int y, int z) {
        return (((long) x & 0xFFFFFL) << 40) | (((long) y & 0xFFFFFL) << 20) | ((long) z & 0xFFFFFL);
    }

    private static boolean isOpaqueAt(
            int x, int y, int z, int gx, int gy, int gz, byte[] denseOcc, Map<Long, Byte> sparseOcc
    ) {
        if (x < 0 || y < 0 || z < 0 || x >= gx || y >= gy || z >= gz) return false;
        if (denseOcc != null) {
            return denseOcc[(y * gz + z) * gx + x] == 2;
        }
        Byte b = sparseOcc.get(packCoord(x, y, z));
        return b != null && b == 2;
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

    private static boolean isOpaqueFullBlock(String blockId) {
        if (blockId == null) return true;
        String s = blockId.toLowerCase(Locale.ROOT);
        return !(s.contains("glass") || s.contains("water") || s.contains("leaves")
                || s.contains("pane") || s.contains("bars") || s.contains("fence")
                || s.contains("wall") || s.contains("slab") || s.contains("stairs")
                || s.contains("rail") || s.contains("torch") || s.contains("lantern")
                || s.contains("trapdoor") || s.contains("door") || s.contains("sign")
                || s.contains("button") || s.contains("pressure_plate") || s.contains("carpet")
                || s.contains("chain") || s.contains("rod") || s.contains("ladder")
                || s.equals("minecraft:ice") || s.contains("frosted_ice"));
    }

    /**
     * Minecraft-accurate block preview palette for fast pure-Java 3D rendering.
     */
    public static int colorForBlock(String blockId) {
        String s = blockId == null ? "" : blockId.toLowerCase(Locale.ROOT);
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(colon + 1);

        // Ice & water (specific before generic stone/blue)
        if (s.contains("blue_ice")) return 0xFF5FA8FF;
        if (s.contains("packed_ice")) return 0xFF8CC4FF;
        if (s.contains("ice")) return 0xFF9ED7FF;
        if (s.contains("water")) return 0xFF3376D8;
        if (s.contains("lava")) return 0xFFFF681F;

        // Rails & redstone mechanisms
        if (s.contains("powered_rail") || s.contains("activator_rail") || s.contains("detector_rail")) return 0xFFD4A037;
        if (s.contains("rail")) return 0xFF8A7562;
        if (s.contains("redstone_lamp")) return 0xFFC27B3A;
        if (s.contains("redstone_block")) return 0xFFD9261C;
        if (s.contains("redstone") || s.contains("repeater") || s.contains("comparator")) return 0xFFC82820;
        if (s.contains("piston")) return 0xFF9C8462;
        if (s.contains("hopper") || s.contains("cauldron") || s.contains("anvil") || s.contains("chain")) return 0xFF4C4F57;
        if (s.contains("observer") || s.contains("dispenser") || s.contains("dropper")) return 0xFF6B6E73;
        if (s.contains("chest") || s.contains("barrel") || s.contains("lectern") || s.contains("bookshelf")) return 0xFF996B3D;

        // Stones (most specific first)
        if (s.contains("mossy_cobblestone") || s.contains("mossy_stone")) return 0xFF677A5D;
        if (s.contains("cobblestone")) return 0xFF7B7B7B;
        if (s.contains("smooth_stone")) return 0xFF9EA0A3;
        if (s.contains("stone_brick")) return 0xFF7D7E80;
        if (s.contains("tuff")) return 0xFF6B6D62;
        if (s.contains("andesite")) return 0xFF898A8B;
        if (s.contains("diorite")) return 0xFFDADADA;
        if (s.contains("granite")) return 0xFFA1705D;
        if (s.contains("deepslate")) return 0xFF4E5056;
        if (s.contains("blackstone") || s.contains("basalt")) return 0xFF38363B;
        if (s.contains("obsidian") || s.contains("crying_obsidian")) return 0xFF281D38;
        if (s.contains("bedrock")) return 0xFF474747;
        if (s.contains("stone") && !s.contains("sandstone") && !s.contains("redstone") && !s.contains("glowstone") && !s.contains("end_stone")) {
            return 0xFF8C8D8F;
        }

        // Woods (by species)
        if (s.contains("dark_oak")) return 0xFF4A311B;
        if (s.contains("spruce")) return 0xFF705234;
        if (s.contains("birch")) return 0xFFCDBB7B;
        if (s.contains("jungle")) return 0xFFA37651;
        if (s.contains("acacia")) return 0xFFAF6036;
        if (s.contains("mangrove")) return 0xFF793732;
        if (s.contains("cherry")) return 0xFFDFAEB2;
        if (s.contains("bamboo")) return 0xFFCDB354;
        if (s.contains("crimson")) return 0xFF7A3248;
        if (s.contains("warped")) return 0xFF2E7E78;
        if (s.contains("oak") || s.contains("plank") || s.contains("wood") || s.contains("log") || s.contains("fence") || s.contains("trapdoor")) {
            return 0xFFA4834F;
        }

        // Copper states & metals
        if (s.contains("oxidized_copper")) return 0xFF53A385;
        if (s.contains("weathered_copper")) return 0xFF6BA676;
        if (s.contains("exposed_copper")) return 0xFF9F7C67;
        if (s.contains("copper")) return 0xFFC56B4E;
        if (s.contains("netherite")) return 0xFF453E42;
        if (s.contains("diamond")) return 0xFF5CE6DC;
        if (s.contains("emerald")) return 0xFF2FD46B;
        if (s.contains("lapis")) return 0xFF2855B8;
        if (s.contains("gold") || s.contains("raw_gold")) return 0xFFF4C538;
        if (s.contains("iron") || s.contains("raw_iron")) return 0xFFD9DCE0;
        if (s.contains("coal")) return 0xFF2D2E32;

        // Lighting & Nether / End / Prismarine
        if (s.contains("sea_lantern")) return 0xFFB8DFD8;
        if (s.contains("glowstone") || s.contains("shroomlight") || s.contains("ochre_froglight")) return 0xFFF2BC54;
        if (s.contains("lantern") || s.contains("torch") || s.contains("campfire")) return 0xFFF5AA36;
        if (s.contains("nether_brick")) return 0xFF3E2228;
        if (s.contains("netherrack")) return 0xFF6B2D2D;
        if (s.contains("soul_sand") || s.contains("soul_soil")) return 0xFF544237;
        if (s.contains("dark_prismarine")) return 0xFF375C50;
        if (s.contains("prismarine")) return 0xFF65A699;
        if (s.contains("purpur")) return 0xFFA97BA9;
        if (s.contains("end_stone")) return 0xFFDBD79E;
        if (s.contains("quartz")) return 0xFFECE7DF;
        if (s.contains("calcite")) return 0xFFE3E4DF;
        if (s.contains("amethyst")) return 0xFF8C5CC9;
        if (s.contains("red_sandstone") || s.contains("red_sand")) return 0xFFBC6326;
        if (s.contains("sandstone") || s.contains("sand")) return 0xFFDDD09C;
        if (s.contains("brick") && !s.contains("stone_brick")) return 0xFF9C5446;

        // Nature / terrain
        if (s.contains("grass_block")) return 0xFF5CA641;
        if (s.contains("leaves") || s.contains("moss") || s.contains("azalea")) return 0xFF478738;
        if (s.contains("podzol")) return 0xFF63482E;
        if (s.contains("mycelium")) return 0xFF746672;
        if (s.contains("dirt") || s.contains("mud") || s.contains("farmland")) return 0xFF855E3E;
        if (s.contains("gravel")) return 0xFF827E7C;
        if (s.contains("clay")) return 0xFF9EA5B1;
        if (s.contains("snow")) return 0xFFF2F6FA;

        // Dyed blocks (wool, concrete, terracotta, glass, glazed_terracotta)
        Integer dye = dyeColor(s);
        if (dye != null) {
            if (s.contains("glass")) return (0xCC << 24) | (dye & 0x00FFFFFF);
            if (s.contains("terracotta")) return mixColor(dye, 0xFF995B45, 0.45f);
            return dye;
        }
        if (s.contains("glass")) return 0xBB9ADAF5;
        if (s.contains("terracotta")) return 0xFF995E46;
        if (s.contains("slime")) return 0xFF74C25B;
        if (s.contains("honey")) return 0xFFE59D2E;
        if (s.contains("hay")) return 0xFFCBB138;
        if (s.contains("sponge")) return 0xFFC8C24A;

        return 0xFF848D9C;
    }

    private static Integer dyeColor(String s) {
        if (s.contains("light_blue")) return 0xFF5FB4E8;
        if (s.contains("light_gray") || s.contains("light_grey")) return 0xFF9D9D97;
        if (s.contains("white")) return 0xFFECEEF0;
        if (s.contains("orange")) return 0xFFF27824;
        if (s.contains("magenta")) return 0xFFC54BB8;
        if (s.contains("yellow")) return 0xFFF4D038;
        if (s.contains("lime")) return 0xFF73C926;
        if (s.contains("pink")) return 0xFFF08FB2;
        if (s.contains("gray") || s.contains("grey")) return 0xFF4E5458;
        if (s.contains("cyan")) return 0xFF1F91A1;
        if (s.contains("purple")) return 0xFF8234BA;
        if (s.contains("blue")) return 0xFF3449B4;
        if (s.contains("brown")) return 0xFF7C4D2D;
        if (s.contains("green")) return 0xFF537323;
        if (s.contains("red")) return 0xFFB4302B;
        if (s.contains("black")) return 0xFF1E1F25;
        return null;
    }

    private static int mixColor(int a, int b, float tB) {
        float tA = 1f - tB;
        int r = Math.round(((a >>> 16) & 0xFF) * tA + ((b >>> 16) & 0xFF) * tB);
        int g = Math.round(((a >>> 8) & 0xFF) * tA + ((b >>> 8) & 0xFF) * tB);
        int bl = Math.round((a & 0xFF) * tA + (b & 0xFF) * tB);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
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
