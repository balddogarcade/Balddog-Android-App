# Reglas para Agentes (`AGENTS.md`)

Este archivo define reglas obligatorias que todo agente de IA debe respetar al trabajar en este repositorio.

## Reglas de Comunicación

1. **Idioma obligatorio — Comunicación siempre en español:**
   - **SIEMPRE** comunícate con el usuario en **español** (respuestas, explicaciones, preguntas, reportes de estado y mensajes de error).
   - *(Esta regla aplica a toda la comunicación con el usuario; los mensajes de commit en Git deben seguir redactándose en inglés según la regla correspondiente más abajo).*

---

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

3. **Compilar e instalar el APK en el celular:**
   Ejecuta directamente:
   ```bash
   source scripts/env.sh && unset GRADLE_USER_HOME && ./gradlew installDebug
   ```
   - Si falla en `:app:processDebugGoogleServices` porque `app/google-services.json` solo registra `dog.bald.arcade` (sin `.debug`), instala la variante Release (hace fallback a firma debug si no hay keystore configurado):
     ```bash
     source scripts/env.sh && unset GRADLE_USER_HOME && ./gradlew installRelease
     ```
   - Si falla por errores de compilación Kotlin/Gradle, corrige el error puntual y vuelve a ejecutar.

4. **Abrir la aplicación en el celular:**
   Ejecuta (según la variante instalada):
   ```bash
   adb shell am start -n dog.bald.arcade.debug/dog.bald.arcade.MainActivity
   # o para release:
   adb shell am start -n dog.bald.arcade/dog.bald.arcade.MainActivity
   ```

5. **Confirmar que arrancó sin crash inmediato:**
   Ejecuta una verificación rápida:
   ```bash
   adb shell pidof dog.bald.arcade.debug || adb shell pidof dog.bald.arcade
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
  - **Mecanismo de CoinTracker:** `CoinTracker` (`rust/balddog-core/src/session.rs`) contabiliza `RETRO_COIN_MASK` en `P1..P4` desde el frame 0 (arranque, título, selección de personaje, partida y continue), descalificando de inmediato si se cargan 2 o más créditos en cualquier momento y congelando permanentemente los puntajes tras un continue. Si el flag monitoreado por `STA`/`SUB` parpadea a `0` durante la selección de personaje, cinemáticas, animaciones de daño o transiciones de fase y coincide con un input de `START`/`COIN`, `CoinTracker` interpretará que inició una 2ª partida (`credits_used = 2`) e invalidará el ranking.
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
- **Cero cuentas anónimas / visitantes:** Todos los usuarios en la plataforma deben estar registrados (`is_anonymous = false`). Prohibido reintroducir flujos de invitados o lectura pública de `public.profiles`; la web pública consume únicamente agregados vía `get_public_arcade_stats()`.
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

### 5. Invariantes de Salas y Edge Functions
- **Salas (`public.rooms`):** `rooms_update_host` fue eliminado (`037`); toda modificación de estado o slots debe ejecutarse mediante `update_room_slot(...)`, y `rooms_insert_host` exige `status = 'waiting'`.
- **Eliminación de cuenta:** La Edge Function `delete-account` (`supabase/functions/delete-account/index.ts`) autentica el JWT del usuario, limpia sus archivos en `storage.from('replays')` y elimina la cuenta vía `auth.admin.deleteUser`.

---

## Protocolo Obligatorio: Netplay P2P y Resolución de Red

Al trabajar en la capa de red, señalización en Supabase, sockets UDP o el motor de sincronización GGRS (`rust/balddog-netplay`, `rust/balddog-core`, `app/.../net`):

### 1. Regla de Candidatos Duales (Invariante de Formato)
- Todo endpoint de juego y de voz anunciado en `public.rooms.slots_json` DEBE mantener el formato de candidato dual `public_ip:port|lan_ip:port` generado por `NetplayEndpointResolver.formatCandidateEndpoint()`.
- **NUNCA** asumir que STUN WAN es suficiente: los routers hogareños típicamente carecen de soporte para **NAT Hairpinning / Loopback** y descartan silenciosamente paquetes UDP dirigidos a su propia IP pública cuando dos jugadores están en la misma red Wi-Fi.

### 2. Resolución de Pares Obligatoria
- Antes de pasar las direcciones remotas (`peerAddrs` o `voicePeerAddrs`) a Rust o a los sockets de audio/ping, SIEMPRE resolver mediante `NetplayEndpointResolver.resolveEffectiveEndpoint(peerEndpoint, myPublicIp)`.
- Si ambos pares comparten la misma IP pública WAN (misma red local), el tráfico DEBE dirigirse a la IP LAN directa (`192.168.0.x:puerto`).

### 3. Ciclo de Vida de GGRS y Emulación de Video
- Recordar que `core.take_video_frame()` de Libretro y el pipeline de renderizado solo se ejecutan cuando GGRS alcanza `SessionState::Running`.
- Si la pantalla queda en negro o en sincronización perpetua, el fallo es 100% de conectividad UDP en la fase `SessionState::Synchronizing` (verificar eventos con `adb logcat | grep GGRS`).
- Consultar la guía exhaustiva, diagramas y herramientas de diagnóstico en [docs/NETPLAY_ARCHITECTURE.md](file:///media/carlos/DATA1/DEV/Balddog-Android-App/docs/NETPLAY_ARCHITECTURE.md).

---

## Protocolo Obligatorio: Crashlytics y Diagnóstico (Kotlin + Rust + NDK)

Al implementar nuevas funcionalidades, pantallas, flujos de red/netplay, audio, replays o código nativo (`rust/`, FBNeo):

1. **Instrumentación automática con `CrashlyticsHelper`:**
   - Usa `dog.bald.arcade.diagnostics.CrashlyticsHelper` (`setCustomKey` vía helpers dedicados, `log()` para breadcrumbs de transiciones de estado, y `recordNonFatal(t)` en `catch` críticos o fallos recuperables).
   - **NUNCA** envíes credenciales, tokens, contraseñas ni PII en logs o Custom Keys.
2. **Invariante de símbolos nativos (`unstripped` vs `stripped`):**
   - Mantén `debug = "line-tables-only"` y `panic = "unwind"` en `rust/Cargo.toml` (`[profile.release]`) y `-gline-tables-only` en `scripts/build-fbneo-android.sh`.
   - Toda compilación nativa debe conservar la copia *unstripped* en `.cache/unstripped-jniLibs/arm64-v8a/` y empaquetar solo la versión *stripped* en `app/src/main/jniLibs/arm64-v8a/`.
3. **Credenciales Firebase:**
   - `app/google-services.json` está ignorado en `.gitignore`; **NUNCA** lo agregues al repositorio.
4. **Referencia y consulta:**
   - Guía técnica completa: [docs/CRASHLYTICS_AND_DIAGNOSTICS.md](file:///media/carlos/DATA1/DEV/Balddog-Android-App/docs/CRASHLYTICS_AND_DIAGNOSTICS.md).
   - Analítica (GA4): Toda nueva pantalla, tab, overlay o acción clave debe instrumentarse en `dog.bald.arcade.diagnostics.AnalyticsHelper` (`logScreenView` + eventos tipados sin PII, saneados a ≤ 100 chars en paridad con `Balddog-Main-Webpage`) y cubrirse en `AnalyticsHelperTest.kt`; guía en [docs/ANALYTICS.md](file:///media/carlos/DATA1/DEV/Balddog-Android-App/docs/ANALYTICS.md).
   - Rendimiento: Usa `dog.bald.arcade.diagnostics.PerformanceHelper` para trazas personalizadas; guía en [docs/PERFORMANCE.md](file:///media/carlos/DATA1/DEV/Balddog-Android-App/docs/PERFORMANCE.md).
   - Runbook operativo (build de símbolos, `logcat` `balddog-panic`, consulta en Firebase): `.agents/skills/crashlytics-diagnostics/SKILL.md`.

---

## Protocolo Obligatorio: Versionamiento, Releases y Firma

1. **Versionamiento SemVer y `CHANGELOG.md`:**
   - Todo cambio relevante para el usuario/app debe registrarse en `CHANGELOG.md` bajo `## [Unreleased]` siguiendo *Keep a Changelog*, **sin crear tags** hasta publicar un release.
   - Los cambios puramente internos (documentación en `docs/`, `AGENTS.md`, `.agents/skills/`) no requieren entrada en `CHANGELOG.md` (quedan registrados en `git log`).
   - **NUNCA** crees tags `vX.Y.Z` para cambios internos sin intención de publicar: cualquier tag `v*.*.*` dispara `.github/workflows/release.yml` y publica un nuevo GitHub Release + APK.
   - Al preparar un release `vX.Y.Z`, mueve los ítems de `[Unreleased]` a `[X.Y.Z] - YYYY-MM-DD` antes de crear el tag.
2. **Firma de Release:**
   - `app/build.gradle.kts` prioriza `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` y `ANDROID_KEY_PASSWORD` desde variables de entorno (CI) y hace fallback a `local.properties`.
   - **NUNCA** hagas commit de archivos `.jks`/`.keystore`, `google-services.json`, `local.properties` ni de `VIRUSTOTAL_API_KEY`.
3. **Referencia y consulta:**
   - Guías técnicas: [docs/RELEASES_AND_UPDATES.md](file:///media/carlos/DATA1/DEV/Balddog-Android-App/docs/RELEASES_AND_UPDATES.md) y [docs/SECURITY_AND_SIGNING.md](file:///media/carlos/DATA1/DEV/Balddog-Android-App/docs/SECURITY_AND_SIGNING.md).
   - Runbook operativo de releases: `.agents/skills/release-management/SKILL.md`.

---

## Protocolo Obligatorio: Correos Transaccionales (Resend + Cloudflare)

1. **Remitente oficial:** Todos los correos transaccionales se envían desde `noreply@bald.dog` vía Resend (`EMAIL_FROM`).
2. **Invariante DNS en Cloudflare:** Nunca activar el proxy naranja (`proxied=true`) sobre los registros de correo (`resend._domainkey.bald.dog`, `send.bald.dog`, `rsend.bald.dog`, `_dmarc.bald.dog`).
3. **Idempotencia de bienvenida:** Toda modificación a `supabase/functions/send-welcome-email/index.ts` debe preservar la validación por JWT, el chequeo/actualización de `public.profiles.welcome_email_sent` y redesplegarse en Supabase.
4. **Referencia:** Consultar [docs/EMAIL_AND_CLOUDFLARE.md](file:///media/carlos/DATA1/DEV/Balddog-Android-App/docs/EMAIL_AND_CLOUDFLARE.md).






---

## Protocolo Obligatorio: Calidad de Código, Seguridad y Toolchain CI

Al modificar workflows de CI, dependencias Gradle/Rust o código Kotlin/Rust:

1. **Toolchain Android (Gradle 9.9.2 + AGP 9.5.3):**
   - AGP 9 usa **built-in Kotlin**: **NUNCA** agregues `id("org.jetbrains.kotlin.android")` ni bloques `kotlinOptions {}` en `app/build.gradle.kts`.
   - Mantén los plugins de Kotlin (`compose` y `serialization`) alineados (`2.4.21`) y `compileSdk = 37.2` (`targetSdk = 36`).
   - `scripts/build-rust.sh` incluye `-C link-arg=-Wl,-z,max-page-size=16384` para alineamiento de páginas de 16 KB.
   - `app/proguard-rules.pro` mantiene R8 activo sobre `dog.bald.arcade.**` (sin `-keep` global).
   - `app/google-services.json` requiere el cliente `dog.bald.arcade.debug` además de `dog.bald.arcade` para ejecutar `./gradlew testDebugUnitTest` e `installDebug`.
2. **Invariantes de Seguridad (`.github/workflows/security.yml`):**
   - **OpenSSF Scorecard:** Mantén `permissions: read-all` a nivel global del workflow; `security-events: write` va únicamente en los jobs individuales que suben SARIF.
   - **`cargo-deny`:** Los crates internos del workspace (`balddog-core`, `balddog-netplay`, `balddog-android`) deben mantener `publish = false` en sus `Cargo.toml`.
   - **Cero secretos en CI:** No uses `secrets.*` en `jobs.<id>.if` (inválido en GitHub Actions). `SONAR_TOKEN` vive en `local.properties` (ignorado) y GitHub Secrets.
3. **Invariantes de Calidad (`.github/workflows/quality.yml`):**
   - **Detekt / ktlint / Qodana / SonarCloud:** Excluyen `app/src/main/java/uniffi/**` (bindings autogenerados).
   - Antes de pushear cambios en Rust, ejecuta `cargo fmt --manifest-path rust/Cargo.toml --all -- --check` y `cargo clippy --manifest-path rust/Cargo.toml --all-targets`.
4. **Referencia:** Consultar [docs/CI_QUALITY_AND_SECURITY.md](file:///media/carlos/DATA1/DEV/Balddog-Android-App/docs/CI_QUALITY_AND_SECURITY.md).

---

## Protocolo: Enriquecimiento de Metadatos (Arcade Database + IPFS)

Al agregar nuevos juegos al catálogo (`public.games`), ejecuta el seeder para poblar los 22 campos de metadatos desde `adb.arcadeitalia.net` y subir sus 6 imágenes a Pinata/IPFS:

1. **Referencia de campos y endpoints:** [docs/ARCADE_DATABASE_API_REFERENCE.md](file:///media/carlos/DATA1/DEV/Balddog-Android-App/docs/ARCADE_DATABASE_API_REFERENCE.md).
2. **Ejecución por ROM nueva:**
   ```bash
   python3 scripts/enrich-games-metadata.py --rom <slug>
   ```
3. **Invariantes:**
   - La respuesta de `adb.arcadeitalia.net` viene comprimida en **GZIP** (`\x1f\x8b`).
   - El seeder preserva intactos `cover_url` y `title` actuales del catálogo (el título de ADB se guarda en `adb_title`).
   - Requiere `SUPABASE_PROJECT_ID`, `SUPABASE_ACCESS_TOKEN` y credenciales de Pinata (`PINATA_JWT` o `PINATA_API_KEY` + `PINATA_SECRET_API_KEY`) en `local.properties` o entorno.

---

## Protocolo: Mantenimiento Automático con `pg_cron` y Analítica Dual

1. **Limpieza automática de salas (`pg_cron`):**
   - La extensión `pg_cron` está habilitada en Supabase (`036_enable_pg_cron_maintenance.sql`) y ejecuta `public.cleanup_stale_rooms()` cada 5 minutos (`*/5 * * * *`) para cerrar salas en estado `'waiting'` o `'playing'` sin actividad (`updated_at`) durante más de 30 minutos.
2. **Analítica Dual:**
   - **App Android:** Firebase Analytics (GA4) vía `AnalyticsHelper` (sin PII).
   - **Web pública (`bald.dog`):** Cloudflare Web Analytics (sin cookies, inyección automática vía `auto_install: True`).

---

## Protocolo: Verificación Automática de Replays (GitHub Actions)

1. **Principio Anti-Cheat:**
   - **NUNCA** permitir que el cliente Android marque un replay como `verified`. Toda certificación ocurre en servidor/CI mediante `verify_replay` + `fbneo_libretro.so` (Linux) compilado desde el mismo commit fijado (`5f6bdff9451d5c3dedd3d992d7412660eb202cf4`) y con flags deterministas (`scripts/build-fbneo-linux.sh`).
2. **Pipeline Serverless (`.github/workflows/verify-replays.yml`):**
   - Se ejecuta cada 15 minutos (`schedule`), manualmente (`workflow_dispatch`) o vía `repository_dispatch` (`verify-replays`).
   - El job `check-queue` verifica primero si existen `high_scores` en `pending` o `verifying`; si la cola está vacía, omite el build de Rust/FBNeo.
   - `scripts/verify-replays-worker.py` descarga bajo demanda las ROMs faltantes desde `public.games.rom_url` y audita con `ONCE=1`.
   - Requiere `SUPABASE_URL` y `SUPABASE_SERVICE_ROLE_KEY` configurados en GitHub Secrets.
3. **Referencia:** Consultar [docs/REPLAY_VERIFICATION.md](file:///media/carlos/DATA1/DEV/Balddog-Android-App/docs/REPLAY_VERIFICATION.md).

---

## Protocolo: Emparejamiento Rápido, Baneos y App Links (`048`)

- **Cola y Backfill (`find_or_enqueue_match`):** Siempre priorizar el relleno de salas públicas `waiting` (`player_count DESC, created_at ASC`, excluyendo `banned_user_ids`) antes de crear salas nuevas desde `matchmaking_queue`.
- **Invariante de Privacidad Web (`get_public_open_rooms`):** Nunca exponer IPs (`endpoint`, `local_ip`, `host_endpoint`) ni correos en RPCs anónimos; solo `room_id`, `code`, datos del juego y `username` + `avatar_seed`.
- **App Links (`https://bald.dog/room/<uuid>`):** Mantener `assetlinks.json` sincronizado con el SHA-256 del keystore de release en `Balddog-Main-Webpage/public/.well-known/assetlinks.json` (huella actual: `45:4F:DE:27:03:83:76:78:D6:68:1B:00:10:CD:80:A2:9D:1A:DD:08:C8:5A:94:86:B6:46:D3:FE:7C:09:7A:0B`).
