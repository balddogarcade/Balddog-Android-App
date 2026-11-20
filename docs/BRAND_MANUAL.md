# bald.dog ARCADE — Brand Manual & Design System

**Version:** 1.0  
**Scope:** Android Mobile Application (`dog.bald.arcade`)  
**Aesthetic Direction:** High-Contrast Monochrome Editorial Arcade

---

## 1. Brand Philosophy

**bald.dog ARCADE** pairs classic arcade preservation with a stark, modern, gallery-grade monochrome interface. By stripping away neon distractions from the chrome, **the full-color arcade cabinet posters and gameplay become the focal point** of every screen.

### Core Principles
1. **Poster-First Hierarchy:** Game artwork is presented uncropped at full vertical height (`3:4` aspect ratio) inside a quiet, dark architectural frame.
2. **High-Contrast Clarity:** Pure black surfaces, elevated charcoal cards, hairline borders, and crisp white typography ensure instant legibility on OLED displays.
3. **Offline-First Craft:** All brand fonts and cover assets are bundled locally inside the APK—zero runtime font fetching, zero layout shift.
4. **Precision & Restraint:** Information is organized through consistent spacing, micro-badges, and fixed-height typography rather than decorative color.

---

## 2. Logo & App Icon Usage

### Top Navigation Bar Mark
- The top bar replaces the legacy `"bald.dog ARCADE"` text header with the official app mark (`ic_launcher_foreground`).
- **Container Height:** `36.dp`–`40.dp` with `ContentScale.Fit`, aligned to the start of the top bar.
- **Accessibility:** Always provide `contentDescription = "bald.dog ARCADE"`.
- **Clear Space:** Maintain at least `12.dp` vertical breathing room and `16.dp` horizontal screen margin.
- **Auth & Splash Screens:** May pair the icon (`72.dp`–`96.dp`) with a clean monochrome wordmark (`bald.dog ARCADE`) using `Space Grotesk Bold`.

---

## 3. Color System (High-Contrast Grayscale)

All UI chrome uses an achromatic scale engineered for OLED contrast and WCAG AA/AAA readability.

### Core Palette Tokens

| Token Name | Hex | Role | Contrast vs `#050506` |
| :--- | :--- | :--- | :--- |
| `ObsidianBlack` | `#050506` | Primary app background & canvas | — |
| `CarbonSurface` | `#101012` | Cards, bottom navigation bar, dialogs | 1.1:1 (elevation) |
| `GraphiteSurface` | `#1A1A1E` | Search bars, chips, active nav pill, secondary containers | 1.3:1 (elevation) |
| `CharcoalBorder` | `#2A2A30` | Default `1.dp` hairline card & divider borders | — |
| `SteelBorder` | `#44444E` | Focused inputs, active chip borders | — |
| `MutedGray` | `#8E8E98` | Secondary metadata, unselected icons, placeholders | 5.8:1 (AA) |
| `SilverGray` | `#C8C8D0` | Secondary body text, inactive labels | 11.9:1 (AAA) |
| `PureWhite` | `#FAFAFA` | Primary headings, primary CTA background, active nav icons | 19.3:1 (AAA) |
| `PureBlack` | `#000000` | Text & icons placed on `PureWhite` primary buttons | 19.3:1 (AAA) |

### Material 3 Mapping
- `background` / `onBackground`: `ObsidianBlack` / `PureWhite`
- `surface` / `onSurface`: `CarbonSurface` / `PureWhite`
- `surfaceVariant` / `onSurfaceVariant`: `GraphiteSurface` / `MutedGray`
- `primary` / `onPrimary`: `PureWhite` / `PureBlack`
- `secondary` / `onSecondary`: `SilverGray` / `PureBlack`
- `tertiary` / `onTertiary`: `GraphiteSurface` / `PureWhite`
- `outline` / `outlineVariant`: `SteelBorder` / `CharcoalBorder`

### State & Feedback Rules (Monochrome)
- **Primary Action (`PLAY`, `CREATE`, `READY`):** Solid `PureWhite` (`#FAFAFA`) fill with `PureBlack` (`#000000`) bold label.
- **Secondary Action (`DOWNLOAD`, `REFRESH`, `CHANGE`):** `GraphiteSurface` (`#1A1A1E`) fill with `1.dp` `SteelBorder` (`#44444E`) and `PureWhite` label.
- **Destructive / Critical Confirmation (`DELETE ROM`, `SIGN OUT`):** Outlined `CarbonSurface` with `PureWhite` icon/label inside a mandatory confirmation dialog for irreversible actions.
- **Progress Indicators:** `PureWhite` track fill over `GraphiteSurface` track.

---

## 4. Typography System

All fonts are bundled as static `.ttf` files in `app/src/main/res/font/`.

| Role | Font Family | Weights Bundled | Usage |
| :--- | :--- | :--- | :--- |
| **Display & Headings** | `Space Grotesk` | `Medium (500)`, `SemiBold (600)`, `Bold (700)` | Screen titles, card titles, badges, primary buttons |
| **Body & UI** | `Inter` | `Regular (400)`, `Medium (500)`, `SemiBold (600)` | Descriptions, search input, nav labels, dialogs |
| **Technical / Mono** | `JetBrains Mono` | `Regular (400)`, `Medium (500)` | Room codes (`#4821A`), file sizes (`12.4 MB`), SHA-256, Rust logs |

### Type Scale
- `headlineMedium`: `Space Grotesk Bold`, `22.sp`, tracking `-0.3.sp`
- `titleMedium`: `Space Grotesk SemiBold`, `15.sp`, line-height `20.sp`
- `titleSmall`: `Space Grotesk SemiBold`, `13.sp`, line-height `17.sp` (Card titles)
- `bodyMedium`: `Inter Regular`, `13.sp`, line-height `18.sp`
- `bodySmall`: `Inter Regular`, `12.sp`, line-height `16.sp`
- `labelLarge`: `Space Grotesk Bold`, `12.sp`, uppercase, tracking `0.6.sp` (Buttons)
- `labelMedium`: `Inter Medium`, `11.sp`, tracking `0.2.sp` (Nav & filter chips)
- `labelSmall`: `JetBrains Mono Medium`, `10.sp`, tracking `0.4.sp` (Badges & technical tags)

---

## 5. Grid, Card Anatomy & Badges

### 2-Column Grid (Catalog & Library)
- **Layout:** `LazyVerticalGrid(columns = GridCells.Fixed(2))`
- **Screen Horizontal Padding:** `16.dp`
- **Grid Gutters:** `12.dp` horizontal, `12.dp` vertical.

### Card Anatomy (`ArcadeGameCard`)
Each card uses `CarbonSurface` (`#101012`), `RoundedCornerShape(14.dp)`, and a `1.dp` `CharcoalBorder` (`#2A2A30`):
1. **Poster Stage (Top):**
   - Aspect ratio `3f / 4f` (`width : height = 3 : 4`).
   - Renders `GameCoverImage` with `ContentScale.Fit` on an `ObsidianBlack` (`#050506`) backdrop so the **entire vertical height of the flyer is visible** without cropping.
   - Top-left overlay badge: **System** (`NEOGEO`, `CPS2`) in high-contrast dark pill (`ObsidianBlack` 82% alpha + `1.dp` `SteelBorder`).
   - Top-right overlay (Library only): subtle circular `Delete` icon button (`ObsidianBlack` 78% alpha) triggering a confirmation dialog.
2. **Metadata Block (Middle, `10.dp` padding):**
   - **Title:** `titleSmall` (`Space Grotesk SemiBold`, `13.sp`), `minLines = 2`, `maxLines = 2`, `TextOverflow.Ellipsis` to guarantee uniform row heights across columns.
   - **Badge Row:** Compact `FlowRow` / `Row` with `4.dp` gap:
     - Player count badge (`2P`, `4P`)
     - `CO-OP` badge (when `isCoop == true`)
     - Size badge in `JetBrains Mono` (`e.g. 24 MB`, when `sizeBytes > 0`)
3. **Action Footer (Bottom):**
   - **Catalog:** Full-width `DOWNLOAD` button (`GraphiteSurface` + `PureWhite` border/text), transitioning to an inline progress bar + percentage when actively downloading.
   - **Library:** Full-width `PLAY` button (`PureWhite` fill + `PureBlack` bold text).

### Badge Taxonomy
- **Shape:** `RoundedCornerShape(6.dp)`
- **Padding:** `horizontal = 6.dp, vertical = 2.dp`
- **Variants:**
  - *Solid Contrast:* `PureWhite` bg / `PureBlack` text (for primary status like `CO-OP` or `READY`)
  - *Outline:* `GraphiteSurface` bg / `CharcoalBorder` `1.dp` / `SilverGray` text (for `2P`, `NEOGEO`, `MB`)

---

## 6. Quick Search Bar Specification

- Present at the top of both **Catalog** and **Library**.
- **Container:** `CarbonSurface` (`#101012`), `RoundedCornerShape(12.dp)`, `1.dp` `CharcoalBorder` (`SteelBorder` when focused).
- **Elements:** Leading `Search` icon (`MutedGray`), single-line `Inter` input (`PureWhite`), trailing `Close` (`X`) icon button when query is non-empty.
- **Behavior:** Instant client-side filtering matching `title`, `system`, or `slug`; `ImeAction.Search` dismisses the keyboard.

---

## 7. In-Game Overlay & Touch Controls

- Overlays (`ArcadeTouchOverlay`, `InGameQuickMenu`) use translucent `ObsidianBlack` (`75%–85%` alpha), `CarbonSurface` panels, and `PureWhite` / `SilverGray` strokes (`1.dp–1.5.dp`) so controls stay legible over bright arcade frames without visual clashing.
