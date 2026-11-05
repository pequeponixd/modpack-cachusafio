package cl.cachusafio.skins.client;

import cl.cachusafio.skins.CachusafioSkins;
import cl.cachusafio.skins.net.SkinData;
import cl.cachusafio.skins.net.SkinNetwork;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Texturas de skin/capa por jugador, listas para que {@code PlayerInfoMixin} las use. */
public final class ClientSkins {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Path DIR = FMLPaths.GAMEDIR.get().resolve("cachusafio");
    private static final Path CAPE_CACHE = DIR.resolve("capes");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** Lo que se pinta para un jugador. Los campos nulos dejan el valor normal de Minecraft. */
    public record Applied(@Nullable ResourceLocation skin, boolean slim, @Nullable ResourceLocation cape) {}

    private static final Map<UUID, Applied> APPLIED = new ConcurrentHashMap<>();
    private static final Map<String, ResourceLocation> CAPES = new ConcurrentHashMap<>();
    private static final Set<String> CAPES_LOADING = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, String> WAITING_CAPE = new ConcurrentHashMap<>();
    private static int counter;

    private ClientSkins() {}

    @Nullable
    public static Applied get(UUID player) {
        return APPLIED.get(player);
    }

    /** Siempre en el hilo principal del cliente. */
    public static void set(UUID player, @Nullable SkinData data) {
        Applied old = APPLIED.remove(player);
        WAITING_CAPE.remove(player);
        if (old != null && old.skin() != null) Minecraft.getInstance().getTextureManager().release(old.skin());
        if (data == null) return;

        ResourceLocation skin = data.hasSkin() ? registerSkin(data.png()) : null;
        ResourceLocation cape = null;
        if (data.hasCape()) {
            cape = CAPES.get(data.cape());
            if (cape == null) {
                WAITING_CAPE.put(player, data.cape());
                loadCape(data.cape());
            }
        }
        if (skin == null && cape == null && !data.hasCape()) return;
        APPLIED.put(player, new Applied(skin, data.slim(), cape));
        LOG.debug("Skin aplicada a {} (skin: {}, capa: {})", player, skin != null, data.hasCape());
    }

    @Nullable
    private static ResourceLocation registerSkin(byte[] png) {
        try (InputStream in = new ByteArrayInputStream(png)) {
            NativeImage img = NativeImage.read(in);
            if (img.getWidth() != 64 || img.getHeight() != 64) {
                LOG.warn("Skin ignorada: mide {}x{} y debe ser 64x64", img.getWidth(), img.getHeight());
                img.close();
                return null;
            }
            fixAlpha(img);
            ResourceLocation id = new ResourceLocation(CachusafioSkins.MOD_ID, "skins/" + (counter++));
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(img));
            return id;
        } catch (IOException | RuntimeException e) {
            LOG.warn("No se pudo leer una skin", e);
            return null;
        }
    }

    /** Igual que Minecraft: la capa base de la cabeza y el cuerpo es opaca. */
    private static void fixAlpha(NativeImage img) {
        opaque(img, 0, 0, 32, 16);
        opaque(img, 0, 16, 64, 32);
        opaque(img, 16, 48, 48, 64);
    }

    private static void opaque(NativeImage img, int x0, int y0, int x1, int y1) {
        for (int x = x0; x < x1; x++)
            for (int y = y0; y < y1; y++)
                img.setPixelRGBA(x, y, img.getPixelRGBA(x, y) | 0xFF000000);
    }

    private static void loadCape(String hash) {
        if (!CAPES_LOADING.add(hash)) return;
        CompletableFuture.supplyAsync(() -> {
            try {
                Path cached = CAPE_CACHE.resolve(hash + ".png");
                if (Files.isRegularFile(cached)) return Files.readAllBytes(cached);
                HttpRequest req = HttpRequest.newBuilder(URI.create("https://textures.minecraft.net/texture/" + hash))
                        .timeout(Duration.ofSeconds(15)).build();
                HttpResponse<byte[]> res = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (res.statusCode() != 200 || res.body().length > 64_000) return null;
                try {
                    Files.createDirectories(CAPE_CACHE);
                    Files.write(cached, res.body());
                } catch (IOException ignored) {}
                return res.body();
            } catch (Exception e) {
                LOG.warn("No se pudo descargar la capa {}", hash, e);
                return null;
            }
        }).thenAcceptAsync(bytes -> {
            CAPES_LOADING.remove(hash);
            if (bytes == null) return;
            try (InputStream in = new ByteArrayInputStream(bytes)) {
                NativeImage img = NativeImage.read(in);
                ResourceLocation id = new ResourceLocation(CachusafioSkins.MOD_ID, "capes/" + hash);
                Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(img));
                CAPES.put(hash, id);
                LOG.debug("Capa {} lista", hash);
                WAITING_CAPE.forEach((player, h) -> {
                    if (!h.equals(hash)) return;
                    Applied a = APPLIED.get(player);
                    if (a != null) APPLIED.put(player, new Applied(a.skin(), a.slim(), id));
                });
                WAITING_CAPE.values().removeIf(hash::equals);
            } catch (IOException | RuntimeException e) {
                LOG.warn("Capa {} inválida", hash, e);
            }
        }, Minecraft.getInstance());
    }

    /** Lee lo que dejó el launcher en {@code cachusafio/skin.png} y {@code cachusafio/skin.json}. */
    @Nullable
    static SkinData readLocal() {
        try {
            Path png = DIR.resolve("skin.png");
            Path json = DIR.resolve("skin.json");
            boolean slim = false;
            String cape = "";
            if (Files.isRegularFile(json)) {
                JsonObject o = JsonParser.parseString(Files.readString(json, StandardCharsets.UTF_8)).getAsJsonObject();
                if (o.has("slim")) slim = o.get("slim").getAsBoolean();
                if (o.has("cape") && !o.get("cape").isJsonNull()) cape = o.get("cape").getAsString();
            }
            byte[] bytes = Files.isRegularFile(png) && Files.size(png) <= SkinData.MAX_PNG ? Files.readAllBytes(png) : new byte[0];
            SkinData data = new SkinData(bytes, slim, cape);
            if (!data.isValid()) {
                LOG.warn("La skin guardada por el launcher no es válida; se ignora");
                return null;
            }
            return data.hasSkin() || data.hasCape() ? data : null;
        } catch (Exception e) {
            LOG.warn("No se pudo leer la skin guardada por el launcher", e);
            return null;
        }
    }

    static void clear() {
        var tm = Minecraft.getInstance().getTextureManager();
        APPLIED.values().forEach(a -> { if (a.skin() != null) tm.release(a.skin()); });
        APPLIED.clear();
        WAITING_CAPE.clear();
    }

    public static final class Events {
        private Events() {}

        @SubscribeEvent
        public static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
            UUID self = event.getPlayer().getUUID();
            SkinData mine = readLocal();
            set(self, mine);
            if (mine != null && SkinNetwork.CHANNEL.isRemotePresent(event.getConnection())) {
                SkinNetwork.CHANNEL.sendToServer(new SkinNetwork.Upload(mine));
            }
        }

        @SubscribeEvent
        public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
            clear();
        }
    }
}
