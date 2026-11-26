# Correos Transaccionales, Resend y DNS en Cloudflare

Este documento detalla la configuración de envío de correos de **bald.dog ARCADE**, los registros DNS requeridos en Cloudflare y el flujo idempotente de correo de bienvenida para usuarios nuevos.

---

## 1. Proveedor y Remitente Oficial

- **Proveedor:** [Resend](https://resend.com) (`us-east-1`).
- **Remitente oficial:** `bald.dog ARCADE <noreply@bald.dog>` (configurado mediante `EMAIL_FROM` en `local.properties` y en los secrets de Supabase Edge Functions).
- **Credenciales (nunca en Git):** `RESEND_API_KEY`, `RESEND_DOMAIN_ID` y `EMAIL_FROM` se mantienen únicamente en `local.properties` (ignorado por `.gitignore`) y en los secrets de Supabase.

---

## 2. Registros DNS en Cloudflare (`bald.dog`)

Todos los registros de correo deben mantenerse **sin proxy de Cloudflare** (`proxied = false` / DNS only):

| Tipo | Nombre | Contenido / Destino | Propósito |
|---|---|---|---|
| `TXT` | `resend._domainkey.bald.dog` | Clave pública DKIM de Resend | Firma criptográfica DKIM |
| `MX` | `send.bald.dog` | `feedback-smtp.us-east-1.amazonses.com` | Return-Path / Bounce handling |
| `TXT` | `send.bald.dog` | `v=spf1 include:amazonses.com ~all` | Autorización SPF |
| `CNAME` | `rsend.bald.dog` | `send.forge.rmta.net` | Seguimiento / enrutamiento Resend |
| `TXT` | `_dmarc.bald.dog` | `v=DMARC1; p=none;` | Política DMARC |

---

## 3. Flujo del Correo de Bienvenida

El correo de bienvenida se envía **una sola vez** por usuario nuevo, sin importar si se registró mediante el formulario de email o mediante **Google OAuth** en su primer inicio de sesión:

1. Al crearse la cuenta en `auth.users`, el trigger `on_auth_user_created` (`public.handle_new_user()`) inserta la fila en `public.profiles` con `welcome_email_sent = false` (migración `029_welcome_email_sent_flag.sql`).
2. Cuando la app carga el perfil en `ArcadeRepository.fetchMyProfile()` (`Models.kt` → `ProfileDto.welcomeEmailSent`), si `welcomeEmailSent == false`, dispara de forma asíncrona y no bloqueante una petición `POST` a `${SUPABASE_URL}/functions/v1/send-welcome-email` con el JWT de la sesión actual.
3. La Edge Function `supabase/functions/send-welcome-email/index.ts`:
   - Valida el JWT del usuario autenticado (`admin.auth.getUser(jwt)`).
   - Consulta `public.profiles.welcome_email_sent` usando `SUPABASE_SERVICE_ROLE_KEY`.
   - Si ya es `true`, responde `200 { skipped: true }`.
   - Si es `false`, envía el correo vía Resend y, tras recibir `200 OK`, actualiza `welcome_email_sent = true` en `public.profiles`.
