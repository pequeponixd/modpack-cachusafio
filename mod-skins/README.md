# Cachusafio Skins (Forge 1.20.1)

Mod que hace que la skin y la capa elegidas en el **Vestidor** del launcher se vean dentro del servidor,
también con cuentas no premium.

- El launcher deja `cachusafio/skin.png` y `cachusafio/skin.json` en la carpeta del juego al darle Jugar.
- Al entrar a un mundo, el cliente se los manda al servidor y el servidor los reparte a todos los conectados.
- Las capas son las oficiales de Mojang: solo viaja el id de la textura y cada cliente la baja de `textures.minecraft.net`.

**Tiene que estar en el servidor también** (carpeta `mods/` del servidor). Sin él, cada uno ve solo su propia skin.
Es opcional en ambos lados: nadie queda bloqueado por no tenerlo.

## Compilar

```
./gradlew build          # -> build/libs/cachusafio-skins-<versión>.jar
```

Prueba local: `./gradlew runServer` y luego `./gradlew runClient -PtestJoin=localhost:<puerto>`
(`-PtestName=Amigo -PclientDir=run-client2` para un segundo jugador).
