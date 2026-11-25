import { serve } from "https://deno.land/std@0.177.0/http/server.ts";

const RESEND_API_KEY = Deno.env.get("RESEND_API_KEY") ?? "";
const EMAIL_FROM = Deno.env.get("EMAIL_FROM") ?? "bald.dog ARCADE <noreply@bald.dog>";

interface WelcomePayload {
  email?: string;
  username?: string;
  record?: {
    email?: string;
    raw_user_meta_data?: { username?: string };
  };
}

function buildWelcomeHtml(username: string): string {
  return `<!DOCTYPE html>
<html lang="es">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>Bienvenido a bald.dog ARCADE</title>
</head>
<body style="margin:0;padding:0;background-color:#0b0b0e;color:#f4f4f6;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;">
  <table width="100%" cellpadding="0" cellspacing="0" style="background-color:#0b0b0e;padding:32px 16px;">
    <tr>
      <td align="center">
        <table width="100%" style="max-width:580px;background-color:#15151a;border:1px solid #2a2a35;padding:32px;">
          <tr>
            <td>
              <h1 style="margin:0 0 8px 0;font-size:24px;letter-spacing:1px;color:#ffffff;">BALD.DOG ARCADE</h1>
              <p style="margin:0 0 24px 0;font-size:14px;color:#9ca3af;">El patio de recreo de los perros calvos.</p>
              <p style="margin:0 0 16px 0;font-size:16px;line-height:1.5;color:#e5e7eb;">
                ¡Bienvenido, <strong>${username}</strong>! Tu cuenta está lista. Aquí tienes todo lo que puedes hacer en la plataforma:
              </p>
              <table width="100%" cellpadding="0" cellspacing="0" style="margin:20px 0;background-color:#0f0f14;border:1px solid #262630;">
                <tr><td style="padding:14px 16px;border-bottom:1px solid #262630;">
                  <strong style="color:#ffffff;">🕹️ Catálogo Arcade Clásico (FBNeo)</strong><br/>
                  <span style="font-size:13px;color:#9ca3af;">Descarga automática de ROMs y BIOS con caché local lista para jugar al instante.</span>
                </td></tr>
                <tr><td style="padding:14px 16px;border-bottom:1px solid #262630;">
                  <strong style="color:#ffffff;">🌐 Netplay P2P con Rollback (GGRS)</strong><br/>
                  <span style="font-size:13px;color:#9ca3af;">Crea o únete a salas públicas y privadas en tiempo real con chat de voz integrado.</span>
                </td></tr>
                <tr><td style="padding:14px 16px;border-bottom:1px solid #262630;">
                  <strong style="color:#ffffff;">🏆 Leaderboards 1CC Verificados</strong><br/>
                  <span style="font-size:13px;color:#9ca3af;">Compite en rankings globales con verificación de memoria (rcheevos) y replays.</span>
                </td></tr>
                <tr><td style="padding:14px 16px;">
                  <strong style="color:#ffffff;">👥 Perfil y Comunidad</strong><br/>
                  <span style="font-size:13px;color:#9ca3af;">Consulta tu historial de partidas, sigue a otros jugadores y comparte tus mejores runs.</span>
                </td></tr>
              </table>
              <p style="margin:24px 0 0 0;font-size:13px;color:#6b7280;">
                ¡Insert coin y nos vemos en el arcade!
              </p>
            </td>
          </tr>
        </table>
      </td>
    </tr>
  </table>
</body>
</html>`;
}

serve(async (req: Request) => {
  if (req.method !== "POST") {
    return new Response(JSON.stringify({ error: "Method not allowed" }), { status: 405 });
  }
  if (!RESEND_API_KEY) {
    return new Response(JSON.stringify({ error: "RESEND_API_KEY not configured" }), { status: 500 });
  }

  try {
    const payload: WelcomePayload = await req.json();
    const email = payload.email ?? payload.record?.email;
    const username =
      payload.username ??
      payload.record?.raw_user_meta_data?.username ??
      email?.split("@")[0] ??
      "Jugador";

    if (!email) {
      return new Response(JSON.stringify({ error: "Missing email" }), { status: 400 });
    }

    const res = await fetch("https://api.resend.com/emails", {
      method: "POST",
      headers: {
        Authorization: `Bearer ${RESEND_API_KEY}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        from: EMAIL_FROM,
        to: [email],
        subject: "¡Bienvenido a bald.dog ARCADE! 🕹️",
        html: buildWelcomeHtml(username),
      }),
    });

    const data = await res.json();
    return new Response(JSON.stringify(data), {
      status: res.ok ? 200 : res.status,
      headers: { "Content-Type": "application/json" },
    });
  } catch (err) {
    return new Response(JSON.stringify({ error: String(err) }), { status: 500 });
  }
});
