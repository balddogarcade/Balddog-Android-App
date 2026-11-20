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
