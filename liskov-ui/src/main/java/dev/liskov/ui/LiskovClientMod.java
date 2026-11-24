package dev.liskov.ui;

import dev.liskov.core.LiskovFs;
import dev.liskov.core.LiskovMigration;
import dev.liskov.core.LiskovNamespace;
import dev.liskov.core.ReloadWatcher;
import dev.liskov.core.SemanticColor;
import dev.liskov.net.LiskovSyncClient;
import dev.liskov.ui.explorer.LiskovExplorer;
import dev.liskov.ui.host.LiskovUiHost;
import dev.liskov.ui.menu.MenuOverviewRegistry;
import dev.liskov.ui.theme.MubTheme;
import dev.liskov.ui.theme.MubWidgets;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Fabric Client entrypoint for Liskov: initializes {@link LiskovFs}, runs non-destructive
 * {@link LiskovMigration#migrateLegacyOnce}, boots {@link LiskovSyncClient}, registers the global
 * {@code F8} Explorer keybind, and wires {@link LiskovExplorer} + {@link MenuOverviewRegistry} into {@link LiskovUiHost}.
 */
public final class LiskovClientMod implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("liskov-client");
    private static final ReloadWatcher WATCHER = new ReloadWatcher();
    private static KeyMapping openExplorerKey;

    public static ReloadWatcher watcher() {
        return WATCHER;
    }

    @Override
    public void onInitializeClient() {
        Path gameDir = FabricLoader.getInstance().getGameDir();
        Path configDir = FabricLoader.getInstance().getConfigDir();
        try {
            LiskovFs fs = LiskovFs.init(gameDir, configDir);
            fs.setClipboardBridge(text -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc != null && mc.keyboardHandler != null) mc.keyboardHandler.setClipboard(text);
            });
            LiskovMigration.Receipt receipt = LiskovMigration.migrateLegacyOnce(fs);
            if (receipt != null && !receipt.copiedFiles.isEmpty()) {
                LOG.info("[Liskov] Non-destructively migrated {} file(s) into {}", receipt.copiedFiles.size(), fs.localRoot());
            }
            for (LiskovNamespace ns : fs.namespaces()) {
                WATCHER.watch(ns.id(), ns.rootDir());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize Liskov filesystem", e);
        }

        MubTheme.loadFromDisk();
        LiskovSyncClient.get().initClientNetworking();
        LiskovExplorer.get().init(WATCHER);

        LiskovUiHost.register(MenuOverviewRegistry.get());
        LiskovUiHost.register(LiskovExplorer.get());

        registerPaletteCommands();

        KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("liskov", "main"));
        openExplorerKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.liskov.explorer", 297 /* GLFW_KEY_F8 */, cat));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (openExplorerKey.consumeClick()) {
                LiskovExplorer.get().toggle();
            }
            WATCHER.pollIfDue(System.currentTimeMillis());
        });
    }

    private static void registerPaletteCommands() {
        MubWidgets.CommandPalette.register(new MubWidgets.CommandPalette.Entry(
                "liskov.toggle", "Liskov: Toggle File Explorer (F8)", "Lk", SemanticColor.FOCUS,
                () -> LiskovExplorer.get().toggle()));
        MubWidgets.CommandPalette.register(new MubWidgets.CommandPalette.Entry(
                "liskov.new_window", "Liskov: Open New Explorer Window (Drag & Drop)", "Lk", SemanticColor.PLAYER,
                () -> {
                    LiskovExplorer.get().setOpen(true);
                    LiskovExplorer.get().spawnSecondWindow();
                }));
        MubWidgets.CommandPalette.register(new MubWidgets.CommandPalette.Entry(
                "liskov.reload", "Liskov: Force ReloadWatcher Scan Now", "Fs", SemanticColor.MR,
                () -> {
                    WATCHER.reloadNow();
                    MubWidgets.ToastHost.push("ReloadWatcher scan complete", SemanticColor.MR);
                }));
        for (MubTheme.Preset p : MubTheme.Preset.values()) {
            MubWidgets.CommandPalette.register(new MubWidgets.CommandPalette.Entry(
                    "liskov.theme." + p.id, "Theme: Switch to " + p.displayName, "Th", SemanticColor.META,
                    () -> MubTheme.setPreset(p)));
        }
    }
}
