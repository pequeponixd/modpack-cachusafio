package cl.cachusafio.skins.server;

import cl.cachusafio.skins.SkinsConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Protege los nombres en un servidor sin premium. El launcher manda una clave derivada del PIN del jugador
 * (nunca el PIN). La primera vez que un nombre entra, su clave queda registrada; después, quien entre con ese
 * nombre y otra clave es expulsado. Mientras se verifica (normalmente menos de un segundo) el jugador no puede
 * moverse, romper, poner, chatear ni recibir daño.
 *
 * Solo en servidores dedicados (en un mundo de un jugador no hace falta). PIN olvidado:
 * {@code /cachusafio pin reset <nombre>} (operadores).
 */
public final class PinAuth {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Pattern TOKEN = Pattern.compile("[0-9a-f]{64}");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_FAILS = 5;
    private static final long LOCK_MS = 10 * 60_000L;

    private record Pending(long deadline, Vec3 pos, float yRot, float xRot) {}
    private record Stored(String salt, String hash) {}
    private static final class Fails { int count; long lockedUntil; }

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();
    private static final Set<UUID> VERIFIED = ConcurrentHashMap.newKeySet();
    private static final Map<String, Fails> FAILS = new ConcurrentHashMap<>();
    private static Map<String, Stored> store;
    private static boolean storeBroken;

    private PinAuth() {}

    private static Path file() {
        return FMLPaths.GAMEDIR.get().resolve("cachusafio").resolve("pins.json");
    }

    private static boolean enabled(@Nullable MinecraftServer server) {
        return server != null && server.isDedicatedServer() && SkinsConfig.PIN_ENABLED.get();
    }

    /** ¿Este jugador ya probó que es quien dice ser? (o el PIN está desactivado) */
    public static boolean isVerified(Player player) {
        return VERIFIED.contains(player.getUUID()) || !enabled(ServerLifecycleHooks.getCurrentServer());
    }

    private static boolean isPending(@Nullable Object entity) {
        return entity instanceof ServerPlayer p && PENDING.containsKey(p.getUUID());
    }

    private static String key(ServerPlayer p) {
        return p.getGameProfile().getName().toLowerCase(Locale.ROOT);
    }

    /* ------------------------------------------------------------------ almacenamiento */

    /** null si pins.json está dañado: en ese caso nadie entra (mejor eso que dejar los nombres sin proteger). */
    @Nullable
    private static synchronized Map<String, Stored> store() {
        if (store != null || storeBroken) return store;
        try {
            Map<String, Stored> loaded = new HashMap<>();
            if (Files.isRegularFile(file())) {
                Map<String, Stored> m = GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8),
                        new TypeToken<Map<String, Stored>>() {}.getType());
                if (m != null) loaded.putAll(m);
            }
            store = loaded;
        } catch (Exception e) {
            storeBroken = true;
            LOG.error("No se pudo leer {}: nadie podrá entrar hasta que se arregle o se borre (y reinicies)", file(), e);
        }
        return store;
    }

    private static synchronized void save() {
        try {
            Files.createDirectories(file().getParent());
            Path tmp = file().resolveSibling("pins.json.tmp");
            Files.writeString(tmp, GSON.toJson(store), StandardCharsets.UTF_8);
            Files.move(tmp, file(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            LOG.error("No se pudo guardar {}", file(), e);
        }
    }

    private static byte[] derive(String token, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(token.toCharArray(), salt, 20_000, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Stored hashNew(String token) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        Base64.Encoder b64 = Base64.getEncoder();
        return new Stored(b64.encodeToString(salt), b64.encodeToString(derive(token, salt)));
    }

    private static boolean matches(Stored s, String token) {
        Base64.Decoder b64 = Base64.getDecoder();
        return MessageDigest.isEqual(b64.decode(s.hash()), derive(token, b64.decode(s.salt())));
    }

    /* ------------------------------------------------------------------ verificación */

    /** Llega el paquete del cliente con su clave (cadena vacía si no tiene). Hilo principal del servidor. */
    public static void receive(ServerPlayer player, String token) {
        UUID id = player.getUUID();
        if (!PENDING.containsKey(id)) return; // ya verificado, o PIN desactivado
        String name = key(player);

        Fails f = FAILS.get(name);
        long now = System.currentTimeMillis();
        if (f != null && f.lockedUntil > now) {
            long min = Math.max(1, (f.lockedUntil - now + 59_999) / 60_000);
            kick(player, "Demasiados intentos con un PIN incorrecto. Prueba de nuevo en " + min + " min.");
            return;
        }
        if (!TOKEN.matcher(token).matches()) {
            kick(player, "Este servidor pide un PIN. Entra con el launcher de Cachusafio.");
            return;
        }

        Map<String, Stored> pins = store();
        if (pins == null) {
            kick(player, "El servidor tiene un problema con los PIN guardados. Avisa a un admin.");
            return;
        }
        Stored s = pins.get(name);
        if (s == null) {
            synchronized (PinAuth.class) { pins.put(name, hashNew(token)); }
            save();
            LOG.info("PIN registrado para {}", player.getGameProfile().getName());
            accept(player);
            player.sendSystemMessage(Component.literal("§aTu PIN quedó registrado: desde ahora nadie más puede entrar con tu nombre."));
        } else if (matches(s, token)) {
            FAILS.remove(name);
            accept(player);
        } else {
            Fails fails = FAILS.computeIfAbsent(name, k -> new Fails());
            if (++fails.count >= MAX_FAILS) {
                fails.count = 0;
                fails.lockedUntil = now + LOCK_MS;
            }
            LOG.warn("PIN incorrecto para {} ({})", player.getGameProfile().getName(), player.getIpAddress());
            kick(player, "PIN incorrecto. Si lo olvidaste, pídele a un admin que lo reinicie.");
        }
    }

    private static void accept(ServerPlayer player) {
        PENDING.remove(player.getUUID());
        VERIFIED.add(player.getUUID());
        ServerSkins.onVerified(player);
    }

    private static void kick(ServerPlayer player, String why) {
        PENDING.remove(player.getUUID());
        player.connection.disconnect(Component.literal(why));
    }

    /* ------------------------------------------------------------------ eventos */

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        if (!enabled(p.getServer())) return;
        long timeout = SkinsConfig.PIN_TIMEOUT_SECONDS.get() * 1000L;
        PENDING.put(p.getUUID(), new Pending(System.currentTimeMillis() + timeout, p.position(), p.getYRot(), p.getXRot()));
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        PENDING.remove(event.getEntity().getUUID());
        VERIFIED.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || PENDING.isEmpty()) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Pending> e : PENDING.entrySet()) {
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p == null) continue;
            Pending w = e.getValue();
            if (now > w.deadline()) kick(p, "Este servidor pide un PIN. Entra con el launcher de Cachusafio.");
            else if (p.position().distanceToSqr(w.pos()) > 0.01) p.connection.teleport(w.pos().x, w.pos().y, w.pos().z, w.yRot(), w.xRot());
        }
    }

    private static void block(Event e, @Nullable Object who) {
        if (isPending(who) && e.isCancelable()) e.setCanceled(true);
    }

    @SubscribeEvent public static void onBreak(BlockEvent.BreakEvent e) { block(e, e.getPlayer()); }
    @SubscribeEvent public static void onPlace(BlockEvent.EntityPlaceEvent e) { block(e, e.getEntity()); }
    @SubscribeEvent public static void onUseBlock(PlayerInteractEvent.RightClickBlock e) { block(e, e.getEntity()); }
    @SubscribeEvent public static void onUseItem(PlayerInteractEvent.RightClickItem e) { block(e, e.getEntity()); }
    @SubscribeEvent public static void onHitBlock(PlayerInteractEvent.LeftClickBlock e) { block(e, e.getEntity()); }
    @SubscribeEvent public static void onUseEntity(PlayerInteractEvent.EntityInteract e) { block(e, e.getEntity()); }
    @SubscribeEvent public static void onUseEntityAt(PlayerInteractEvent.EntityInteractSpecific e) { block(e, e.getEntity()); }
    @SubscribeEvent public static void onAttack(AttackEntityEvent e) { block(e, e.getEntity()); }
    @SubscribeEvent public static void onHurt(LivingAttackEvent e) { block(e, e.getEntity()); }
    @SubscribeEvent public static void onChat(ServerChatEvent e) { block(e, e.getPlayer()); }
    @SubscribeEvent public static void onCommand(CommandEvent e) { block(e, e.getParseResults().getContext().getSource().getEntity()); }

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("cachusafio")
                .requires(src -> src.hasPermission(3))
                .then(Commands.literal("pin").then(Commands.literal("reset")
                        .then(Commands.argument("nombre", StringArgumentType.word()).executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "nombre").toLowerCase(Locale.ROOT);
                            Map<String, Stored> pins = store();
                            if (pins == null) {
                                ctx.getSource().sendFailure(Component.literal("pins.json está dañado; revisa el log del servidor."));
                                return 0;
                            }
                            boolean had;
                            synchronized (PinAuth.class) {
                                had = pins.remove(name) != null;
                                if (had) save();
                            }
                            FAILS.remove(name);
                            ctx.getSource().sendSuccess(() -> Component.literal(had
                                    ? "PIN de " + name + " borrado: la próxima vez que entre registra uno nuevo."
                                    : name + " no tenía PIN registrado."), true);
                            return had ? 1 : 0;
                        })))));
    }
}
