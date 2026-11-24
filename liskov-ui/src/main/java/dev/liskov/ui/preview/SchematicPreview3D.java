package dev.liskov.ui.preview;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.liskov.core.LitematicReader;
import dev.liskov.core.SemanticColor;
import dev.liskov.ui.host.LiskovUiHost;
import dev.liskov.ui.theme.MubTheme;
import dev.liskov.ui.theme.MubWidgets;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiButtonFlags;
import imgui.flag.ImGuiMouseButton;
import imgui.type.ImInt;

import java.util.Arrays;

/**
 * Interactive high-precision 3D shaded-cube schematic preview canvas for {@code .litematic}
 * (including read-only previews of older {@code .liskov-vcs/} commits).
 *
 * <p>Uses {@link ImGui#invisibleButton} so orbiting/panning inside the viewport never drags the enclosing
 * ImGui window, renders shaded 3D isometric/orthographic block cubes at 640x440 with per-face directional
 * lighting, supports zoom up to 15x + pan + bounds wireframe, and exposes a discrete Y-layer slice slider.
 */
public final class SchematicPreview3D {
    private static final int TEX_W = 640;
    private static final int TEX_H = 440;

    private String loadedKey = "";
    private LitematicReader.Summary summary;
    private float yaw = 0.68f;
    private float pitch = 0.48f;
    private float zoom = 1.0f;
    private float panX = 0f;
    private float panY = 0f;
    private boolean shadedMode = true;
    private boolean showBoundsBox = true;
    private final ImInt sliceLayer = new ImInt(1);

    private boolean dirty = true;
    private GpuTexture gpuTex;
    private GpuTextureView gpuView;
    private long imguiTexId;
    private final int[] colorBuf = new int[TEX_W * TEX_H];
    private final float[] depthBuf = new float[TEX_W * TEX_H];

    public void setSummary(String key, LitematicReader.Summary summary) {
        if (key != null && key.equals(this.loadedKey)) return;
        this.loadedKey = key == null ? "" : key;
        this.summary = summary;
        if (summary != null) {
            this.sliceLayer.set(Math.max(1, summary.gridY));
            this.panX = 0f;
            this.panY = 0f;
        }
        this.dirty = true;
    }

    public LitematicReader.Summary summary() {
        return summary;
    }

    public void resetCamera() {
        yaw = 0.68f;
        pitch = 0.48f;
        zoom = 1.0f;
        panX = 0f;
        panY = 0f;
        if (summary != null) {
            sliceLayer.set(Math.max(1, summary.gridY));
        }
        dirty = true;
    }

    public void drawInspectorPreview(float width) {
        if (summary == null) return;
        ensureTexture();
        if (dirty) {
            rasterize();
            uploadTexture();
            dirty = false;
        }

        if (MubWidgets.termButton((shadedMode ? "SHADED" : "FLAT") + "##p3_shade",
                shadedMode ? SemanticColor.FOCUS : SemanticColor.INK_MUTED)) {
            shadedMode = !shadedMode;
            dirty = true;
        }
        MubWidgets.tooltip("Toggle Shaded Cubes / Flat", "Switch between lit 3D block shading with edges and flat shading.");
        ImGui.sameLine();
        if (MubWidgets.termButton("BOX##p3_box", showBoundsBox ? SemanticColor.PLAYER : SemanticColor.INK_MUTED)) {
            showBoundsBox = !showBoundsBox;
            dirty = true;
        }
        MubWidgets.tooltip("Toggle Bounding Box & Grid", "Show or hide the schematic wireframe box and floor grid.");
        ImGui.sameLine();
        if (MubWidgets.termButton("RESET##p3_rst", SemanticColor.INK_MUTED)) {
            resetCamera();
        }
        MubWidgets.tooltip("Reset Camera", "Restore default angle, zoom, pan, and full Y layers (or double-click preview).");
        ImGui.sameLine();
        String scaleTag = summary.sampleStep <= 1 ? "1:1" : "1:" + summary.sampleStep + " LOD";
        MubTheme.textColored(MubTheme.GUTTER,
                summary.sizeX + "x" + summary.sizeY + "x" + summary.sizeZ + " | " + scaleTag);
        MubWidgets.tooltip("Dimensions & Voxel Precision",
                "Width x Height x Depth in blocks (" + summary.totalBlocks + " blocks total).");

        float aspect = (float) TEX_H / TEX_W;
        float dispW = (float) Math.floor(Math.max(140f, width));
        float dispH = (float) Math.floor(dispW * aspect);
        if (imguiTexId != 0L) {
            float px = ImGui.getCursorScreenPosX();
            float py = ImGui.getCursorScreenPosY();
            // Capture all 3 mouse buttons so dragging inside the canvas NEVER drags the enclosing window
            ImGui.invisibleButton("##preview3d_canvas", dispW, dispH,
                    ImGuiButtonFlags.MouseButtonLeft | ImGuiButtonFlags.MouseButtonRight | ImGuiButtonFlags.MouseButtonMiddle);

            ImDrawList dl = ImGui.getWindowDrawList();
            dl.addImage(imguiTexId, px, py, px + dispW, py + dispH);
            dl.addRect(px, py, px + dispW, py + dispH,
                    ImGui.isItemHovered() || ImGui.isItemActive()
                            ? MubTheme.u32(MubTheme.BORDER_HI)
                            : MubTheme.u32(MubTheme.LINE));

            if (ImGui.isItemActive()) {
                var delta = ImGui.getIO().getMouseDelta();
                if (Math.abs(delta.x) > 0.0001f || Math.abs(delta.y) > 0.0001f) {
                    boolean panDrag = ImGui.isMouseDown(ImGuiMouseButton.Right)
                            || ImGui.isMouseDown(ImGuiMouseButton.Middle)
                            || ImGui.getIO().getKeyShift();
                    if (panDrag) {
                        panX += delta.x * (TEX_W / dispW);
                        panY += delta.y * (TEX_H / dispH);
                    } else if (ImGui.isMouseDown(ImGuiMouseButton.Left)) {
                        yaw += delta.x * 0.012f;
                        pitch = Math.max(-1.45f, Math.min(1.45f, pitch + delta.y * 0.011f));
                    }
                    dirty = true;
                }
            }
            if (ImGui.isItemHovered()) {
                float wheel = ImGui.getIO().getMouseWheel();
                if (Math.abs(wheel) > 0.001f) {
                    float factor = 1f + wheel * 0.16f;
                    zoom = Math.max(0.25f, Math.min(15.0f, zoom * factor));
                    dirty = true;
                }
                if (ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left)) {
                    resetCamera();
                }
                MubWidgets.tooltip("3D Schematic Preview",
                        "Left-drag: Orbit  |  Right/Middle/Shift+Left: Pan\nScroll: Zoom (up to 15x)  |  Double-click: Reset");
            }
        }

        // Discrete Y-Layer slice control
        if (summary.gridY > 1) {
            int maxLayer = summary.gridY;
            if (sliceLayer.get() < 1) sliceLayer.set(1);
            if (sliceLayer.get() > maxLayer) sliceLayer.set(maxLayer);
            ImGui.setNextItemWidth(dispW);
            if (ImGui.sliderInt("##yslice", sliceLayer.getData(), 1, maxLayer, "Y-LAYER %d / " + maxLayer)) {
                dirty = true;
            }
            MubWidgets.tooltip("Y-Layer Slice",
                    "Inspect cross-sections layer by layer from Y=1 up to Y=" + maxLayer + ".");
        } else {
            MubWidgets.termBadge("Y-LAYER 1 / 1 (FLAT)", SemanticColor.INK_MUTED);
            MubWidgets.tooltip("Y-Layer Slice", "This schematic has a height of 1 block.");
        }
    }

    private void ensureTexture() {
        if (gpuTex != null && !gpuTex.isClosed()) return;
        GpuDevice dev = RenderSystem.getDevice();
        gpuTex = dev.createTexture("Liskov 3D Preview",
                GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA8_UNORM, TEX_W, TEX_H, 1, 1);
        gpuView = dev.createTextureView(gpuTex);
        imguiTexId = LiskovUiHost.registerTexture(gpuView);
        dirty = true;
    }

    private void uploadTexture() {
        if (gpuTex == null || gpuTex.isClosed()) return;
        GpuDevice dev = RenderSystem.getDevice();
        try (NativeImage img = new NativeImage(TEX_W, TEX_H, false)) {
            for (int y = 0; y < TEX_H; y++) {
                int row = y * TEX_W;
                for (int x = 0; x < TEX_W; x++) {
                    img.setPixelABGR(x, y, argbToAbgr(colorBuf[row + x]));
                }
            }
            CommandEncoder enc = dev.createCommandEncoder();
            enc.writeToTexture(gpuTex, img);
            enc.submit();
        }
    }

    private static int argbToAbgr(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    private void rasterize() {
        int bg = 0xFF000000 | MubTheme.BG;
        Arrays.fill(colorBuf, bg);
        Arrays.fill(depthBuf, Float.NEGATIVE_INFINITY);
        int dot = 0xFF000000 | MubTheme.LINE;
        for (int y = 0; y < TEX_H; y += 24) {
            for (int x = 0; x < TEX_W; x += 24) {
                colorBuf[y * TEX_W + x] = dot;
            }
        }
        if (summary == null || summary.voxels.isEmpty()) return;

        int cutLayers = Math.max(1, Math.min(summary.gridY, sliceLayer.get()));
        float maxSpan = Math.max(summary.gridX, Math.max(summary.gridY, summary.gridZ));
        float scale = (Math.min(TEX_W, TEX_H) * 0.70f / Math.max(1f, maxSpan)) * zoom;

        float cy = (float) Math.cos(yaw), sy = (float) Math.sin(yaw);
        float cp = (float) Math.cos(pitch), sp = (float) Math.sin(pitch);

        // Camera projection basis:
        // World (wx, wy, wz) -> screenX = cx0 + (wx*rx + wz*rz) * scale
        //                       screenY = cy0 - (wy*uy + wx*ux + wz*uz) * scale
        //                       depth   = wx*dx + wy*dy + wz*dz (larger = closer to camera)
        float rx = cy, rz = -sy;
        float ux = -sy * sp, uy = cp, uz = -cy * sp;
        float dx = sy * cp, dy = sp, dz = cy * cp;

        float cx0 = TEX_W * 0.5f + panX;
        float cy0 = TEX_H * 0.52f + panY;
        float ox = summary.gridX * 0.5f;
        float oy = summary.gridY * 0.5f;
        float oz = summary.gridZ * 0.5f;

        if (showBoundsBox) {
            drawBoundsAndFloor(ox, oy, oz, scale, cx0, cy0, rx, rz, ux, uy, uz, dx, dy, dz);
        }

        // Camera-facing direction signs
        boolean posXFront = dx >= 0f;
        boolean posYFront = dy >= 0f;
        boolean posZFront = dz >= 0f;
        int xBit = posXFront ? LitematicReader.FACE_POS_X : LitematicReader.FACE_NEG_X;
        int yBit = posYFront ? LitematicReader.FACE_POS_Y : LitematicReader.FACE_NEG_Y;
        int zBit = posZFront ? LitematicReader.FACE_POS_Z : LitematicReader.FACE_NEG_Z;

        // Projected corner deltas for unit cube of side 1 in world coords
        float xsX = rx * scale, xsY = -ux * scale;
        float ysY = -uy * scale;
        float zsX = rz * scale, zsY = -uz * scale;

        float[] sx = new float[8];
        float[] syArr = new float[8];
        float[] sz = new float[8];

        int edgeTint = shadedMode && scale >= 4.0f ? (0xFF000000 | MubTheme.BG) : 0;
        boolean isSliced = cutLayers < summary.gridY;
        int maxCutY = cutLayers - 1;

        for (LitematicReader.Voxel v : summary.voxels) {
            if (v.y() > maxCutY) continue;

            int fm = v.faceMask() & 0x3F;
            if (isSliced && v.y() == maxCutY) {
                fm |= LitematicReader.FACE_POS_Y;
            }
            // Fast skip if none of the 3 camera-facing faces are exposed
            int visFaces = fm & (xBit | yBit | zBit);
            if (visFaces == 0) continue;

            float x0 = v.x() - ox;
            float y0 = v.y() - oy;
            float z0 = v.z() - oz;

            float baseSx = cx0 + x0 * xsX + z0 * zsX;
            float baseSy = cy0 + x0 * xsY + y0 * ysY + z0 * zsY;
            float baseD = x0 * dx + y0 * dy + z0 * dz;

            // Corner indexing: bit0=+x, bit1=+y, bit2=+z
            for (int c = 0; c < 8; c++) {
                float ax = (c & 1) != 0 ? 1f : 0f;
                float ay = (c & 2) != 0 ? 1f : 0f;
                float az = (c & 4) != 0 ? 1f : 0f;
                sx[c] = baseSx + ax * xsX + az * zsX;
                syArr[c] = baseSy + ax * xsY + ay * ysY + az * zsY;
                sz[c] = baseD + ax * dx + ay * dy + az * dz;
            }

            // Reject completely off-screen cubes quickly
            float minBx = Math.min(Math.min(sx[0], sx[1]), Math.min(sx[4], sx[5]));
            float maxBx = Math.max(Math.max(sx[0], sx[1]), Math.max(sx[4], sx[5]));
            if (maxBx < 0f || minBx >= TEX_W) continue;

            float ao = summary.gridY > 1 ? (0.88f + 0.12f * ((float) v.y() / summary.gridY)) : 1.0f;
            int baseColor = v.argbColor();

            if ((visFaces & yBit) != 0) {
                float shadeF = posYFront ? (shadedMode ? 1.02f : 0.96f) : (shadedMode ? 0.50f : 0.72f);
                int cTop = isSliced && posYFront && v.y() == maxCutY
                        ? mixHighlight(shade(baseColor, shadeF * ao), MubTheme.ACCENT, 0.25f)
                        : shade(baseColor, shadeF * ao);
                if (posYFront) {
                    drawQuad(sx, syArr, sz, 2, 6, 7, 3, cTop, edgeTint);
                } else {
                    drawQuad(sx, syArr, sz, 0, 1, 5, 4, cTop, edgeTint);
                }
            }
            if ((visFaces & zBit) != 0) {
                float shadeF = (posZFront ? 0.85f : 0.79f) * (shadedMode ? 1.0f : 0.96f) * ao;
                int cZ = shade(baseColor, shadeF);
                if (posZFront) {
                    drawQuad(sx, syArr, sz, 4, 5, 7, 6, cZ, edgeTint);
                } else {
                    drawQuad(sx, syArr, sz, 0, 2, 3, 1, cZ, edgeTint);
                }
            }
            if ((visFaces & xBit) != 0) {
                float shadeF = (posXFront ? 0.70f : 0.64f) * (shadedMode ? 1.0f : 0.94f) * ao;
                int cX = shade(baseColor, shadeF);
                if (posXFront) {
                    drawQuad(sx, syArr, sz, 1, 3, 7, 5, cX, edgeTint);
                } else {
                    drawQuad(sx, syArr, sz, 0, 4, 6, 2, cX, edgeTint);
                }
            }
        }

        drawAxisTriad(rx, rz, ux, uy, uz);
    }

    private void drawBoundsAndFloor(
            float ox, float oy, float oz, float scale, float cx0, float cy0,
            float rx, float rz, float ux, float uy, float uz, float dx, float dy, float dz
    ) {
        int gx = summary.gridX, gy = summary.gridY, gz = summary.gridZ;
        int gridCol = mixHighlight(0xFF000000 | MubTheme.LINE, MubTheme.BG, 0.25f);
        int boxCol = 0xFF000000 | MubTheme.BORDER_HI;

        int stepGrid = Math.max(1, Math.max(gx, gz) / 16);
        for (int x = 0; x <= gx; x += stepGrid) {
            drawLine3D(x - ox, -oy, -oz, x - ox, -oy, gz - oz, gridCol, scale, cx0, cy0, rx, rz, ux, uy, uz, dx, dy, dz);
        }
        for (int z = 0; z <= gz; z += stepGrid) {
            drawLine3D(-ox, -oy, z - oz, gx - ox, -oy, z - oz, gridCol, scale, cx0, cy0, rx, rz, ux, uy, uz, dx, dy, dz);
        }

        // 12 outer bounding box edges
        float x0 = -ox, x1 = gx - ox;
        float y0 = -oy, y1 = gy - oy;
        float z0 = -oz, z1 = gz - oz;
        drawLine3D(x0, y0, z0, x1, y0, z0, boxCol, scale, cx0, cy0, rx, rz, ux, uy, uz, dx, dy, dz);
        drawLine3D(x1, y0, z0, x1, y0, z1, boxCol, scale, cx0, cy0, rx, rz, ux, uy, uz, dx, dy, dz);
        drawLine3D(x1, y0, z1, x0, y0, z1, boxCol, scale, cx0, cy0, rx, rz, ux, uy, uz, dx, dy, dz);
        drawLine3D(x0, y0, z1, x0, y0, z0, boxCol, scale, cx0, cy0, rx, rz, ux, uy, uz, dx, dy, dz);

        drawLine3D(x0, y1, z0, x1, y1, z0, boxCol, scale, cx0, cy0, rx, rz, ux, uy, uz, dx, dy, dz);
        drawLine3D(x1, y1, z0, x1, y1, z1, boxCol, scale, cx0, cy0, rx, rz, ux, uy, uz, dx, dy, dz);
        drawLine3D(x1, y1, z1, x0, y1, z1, boxCol, scale, cx0, cy0, rx, rz, ux, uy, uz, dx, dy, dz);
        drawLine3D(x0, y1, z1, x0, y1, z0, boxCol, scale, cx0, cy0, rx, rz, ux, uy, uz, dx, dy, dz);

        drawLine3D(x0, y0, z0, x0, y1, z0, boxCol, scale, cx0, cy0, rx, rz, ux, uy, uz, dx, dy, dz);
        drawLine3D(x1, y0, z0, x1, y1, z0, boxCol, scale, cx0, cy0, rx, rz, ux, uy, uz, dx, dy, dz);
        drawLine3D(x1, y0, z1, x1, y1, z1, boxCol, scale, cx0, cy0, rx, rz, ux, uy, uz, dx, dy, dz);
        drawLine3D(x0, y0, z1, x0, y1, z1, boxCol, scale, cx0, cy0, rx, rz, ux, uy, uz, dx, dy, dz);
    }

    private void drawLine3D(
            float ax, float ay, float az, float bx, float by, float bz, int argb,
            float scale, float cx0, float cy0,
            float rx, float rz, float ux, float uy, float uz, float dx, float dy, float dz
    ) {
        float sx0 = cx0 + (ax * rx + az * rz) * scale;
        float sy0 = cy0 - (ax * ux + ay * uy + az * uz) * scale;
        float d0 = ax * dx + ay * dy + az * dz;
        float sx1 = cx0 + (bx * rx + bz * rz) * scale;
        float sy1 = cy0 - (bx * ux + by * uy + bz * uz) * scale;
        float d1 = bx * dx + by * dy + bz * dz;
        drawLineScreenDepth(sx0, sy0, d0, sx1, sy1, d1, argb);
    }

    private void drawLineScreenDepth(float x0, float y0, float d0, float x1, float y1, float d1, int argb) {
        float dist = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
        int steps = Math.min(2000, Math.max(1, Math.round(dist)));
        for (int i = 0; i <= steps; i++) {
            float t = (float) i / steps;
            int px = Math.round(x0 + (x1 - x0) * t);
            int py = Math.round(y0 + (y1 - y0) * t);
            if (px < 0 || px >= TEX_W || py < 0 || py >= TEX_H) continue;
            float d = d0 + (d1 - d0) * t + 0.002f;
            int idx = py * TEX_W + px;
            if (d >= depthBuf[idx]) {
                depthBuf[idx] = d;
                colorBuf[idx] = argb;
            }
        }
    }

    private void drawQuad(float[] sx, float[] sy, float[] sz, int i0, int i1, int i2, int i3, int fillArgb, int edgeArgb) {
        drawTri(sx[i0], sy[i0], sz[i0], sx[i1], sy[i1], sz[i1], sx[i2], sy[i2], sz[i2], fillArgb);
        drawTri(sx[i0], sy[i0], sz[i0], sx[i2], sy[i2], sz[i2], sx[i3], sy[i3], sz[i3], fillArgb);
        if (edgeArgb != 0) {
            int subtleEdge = mixHighlight(fillArgb, edgeArgb, 0.35f);
            drawLineScreenDepth(sx[i0], sy[i0], sz[i0] + 0.01f, sx[i1], sy[i1], sz[i1] + 0.01f, subtleEdge);
            drawLineScreenDepth(sx[i1], sy[i1], sz[i1] + 0.01f, sx[i2], sy[i2], sz[i2] + 0.01f, subtleEdge);
            drawLineScreenDepth(sx[i2], sy[i2], sz[i2] + 0.01f, sx[i3], sy[i3], sz[i3] + 0.01f, subtleEdge);
            drawLineScreenDepth(sx[i3], sy[i3], sz[i3] + 0.01f, sx[i0], sy[i0], sz[i0] + 0.01f, subtleEdge);
        }
    }

    private void drawTri(
            float ax, float ay, float az,
            float bx, float by, float bz,
            float cx, float cy, float cz,
            int argb
    ) {
        int minX = Math.max(0, (int) Math.floor(Math.min(ax, Math.min(bx, cx))));
        int maxX = Math.min(TEX_W - 1, (int) Math.ceil(Math.max(ax, Math.max(bx, cx))));
        int minY = Math.max(0, (int) Math.floor(Math.min(ay, Math.min(by, cy))));
        int maxY = Math.min(TEX_H - 1, (int) Math.ceil(Math.max(ay, Math.max(by, cy))));
        if (minX > maxX || minY > maxY) return;

        float area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
        if (Math.abs(area) < 0.01f) return;
        float invArea = 1.0f / area;

        for (int py = minY; py <= maxY; py++) {
            float fy = py + 0.5f;
            int row = py * TEX_W;
            for (int px = minX; px <= maxX; px++) {
                float fx = px + 0.5f;
                float w0 = ((bx - fx) * (cy - fy) - (by - fy) * (cx - fx)) * invArea;
                float w1 = ((cx - fx) * (ay - fy) - (cy - fy) * (ax - fx)) * invArea;
                float w2 = 1.0f - w0 - w1;
                if (w0 >= -0.001f && w1 >= -0.001f && w2 >= -0.001f) {
                    float d = w0 * az + w1 * bz + w2 * cz;
                    int idx = row + px;
                    if (d >= depthBuf[idx]) {
                        depthBuf[idx] = d;
                        colorBuf[idx] = argb;
                    }
                }
            }
        }
    }

    private void drawAxisTriad(float rx, float rz, float ux, float uy, float uz) {
        float ox = 28f, oy = TEX_H - 28f, len = 18f;
        drawLineUnbuffered(ox, oy, ox + rx * len, oy - ux * len, 0xFFF45B69); // +X red
        drawLineUnbuffered(ox, oy, ox, oy - uy * len, 0xFF22D37E);             // +Y green
        drawLineUnbuffered(ox, oy, ox + rz * len, oy - uz * len, 0xFF38BDF8); // +Z blue
    }

    private void drawLineUnbuffered(float x0, float y0, float x1, float y1, int argb) {
        int steps = Math.max(1, Math.round(Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0))));
        for (int i = 0; i <= steps; i++) {
            float t = (float) i / steps;
            int px = Math.round(x0 + (x1 - x0) * t);
            int py = Math.round(y0 + (y1 - y0) * t);
            if (px >= 0 && px < TEX_W && py >= 0 && py < TEX_H) {
                colorBuf[py * TEX_W + px] = argb;
            }
        }
    }

    private static int shade(int argb, float factor) {
        int r = Math.min(255, Math.max(0, Math.round(((argb >>> 16) & 0xFF) * factor)));
        int g = Math.min(255, Math.max(0, Math.round(((argb >>> 8) & 0xFF) * factor)));
        int b = Math.min(255, Math.max(0, Math.round((argb & 0xFF) * factor)));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int mixHighlight(int argb, int rgbAccent, float t) {
        float keep = 1f - t;
        int r = Math.min(255, Math.round(((argb >>> 16) & 0xFF) * keep + ((rgbAccent >>> 16) & 0xFF) * t));
        int g = Math.min(255, Math.round(((argb >>> 8) & 0xFF) * keep + ((rgbAccent >>> 8) & 0xFF) * t));
        int b = Math.min(255, Math.round((argb & 0xFF) * keep + (rgbAccent & 0xFF) * t));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    public void dispose() {
        if (imguiTexId != 0L) {
            LiskovUiHost.unregisterTexture(imguiTexId);
            imguiTexId = 0L;
        }
        if (gpuView != null) { gpuView.close(); gpuView = null; }
        if (gpuTex != null) { gpuTex.close(); gpuTex = null; }
    }
}
