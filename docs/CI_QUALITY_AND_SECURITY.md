# Guía de Calidad de Código, Seguridad y Toolchain CI

Este documento centraliza todas las configuraciones de **seguridad**, **calidad de código** y **toolchain** implementadas en `balddogarcade/Balddog-Android-App` (Android/Kotlin + Rust + GitHub Actions).

---

## 1. Workflows de Seguridad (`.github/workflows/security.yml`)

El workflow `Security Audit` se ejecuta en cada `push` a `main`, `pull_request` hacia `main` y semanalmente (`cron: '0 6 * * 1'`).

> [!IMPORTANT]
> **Invariante de permisos para OpenSSF Scorecard:** El bloque global del workflow **DEBE** mantenerse en `permissions: read-all`. Los permisos `security-events: write` e `id-token: write` se declaran únicamente dentro de los jobs que suben reportes SARIF.

| Job | Herramienta | Qué analiza | Configuración |
| :--- | :--- | :--- | :--- |
| `secrets-scan` | **Gitleaks** (`gitleaks/gitleaks-action@v2`) | Historial completo (`fetch-depth: 0`) buscando tokens, API keys y credenciales | Automática |
| `rust-audit` | **`cargo-audit`** (`rustsec/audit-check@v2`) | Vulnerabilidades conocidas (RustSec) en `rust/Cargo.lock` | `rust/Cargo.toml` |
| `rust-audit` | **`cargo-deny`** (`EmbarkStudios/cargo-deny-action@v2`) | Advisories, licencias permitidas y fuentes de crates en Rust | [rust/deny.toml](file:///media/carlos/DATA1/DEV/Balddog-Android-App/rust/deny.toml) |
| `trivy-scan` | **Trivy** (`aquasecurity/trivy-action@0.35.0`) | Vulnerabilidades `CRITICAL,HIGH` en dependencias Gradle/Cargo y configs (SARIF) | Automática |
| `scorecard` | **OpenSSF Scorecard** (`ossf/scorecard-action@v2.4.1`) | Postura de seguridad del repo (permisos, acciones, branch protection) (SARIF) | `publish_results: true` |
| `codeql` | **GitHub CodeQL** (`github/codeql-action/*@v3`) | Análisis semántico de seguridad en Kotlin / Java | Automática |

### Invariante de `cargo-deny` (`rust/deny.toml`)
- Los crates internos del workspace (`balddog-core`, `balddog-netplay`, `balddog-android`) deben mantener `publish = false` en sus `Cargo.toml` y `private = { ignore = true }` en `rust/deny.toml` para que `cargo-deny check licenses` no falle por ausencia de licencia pública.

---

## 2. Workflows de Calidad de Código (`.github/workflows/quality.yml`)

El workflow `Code Quality` se ejecuta en cada `push` y `pull_request` hacia `main`.

| Job | Herramienta | Qué analiza | Configuración |
| :--- | :--- | :--- | :--- |
| `rust-lint` | **`rustfmt`** + **`cargo clippy`** + **`autofix.ci`** | Formato y lints de Rust (`--all-targets`); en PRs `autofix-ci/action@v1` aplica formato automático | `rust/Cargo.toml` |
| `kotlin-lint` | **Detekt** (`1.23.8`) + **ktlint** | Complejidad, *anti-patterns* Compose y estilo Kotlin | [config/detekt/detekt.yml](file:///media/carlos/DATA1/DEV/Balddog-Android-App/config/detekt/detekt.yml) |
| `qodana` | **JetBrains Qodana Community** (`jetbrains/qodana-jvm-community:2025.1`) | Inspecciones profundas de IntelliJ/Android (sube SARIF a GitHub Security) | [qodana.yaml](file:///media/carlos/DATA1/DEV/Balddog-Android-App/qodana.yaml) |
| `sonarcloud` | **SonarCloud** (`SonarSource/sonarqube-scan-action@v6`) | Deuda técnica, *code smells* y *Quality Gate* para Kotlin y Rust | [sonar-project.properties](file:///media/carlos/DATA1/DEV/Balddog-Android-App/sonar-project.properties) |

### Exclusión de código autogenerado (UniFFI)
- Tanto **Detekt** (`source.setFrom(files("src/main/java/dog"))` en `app/build.gradle.kts`), **ktlint** (`"app/src/main/java/dog/**/*.kt"`), **Qodana** (`qodana.yaml`) como **SonarCloud** (`sonar.exclusions=**/uniffi/**`) excluyen `app/src/main/java/uniffi/**` para evitar falsos positivos en bindings generados.

### Requisitos externos opcionales
1. **`autofix.ci`:** Instalar la GitHub App [autofix.ci](https://github.com/apps/autofix-ci) en el repositorio para habilitar commits automáticos de formato en PRs.
2. **SonarCloud:** El secreto `SONAR_TOKEN` está configurado en `local.properties` (ignorado por Git) y en GitHub Actions Secrets. Si la organización/proyecto aún no está vinculada en [sonarcloud.io](https://sonarcloud.io), el step tiene `continue-on-error: true` para no bloquear el workflow.

---

## 3. Verificación Local Rápida

Antes de hacer `push`, podés validar localmente:

```bash
# Rust: formato, lints y tests unitarios
cargo fmt --manifest-path rust/Cargo.toml --all -- --check
cargo clippy --manifest-path rust/Cargo.toml --all-targets
cargo test --manifest-path rust/Cargo.toml

# Android: Detekt y compilación Release con R8
source scripts/env.sh && unset GRADLE_USER_HOME && ./gradlew detekt
source scripts/env.sh && unset GRADLE_USER_HOME && ./gradlew assembleRelease
```

---

## 4. Toolchain Android, Gradle 9 y AGP 9

- **Gradle Wrapper:** `9.9.2` (`gradle/wrapper/gradle-wrapper.properties`).
- **Android Gradle Plugin (AGP):** `9.5.3` con **built-in Kotlin** (no aplicar `org.jetbrains.kotlin.android` ni `kotlinOptions {}` en `app/build.gradle.kts`).
- **Plugins Kotlin (`compose` y `serialization`):** `2.4.21` en `build.gradle.kts`.
- **SDKs:** `compileSdk = 37.2` (`platforms;android-37.2`), `targetSdk = 36`, `minSdk = 26`.
- **Supabase BOM (`3.9.0`):** `EncryptedSessionManager` en [SupabaseClientProvider.kt](file:///media/carlos/DATA1/DEV/Balddog-Android-App/app/src/main/java/dog/bald/arcade/data/SupabaseClientProvider.kt) implementa `loadSession(): UserSession` y `loadSessionOrNull(): UserSession?`.
