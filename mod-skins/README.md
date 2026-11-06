# Cachusafio Skins (Forge 1.20.1)

Mod que hace que la skin y la capa elegidas en el **Vestidor** del launcher se vean dentro del servidor,
también con cuentas no premium.

- El launcher deja `cachusafio/skin.png` y `cachusafio/skin.json` en la carpeta del juego al darle Jugar.
- Al entrar a un mundo, el cliente se los manda al servidor y el servidor los reparte a todos los conectados.
- Las capas son las oficiales de Mojang: solo viaja el id de la textura y cada cliente la baja de `textures.minecraft.net`.

## PIN (nombres protegidos)

En un servidor sin premium cualquiera puede entrar con cualquier nombre. Con este mod, el launcher manda una clave
derivada del PIN del jugador (nunca el PIN). La primera vez que un nombre entra queda registrado en
`cachusafio/pins.json` del servidor; después, quien entre con ese nombre y otro PIN es expulsado, igual que quien
entre sin el launcher. Mientras se verifica (menos de un segundo) el jugador no puede moverse, romper ni chatear.

- PIN olvidado: `/cachusafio pin reset <nombre>` (operadores) y el jugador registra uno nuevo al entrar.
- Apagarlo: `config/cachusafio_skins-common.toml` → `activado = false`.
- Solo se aplica en servidores dedicados (no en un mundo de un jugador).

**Tiene que estar en el servidor también** (carpeta `mods/` del servidor). Sin él, cada uno ve solo su propia skin.
Es opcional en ambos lados: nadie queda bloqueado por no tenerlo.

## Compilar

```
./gradlew build          # -> build/libs/cachusafio-skins-<versión>.jar
```

Prueba local: `./gradlew runServer` y luego `./gradlew runClient -PtestJoin=localhost:<puerto>`
(`-PtestName=Amigo -PclientDir=run-client2` para un segundo jugador).
