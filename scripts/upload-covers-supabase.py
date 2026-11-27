#!/usr/bin/env python3
"""Sube portadas .webp al bucket público 'covers' de Supabase Storage.

Por defecto lee los 30 archivos .webp desde el historial de Git
(8481c58~1:app/src/main/assets/covers) para no empaquetarlos en el APK,
o desde un directorio local si se especifica --dir.

Uso:
  python3 scripts/upload-covers-supabase.py
  python3 scripts/upload-covers-supabase.py --dir /ruta/portadas --slug juego_nuevo
"""

import argparse
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GIT_COVERS_COMMIT = "8481c58~1"
GIT_COVERS_PREFIX = "app/src/main/assets/covers"
BUCKET_NAME = "covers"


def load_props() -> dict[str, str]:
    props: dict[str, str] = {}
    local_props = ROOT / "local.properties"
    if local_props.exists():
        for line in local_props.read_text(encoding="utf-8").splitlines():
            m = re.match(r"^([^#=]+)=(.*)$", line.strip())
            if m:
                props[m.group(1).strip()] = m.group(2).strip()
    return props


def fetch_service_role_key(project_id: str, access_token: str) -> str:
    req = urllib.request.Request(
        f"https://api.supabase.com/v1/projects/{project_id}/api-keys",
        headers={
            "Authorization": f"Bearer {access_token}",
            "User-Agent": "balddog-covers-uploader/1.0",
        },
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        keys = json.loads(resp.read().decode("utf-8"))
    for item in keys:
        if item.get("name") == "service_role" and item.get("api_key"):
            return item["api_key"]
    raise RuntimeError("No se encontró la clave service_role en Supabase Management API")


def list_git_covers() -> list[tuple[str, bytes]]:
    res = subprocess.run(
        ["git", "-C", str(ROOT), "ls-tree", "--name-only", GIT_COVERS_COMMIT, f"{GIT_COVERS_PREFIX}/"],
        check=True,
        capture_output=True,
        text=True,
    )
    items: list[tuple[str, bytes]] = []
    for rel_path in sorted(res.stdout.splitlines()):
        if not rel_path.endswith(".webp"):
            continue
        slug = Path(rel_path).stem
        blob = subprocess.run(
            ["git", "-C", str(ROOT), "show", f"{GIT_COVERS_COMMIT}:{rel_path}"],
            check=True,
            capture_output=True,
        ).stdout
        items.append((slug, blob))
    return items


def list_dir_covers(directory: Path) -> list[tuple[str, bytes]]:
    return [(p.stem, p.read_bytes()) for p in sorted(directory.glob("*.webp"))]


def upload_cover(supabase_url: str, service_role_key: str, slug: str, data: bytes) -> str:
    object_name = f"{slug}.webp"
    endpoint = f"{supabase_url.rstrip('/')}/storage/v1/object/{BUCKET_NAME}/{object_name}"
    req = urllib.request.Request(
        endpoint,
        data=data,
        headers={
            "Authorization": f"Bearer {service_role_key}",
            "apikey": service_role_key,
            "Content-Type": "image/webp",
            "Cache-Control": "max-age=31536000",
            "x-upsert": "true",
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        if resp.status not in (200, 201):
            raise RuntimeError(f"Error HTTP {resp.status} subiendo {object_name}")
    return f"{supabase_url.rstrip('/')}/storage/v1/object/public/{BUCKET_NAME}/{object_name}"


def main() -> int:
    parser = argparse.ArgumentParser(description="Sube portadas .webp a Supabase Storage (bucket covers)")
    parser.add_argument("--dir", type=Path, help="Directorio local con archivos <slug>.webp")
    parser.add_argument("--slug", type=str, help="Filtrar a un único slug")
    args = parser.parse_args()

    props = load_props()
    project_id = os.environ.get("SUPABASE_PROJECT_ID") or props.get("SUPABASE_PROJECT_ID")
    supabase_url = os.environ.get("SUPABASE_URL") or props.get("SUPABASE_URL")
    access_token = os.environ.get("SUPABASE_ACCESS_TOKEN") or props.get("SUPABASE_ACCESS_TOKEN")
    service_role_key = os.environ.get("SUPABASE_SERVICE_ROLE_KEY") or props.get("SUPABASE_SERVICE_ROLE_KEY")

    if not supabase_url and project_id:
        supabase_url = f"https://{project_id}.supabase.co"
    if not supabase_url:
        print("ERROR: Falta SUPABASE_URL o SUPABASE_PROJECT_ID.", file=sys.stderr)
        return 1

    if not service_role_key:
        if not project_id or not access_token:
            print("ERROR: Faltan SUPABASE_PROJECT_ID y SUPABASE_ACCESS_TOKEN para obtener service_role.", file=sys.stderr)
            return 1
        service_role_key = fetch_service_role_key(project_id, access_token)

    covers = list_dir_covers(args.dir) if args.dir else list_git_covers()
    if args.slug:
        covers = [(s, b) for s, b in covers if s == args.slug]

    if not covers:
        print("No se encontraron portadas .webp para subir.", file=sys.stderr)
        return 1

    print(f"Subiendo {len(covers)} portadas .webp a Supabase Storage (bucket '{BUCKET_NAME}')...")
    for idx, (slug, blob) in enumerate(covers, 1):
        public_url = upload_cover(supabase_url, service_role_key, slug, blob)
        print(f"[{idx}/{len(covers)}] {slug} ({len(blob)} B) -> {public_url}")

    print("Subida completada.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
