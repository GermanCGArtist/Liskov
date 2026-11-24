package dev.liskov.ui.host;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostCoreDockedViewportTest {

    @AfterEach
    void cleanup() throws Exception {
        Field layersField = HostCore.class.getDeclaredField("LAYERS");
        layersField.setAccessible(true);
        ((List<?>) layersField.get(null)).clear();

        Field vpField = HostCore.class.getDeclaredField("lastDockedViewport");
        vpField.setAccessible(true);
        vpField.set(null, null);
    }

    @Test
    void dockedViewportClearsWhenAllLayersBecomeInvisible() throws Exception {
        AtomicBoolean visible = new AtomicBoolean(true);
        float[] vp = new float[] { 200f, 50f, 800f, 500f };

        HostCore.register(new UiLayer() {
            @Override public String id() { return "test-docked"; }
            @Override public int order() { return 0; }
            @Override public boolean visible() { return visible.get(); }
            @Override public void draw() {}
            @Override public float[] dockedGameViewport() { return visible.get() ? vp : null; }
        });

        Field vpField = HostCore.class.getDeclaredField("lastDockedViewport");
        vpField.setAccessible(true);
        vpField.set(null, vp);

        assertTrue(HostCore.isDockedViewportActive());

        // Leaving the UI hides the layer; docked viewport must deactivate immediately
        visible.set(false);
        assertFalse(HostCore.isDockedViewportActive());
        assertEquals(640.0, HostCore.remapMouseX(640.0));
        assertEquals(360.0, HostCore.remapMouseY(360.0));
    }
}
