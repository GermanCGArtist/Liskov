package dev.liskov.ui.explorer;

import dev.liskov.core.LiskovFileType;
import dev.liskov.core.LiskovFs;
import dev.liskov.core.LitematicReader;
import dev.liskov.core.SemanticColor;
import dev.liskov.ui.preview.SchematicPreview3D;
import dev.liskov.ui.theme.MubTheme;
import dev.liskov.ui.theme.MubWidgets;
import imgui.ImGui;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImString;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Shared File Picker modal API ({@link #openPicker}) for Vitruvius, Manhattan, and Heavymatica.
 */
public final class FilePickerModal {
    public record Request(String title, Set<String> allowedTypeIds, Path initialDir) {}

    private static Request activeRequest;
    private static Consumer<Path> callback;
    private static final ImString SEARCH = new ImString(128);
    private static Path selected;
    private static final SchematicPreview3D PREVIEW = new SchematicPreview3D();

    private FilePickerModal() {}

    public static void openPicker(Request request, Consumer<Path> onPick) {
        activeRequest = request;
        callback = onPick;
        SEARCH.set("");
        selected = null;
    }

    public static boolean isOpen() {
        return activeRequest != null;
    }

    public static void close() {
        activeRequest = null;
        callback = null;
    }

    public static void draw() {
        if (activeRequest == null) return;
        var vp = ImGui.getMainViewport();
        ImGui.setNextWindowPos(vp.getPosX() + (vp.getSizeX() - 780f) * 0.5f, vp.getPosY() + (vp.getSizeY() - 480f) * 0.5f);
        ImGui.setNextWindowSize(780f, 480f);
        if (ImGui.begin(activeRequest.title() + "###LiskovPicker", ImGuiWindowFlags.NoCollapse)) {
            ImGui.inputTextWithHint("##pick_q", "Filter files...", SEARCH);
            ImGui.separator();

            float availH = ImGui.getContentRegionAvailY() - 36f;
            if (ImGui.beginChild("##pick_list", 440f, availH, true)) {
                LiskovFs fs = LiskovFs.get();
                Path root = activeRequest.initialDir() != null ? activeRequest.initialDir() : fs.schematicsDir();
                List<Path> files = listMatching(root, fs, activeRequest.allowedTypeIds(), SEARCH.get().trim().toLowerCase());
                for (Path p : files) {
                    LiskovFileType ft = fs.fileTypes().detect(p);
                    MubWidgets.fileTypeBadge(ft, false);
                    ImGui.sameLine();
                    String rel = root.relativize(p).toString().replace('\\', '/');
                    boolean isSel = p.equals(selected);
                    if (ImGui.selectable(rel + "##p_" + rel, isSel)) {
                        selected = p;
                        if (ft.previewKind() == LiskovFileType.PreviewKind.SCHEMATIC_3D && p.toString().endsWith(".litematic")) {
                            try {
                                PREVIEW.setSummary(p.toString(), LitematicReader.read(p));
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
            }
            ImGui.endChild();
            ImGui.sameLine();
            if (ImGui.beginChild("##pick_prev", 0f, availH, true)) {
                MubWidgets.sectionHeader("PREVIEW");
                if (selected != null && selected.toString().endsWith(".litematic")) {
                    PREVIEW.drawInspectorPreview(ImGui.getContentRegionAvailX());
                } else if (selected != null) {
                    MubTheme.textColored(MubTheme.DIM, selected.getFileName().toString());
                } else {
                    MubTheme.textColored(MubTheme.GUTTER, "Select a file to preview.");
                }
            }
            ImGui.endChild();

            ImGui.separator();
            if (MubWidgets.termButton("CANCEL", SemanticColor.INK_MUTED)) {
                close();
            }
            ImGui.sameLine();
            if (selected != null && MubWidgets.termButton("SELECT FILE", SemanticColor.CTA)) {
                Path picked = selected;
                Consumer<Path> cb = callback;
                close();
                if (cb != null) cb.accept(picked);
            }
        }
        ImGui.end();
    }

    private static List<Path> listMatching(Path root, LiskovFs fs, Set<String> allowedIds, String query) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(root)) return out;
        try (Stream<Path> walk = Files.walk(root, 6)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                LiskovFileType ft = fs.fileTypes().detect(p);
                if (allowedIds != null && !allowedIds.isEmpty() && !allowedIds.contains(ft.id())) continue;
                String name = p.getFileName().toString().toLowerCase();
                if (!query.isEmpty() && !name.contains(query)) continue;
                out.add(p);
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}
