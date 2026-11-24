package dev.liskov.ui.mixin;

import dev.liskov.ui.host.HostCore;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiRenderer.class)
public abstract class GuiRendererMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void liskov$renderImGui(CallbackInfo ci) {
        HostCore.renderFrame();
    }
}
