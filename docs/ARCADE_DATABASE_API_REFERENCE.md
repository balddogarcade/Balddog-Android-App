# Referencia de API: Arcade Database (`adb.arcadeitalia.net`)

Documento de referencia generado a partir de pruebas reales contra el servicio `adb.arcadeitalia.net` usando la ROM `mslug` (Metal Slug - Super Vehicle-001).

---

## 1. Endpoint Principal

```http
GET https://adb.arcadeitalia.net/service_scraper.php?ajax=query_mame&game_name={rom_name}
```

- **Formato de respuesta:** JSON comprimido con **GZIP** (`\x1f\x8b`).
- **Autenticación:** No requiere API key.
- **Estructura raíz del JSON:**
  ```json
  {
    "release": 1,
    "result": [ { ... } ]
  }
  ```

---

## 2. Tupla Completa de Campos Disponibles (`result[0]`)

El objeto dentro de `result[0]` devuelve **34 campos**:

| # | Campo | Tipo | Valor de ejemplo (`mslug`) | Descripción |
|---|-------|------|----------------------------|-------------|
| 1 | `index` | `int` | `0` | Índice del resultado en el array |
| 2 | `url` | `string` | `https://adb.arcadeitalia.net/?mame=mslug` | Ficha web del juego en Arcade Database |
| 3 | `game_name` | `string` | `mslug` | Nombre del romset MAME |
| 4 | `title` | `string` | `Metal Slug - Super Vehicle-001` | Título completo oficial |
| 5 | `short_title` | `string` | `Metal Slug` | Título corto |
| 6 | `cloneof` | `string` | `""` | Romset padre si es un clon (vacío si es parent) |
| 7 | `manufacturer` | `string` | `Nazca` | Fabricante / desarrollador |
| 8 | `year` | `string` | `1996` | Año de lanzamiento |
| 9 | `genre` | `string` | `Platform / Shooter Scrolling` | Género / categoría (en inglés) |
| 10 | `serie` | `string` | `Metal Slug` | Saga / franquicia a la que pertenece |
| 11 | `players` | `int` | `2` | Número máximo de jugadores |
| 12 | `nplayers` | `string` | `2P sim` | Modo de jugadores (ej. `2P sim` = simultáneo, `2P alt` = alternado) |
| 13 | `languages` | `string` | `English` | Idiomas soportados por la ROM |
| 14 | `rate` | `int` | `90` | Puntuación / rating comunitario (0-100) |
| 15 | `status` | `string` | `GOOD` | Estado de emulación en MAME (`GOOD`, `IMPERFECT`, `PRELIMINARY`) |
| 16 | `emulator_id` | `int` | `1034` | ID interno de versión de MAME |
| 17 | `emulator_name` | `string` | `Mame 0.289 (jul-31 2026)` | Versión de MAME de referencia |
| 18 | `input_controls` | `string` | `joystick (8-way)` | Tipo de control principal |
| 19 | `input_buttons` | `int` | `4` | Cantidad de botones de acción |
| 20 | `buttons_colors` | `string` | `P1_COIN:White:;P1_START:White:;P1_BUTTON1:Red:;...` | Mapeo de colores de botones |
| 21 | `screen_orientation` | `string` | `Horizontal` | Orientación de pantalla (`Horizontal` / `Vertical`) |
| 22 | `screen_resolution` | `string` | `320x224px @59.185606Hz` | Resolución nativa y tasa de refresco |
| 23 | `history` | `string` | `SNK Neo-Geo MVS cart. published 30 years ago:...` | Reseña histórica completa (en inglés, de `arcade-history.com`) |
| 24 | `history_copyright_long` | `string` | `Information provided by Arcade History...` | Atribución completa de `history` |
| 25 | `history_copyright_short` | `string` | `(C) arcade-history.com` | Atribución corta de `history` |
| 26 | `youtube_video_id` | `string` | `Qu5OEWVnVXU` | ID de video gameplay en YouTube |
| 27 | `url_video_shortplay` | `string` | `https://adb.arcadeitalia.net/download_file.php?...` | Video corto de gameplay (SD) |
| 28 | `url_video_shortplay_hd` | `string` | `https://adb.arcadeitalia.net/download_file.php?...` | Video corto de gameplay (HD) |
| 29 | `url_image_ingame` | `string` | `https://adb.arcadeitalia.net/?mame=mslug&type=ingame` | Captura in-game |
| 30 | `url_image_title` | `string` | `https://adb.arcadeitalia.net/?mame=mslug&type=title` | Captura de pantalla de título |
| 31 | `url_image_marquee` | `string` | `https://adb.arcadeitalia.net/?mame=mslug&type=marquee` | Arte del marquee |
| 32 | `url_image_cabinet` | `string` | `https://adb.arcadeitalia.net/?mame=mslug&type=cabinet` | Foto/arte del gabinete arcade |
| 33 | `url_image_flyer` | `string` | `https://adb.arcadeitalia.net/?mame=mslug&type=flyer` | Flyer promocional original |
| 34 | `url_icon` | `string` | `https://adb.arcadeitalia.net/?mame=mslug&type=icon` | Ícono del juego |

---

## 3. Otros Endpoints Útiles (Media Directa)

Además del endpoint JSON, las URLs de media siguen el patrón:
- **Imágenes:** `https://adb.arcadeitalia.net/?mame={rom_name}&type={ingame|title|marquee|cabinet|flyer|icon}`
- **Videos Shortplay:** `https://adb.arcadeitalia.net/download_file.php?tipo=mame_current&codice={rom_name}&entity={shortplay|shortplay_hd}&oper=view`
