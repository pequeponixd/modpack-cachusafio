package cl.cachusafio.skins.net;

import net.minecraft.network.FriendlyByteBuf;

import javax.annotation.Nullable;
import java.util.regex.Pattern;

/** Skin (PNG crudo de 64x64), tipo de brazos y capa oficial (hash de textures.minecraft.net). */
public record SkinData(byte[] png, boolean slim, String cape) {
    /** Los paquetes cliente→servidor no pueden pasar de 32 KiB. */
    public static final int MAX_PNG = 30_000;
    private static final Pattern CAPE = Pattern.compile("[0-9a-f]{40,64}");
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    public SkinData {
        if (png == null) png = new byte[0];
        if (cape == null) cape = "";
    }

    public boolean hasSkin() { return png.length > 0; }
    public boolean hasCape() { return !cape.isEmpty(); }

    /** Revisión barata y segura (no decodifica la imagen: eso lo hace el cliente). */
    public boolean isValid() {
        if (png.length > MAX_PNG) return false;
        if (png.length > 0) {
            if (png.length < PNG_MAGIC.length) return false;
            for (int i = 0; i < PNG_MAGIC.length; i++) if (png[i] != PNG_MAGIC[i]) return false;
        }
        return cape.isEmpty() || CAPE.matcher(cape).matches();
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeByteArray(png);
        buf.writeBoolean(slim);
        buf.writeUtf(cape, 64);
    }

    @Nullable
    public static SkinData read(FriendlyByteBuf buf) {
        byte[] png = buf.readByteArray(MAX_PNG);
        boolean slim = buf.readBoolean();
        String cape = buf.readUtf(64);
        SkinData d = new SkinData(png, slim, cape);
        return d.isValid() ? d : null;
    }
}
