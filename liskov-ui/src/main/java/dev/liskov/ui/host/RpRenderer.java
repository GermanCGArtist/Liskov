package dev.liskov.ui.host;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.liskov.ui.theme.MubTheme;
import imgui.ImDrawData;
import imgui.ImFontAtlas;
import imgui.ImGuiIO;
import imgui.ImVec4;
import imgui.flag.ImGuiBackendFlags;
import imgui.type.ImInt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongFunction;

/**
 * Mojang RenderPearl / Blaze3D backend for Dear ImGui ({@code MC 26.3+}), supporting both
 * standard overlay layers (Vitruvius, Liskov) and docked game-viewport mode (Manhattan).
 */
final class RpRenderer {
    private static final int VERTEX_SIZE = 24;

    private static final RenderPipeline PIPELINE = buildImGuiPipeline();
    private static final RenderPipeline GAME_QUAD_PIPELINE = RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("liskov", "pipeline/imgui_game_quad"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("liskov", "core/imgui_game_quad"))
            .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withCull(false)
            .withDepthStencilState(Optional.empty())
            .build();

    private static RenderPipeline buildImGuiPipeline() {
        RenderPipeline g = RenderPipelines.GUI_TEXTURED;
        RenderPipeline.Builder b = RenderPipeline.builder()
                .withLocation("pipeline/liskov_imgui")
                .withVertexShader(g.getShaders().get(com.mojang.renderpearl.api.pipeline.ShaderType.VERTEX))
                .withFragmentShader(g.getShaders().get(com.mojang.renderpearl.api.pipeline.ShaderType.FRAGMENT))
                .withColorTargetState(g.getColorTargetStates().get(0))
                .withDepthStencilState(Optional.ofNullable(g.getDepthStencilState()))
                .withVertexBinding(0, g.getVertexFormatBinding(0))
                .withPrimitiveTopology(g.getPrimitiveTopology())
                .withPolygonMode(g.getPolygonMode())
                .withCull(false);
        for (var layout : g.getBindGroupLayouts()) b.withBindGroupLayout(layout);
        return b.build();
    }

    static volatile LongFunction<GpuTextureView> extraTextureLookup = id -> null;
    private static final Map<Long, GpuTextureView> REGISTERED_TEXTURES = new ConcurrentHashMap<>();
    private static long nextTexId = 100L;

    static long registerTexture(GpuTextureView view) {
        if (view == null) return 0L;
        for (var e : REGISTERED_TEXTURES.entrySet()) {
            if (e.getValue() == view) return e.getKey();
        }
        long id = nextTexId++;
        REGISTERED_TEXTURES.put(id, view);
        return id;
    }

    static void unregisterTexture(long id) {
        REGISTERED_TEXTURES.remove(id);
    }

    private GpuTexture fontTexture;
    private GpuTextureView fontView;
    private GpuTexture gameCopyTexture;
    private GpuTextureView gameCopyView;
    private int gameCopyW, gameCopyH;
    private final Projection projection = new Projection();
    private ProjectionMatrixBuffer projectionBuffer;
    private final ArrayDeque<GpuBuffer[]> retired = new ArrayDeque<>();

    void init(ImGuiIO io) {
        io.setBackendRendererName("liskov-renderpearl");
        io.addBackendFlags(ImGuiBackendFlags.RendererHasVtxOffset);
        projectionBuffer = new ProjectionMatrixBuffer("liskov-uihost");
        createFontTexture(io);
    }

    void createFontTexture(ImGuiIO io) {
        destroyFontTexture();
        ImFontAtlas atlas = io.getFonts();
        ImInt w = new ImInt(), h = new ImInt();
        ByteBuffer px = atlas.getTexDataAsRGBA32(w, h);
        int width = Math.max(1, w.get()), height = Math.max(1, h.get());
        GpuDevice device = RenderSystem.getDevice();
        fontTexture = device.createTexture("liskov font atlas",
                GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA8_UNORM, width, height, 1, 1);
        fontView = device.createTextureView(fontTexture);
        try (NativeImage img = new NativeImage(NativeImage.Format.RGBA, width, height, false)) {
            ByteBuffer src = px.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) img.setPixelABGR(x, y, src.getInt((y * width + x) * 4));
            }
            CommandEncoder upload = device.createCommandEncoder();
            upload.writeToTexture(fontTexture, img);
            upload.submit();
        }
        atlas.setTexID(1L);
        atlas.clearTexData();
    }

    private void destroyFontTexture() {
        if (fontView != null) fontView.close();
        if (fontTexture != null) fontTexture.close();
        fontView = null;
        fontTexture = null;
    }

    void render(Minecraft mc, ImDrawData dd, float[] dockedViewport) {
        int lists = dd.getCmdListsCount();
        int totalVtx = dd.getTotalVtxCount(), totalIdx = dd.getTotalIdxCount();
        if (fontView == null) return;
        RenderTarget target = mc.gameRenderer.mainRenderTarget();
        if (target == null || target.getColorTexture() == null || target.getColorTextureView() == null) return;
        var window = mc.getWindow();
        int fbW = window.getWidth(), fbH = window.getHeight();
        if (fbW <= 0 || fbH <= 0) return;

        float sx = dd.getFramebufferScaleX(), sy = dd.getFramebufferScaleY();
        if (sx <= 0f) sx = 1f;
        if (sy <= 0f) sy = 1f;
        float ox = dd.getDisplayPosX(), oy = dd.getDisplayPosY();

        GpuDevice device = RenderSystem.getDevice();
        CommandEncoder encoder = device.createCommandEncoder();
        boolean drawGameQuad = false;
        if (dockedViewport != null && dockedViewport.length == 4 && dockedViewport[2] > 1f && dockedViewport[3] > 1f) {
            ensureGameCopy(device, target.getColorTexture(), target.width, target.height);
            if (gameCopyTexture != null && gameCopyView != null) {
                encoder.copyTextureToTexture(target.getColorTexture(), gameCopyTexture, 0, 0, 0, 0, 0, target.width, target.height);
                encoder.clearColorTexture(target.getColorTexture(), new Vector4f(
                        MubTheme.r(MubTheme.BG), MubTheme.g(MubTheme.BG), MubTheme.b(MubTheme.BG), 1.0f));
                drawGameQuad = true;
            }
        }

        if (!drawGameQuad && (lists == 0 || totalVtx == 0 || totalIdx == 0)) {
            encoder.submit();
            return;
        }

        int quadVtx = drawGameQuad ? 4 : 0;
        int quadIdx = drawGameQuad ? 6 : 0;
        ByteBuffer vtx = MemoryUtil.memAlloc((totalVtx + quadVtx) * VERTEX_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer idx = MemoryUtil.memAlloc((totalIdx + quadIdx) * 2).order(ByteOrder.LITTLE_ENDIAN);
        int[] baseVertex = new int[lists], firstIndex = new int[lists];

        try {
            if (drawGameQuad) {
                float x0 = dockedViewport[0] * sx, y0 = dockedViewport[1] * sy;
                float x1 = (dockedViewport[0] + dockedViewport[2]) * sx, y1 = (dockedViewport[1] + dockedViewport[3]) * sy;
                putVtx(vtx, x0, y0, 0f, 1f, 0xFFFFFFFF);
                putVtx(vtx, x1, y0, 1f, 1f, 0xFFFFFFFF);
                putVtx(vtx, x1, y1, 1f, 0f, 0xFFFFFFFF);
                putVtx(vtx, x0, y1, 0f, 0f, 0xFFFFFFFF);
                idx.putShort((short) 0).putShort((short) 1).putShort((short) 2);
                idx.putShort((short) 0).putShort((short) 2).putShort((short) 3);
            }
            int vOff = quadVtx, iOff = quadIdx;
            for (int n = 0; n < lists; n++) {
                baseVertex[n] = vOff;
                firstIndex[n] = iOff;
                ByteBuffer v = dd.getCmdListVtxBufferData(n).order(ByteOrder.LITTLE_ENDIAN);
                int vc = dd.getCmdListVtxBufferSize(n);
                for (int i = 0; i < vc; i++) {
                    int p = i * 20;
                    vtx.putFloat((v.getFloat(p) - ox) * sx).putFloat((v.getFloat(p + 4) - oy) * sy).putFloat(0f);
                    vtx.putFloat(v.getFloat(p + 8)).putFloat(v.getFloat(p + 12));
                    vtx.putInt(v.getInt(p + 16));
                }
                ByteBuffer ib = dd.getCmdListIdxBufferData(n).order(ByteOrder.LITTLE_ENDIAN);
                int ic = dd.getCmdListIdxBufferSize(n);
                for (int i = 0; i < ic; i++) idx.putShort(ib.getShort(i * 2));
                vOff += vc;
                iOff += ic;
            }
            vtx.flip();
            idx.flip();

            GpuBuffer vb = device.createBuffer(() -> "liskov imgui vtx", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, vtx);
            GpuBuffer ib = device.createBuffer(() -> "liskov imgui idx", GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST, idx);
            retired.addLast(new GpuBuffer[]{vb, ib});
            while (retired.size() > 3) for (GpuBuffer b : retired.removeFirst()) b.close();

            drawPass(encoder, target, dd, lists, vb, ib, baseVertex, firstIndex, fbW, fbH, sx, sy, ox, oy, drawGameQuad);
        } finally {
            MemoryUtil.memFree(vtx);
            MemoryUtil.memFree(idx);
        }
    }

    private void drawPass(CommandEncoder encoder, RenderTarget target, ImDrawData dd, int lists,
                          GpuBuffer vb, GpuBuffer ib, int[] baseVertex, int[] firstIndex,
                          int fbW, int fbH, float sx, float sy, float ox, float oy, boolean drawGameQuad) {
        GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        long boundTex = -1L;

        RenderSystem.backupProjectionMatrix();
        try {
            projection.setupOrtho(1000f, 11000f, fbW, fbH, true);
            RenderSystem.setProjectionMatrix(projectionBuffer.getBuffer(projection), ProjectionType.ORTHOGRAPHIC);
            var transform = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f().setTranslation(0f, 0f, -11000f));
            var pipeline = RenderSystem.getCompiledPipeline(PIPELINE);

            try (RenderPass pass = encoder.createRenderPass(() -> "liskov imgui", target.getColorTextureView(), Optional.empty(),
                    target.hasDepth() ? target.getDepthTextureView() : null, java.util.OptionalDouble.empty())) {
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", transform);
                pass.setVertexBuffer(0, vb.slice());
                pass.setIndexBuffer(ib, IndexType.SHORT);

                if (drawGameQuad) {
                    var gamePipe = RenderSystem.getCompiledPipelineNullable(GAME_QUAD_PIPELINE);
                    if (gamePipe == null) gamePipe = RenderSystem.getCompiledPipeline(GAME_QUAD_PIPELINE);
                    pass.setPipeline(gamePipe != null ? gamePipe : pipeline);
                    pass.disableScissor();
                    pass.setUniform("Sampler0", gameCopyView, sampler);
                    pass.drawIndexed(6, 1, 0, 0, 0);
                }

                pass.setPipeline(pipeline);
                pass.setUniform("Sampler0", fontView, sampler);
                boundTex = 1L;
                ImVec4 clip = new ImVec4();
                for (int n = 0; n < lists; n++) {
                    int cmds = dd.getCmdListCmdBufferSize(n);
                    for (int c = 0; c < cmds; c++) {
                        dd.getCmdListCmdBufferClipRect(clip, n, c);
                        int x0 = Math.max(0, (int) ((clip.x - ox) * sx)), y0 = Math.max(0, (int) ((clip.y - oy) * sy));
                        int x1 = Math.min(fbW, (int) Math.ceil((clip.z - ox) * sx)), y1 = Math.min(fbH, (int) Math.ceil((clip.w - oy) * sy));
                        if (x1 <= x0 || y1 <= y0) continue;
                        int count = dd.getCmdListCmdBufferElemCount(n, c);
                        if (count <= 0) continue;
                        long tex = dd.getCmdListCmdBufferTextureId(n, c);
                        if (tex != boundTex) {
                            GpuTextureView extra = tex == 1L ? null : resolveExtraTexture(tex);
                            if (tex == 1L || extra == null) pass.setUniform("Sampler0", fontView, sampler);
                            else pass.setUniform("Sampler0", extra, nearest);
                            boundTex = tex;
                        }
                        pass.enableScissor(x0, fbH - y1, x1 - x0, y1 - y0);
                        pass.drawIndexed(count, 1, firstIndex[n] + dd.getCmdListCmdBufferIdxOffset(n, c),
                                baseVertex[n] + dd.getCmdListCmdBufferVtxOffset(n, c), 0);
                    }
                }
                pass.disableScissor();
            }
        } finally {
            RenderSystem.restoreProjectionMatrix();
        }
        encoder.submit();
    }

    private static GpuTextureView resolveExtraTexture(long texId) {
        GpuTextureView registered = REGISTERED_TEXTURES.get(texId);
        if (registered != null) return registered;
        return extraTextureLookup.apply(texId);
    }

    private void ensureGameCopy(GpuDevice dev, GpuTexture mainColor, int w, int h) {
        if (gameCopyTexture != null && !gameCopyTexture.isClosed() && gameCopyW == w && gameCopyH == h) return;
        if (gameCopyView != null) { gameCopyView.close(); gameCopyView = null; }
        if (gameCopyTexture != null) { gameCopyTexture.close(); gameCopyTexture = null; }
        gameCopyTexture = dev.createTexture("Liskov Game Copy",
                GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                mainColor.getFormat(), w, h, 1, 1);
        gameCopyView = dev.createTextureView(gameCopyTexture);
        gameCopyW = w;
        gameCopyH = h;
    }

    private static void putVtx(ByteBuffer dst, float x, float y, float u, float v, int abgr) {
        dst.putFloat(x).putFloat(y).putFloat(0f).putFloat(u).putFloat(v).putInt(abgr);
    }
}
