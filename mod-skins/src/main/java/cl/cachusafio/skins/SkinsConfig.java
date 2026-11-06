package cl.cachusafio.skins;

import net.minecraftforge.common.ForgeConfigSpec;

/** config/cachusafio_skins-common.toml */
public final class SkinsConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue PIN_ENABLED;
    public static final ForgeConfigSpec.IntValue PIN_TIMEOUT_SECONDS;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("pin");
        PIN_ENABLED = b.comment("Pedir el PIN del launcher para entrar (solo servidores dedicados).",
                        "Si lo apagas, cualquiera puede entrar con cualquier nombre.")
                .define("activado", true);
        PIN_TIMEOUT_SECONDS = b.comment("Segundos que tiene un jugador para mandar su PIN antes de ser expulsado.")
                .defineInRange("segundosParaVerificar", 20, 5, 120);
        b.pop();
        SPEC = b.build();
    }

    private SkinsConfig() {}
}
