package cl.cachusafio.skins.mixin;

import cl.cachusafio.skins.client.ClientSkins;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Toda la ropa del jugador (mundo, lista de TAB, inventario) se lee desde PlayerInfo. */
@Mixin(PlayerInfo.class)
public abstract class PlayerInfoMixin {
    @Shadow
    public abstract GameProfile getProfile();

    @Inject(method = "getSkinLocation", at = @At("HEAD"), cancellable = true)
    private void cachusafio$skin(CallbackInfoReturnable<ResourceLocation> cir) {
        ClientSkins.Applied a = ClientSkins.get(getProfile().getId());
        if (a != null && a.skin() != null) cir.setReturnValue(a.skin());
    }

    @Inject(method = "getModelName", at = @At("HEAD"), cancellable = true)
    private void cachusafio$model(CallbackInfoReturnable<String> cir) {
        ClientSkins.Applied a = ClientSkins.get(getProfile().getId());
        if (a != null && a.skin() != null) cir.setReturnValue(a.slim() ? "slim" : "default");
    }

    @Inject(method = "getCapeLocation", at = @At("HEAD"), cancellable = true)
    private void cachusafio$cape(CallbackInfoReturnable<ResourceLocation> cir) {
        ClientSkins.Applied a = ClientSkins.get(getProfile().getId());
        if (a != null && a.cape() != null) cir.setReturnValue(a.cape());
    }
}
