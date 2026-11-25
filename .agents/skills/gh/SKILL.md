---
name: gh
description: >-
  Use this skill when inspecting GitHub Actions workflows, diagnosing failed CI/release builds,
  re-running failed jobs, checking published GitHub Releases/assets, or verifying SLSA attestations
  in balddogarcade/Balddog-Android-App using the gh CLI.
---

# GitHub CLI (`gh`) Runbook

Procedimientos operativos con `gh` para monitorear CI/CD y releases de **bald.dog ARCADE**.

> **Nota de ejecución:** Todos los comandos `gh` requieren acceso de red; ejecútalos con `BypassSandbox: true`.

## 1. Verificar autenticación
```bash
gh auth status
```
Si no hay sesión activa, pide al usuario ejecutar `gh auth login` en su terminal (o proveer `GH_TOKEN`).

## 2. Inspeccionar y diagnosticar Workflows (GitHub Actions)
- **Listar ejecuciones recientes:**
  ```bash
  gh run list --limit 5
  gh run list --workflow release.yml --limit 3
  ```
- **Ver el estado de una ejecución específica:**
  ```bash
  gh run view <RUN_ID> --json status,conclusion,url
  ```
- **Ver solo los logs de pasos fallidos:**
  ```bash
  gh run view <RUN_ID> --log-failed
  ```
- **Relanzar únicamente los jobs fallidos (útil ante fallos transitorios de caché/runner):**
  ```bash
  gh run rerun <RUN_ID> --failed
  ```

## 3. Verificar Releases y Atestación SLSA
- **Consultar release y assets publicados (`app-release.apk`, `app-release.apk.sha256`):**
  ```bash
  gh release view vX.Y.Z --json tagName,name,assets,url
  ```
- **Verificar atestación SLSA Build Provenance del APK descargado:**
  ```bash
  gh attestation verify app-release.apk --owner balddogarcade
  ```
