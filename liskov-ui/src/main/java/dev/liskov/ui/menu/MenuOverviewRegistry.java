package dev.liskov.ui.menu;

import dev.liskov.core.SemanticColor;
import dev.liskov.ui.host.LiskovUiHost;
import dev.liskov.ui.host.UiLayer;
import dev.liskov.ui.theme.MubTheme;
import dev.liskov.ui.theme.MubWidgets;
import imgui.ImGui;
import imgui.flag.ImGuiWindowFlags;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Registry allowing Liskov, Manhattan, and Vitruvius to contribute toggleable ModsUndBots
 * overview cards onto the Minecraft Title Screen and ESC Pause Menu.
 */
public final class MenuOverviewRegistry implements UiLayer {
    public enum Surface {
        TITLE_SCREEN,
        PAUSE_MENU
    }

    public interface CardProvider {
        String id();

        String title();

        SemanticColor accentRole();

        int order();

        boolean supports(Surface surface);

        void drawCardBody(Surface surface);
    }

    private static final MenuOverviewRegistry INSTANCE = new MenuOverviewRegistry();
    private final List<CardProvider> providers = new ArrayList<>();

    private MenuOverviewRegistry() {}

    public static MenuOverviewRegistry get() { return INSTANCE; }

    public synchronized void register(CardProvider provider) {
        if (provider == null) return;
        providers.removeIf(p -> p.id().equals(provider.id()));
        providers.add(provider);
        providers.sort(Comparator.comparingInt(CardProvider::order));
    }

    private Surface activeSurface() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gui == null) return null;
        Screen s = mc.gui.screen();
        if (s instanceof TitleScreen) return Surface.TITLE_SCREEN;
        if (s instanceof PauseScreen) return Surface.PAUSE_MENU;
        return null;
    }

    @Override
    public String id() { return "liskov.menu_overview"; }

    @Override
    public int order() { return 50; }

    @Override
    public boolean visible() {
        return activeSurface() != null && !providers.isEmpty();
    }

    @Override
    public void draw() {
        Surface surface = activeSurface();
        if (surface == null) return;
        List<CardProvider> matching;
        synchronized (this) {
            matching = providers.stream().filter(p -> p.supports(surface)).toList();
        }
        if (matching.isEmpty()) return;

        var vp = ImGui.getMainViewport();
        float cardW = 300f * LiskovUiHost.scale();
        ImGui.setNextWindowPos(vp.getPosX() + vp.getSizeX() - cardW - 18f, vp.getPosY() + 18f);
        ImGui.setNextWindowSize(cardW, 0f);
        int flags = ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoMove
                | ImGuiWindowFlags.AlwaysAutoResize | ImGuiWindowFlags.NoSavedSettings;
        if (ImGui.begin("##LiskovMenuOverview", flags)) {
            boolean open = surface == Surface.TITLE_SCREEN
                    ? MubTheme.config().titleOverviewOpen
                    : MubTheme.config().pauseOverviewOpen;

            for (int i = 0; i < matching.size(); i++) {
                CardProvider cp = matching.get(i);
                if (i > 0) ImGui.separator();
                if (i == 0) {
                    if (MubWidgets.termToggle("ov_main", cp.title(), open, cp.accentRole())) {
                        open = !open;
                        if (surface == Surface.TITLE_SCREEN) MubTheme.config().titleOverviewOpen = open;
                        else MubTheme.config().pauseOverviewOpen = open;
                        MubTheme.saveToDisk();
                    }
                } else {
                    MubWidgets.sectionHeader(cp.title());
                }
                if (open) {
                    cp.drawCardBody(surface);
                }
            }
        }
        ImGui.end();
    }
}
