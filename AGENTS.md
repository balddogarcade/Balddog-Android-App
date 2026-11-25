# Reglas para Agentes (`AGENTS.md`)

Este archivo define reglas obligatorias que todo agente de IA debe respetar al trabajar en este repositorio.

## Reglas Críticas de Git y Commits

1. **Autoría exclusiva (`cjbaezilla`) — Prohibido co-autorear commits:**
   - **NUNCA** agregues líneas `Co-authored-by:` (ni ningún otro trailer de co-autoría o atribución a IA/herramientas) en los mensajes de commit.
   - **NUNCA** cambies ni sobrescribas el autor o committer del repositorio para atribuir el commit a un agente, bot o tercero.
   - El **único autor** de todos los commits debe ser siempre **`cjbaezilla`** y nadie más.

2. **Verificación obligatoria de secretos y datos sensibles antes de hacer commit:**
   - **SIEMPRE** revisa cuidadosamente los cambios (`git diff --cached` / `git diff`) antes de realizar cualquier commit o `git add`.
   - Asegúrate de que **no** se incluyan datos secretos, credenciales o información sensible hardcodeada, incluyendo (sin limitarse a):
     - Claves de API (`API keys`), tokens de acceso, JWTs o secretos de OAuth.
     - Contraseñas, frases de paso, claves privadas, certificados o archivos `.keystore` / `.jks`.
     - URLs privadas con credenciales embebidas, cadenas de conexión a bases de datos o llaves de Supabase/servicios externos (`service_role`, secretos de firma, etc.).
     - Archivos locales de configuración que deban permanecer ignorados (como `local.properties`, `.env`, etc.).
   - Si detectas cualquier dato secreto o sensible hardcodeado, **detente de inmediato**, no hagas el commit, muévelo a configuración segura/variables de entorno/archivos ignorados por `.gitignore` y notifícalo al usuario.

3. **Título y descripción obligatorios y detallados en cada commit:**
   - **SIEMPRE** incluye un **título** claro y conciso, seguido de una **descripción** (cuerpo del mensaje) lo más descriptiva posible en cada commit.
   - El **título** debe resumir con precisión el propósito del cambio.
   - La **descripción** debe detallar qué se modificó, por qué se realizó el cambio y cualquier contexto o impacto relevante para permitir un seguimiento adecuado en el historial del repositorio (evita commits sin cuerpo descriptivo).

4. **Idioma obligatorio — Todos los commits en inglés:**
   - **SIEMPRE** redacta tanto el título como la descripción (cuerpo) de cada commit en **inglés**.

---

## Protocolo Rápido: Probar la App en Celular por USB

Cuando el usuario pida probar, correr, instalar o abrir la app en su celular conectado por USB (ej. *"quiero probar la app en el celular"*, *"instálala en el teléfono"*, *"córrela en el dispositivo"*), **NO explores ni analices el repositorio**. Sigue exactamente este paso a paso:

### Datos fijos del proyecto (no re-investigar)
- **Entorno toolchain:** `scripts/env.sh` (configura `JAVA_HOME`, `ANDROID_HOME`, `ANDROID_NDK_HOME` y `PATH`).
- **Módulo Android:** `:app` (arquitectura soportada: `arm64-v8a`).
- **Application ID (debug):** `dog.bald.arcade.debug`
- **Activity principal:** `dog.bald.arcade.MainActivity`
- **Componente para lanzar:** `dog.bald.arcade.debug/dog.bald.arcade.MainActivity`
- **Binarios nativos esperados en `app/src/main/jniLibs/arm64-v8a/`:** `libbalddog_android.so`, `libc++_shared.so`, `libfbneo_libretro.so`.

### Paso a paso obligatorio

1. **Verificar dispositivo USB conectado:**
   Ejecuta:
   ```bash
   adb devices
   ```
   - Debe aparecer un dispositivo con estado `device`.
   - Si aparece `unauthorized`, pide al usuario aceptar la depuración USB en la pantalla del celular y detente.
   - Si no aparece ningún dispositivo, pide al usuario verificar el cable/depuración USB y detente.
   - *(Nota: si `adb` falla dentro del sandbox por acceso USB/socket, reintenta ese comando fuera del sandbox).*

2. **Compilar Rust solo si hubo cambios en `rust/` o faltan `.so`:**
   - Si **NO** se modificaron archivos en `rust/` y ya existen `libbalddog_android.so`, `libc++_shared.so` y `libfbneo_libretro.so` en `app/src/main/jniLibs/arm64-v8a/`, **salta directo al paso 3**.
   - Si se modificó código en `rust/` o falta `libbalddog_android.so`, ejecuta primero:
     ```bash
     source scripts/env.sh && ./scripts/build-rust.sh debug
     ```
   - Solo si falta `libfbneo_libretro.so`, ejecuta antes:
     ```bash
     source scripts/env.sh && ./scripts/build-fbneo-android.sh
     ```

3. **Compilar e instalar el APK debug en el celular:**
   Ejecuta directamente:
   ```bash
   source scripts/env.sh && ./gradlew installDebug
   ```
   - Si falla por errores de compilación Kotlin/Gradle, corrige el error puntual y vuelve a ejecutar `./gradlew installDebug`.

4. **Abrir la aplicación en el celular:**
   Ejecuta:
   ```bash
   adb shell am start -n dog.bald.arcade.debug/dog.bald.arcade.MainActivity
   ```

5. **Confirmar que arrancó sin crash inmediato:**
   Ejecuta una verificación rápida:
   ```bash
   adb shell pidof dog.bald.arcade.debug
   ```
   - Si devuelve un PID, confirma al usuario que la app quedó instalada y abierta en el celular.
   - Si no devuelve PID o la app se cerró, revisa el error con:
     ```bash
     adb logcat -d -t 80 *:E
     ```
     y reporta o corrige la causa exacta del fallo.

---

## Protocolo Obligatorio: Subir APK a Pinata (IPFS)

Cuando el usuario solicite subir el APK a Pinata (o "piñata", IPFS, etc.), **SIEMPRE debe ser la versión RELEASE** (nunca debug). El APK Debug pesa ~45 MB por contener multi-DEX sin optimizar ni ofuscar; la versión Release con R8/ProGuard y binarios nativos optimizados reduce el tamaño a **~23-24 MB**.

### Regla de Credenciales de Pinata
- La API de Pinata requiere obligatoriamente:
  - El **JWT** Bearer (`Authorization: Bearer <PINATA_JWT>`), **O**
  - Ambos `pinata_api_key` (20 caracteres) Y `pinata_secret_api_key` (64 caracteres).
  *(El API Key solo de 20 caracteres es rechazado con error 401).*
- **NUNCA** almacenes ni hagas commit de estas credenciales en el repositorio (respetar la Regla Crítica 2 de Git). Usa las provistas por el usuario en la sesión o solicítalas si no están disponibles.

### Flujo Paso a Paso de Compilación y Subida

1. **Verificar Toolchain y Java (JDK 21):**
   - Cargar siempre `scripts/env.sh`. Asegurarse de que `JAVA_HOME` apunte a JDK 21 (ej. `/tmp/jdk-21` o JDK 21/17 instalado), ya que Android Gradle Plugin falla con Java 25.

2. **Compilar y optimizar backend nativo en Rust (Release):**
   - Compilar `libbalddog_android.so` con perfil `--release`:
     ```bash
     source scripts/env.sh && ./scripts/build-rust.sh release
     ```
   - Remover símbolos de depuración para reducir tamaño:
     ```bash
     source scripts/env.sh && "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-all app/src/main/jniLibs/arm64-v8a/libbalddog_android.so
     ```
   - Si falta `libfbneo_libretro.so` en `app/src/main/jniLibs/arm64-v8a/`, compilarlo previamente con:
     ```bash
     source scripts/env.sh && ./scripts/build-fbneo-android.sh
     ```

3. **Compilar el APK Release con R8 / ProGuard:**
   - Ejecutar la tarea `assembleRelease` (con `unset GRADLE_USER_HOME` para usar las cachés locales del sistema y `BypassSandbox: true` si es necesario acceso a red o disco completo):
     ```bash
     source scripts/env.sh && unset GRADLE_USER_HOME && ./gradlew assembleRelease
     ```
   - Verificar que el APK exista en `app/build/outputs/apk/release/app-release.apk` y que su tamaño sea de ~23-24 MB.

4. **Verificar firma del APK Release:**
   - Comprobar que el APK esté correctamente firmado (con keystore de release o fallback a debug para permitir instalación directa):
     ```bash
     source scripts/env.sh && "$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose app/build/outputs/apk/release/app-release.apk
     ```

5. **Subir el APK a Pinata Cloud (IPFS):**
   - Usar el endpoint `https://api.pinata.cloud/pinning/pinFileToIPFS` con `BypassSandbox: true`:
     ```bash
     curl -s -X POST https://api.pinata.cloud/pinning/pinFileToIPFS \
       -H "Authorization: Bearer <PINATA_JWT>" \
       -F "file=@app/build/outputs/apk/release/app-release.apk;filename=balddog-arcade-release.apk" \
       -F 'pinataMetadata={"name":"balddog-arcade-release.apk"}'
     ```
     *(O alternativamente con cabeceras `pinata_api_key: <KEY>` y `pinata_secret_api_key: <SECRET>` si se usan API keys).*

6. **Entregar resultado al usuario:**
   - Extraer el `IpfsHash` (CID) del JSON de respuesta y compartir los enlaces:
     - Pinata Gateway: `https://gateway.pinata.cloud/ipfs/<CID>`
     - IPFS.io Gateway: `https://ipfs.io/ipfs/<CID>`
     - dweb.link: `https://dweb.link/ipfs/<CID>`

---

## Protocolo: Integrar Nuevos Juegos Arcade

Para integrar y certificar nuevos juegos arcade en el catálogo de bald.dog ARCADE (metadatos, IPFS, ingeniería inversa de RAM y leaderboards 1CC):
- **Guía Técnica Exhaustiva:** Consulta obligatoriamente [docs/ADDING_GAMES.md](file:///media/carlos/DATA1/DEV/Balddog-Android-App/docs/ADDING_GAMES.md).
- **Herramientas de Diagnóstico:**
  - `rust/balddog-core/examples/ram_probe.rs` para inspeccionar `RETRO_MEMORY_SYSTEM_RAM` con `--scan [out.ppm]` y `--scan find:<score>`.
  - Core nativo de diagnóstico para Linux: `/tmp/fbneo-linux/src/burner/libretro/fbneo_libretro.so`.
- **Regla de Integridad de Leaderboards y Ciclo de Vida 1CC:** **NUNCA** inventes ni asumas direcciones de memoria de puntaje o banderas `P1_ACTIVE`, ni utilices directamente candidatos de un escaneo de un solo frame (`ram_probe --scan`) sin verificar su ciclo de vida completo:
  - **Mecanismo de CoinTracker:** Si el flag monitoreado por `STA`/`SUB` parpadea a `0` durante la selección de personaje, cinemáticas, animaciones de daño o transiciones de fase, `rcheevos` se desactivará y reactivará, provocando que `CoinTracker` interprete falsamente que inició una 2ª partida (`credits_used = 2`) e invalidando el ranking.
  - **Verificación obligatoria de ciclo de vida:** Todo juego nuevo o auditado debe validarse a lo largo de:
    1. Attract / Boot (`active == false`)
    2. Selección de personaje / intro (`active == false`)
    3. Fase activa de juego (`active == true` continuo y estable)
    4. Combates, golpes, ataques especiales y pérdida de vidas intermedias (`active` debe mantenerse `true` ininterrumpidamente)
    5. Game Over / Continue screen (`active` pasa a `false` disparando `SUB` exactamente al expirar la última vida).
  - **Prioridad de variables:** Preferir siempre variables de máquina o loop principal (ej. `kStep` en CPS: `0x0C` jugando, `0x10` Game Over) combinadas con el flag maestro de jugador en partida (`kInPlay`), en lugar de registros internos o animaciones del jugador.
  - Toda expresión `rcheevos` final debe incorporarse en los tests unitarios de `rust/balddog-core/src/retro/leaderboard.rs` simulando este ciclo, y sembrarse en Supabase mediante una migración SQL numerada ejecutada y verificada de inmediato.

---

## Protocolo Obligatorio: Migraciones de Base de Datos (Supabase)

Cada vez que se **cree** o **modifique** una migración SQL en `supabase/migrations/` (ej. `025_add_avatar_seed_to_room_slots.sql`):

### 1. Regla Mandatoria
- **NUNCA** dejar una migración solo como archivo SQL local esperando ejecución manual.
- **SIEMPRE** ejecutar la migración inmediatamente contra la base de datos de Supabase.
- **SIEMPRE** verificar que los cambios (funciones, tablas, columnas, índices, triggers o filas actualizadas) hayan tomado efecto real en la base de datos antes de dar la tarea por concluida.
- **NUNCA** hacer commit ni exponer en logs las credenciales de `local.properties` (respetar la Regla Crítica 2 de Git).

### 2. Extracción Segura de Credenciales
Las credenciales se obtienen directamente de `local.properties` (archivo ignorado por git):
- `SUPABASE_PROJECT_ID` (ej. `trwgnawmmslpmykqxpkg`)
- `SUPABASE_ACCESS_TOKEN` (token bearer `sbp_...`)

### 3. Ejecución de la Migración
Ejecuta la migración vía Supabase Management API usando `BypassSandbox: true`:
```bash
python3 -c "
import urllib.request, json, re

props = {}
with open('local.properties') as f:
    for line in f:
        m = re.match(r'^([^#=]+)=(.*)$', line.strip())
        if m: props[m.group(1).strip()] = m.group(2).strip()

project_id = props.get('SUPABASE_PROJECT_ID')
token = props.get('SUPABASE_ACCESS_TOKEN')
assert project_id and token, 'Credenciales de Supabase no encontradas en local.properties'

with open('supabase/migrations/<MIGRATION_FILE>.sql') as f:
    sql = f.read()

req = urllib.request.Request(
    f'https://api.supabase.com/v1/projects/{project_id}/database/query',
    data=json.dumps({'query': sql}).encode('utf-8'),
    headers={'Authorization': f'Bearer {token}', 'Content-Type': 'application/json'}
)
with urllib.request.urlopen(req) as resp:
    print('Status:', resp.status)
"
```

### 4. Verificación Obligatoria
Inmediatamente tras la ejecución, lanza una consulta de verificación contra la API para confirmar el cambio:
- **Para funciones RPC:** Consultar `pg_proc` verificando que `prosrc` contenga el nuevo código/columnas.
- **Para tablas/columnas:** Consultar `information_schema.columns` verificando que `column_name` exista.
- **Para seeds de juegos o configuraciones:** Consultar la fila en `public.games` verificando el nuevo valor.


