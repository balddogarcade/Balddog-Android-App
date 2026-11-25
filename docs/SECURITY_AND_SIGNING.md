# Seguridad, Firma de Releases y Auditoría Continua

Este documento detalla la gestión del Keystore de producción, los secrets requeridos en GitHub Actions y los controles de seguridad del repositorio.

---

## 1. Firma Consistente con Keystore

`app/build.gradle.kts` configura `signingConfigs.release` priorizando variables de entorno (CI) y haciendo fallback a `local.properties` (local):

| Variable / Propiedad | Uso |
|---|---|
| `ANDROID_KEYSTORE_PATH` | Ruta al archivo `.jks` o `.keystore` |
| `ANDROID_KEYSTORE_PASSWORD` | Contraseña del keystore |
| `ANDROID_KEY_ALIAS` | Alias de la clave de firma |
| `ANDROID_KEY_PASSWORD` | Contraseña del alias |

> **Nota:** Por compatibilidad con configuraciones locales previas, Gradle también acepta `BALDDOG_KEYSTORE_*` como fallback si `ANDROID_KEYSTORE_*` no está definido. Nunca se debe commitear un `.jks`, `.keystore` ni `local.properties`.

---

## 2. GitHub Secrets Requeridos (`Settings > Secrets and variables > Actions`)

| Secret | Descripción |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | Keystore `.jks`/`.keystore` codificado en Base64 (`base64 -w 0 release.keystore`) |
| `ANDROID_KEYSTORE_PASSWORD` | Contraseña del keystore |
| `ANDROID_KEY_ALIAS` | Alias de la clave de firma |
| `ANDROID_KEY_PASSWORD` | Contraseña del alias |
| `SUPABASE_URL` | URL del proyecto Supabase para `BuildConfig` |
| `SUPABASE_ANON_KEY` | Anon key pública de Supabase para `BuildConfig` |
| `GOOGLE_SERVICES_JSON_BASE64` | `app/google-services.json` en Base64 para Firebase/Crashlytics |
| `VIRUSTOTAL_API_KEY` | API key v3 de VirusTotal (opcional; referenciada en `local.properties`) |

---

## 3. Controles de Seguridad en CI/CD

### GitHub Build Provenance (SLSA)
`.github/workflows/release.yml` ejecuta `actions/attest-build-provenance@v2` sobre `app-release.apk`, generando una atestación criptográfica verificable con `gh attestation verify app-release.apk --owner balddogarcade`.

### VirusTotal API v3 (Informativo)
El workflow sube `app-release.apk` a VirusTotal y adjunta el enlace `https://www.virustotal.com/gui/file/<sha256>` en el cuerpo del Release. Este paso usa `continue-on-error: true` para no bloquear publicaciones ante falsos positivos heurísticos comunes en binarios nativos NDK/Rust.

### Auditoría Continua (`.github/workflows/security.yml` y `.github/dependabot.yml`)
- **`cargo-audit`:** Analiza `rust/Cargo.lock` en pushes/PRs a `main` y semanalmente.
- **GitHub CodeQL:** Análisis estático para `java-kotlin`.
- **Dependabot:** Actualizaciones semanales automáticas para `gradle`, `cargo` (`/rust`) y `github-actions`.
