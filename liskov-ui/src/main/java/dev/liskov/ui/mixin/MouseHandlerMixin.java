package dev.liskov.ui.mixin;

import dev.liskov.ui.host.HostCore;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Inject(method = "onMove", at = @At("HEAD"), cancellable = true)
    private void liskov$onMove(long window, double xpos, double ypos, double dx, double dy, CallbackInfo ci) {
        if (HostCore.onMouseMove(window, xpos, ypos, dx, dy)) ci.cancel();
    }

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void liskov$onButton(long window, MouseButtonInfo buttonInfo, int action, CallbackInfo ci) {
        if (HostCore.onMouseButton(window, buttonInfo.button(), action, buttonInfo.modifiers())) ci.cancel();
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void liskov$onScroll(long window, double xoffset, double yoffset, CallbackInfo ci) {
        if (HostCore.onMouseScroll(window, xoffset, yoffset)) ci.cancel();
    }
}
