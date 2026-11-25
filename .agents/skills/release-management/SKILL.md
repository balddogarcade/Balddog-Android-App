---
name: release-management
description: Runbook paso a paso para preparar y publicar un release SemVer de bald.dog ARCADE (CHANGELOG.md, tag vX.Y.Z, monitoreo de GitHub Actions, verificación SLSA y VirusTotal).
---

# Release Management Runbook

Sigue este procedimiento cuando el usuario solicite preparar o publicar un nuevo release de **bald.dog ARCADE**.

## 1. Preparar `CHANGELOG.md`
1. Revisa los cambios acumulados bajo `## [Unreleased]` en `CHANGELOG.md`.
2. Crea la nueva sección `## [X.Y.Z] - YYYY-MM-DD` moviendo los ítems correspondientes y dejando `## [Unreleased]` vacío para futuros cambios.
3. Actualiza los enlaces comparativos al final de `CHANGELOG.md`.

## 2. Validar Tests Localmente
```bash
source scripts/env.sh && unset GRADLE_USER_HOME && ./gradlew testReleaseUnitTest --offline
```
*(Nota: `testDebugUnitTest` falla en `:app:processDebugGoogleServices` mientras `app/google-services.json` solo registre `dog.bald.arcade` sin el cliente `.debug`).*

## 3. Commit y Tag SemVer
1. Haz commit de `CHANGELOG.md` en inglés con autoría exclusiva `cjbaezilla` (sin `Co-authored-by`).
2. Crea y pushea el tag anotado:
```bash
git tag -a vX.Y.Z -m "Release vX.Y.Z"
git push origin main --tags
```

## 4. Monitorear GitHub Actions y Verificar Seguridad
1. Monitorea el workflow `.github/workflows/release.yml` en GitHub (`Actions > Release APK`) o mediante el skill `gh` (`gh run list --workflow release.yml`, `gh run view <ID> --log-failed`, `gh run rerun <ID> --failed`).
2. Verifica que el Release publicado incluya:
   - `app-release.apk` y `app-release.apk.sha256`
   - Enlace al reporte de **VirusTotal**
   - Atestación **SLSA Build Provenance** verificable con:
     ```bash
     gh attestation verify app-release.apk --owner balddogarcade
     ```
