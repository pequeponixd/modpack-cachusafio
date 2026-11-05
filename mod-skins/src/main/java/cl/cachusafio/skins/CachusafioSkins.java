package cl.cachusafio.skins;

import cl.cachusafio.skins.client.ClientSkins;
import cl.cachusafio.skins.net.SkinNetwork;
import cl.cachusafio.skins.server.ServerSkins;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;

/**
 * El launcher guarda la skin y la capa elegidas en {@code <juego>/cachusafio/}.
 * Al entrar a un mundo el cliente las envía al servidor, que las reparte a
 * todos los conectados; cada cliente las pinta sobre el jugador correspondiente.
 */
@Mod(CachusafioSkins.MOD_ID)
public class CachusafioSkins {
    public static final String MOD_ID = "cachusafio_skins";

    public CachusafioSkins() {
        SkinNetwork.register();
        MinecraftForge.EVENT_BUS.register(ServerSkins.class);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> MinecraftForge.EVENT_BUS.register(ClientSkins.Events.class));
    }
}
