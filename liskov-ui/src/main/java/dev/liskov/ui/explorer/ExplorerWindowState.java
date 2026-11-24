package dev.liskov.ui.explorer;

import dev.liskov.core.LiskovDrives;
import imgui.type.ImString;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Independent state for one Liskov Explorer window, enabling multiple open windows and cross-window drag-and-drop.
 */
public final class ExplorerWindowState {
    public enum ViewMode { COLUMNS, LIST, GRID }

    public final int windowId;
    public String driveId = LiskovDrives.LOCAL_DRIVE_ID;
    public String namespaceId = "schematics";
    public boolean viewingTrash = false;
    public final List<Path> columnDirs = new ArrayList<>();
    public Path selectedPath;
    public final Set<Path> multiSelection = new LinkedHashSet<>();
    public ViewMode viewMode = ViewMode.COLUMNS;
    public final ImString search = new ImString(128);
    public String filterFileTypeId = null;

    public ExplorerWindowState(int windowId) {
        this.windowId = windowId;
    }
}
