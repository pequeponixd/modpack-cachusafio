package cl.cachusafio.skins.net;

import cl.cachusafio.skins.CachusafioSkins;
import cl.cachusafio.skins.client.ClientSkins;
import cl.cachusafio.skins.server.ServerSkins;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class SkinNetwork {
    private static final String VERSION = "1";

    /**
     * Opcional en ambos lados: un servidor sin el mod deja entrar a clientes con él
     * (y al revés); en ese caso cada uno solo ve su propia skin.
     */
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(CachusafioSkins.MOD_ID, "main"),
            () -> VERSION,
            NetworkRegistry.acceptMissingOr(VERSION),
            NetworkRegistry.acceptMissingOr(VERSION));

    private SkinNetwork() {}

    public static void register() {
        CHANNEL.registerMessage(0, Upload.class, Upload::write, Upload::read, Upload::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(1, Sync.class, Sync::write, Sync::read, Sync::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    public static void sendTo(ServerPlayer player, Sync msg) {
        if (CHANNEL.isRemotePresent(player.connection.connection)) CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), msg);
    }

    /** Cliente → servidor: "esta es mi skin". {@code data == null} si el paquete venía mal formado. */
    public record Upload(@Nullable SkinData data) {
        void write(FriendlyByteBuf buf) { data.write(buf); }

        static Upload read(FriendlyByteBuf buf) { return new Upload(SkinData.read(buf)); }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            ServerPlayer sender = c.getSender();
            if (sender != null && data != null) c.enqueueWork(() -> ServerSkins.receive(sender, data));
            c.setPacketHandled(true);
        }
    }

    /** Servidor → cliente: skin de un jugador ({@code data == null} = quitarla). */
    public record Sync(UUID player, @Nullable SkinData data) {
        void write(FriendlyByteBuf buf) {
            buf.writeUUID(player);
            buf.writeBoolean(data != null);
            if (data != null) data.write(buf);
        }

        static Sync read(FriendlyByteBuf buf) {
            UUID id = buf.readUUID();
            return new Sync(id, buf.readBoolean() ? SkinData.read(buf) : null);
        }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientSkins.set(player, data)));
            c.setPacketHandled(true);
        }
    }
}
