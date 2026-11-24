package dev.liskov.ui.mixin;

import dev.liskov.ui.host.HostCore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Routes mouse input to {@link HostCore}, prevents gameplay cursor grab/turn while a layer blocks
 * grab, and remaps screen coordinates when rendering inside a docked game viewport.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Shadow @Final private Minecraft minecraft;
    @Shadow private boolean mouseGrabbed;
    @Shadow private double xpos;
    @Shadow private double ypos;
    @Shadow private double accumulatedDX;
    @Shadow private double accumulatedDY;

    @Inject(method = "grabMouse()V", at = @At("HEAD"), cancellable = true)
    private void liskov$onGrabMouse(CallbackInfo ci) {
        if (HostCore.blocksMouseGrab()) {
            this.mouseGrabbed = false;
            ci.cancel();
        }
    }

    @Inject(method = "turnPlayer(D)V", at = @At("HEAD"), cancellable = true)
    private void liskov$onTurnPlayer(double movementTime, CallbackInfo ci) {
        if (HostCore.blocksMouseGrab()) {
            this.accumulatedDX = 0.0;
            this.accumulatedDY = 0.0;
            ci.cancel();
        }
    }

    @Inject(method = "onMove", at = @At("HEAD"), cancellable = true)
    private void liskov$onMove(long window, double xpos, double ypos, double dx, double dy, CallbackInfo ci) {
        if (HostCore.onMouseMove(window, xpos, ypos, dx, dy)) {
            ci.cancel();
            return;
        }
        if (!this.mouseGrabbed && HostCore.isDockedViewportActive()) {
            this.accumulatedDX = 0.0;
            this.accumulatedDY = 0.0;
            if (this.minecraft.gui.screen() != null) {
                this.xpos = HostCore.remapMouseX(xpos);
                this.ypos = HostCore.remapMouseY(ypos);
            }
            ci.cancel();
        }
    }

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void liskov$onButton(long window, MouseButtonInfo buttonInfo, int action, CallbackInfo ci) {
        if (HostCore.onMouseButton(window, buttonInfo.button(), action, buttonInfo.modifiers())) {
            ci.cancel();
            return;
        }
        if (!this.mouseGrabbed && HostCore.isDockedViewportActive()) {
            if (this.minecraft.gui.screen() == null) {
                ci.cancel();
                return;
            }
            this.xpos = HostCore.remapMouseX(HostCore.lastMouseX());
            this.ypos = HostCore.remapMouseY(HostCore.lastMouseY());
        }
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void liskov$onScroll(long window, double xoffset, double yoffset, CallbackInfo ci) {
        if (HostCore.onMouseScroll(window, xoffset, yoffset)) ci.cancel();
    }
}
