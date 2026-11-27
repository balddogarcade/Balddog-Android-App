# Guía Técnica: Cómo Agregar Nuevos Juegos a bald.dog ARCADE

Esta guía documenta en profundidad y paso a paso el procedimiento técnico estandarizado para integrar nuevos juegos arcade en el ecosistema **bald.dog ARCADE**. Cubre desde la preparación de ROMs compatibles con FinalBurn Neo y su distribución descentralizada en IPFS (Pinata), hasta el modelado en Supabase, la ingeniería inversa de memoria RAM con `ram_probe` para leaderboards certificados (1CC) y la validación en la app Android.

---

## Índice

1. [Arquitectura General del Sistema](#1-arquitectura-general-del-sistema)
2. [Requisitos y Entorno de Trabajo](#2-requisitos-y-entorno-de-trabajo)
3. [Paso 1: Selección y Validación de la ROM (FBNeo Romset)](#paso-1-selección-y-validación-de-la-rom-fbneo-romset)
4. [Paso 2: Subida y Fijación en IPFS (Pinata Cloud)](#paso-2-subida-y-fijación-en-ipfs-pinata-cloud)
5. [Paso 3: Registro en Base de Datos (Supabase `public.games`)](#paso-3-registro-en-base-de-datos-supabase-publicgames)
6. [Paso 4: Ingeniería Inversa del Puntaje con `ram_probe`](#paso-4-ingeniería-inversa-del-puntaje-con-ram_probe)
   - [Mapeo de memoria y Word-Swapping en FBNeo](#mapeo-de-memoria-y-word-swapping-en-fbneo)
   - [Detección de `P1_ACTIVE`, `CREDITS` y `SCORE`](#detección-de-p1_active-credits-y-score)
   - [Sintaxis de Expresiones `rcheevos`](#sintaxis-de-expresiones-rcheevos)
   - [Prevención de Falsos Positivos de Continue (CoinTracker)](#64-prevención-de-falsos-positivos-de-continue-cointracker-y-validación-de-ciclo-de-vida-completo)
   - [Caso de Estudio: Cadillacs and Dinosaurs](#65-caso-de-estudio-cadillacs-and-dinosaurs-dino)
   - [Script de Diagnóstico para Auditar ROMs](#66-script-de-diagnóstico-para-auditar-el-ciclo-de-vida-de-cualquier-rom)
7. [Paso 5: Pruebas Unitarias del Motor Rust (`balddog-core`)](#paso-5-pruebas-unitarias-del-motor-rust-balddog-core)
8. [Paso 6: Compilación y Verificación en la App Android](#paso-6-compilación-y-verificación-en-la-app-android)
9. [Matriz de Solución de Problemas Comunes](#9-matriz-de-solución-de-problemas-comunes)

---

## 1. Arquitectura General del Sistema

bald.dog ARCADE utiliza una arquitectura híbrida desacoplada:

```
┌────────────────────────────────────────────────────────────────────────┐
│                          Supabase (PostgreSQL)                         │
│  - Catálogo de juegos (slug, rom_crc32, rom_sha256, ipfs_cid, etc.)    │
│  - score_config (lboard expression, max_valid_score, verified)         │
│  - RPCs atómicos de leaderboards (submit_high_score, 1CC enforcement)  │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ Consulta metadatos
                                    ▼
┌───────────────────────┐   Descarga ROM (IPFS)  ┌───────────────────────┐
│     IPFS / Pinata     │───────────────────────▶│     Android Client    │
│ (ROM zips & Covers)   │    (Verifica hashes)   │  (Kotlin + Compose)   │
└───────────────────────┘                        └──────────┬────────────┘
                                                            │ JNI / UniFFI
                                                            ▼
                                                 ┌───────────────────────┐
                                                 │   balddog-core (Rust) │
                                                 │  - libfbneo_libretro  │
                                                 │  - rcheevos evaluator │
                                                 │  - 1CC credit monitor │
                                                 └───────────────────────┘
```

1. **Catálogo & Reglas (`public.games`)**: Supabase define qué juegos existen, su CID en IPFS, sus sumas de verificación de integridad (`crc32`, `sha256`) y la expresión `rcheevos` para extraer el puntaje de la memoria RAM del emulador.
2. **Descarga P2P/IPFS (`IpfsRomManager`)**: La app descarga el archivo `.zip` de la ROM desde IPFS, valida obligatoriamente su tamaño, CRC32 y SHA256 contra la base de datos, y almacena la ROM en el almacenamiento interno de la app (`getFilesDir()/roms/`).
3. **Emulación Nativa (`balddog-core`)**: El core de FinalBurn Neo (`libfbneo_libretro.so`) corre embebido en un hilo de emulación nativo.
4. **Monitoreo 1CC & Leaderboards (`LeaderboardEngine` + `ArcadeSession`)**:
   - Para ser elegible a récord mundial / ranking, la partida debe ser **1CC (One Coin Clear)**: el jugador no puede haber insertado más de 1 crédito (`credits_used <= 1`).
   - En cada cuadro de emulación, `LeaderboardEngine` inspecciona `RETRO_MEMORY_SYSTEM_RAM` evaluando la expresión compilada con la biblioteca `rcheevos`.
   - Cuando el jugador pierde la partida (`SUB` / Game Over) o sale al menú, el puntaje validado se envía a Supabase mediante el RPC seguro `submit_high_score`.

---

## 2. Requisitos y Entorno de Trabajo

Para agregar e inspeccionar un nuevo juego se requieren:

- **Herramientas de construcción Android/Rust**:
  - `source scripts/env.sh` (configura `JAVA_HOME` con JDK 21, `ANDROID_HOME`, `ANDROID_NDK_HOME` y `cargo-ndk`).
- **Core de FBNeo compilado para Linux x86_64** (para diagnóstico rápido en PC sin requerir celular):
  ```bash
  # Si no existe en /tmp/fbneo-linux:
  git clone --depth 1 https://github.com/libretro/FBNeo.git /tmp/fbneo-linux
  cd /tmp/fbneo-linux && git checkout 5f6bdff9451d5c3dedd3d992d7412660eb202cf4
  make -C src/burner/libretro -j$(nproc)
  # Binario generado: /tmp/fbneo-linux/src/burner/libretro/fbneo_libretro.so
  ```
- **Herramienta `ram_probe`**:
  Ubicada en `rust/balddog-core/examples/ram_probe.rs`. Se compila con:
  ```bash
  cargo build --manifest-path rust/Cargo.toml --example ram_probe
  # Binario resultante: rust/target/debug/examples/ram_probe
  ```
- **Credenciales en `local.properties` (gitignored)**:
  - `SUPABASE_PROJECT_ID` y `SUPABASE_ACCESS_TOKEN` (para aplicar migraciones).
  - Credenciales de Pinata (JWT Bearer o API Key + Secret Key).

---

## 3. Paso 1: Selección y Validación de la ROM (FBNeo Romset)

FinalBurn Neo requiere nombres de archivo y contenidos ROM específicos según su base de datos interna.

### 3.1 Nombre y tipo de Set
- El nombre del archivo `.zip` debe coincidir **exactamente** con el driver de FBNeo (ej. `captcomm.zip`, `dino.zip`, `ssriders.zip`).
- Preferir siempre **Parent Sets** (sets completos independientes) o Non-Merged sets para evitar dependencias de ROMs padre faltantes.
- Si el juego corre sobre un sistema con BIOS (como Neo Geo `neogeo.zip`), verificar que la BIOS esté incluida dentro del propio `.zip` de la ROM.

### 3.2 Extracción de hashes de integridad
Calcula el tamaño exacto en bytes, el CRC32 (en formato hexadecimal minúscula de 8 caracteres) y el SHA256 (64 caracteres hexadecimales):

```bash
python3 -c "
import zlib, hashlib, sys, os
path = sys.argv[1]
data = open(path, 'rb').read()
crc = f'{zlib.crc32(data) & 0xffffffff:08x}'
sha = hashlib.sha256(data).hexdigest()
size = len(data)
print(f'Filename: {os.path.basename(path)}')
print(f'Size:     {size} bytes')
print(f'CRC32:    {crc}')
print(f'SHA256:   {sha}')
" /ruta/a/tu/rom.zip
```

---

## 4. Paso 2: Subida y Fijación en IPFS (Pinata Cloud)

Todas las ROMs y portadas se sirven a los clientes a través de IPFS fijadas en Pinata Cloud.

### 4.1 Subir la ROM a Pinata Cloud
Utiliza la API REST de Pinata (`pinFileToIPFS`):

```bash
curl -X POST https://api.pinata.cloud/pinning/pinFileToIPFS \
  -H "Authorization: Bearer <PINATA_JWT>" \
  -F "file=@/ruta/a/tu/rom.zip;filename=<slug>.zip" \
  -F 'pinataMetadata={"name":"<slug>.zip"}'
```

*(Si utilizas claves de API tradicionales, usa los encabezados `pinata_api_key: <KEY>` y `pinata_secret_api_key: <SECRET>`)*.

La respuesta JSON contiene el `IpfsHash` (CID, típicamente `Qm...`):
```json
{
  "IpfsHash": "QmXyz123...",
  "PinSize": 1234567,
  "Timestamp": "2026-11-22T04:00:00.000Z"
}
```

### 4.2 Probar disponibilidad del CID
Verifica que el CID responda correctamente en múltiples gateways:
```bash
curl -sI "https://gateway.pinata.cloud/ipfs/<CID>" | head -n 5
curl -sI "https://ipfs.io/ipfs/<CID>" | head -n 5
curl -sI "https://dweb.link/ipfs/<CID>" | head -n 5
```

---

## 5. Paso 3: Registro en Base de Datos (Supabase `public.games`)

Crea un script de migración SQL en `supabase/migrations/` siguiendo la convención de numeración (ej. `013_add_new_game.sql`).

```sql
INSERT INTO public.games (
    slug,
    title,
    system,
    rom_filename,
    ipfs_cid,
    sha256,
    crc32,
    size_bytes,
    max_players,
    is_coop,
    parent_game_id,
    bios_id,
    cover_url,
    max_valid_score,
    score_config
) VALUES (
    'slug_juego',
    'Título del Juego',
    'cps2',
    'slug_juego.zip',
    'QmXyz123...',
    'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
    '1a2b3c4d',
    12345678,
    4,
    true,
    null,
    null,
    'https://<SUPABASE_PROJECT_ID>.supabase.co/storage/v1/object/public/covers/slug_juego.webp',
    99999990,
    jsonb_build_object(
        'max_valid_score', 99999990,
        'verified', false
    )
)
ON CONFLICT (slug) DO UPDATE SET
    title = EXCLUDED.title,
    size_bytes = EXCLUDED.size_bytes,
    crc32 = EXCLUDED.crc32,
    sha256 = EXCLUDED.sha256,
    ipfs_cid = EXCLUDED.ipfs_cid,
    max_valid_score = EXCLUDED.max_valid_score;
```

> [!NOTE]
> Las portadas principales (`cover_url`) se sirven en formato `.webp` desde el bucket público `covers` en Supabase Storage para carga rápida en el catálogo. Para subir una portada nueva ejecuta `python3 scripts/upload-covers-supabase.py --dir <carpeta> --slug <slug>`. Las ROMs (`ipfs_cid`) y las imágenes secundarias de detalle (`url_image_*`, `url_icon`) permanecen en IPFS (Pinata).

---

## 6. Paso 4: Ingeniería Inversa del Puntaje con `ram_probe`

Para que el sistema de leaderboards funcione de forma 100% segura y automática, necesitamos extraer el puntaje de la memoria RAM del emulador (`RETRO_MEMORY_SYSTEM_RAM`) sin depender de la entrada del usuario ni de reconocedores ópticos.

### 6.1 Mapeo de memoria y Word-Swapping en FBNeo
La mayoría de los arcades de 16 y 32 bits (Capcom CPS-1, CPS-2, Neo Geo MVS, Konami, Sega System 16/18) utilizan CPUs Motorola 68000 o derivadas.
En el core Libretro de FBNeo, la memoria principal (`RETRO_MEMORY_SYSTEM_RAM`) se almacena en formato **word-swapped de 16 bits**:
- Una palabra de 32 bits en Big-Endian `[B3, B2, B1, B0]` se almacena en el búfer de bytes de FBNeo en el orden `[B2, B3, B0, B1]`.
- **Valores BCD (Binary Coded Decimal)**: Un puntaje como `1800` en BCD se representa como bytes `[0x00, 0x00, 0x00, 0x18]`. En memoria word-swapped, los 2 bytes más altos están en el offset `+0` y los 2 bytes más bajos en el offset `+2`.

### 6.2 Uso de `ram_probe` para encontrar direcciones exactas

1. **Escanear y generar captura de pantalla inicial:**
   ```bash
   BIN=rust/target/debug/examples/ram_probe
   CORE=/tmp/fbneo-linux/src/burner/libretro/fbneo_libretro.so

   $BIN $CORE /ruta/a/rom.zip /tmp/fbneo-sys /tmp/fbneo-save --scan /tmp/probe_juego.ppm
   ```
   Convierte el archivo `.ppm` a `.png` para visualizar el puntaje en el HUD:
   ```bash
   python3 -c "from PIL import Image; Image.open('/tmp/probe_juego.ppm').save('/tmp/probe_juego.png')"
   ```

2. **Abrir la imagen e identificar el puntaje en pantalla:**
   Supongamos que el HUD de Player 1 muestra `2800` puntos.

3. **Buscar ese valor numérico en la memoria RAM con `find:`:**
   ```bash
   $BIN $CORE /ruta/a/rom.zip /tmp/fbneo-sys /tmp/fbneo-save --scan find:2800
   ```
   `ram_probe` analiza todos los formatos de codificación en RAM:
   - `bcd4_sw`: BCD de 4 bytes con word-swapping (el estándar en CPS/NeoGeo/Konami).
   - `bcd4_be`: BCD de 4 bytes en Big-Endian lineal.
   - `u32_sw`: Entero binario de 32 bits word-swapped.
   - `u32_be`: Entero binario de 32 bits Big-Endian lineal.

4. **Identificar banderas de jugador activo (`P1_ACTIVE`):**
   `ram_probe --scan` rastrea automáticamente qué bytes en RAM transicionan de `0 -> 0 -> 1` al iniciar una partida tras insertar moneda y pulsar Start.
   Uno de esos bytes es la bandera de "Player 1 Active/Alive" (cuando vale 1, P1 está jugando; cuando vuelve a 0, P1 murió o se acabó la partida).

### 6.3 Sintaxis de Expresiones `rcheevos`

El estándar RetroAchievements (`rcheevos`) adoptado por bald.dog utiliza cadenas estructuradas con prefijos:

- `STA:<condición>`: **Start**. Cuándo se activa el seguimiento de la tabla.
  - Ejemplo: `STA:0xH0820=1` (activa cuando el byte en offset `0x0820` es igual a 1).
- `CAN:<condición>`: **Cancel**. Cuándo se anula la partida (ej. `CAN:0=1`, nunca cancelar automáticamente).
- `SUB:<condición>`: **Submit**. Cuándo se consolida y envía el puntaje final.
  - Ejemplo: `SUB:0xH0820=0` (envía cuando el byte `P1_ACTIVE` vuelve a 0 al morir o terminar el juego).
- `VAL:<cálculo>`: **Value**. Cómo se calcula el valor entero del puntaje.
  - **BCD Word-Swapped (32-bit)**:
    `VAL:A:b0x <OFFSET_HI>*10000_M:b0x <OFFSET_LO>`
    *(Nota crítica: `b0x ` lleva un espacio tras la `x` para indicar lectura de 16 bits en `rcheevos`; `b` indica decodificación BCD).*
  - **Entero Binario Word-Swapped (32-bit)**:
    `VAL:A:0x <OFFSET_HI>*65536_M:0x <OFFSET_LO>`
  - **Byte simple BCD**:
    `VAL:b0xH<OFFSET>`

### 6.4 Prevención de Falsos Positivos de Continue (CoinTracker) y Validación de Ciclo de Vida Completo

> [!CAUTION]
> **Peligro del escaneo estático de un solo frame (`0 -> 0 -> 1`):**
> Al iniciar partida, docenas de bytes en RAM transicionan a `1` (temporizadores de sprites, animaciones iniciales, punteros de interfaz, selección de personajes). Si configuras `STA` o `SUB` con un byte que no sea la bandera maestra del ciclo de vida, **el ranking 1CC se descalificará falsamente**.

#### ¿Cómo opera el detector 1CC (`CoinTracker`)?
En `rust/balddog-core/src/session.rs`, el monitor `CoinTracker` vigila tanto los bitmasks crudos de Libretro (`RETRO_COIN_MASK` y `RETRO_START_MASK` en `P1..P4`) desde el **frame 0** como las transiciones de la bandera `active` reportada por `rcheevos`:
- Cuenta toda inserción de moneda (`total_coins`) desde el arranque, pantalla de título y selección de personaje: si se cargan **2 o más fichas** antes o durante la partida, `credits_used` sube a `>= 2` y la sesión queda descalificada de inmediato.
- Cuando el jugador pulsa `START` e inicia la partida (`active == true`), `CoinTracker` fija `runs_started = 1`.
- Si durante la partida la condición de `rcheevos` cae a `0` (disparando `SUB`) y luego vuelve a `1` (disparando `STA`) tras un input de continue (`COIN` o `START`), `CoinTracker` detecta el continue (`runs_started += 1`), congela los puntajes y bloquea permanentemente cualquier segundo récord en la sesión.

#### Requisitos para elegir la condición `STA` y `SUB`
1. **Inactividad en Boot / Attract / Menú de Selección:** La condición `STA` **no debe cumplirse** mientras el core esté en demo o en la pantalla de selección de personajes.
2. **Estabilidad absoluta en juego:** Una vez iniciada la Fase 1, la condición debe permanecer activa ininterrumpidamente durante golpes, caídas al suelo, ataques especiales, recogida de armas y transiciones entre fases.
3. **Disparo exclusivo en Game Over:** `SUB` debe activarse **únicamente** cuando se pierde la última vida y el juego pasa a la pantalla de Continue o Game Over.
4. **Priorizar variables de estado de máquina (`kStep` / Master State):**
   - En placas Capcom (CPS-1, CPS-2): Usar la variable maestra de estado del juego (`kStep`, en M68K `0xFF84D0`, que en FBNeo word-swapped corresponde a `0x84D1`). Por ejemplo, en CPS-1: `0x04` = selección de personaje, `0x0C` (12) = jugando, `0x10` (16) = Game Over / Continue.
   - En otras placas (NeoGeo, Konami, Irem): Combinar el flag de jugador en partida (`kInPlay`) con el contador de vidas o el registro de modo del juego.

---

### 6.5 Caso de Estudio: Cadillacs and Dinosaurs (`dino`)

En la integración de *Cadillacs and Dinosaurs*, se observaron dos fallos consecutivos antes de llegar a la solución definitiva:
1. **Fallo con `0xB345` (offset `+0xD0` en P1):**
   `0xB345` era una variable interna de animación del sprite. Al recibir un golpe fuerte o realizar un ataque especial, caía a `0`, disparando `SUB` a mitad de la fase y reiniciando `CoinTracker` a 2 créditos.
2. **Fallo con `0xB275` (offset `+0x00` en P1):**
   Aunque `0xB275` parecía el flag de `InPlay`, la traza frame a frame reveló que en la pantalla de selección de personajes oscilaba:
   - Frame 1455: `0xB275 = 01` (se activa `STA`).
   - Frame 1460 (5 frames después): `0xB275 = 00` (se activa `SUB`).
   - Frame 1623 (al confirmar personaje): `0xB275 = 01` (se reactiva `STA`, disparando `runs_started = 2`).
3. **Solución definitiva con `0xB274` + `kStep` (`0x84D1`):**
   - `0xB274` permanece en `00` durante boot y selección de personaje, y pasa a `01` continuo al comenzar la Fase 1.
   - `0x84D1` (`kStep`) vale `12` (`0x0C`) únicamente durante la partida activa, y cambia a `16` (`0x10`) en Game Over.
   - Expresión final certificada:
     ```
     STA:0xHB274=1_0xH84D1=12::CAN:0=1::SUB:0xH84D1=16::VAL:A:b0x B374*10000_M:b0x B376
     ```

---

### 6.6 Script de Diagnóstico para Auditar el Ciclo de Vida de Cualquier ROM

Para certificar cualquier juego del catálogo sin depender de pruebas manuales a ciegas en el celular, utiliza este script de Python con `ctypes` contra `/tmp/fbneo-linux/src/burner/libretro/fbneo_libretro.so`:

```python
import ctypes, os

core = ctypes.CDLL("/tmp/fbneo-linux/src/burner/libretro/fbneo_libretro.so")
# Configurar callbacks de libretro (environment, video, audio, input)...
# Cargar la ROM con retro_load_game y obtener puntero con retro_get_memory_data(2)

# Ejecutar frames simulando botones:
# 1. 1400 frames de boot / attract
# 2. Pulsar COIN (BTN_SELECT) y START
# 3. Avanzar frames en selección de personaje y observar si los bytes candidatos oscilan
# 4. Iniciar nivel 1, ejecutar ataques/movimientos y confirmar estabilidad del flag
# 5. Dejar que los enemigos maten al personaje hasta la pantalla de Game Over / Continue
# 6. Constatar qué byte cambia de forma unívoca de 'jugando' a 'Game Over'
```

---

### 6.7 Ejemplo Real: Sunset Riders (`ssriders`)
- RAM total: 39,040 bytes.
- P1 activo al iniciar: `0x004C` (`0 -> 1`).
- Score BCD word-swapped en `0x0206` (HI) y `0x0208` (LO) mostrando `$300` en pantalla (`raw=[00, 00, 00, 03]`).
- Expresión final:
  ```
  STA:0xH004C=1::CAN:0=1::SUB:0xH004C=0::VAL:A:b0x 0206*10000_M:b0x 0208
  ```

---

## 7. Paso 5: Pruebas Unitarias del Motor Rust (`balddog-core`)

Cada nueva expresión `score_config` debe incorporarse a las pruebas automatizadas del motor para garantizar que el compilador `rcheevos` la analice sin errores de sintaxis ni desbordamientos.

Edita `rust/balddog-core/src/retro/leaderboard.rs`:

```rust
#[test]
fn parses_all_cps_family_leaderboard_expressions() {
    let defs = [
        // ... expresiones existentes ...
        "STA:0xH004C=1::CAN:0=1::SUB:0xH004C=0::VAL:A:b0x 0206*10000_M:b0x 0208", // ssriders
        "STA:0xH0820=1::CAN:0=1::SUB:0xH0820=0::VAL:A:b0x 2000*10000_M:b0x 2002", // altbeast
    ];
    for def in defs {
        LeaderboardEngine::new(def).unwrap_or_else(|e| panic!("Falló {def}: {e}"));
    }
}
```

Ejecuta los tests unitarios:
```bash
cargo test --manifest-path rust/Cargo.toml -p balddog-core leaderboard
```

---

## 8. Paso 6: Compilación y Verificación en la App Android

### 8.1 Actualizar `score_config` en Supabase
Una vez validada la expresión con `ram_probe` y el test de Rust, aplica la actualización en Supabase (actualizando tanto `score_config` como la columna de validación `max_valid_score`):

```sql
UPDATE public.games
SET max_valid_score = 99999999,
    score_config = jsonb_build_object(
        'lboard', 'STA:0xH0820=1::CAN:0=1::SUB:0xH0820=0::VAL:A:b0x 2000*10000_M:b0x 2002',
        'max_valid_score', 99999999,
        'verified', true
    )
WHERE slug = 'altbeast';
```

### 8.2 Compilar e Instalar el APK en Celular
Con el teléfono conectado por USB con depuración habilitada:

```bash
# 1. Compilar e instalar APK debug
source scripts/env.sh && ./gradlew installDebug

# 2. Abrir la app en el dispositivo
adb shell am start -n dog.bald.arcade.debug/dog.bald.arcade.MainActivity
```

### 8.3 Lista de Chequeo de Prueba en Dispositivo
1. **Descarga de la ROM**: Presiona sobre el juego en la pestaña Arcade; la barra de progreso debe completar la descarga desde IPFS sin error de checksum.
2. **Arranque en FBNeo**: El juego debe iniciar con video y audio fluidos.
3. **Elegibilidad 1CC**: Inserta solo 1 moneda (botón SELECT) y pulsa START. Abre el menú rápido (gesto hacia abajo o botón de menú); el panel superior debe mostrar `1CC: ELIGIBLE`.
4. **Lectura de Puntaje en Vivo**: Juega unos momentos y gana puntos. En el menú rápido, el campo `Live Score` debe reflejar exactamente el puntaje visto en pantalla.
5. **Fin de Partida**: Deja morir a tu personaje hasta que termine el contador de continuar.
6. **Persistencia en Leaderboard**: Sal del juego; el logcat (`adb logcat -s BaldDogScore:D ArcadeSession:D`) confirmará el envío:
   ```text
   Score submission successful for <slug>: score=XXXX
   ```
7. Abre la hoja de Leaderboards del juego y confirma que tu usuario y puntaje aparezcan en el Top 50 y en tu tarjeta de rango personal.

---

## 9. Matriz de Solución de Problemas Comunes

| Síntoma | Causa Raíz | Solución |
|---|---|---|
| **FBNeo falla al cargar (`Failed to load game`)** | Faltan ROMs en el zip, o es un clon que requiere el set padre, o falta BIOS (ej. `neogeo.zip`). | Verificar con FBNeo standalone que el zip sea un Parent Set completo de la versión exacta de FBNeo. |
| **Error de checksum al descargar en Android** | El archivo en IPFS no coincide con `rom_crc32` o `rom_sha256` en `public.games`. | Recalcular CRC32 y SHA256 con el script de Python y actualizar la fila en `public.games`. |
| **El puntaje en el menú rápido siempre muestra 0** | La dirección de memoria en `score_config` es incorrecta o usa sintaxis LE en lugar de BCD word-swapped. | Ejecutar `ram_probe --scan find:<score>` con el puntaje exacto visible en pantalla para identificar el offset correcto. |
| **1CC muestra `DISQUALIFIED (>1 CREDIT)`** | El jugador insertó 2 o más monedas en la misma sesión. | Por diseño competitivo, los récords solo son válidos bajo la regla 1CC (1 sola ficha). |
| **Error 401 Unauthorized al subir a Pinata** | El JWT expiró o se usó solo la API Key pública sin la clave secreta. | Pinata exige JWT Bearer completo o el par `pinata_api_key` + `pinata_secret_api_key`. |

---

## 10. Auditoría de Catálogo sin `score_config`

Los juegos sin `score_config->>'lboard'` funcionan en **Modo Libre** (registran tiempo jugado e historial de partidas, sin grabar replays ni participar en leaderboards 1CC).

Para consultar en Supabase qué títulos del catálogo aún requieren certificación empírica con `ram_probe`:

```sql
select slug, title, system
from public.games
where coalesce(score_config->>'lboard', '') = ''
order by slug;
```

Actualmente (tras las migraciones `008`–`043`), 26 de los 30 títulos curados cuentan con `score_config` verificado; restan por certificar con ROM local y `ram_probe`: `btoads`, `nbajam`, `spidman`, `wwfwfestu`.

