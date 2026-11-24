package dev.liskov.ui.preview;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.liskov.core.LitematicReader;
import dev.liskov.ui.host.LiskovUiHost;
import dev.liskov.ui.theme.MubTheme;
import imgui.ImGui;
import imgui.flag.ImGuiMouseButton;
import imgui.type.ImFloat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Interactive RenderPearl-backed 3D schematic preview canvas for {@code .litematic}, {@code .vproj},
 * {@code .lasm}, and {@code .heavymatic} (including read-only previews of older {@code .liskov-vcs/} commits).
 *
 * <p>Uses a fast depth-buffered software voxel rasterizer uploaded to a {@link GpuTexture} registered with
 * {@link LiskovUiHost#registerTexture}, guaranteeing identical output on Vulkan &amp; OpenGL backends and on the Title Screen.
 */
public final class SchematicPreview3D {
    private static final int TEX_W = 320;
    private static final int TEX_H = 220;

    private String loadedKey = "";
    private LitematicReader.Summary summary;
    private float yaw = 0.65f;
    private float pitch = 0.45f;
    private float zoom = 1.0f;
    private boolean texturedShade = true;
    private final ImFloat sliceY = new ImFloat(1.0f);

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
        this.dirty = true;
    }

    public LitematicReader.Summary summary() {
        return summary;
    }

    public void resetCamera() {
        yaw = 0.65f;
        pitch = 0.45f;
        zoom = 1.0f;
        sliceY.set(1.0f);
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

        if (ImGui.button(texturedShade ? "TEX" : "VOX", 46f, 22f)) {
            texturedShade = !texturedShade;
            dirty = true;
        }
        ImGui.sameLine();
        if (ImGui.button("RESET", 56f, 22f)) {
            resetCamera();
        }
        ImGui.sameLine();
        MubTheme.textColored(MubTheme.GUTTER, summary.sizeX + "x" + summary.sizeY + "x" + summary.sizeZ);

        float aspect = (float) TEX_H / TEX_W;
        float dispW = Math.max(120f, width);
        float dispH = Math.max(100f, dispW * aspect);
        if (imguiTexId != 0L) {
            ImGui.image(imguiTexId, dispW, dispH);
            if (ImGui.isItemHovered()) {
                if (ImGui.isMouseDragging(ImGuiMouseButton.Left)) {
                    var delta = ImGui.getIO().getMouseDelta();
                    yaw += delta.x * 0.015f;
                    pitch = Math.max(-1.0f, Math.min(1.25f, pitch + delta.y * 0.012f));
                    dirty = true;
                }
                float wheel = ImGui.getIO().getMouseWheel();
                if (Math.abs(wheel) > 0.001f) {
                    zoom = Math.max(0.45f, Math.min(2.5f, zoom + wheel * 0.1f));
                    dirty = true;
                }
            }
        }

        ImGui.setNextItemWidth(dispW);
        if (ImGui.sliderFloat("##yslice", sliceY.getData(), 0.0f, 1.0f, "Y-SLICE %.0f%%")) {
            dirty = true;
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
        for (int y = 0; y < TEX_H; y += 16) {
            for (int x = 0; x < TEX_W; x += 16) {
                colorBuf[y * TEX_W + x] = dot;
            }
        }
        if (summary == null || summary.voxels.isEmpty()) return;

        int maxY = Math.max(1, summary.gridY - 1);
        int cutY = Math.round(sliceY.get() * maxY);
        float maxSpan = Math.max(summary.gridX, Math.max(summary.gridY, summary.gridZ));
        float scale = (Math.min(TEX_W, TEX_H) * 0.62f / Math.max(1f, maxSpan)) * zoom;

        float cy = (float) Math.cos(yaw), sy = (float) Math.sin(yaw);
        float cp = (float) Math.cos(pitch), sp = (float) Math.sin(pitch);
        float ox = summary.gridX * 0.5f;
        float oy = summary.gridY * 0.5f;
        float oz = summary.gridZ * 0.5f;

        record ProjVox(int sx, int sy, float depth, int argb, boolean sliceTop) {}
        List<ProjVox> list = new ArrayList<>(summary.voxels.size());
        for (LitematicReader.Voxel v : summary.voxels) {
            if (v.y() > cutY) continue;
            float vx = v.x() - ox;
            float vy = v.y() - oy;
            float vz = v.z() - oz;
            float rx = vx * cy - vz * sy;
            float rz = vx * sy + vz * cy;
            float ry = vy * cp - rz * sp;
            float rz2 = vy * sp + rz * cp;
            int sx = Math.round(TEX_W * 0.5f + rx * scale);
            int syScreen = Math.round(TEX_H * 0.52f - ry * scale);
            list.add(new ProjVox(sx, syScreen, rz2, v.argbColor(), v.y() == cutY && cutY < maxY));
        }
        list.sort(Comparator.comparingDouble(ProjVox::depth));

        int rad = Math.max(1, Math.min(7, Math.round(scale * 0.58f)));
        int topTint = 0xFF000000 | MubTheme.ACCENT;
        for (ProjVox p : list) {
            int col = p.sliceTop ? topTint : shade(p.argb, texturedShade ? 1.0f : 0.88f);
            int x0 = Math.max(0, p.sx - rad), x1 = Math.min(TEX_W - 1, p.sx + rad);
            int y0 = Math.max(0, p.sy - rad), y1 = Math.min(TEX_H - 1, p.sy + rad);
            for (int py = y0; py <= y1; py++) {
                int row = py * TEX_W;
                boolean edgeY = (py == y0 || py == y1) && texturedShade && rad >= 2;
                float faceShade = py < p.sy ? 1.05f : 0.82f;
                int shaded = p.sliceTop ? col : (edgeY ? shade(col, 0.72f) : shade(col, faceShade));
                for (int px = x0; px <= x1; px++) {
                    if (p.depth >= depthBuf[row + px]) {
                        depthBuf[row + px] = p.depth;
                        colorBuf[row + px] = shaded;
                    }
                }
            }
        }
    }

    private static int shade(int argb, float factor) {
        int r = Math.min(255, Math.max(0, Math.round(((argb >>> 16) & 0xFF) * factor)));
        int g = Math.min(255, Math.max(0, Math.round(((argb >>> 8) & 0xFF) * factor)));
        int b = Math.min(255, Math.max(0, Math.round((argb & 0xFF) * factor)));
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
