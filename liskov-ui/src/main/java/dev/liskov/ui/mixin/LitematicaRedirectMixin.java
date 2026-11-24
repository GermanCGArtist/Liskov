package dev.liskov.ui.mixin;

import dev.liskov.core.LiskovFs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.file.Path;

/**
 * Redirects Litematica's schematic base directory into {@code <gameDir>/liskov/local/schematics/}
 * when Litematica is installed (guarded by {@link LiskovMixinPlugin}).
 */
@Mixin(targets = "fi.dy.masa.litematica.data.DataManager", remap = false)
public abstract class LitematicaRedirectMixin {
    @Inject(method = "getDefaultBaseSchematicDirectory", at = @At("HEAD"), cancellable = true, require = 0)
    private static void liskov$redirectDefaultBaseDir(CallbackInfoReturnable<Path> cir) {
        if (LiskovFs.isInitialized()) {
            cir.setReturnValue(LiskovFs.get().schematicsDir());
        }
    }

    @Inject(method = "getSchematicsBaseDirectory", at = @At("HEAD"), cancellable = true, require = 0)
    private static void liskov$redirectBaseDir(CallbackInfoReturnable<Path> cir) {
        if (LiskovFs.isInitialized()) {
            cir.setReturnValue(LiskovFs.get().schematicsDir());
        }
    }
}
