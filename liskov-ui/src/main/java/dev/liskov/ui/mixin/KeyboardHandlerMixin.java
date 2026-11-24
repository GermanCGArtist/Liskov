package dev.liskov.ui.mixin;

import dev.liskov.ui.host.HostCore;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void liskov$onKeyPress(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (HostCore.onKey(window, event.key(), action, event.modifiers())) ci.cancel();
    }

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void liskov$onCharTyped(long window, CharacterEvent event, CallbackInfo ci) {
        if (HostCore.onChar(window, event.codepoint())) ci.cancel();
    }
}
