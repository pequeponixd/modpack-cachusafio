package cl.cachusafio.skins.server;

import cl.cachusafio.skins.net.SkinData;
import cl.cachusafio.skins.net.SkinNetwork;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import org.slf4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Guarda en memoria la skin de cada jugador conectado y la reparte. */
public final class ServerSkins {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Map<UUID, SkinData> SKINS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_UPLOAD = new ConcurrentHashMap<>();
    private static final long MIN_INTERVAL_MS = 3_000;

    private ServerSkins() {}

    public static void receive(ServerPlayer sender, SkinData data) {
        UUID id = sender.getUUID();
        long now = System.currentTimeMillis();
        Long last = LAST_UPLOAD.get(id);
        if (last != null && now - last < MIN_INTERVAL_MS) return;
        LAST_UPLOAD.put(id, now);

        SKINS.put(id, data);
        LOG.info("skin de {} recibida ({} bytes, modelo {}, capa {})", sender.getGameProfile().getName(), data.png().length,
                data.slim() ? "slim" : "classic", data.hasCape() ? data.cape().substring(0, 8) : "-");
        broadcast(new SkinNetwork.Sync(id, data));
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        SKINS.forEach((id, data) -> SkinNetwork.sendTo(player, new SkinNetwork.Sync(id, data)));
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        LAST_UPLOAD.remove(id);
        if (SKINS.remove(id) != null) broadcast(new SkinNetwork.Sync(id, null));
    }

    @SubscribeEvent
    public static void onStopped(ServerStoppedEvent event) {
        SKINS.clear();
        LAST_UPLOAD.clear();
    }

    private static void broadcast(SkinNetwork.Sync msg) {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) SkinNetwork.sendTo(p, msg);
    }
}
