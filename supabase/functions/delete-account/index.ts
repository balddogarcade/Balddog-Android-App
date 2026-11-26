import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";

serve(async (req: Request) => {
  if (req.method !== "POST") {
    return new Response(JSON.stringify({ error: "Method not allowed" }), { status: 405 });
  }
  if (!SUPABASE_URL || !SUPABASE_SERVICE_ROLE_KEY) {
    return new Response(JSON.stringify({ error: "Server configuration missing" }), { status: 500 });
  }

  try {
    const authHeader = req.headers.get("Authorization") ?? "";
    const jwt = authHeader.replace(/^Bearer\s+/i, "").trim();
    if (!jwt) {
      return new Response(JSON.stringify({ error: "Missing authorization token" }), { status: 401 });
    }

    const admin = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY, {
      auth: { persistSession: false },
    });

    const { data: userData, error: userError } = await admin.auth.getUser(jwt);
    if (userError || !userData?.user) {
      return new Response(JSON.stringify({ error: "Invalid user token" }), { status: 401 });
    }

    const uid = userData.user.id;

    // Best-effort cleanup of user replay objects in Storage before deleting auth user
    const { data: scores } = await admin
      .from("high_scores")
      .select("replay_path")
      .eq("user_id", uid)
      .not("replay_path", "is", null);

    const replayPaths = (scores ?? [])
      .map((row: { replay_path: string | null }) => row.replay_path)
      .filter((p): p is string => Boolean(p && p.trim().length > 0));

    if (replayPaths.length > 0) {
      await admin.storage.from("replays").remove(replayPaths);
    }

    const { error: deleteError } = await admin.auth.admin.deleteUser(uid);
    if (deleteError) {
      return new Response(JSON.stringify({ error: "Failed to delete account" }), { status: 500 });
    }

    return new Response(JSON.stringify({ deleted: true }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    });
  } catch {
    return new Response(JSON.stringify({ error: "Internal server error" }), { status: 500 });
  }
});
