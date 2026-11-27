#!/usr/bin/env python3
"""
Reinicia a cero todas las estadísticas de juegos y jugadores en Supabase:
- Vacía el bucket 'replays' en Supabase Storage.
- Trunca high_scores, match_history, user_game_stats, matchmaking_queue,
  room_messages, room_session_snapshots y rooms.
- Reinicia download_count y total_play_seconds en public.games.
- Reinicia total_play_seconds y total_plays en public.profiles.

Preserva el catálogo de juegos (public.games) y las cuentas (public.profiles / auth.users).
"""

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent.parent
LOCAL_PROPERTIES = ROOT_DIR / "local.properties"

VERIFICATION_QUERY = """
SELECT 'games (total)' AS metric, count(*) AS cnt FROM public.games
UNION ALL SELECT 'games (non-zero stats)', count(*) FROM public.games WHERE download_count <> 0 OR total_play_seconds <> 0
UNION ALL SELECT 'profiles (total)', count(*) FROM public.profiles
UNION ALL SELECT 'profiles (non-zero stats)', count(*) FROM public.profiles WHERE total_play_seconds <> 0 OR total_plays <> 0
UNION ALL SELECT 'high_scores', count(*) FROM public.high_scores
UNION ALL SELECT 'match_history', count(*) FROM public.match_history
UNION ALL SELECT 'user_game_stats', count(*) FROM public.user_game_stats
UNION ALL SELECT 'matchmaking_queue', count(*) FROM public.matchmaking_queue
UNION ALL SELECT 'rooms', count(*) FROM public.rooms
UNION ALL SELECT 'room_messages', count(*) FROM public.room_messages
UNION ALL SELECT 'room_session_snapshots', count(*) FROM public.room_session_snapshots
UNION ALL SELECT 'storage.objects (replays)', count(*) FROM storage.objects WHERE bucket_id = 'replays';
"""

RESET_SQL = """
BEGIN;

TRUNCATE TABLE
  public.high_scores,
  public.match_history,
  public.user_game_stats,
  public.matchmaking_queue,
  public.room_messages,
  public.room_session_snapshots,
  public.rooms
RESTART IDENTITY CASCADE;

UPDATE public.games
SET
  download_count = 0,
  total_play_seconds = 0;

UPDATE public.profiles
SET
  total_play_seconds = 0,
  total_plays = 0;

COMMIT;
"""


def load_config() -> dict[str, str]:
    """Carga credenciales de Supabase desde local.properties y variables de entorno."""
    props: dict[str, str] = {}
    if LOCAL_PROPERTIES.exists():
        for line in LOCAL_PROPERTIES.read_text(encoding="utf-8").splitlines():
            match = re.match(r"^([^#=]+)=(.*)$", line.strip())
            if match:
                props[match.group(1).strip()] = match.group(2).strip()

    project_id = os.environ.get("SUPABASE_PROJECT_ID") or props.get("SUPABASE_PROJECT_ID", "")
    access_token = os.environ.get("SUPABASE_ACCESS_TOKEN") or props.get("SUPABASE_ACCESS_TOKEN", "")
    service_key = os.environ.get("SUPABASE_SERVICE_ROLE_KEY") or props.get("SUPABASE_SERVICE_ROLE_KEY", "")
    supabase_url = os.environ.get("SUPABASE_URL") or props.get("SUPABASE_URL") or (
        f"https://{project_id}.supabase.co" if project_id else ""
    )

    if not project_id or not access_token or not service_key or not supabase_url:
        missing = [
            name
            for name, val in [
                ("SUPABASE_PROJECT_ID", project_id),
                ("SUPABASE_ACCESS_TOKEN", access_token),
                ("SUPABASE_SERVICE_ROLE_KEY", service_key),
                ("SUPABASE_URL", supabase_url),
            ]
            if not val
        ]
        raise RuntimeError(f"Faltan credenciales requeridas en local.properties o entorno: {', '.join(missing)}")

    return {
        "project_id": project_id,
        "access_token": access_token,
        "service_key": service_key,
        "supabase_url": supabase_url.rstrip("/"),
    }


def run_sql(project_id: str, access_token: str, query: str) -> list[dict]:
    """Ejecuta una consulta SQL vía Supabase Management API."""
    req = urllib.request.Request(
        f"https://api.supabase.com/v1/projects/{project_id}/database/query",
        data=json.dumps({"query": query}).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {access_token}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        raw = resp.read().decode("utf-8")
        return json.loads(raw) if raw else []


def empty_replays_bucket(supabase_url: str, service_key: str) -> str:
    """Vacía el bucket 'replays' usando la Storage API."""
    req = urllib.request.Request(
        f"{supabase_url}/storage/v1/bucket/replays/empty",
        data=b"{}",
        headers={
            "Authorization": f"Bearer {service_key}",
            "apikey": service_key,
            "Content-Type": "application/json",
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        return resp.read().decode("utf-8")


def print_metrics(title: str, rows: list[dict]) -> None:
    """Imprime una tabla legible con las métricas devueltas por VERIFICATION_QUERY."""
    print(f"\n=== {title} ===")
    for row in rows:
        print(f"  - {row['metric']:<30} {row['cnt']}")


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Reinicia a cero estadísticas, récords, replays e historial en Supabase."
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Muestra los conteos actuales sin realizar modificaciones.",
    )
    parser.add_argument(
        "--yes",
        "-y",
        action="store_true",
        help="Omite la confirmación interactiva.",
    )
    args = parser.parse_args()

    try:
        cfg = load_config()
    except RuntimeError as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1

    before = run_sql(cfg["project_id"], cfg["access_token"], VERIFICATION_QUERY)
    print_metrics("Estado actual en Supabase", before)

    if args.dry_run:
        print("\n[dry-run] No se realizaron cambios.")
        return 0

    if not args.yes:
        confirm = input("\nEsta acción eliminará permanentemente récords, historial, replays y estadísticas. ¿Continuar? [y/N]: ")
        if confirm.strip().lower() not in {"y", "yes", "s", "si", "sí"}:
            print("Operación cancelada.")
            return 0

    print("\n1/2 Vaciando bucket 'replays' en Supabase Storage...")
    storage_resp = empty_replays_bucket(cfg["supabase_url"], cfg["service_key"])
    print(f"    Storage: {storage_resp}")

    print("2/2 Reiniciando tablas y contadores en PostgreSQL...")
    run_sql(cfg["project_id"], cfg["access_token"], RESET_SQL)

    after = run_sql(cfg["project_id"], cfg["access_token"], VERIFICATION_QUERY)
    print_metrics("Estado posterior en Supabase", after)

    non_zero_errors = [
        r for r in after
        if r["metric"] not in {"games (total)", "profiles (total)"} and int(r["cnt"]) != 0
    ]
    if non_zero_errors:
        print("\nAdvertencia: Algunas métricas no quedaron en 0:", non_zero_errors, file=sys.stderr)
        return 1

    print("\nReinicio completado y verificado correctamente.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
