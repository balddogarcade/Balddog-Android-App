#!/usr/bin/env python3
"""Sube las portadas .webp optimizadas de app/src/main/assets/covers/ a Pinata (IPFS)
y genera la migración SQL supabase/migrations/035_update_games_cover_url_ipfs.sql.
"""

import json
import os
import re
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
COVERS_DIR = ROOT / "app" / "src" / "main" / "assets" / "covers"
MIGRATION_FILE = ROOT / "supabase" / "migrations" / "035_update_games_cover_url_ipfs.sql"


def load_props() -> dict[str, str]:
    props = {}
    local_props = ROOT / "local.properties"
    if local_props.exists():
        for line in local_props.read_text(encoding="utf-8").splitlines():
            m = re.match(r"^([^#=]+)=(.*)$", line.strip())
            if m:
                props[m.group(1).strip()] = m.group(2).strip()
    return props


def upload_to_pinata(file_path: Path, props: dict[str, str]) -> str:
    jwt = os.environ.get("PINATA_JWT") or props.get("PINATA_JWT")
    api_key = os.environ.get("PINATA_API_KEY") or props.get("PINATA_API_KEY")
    api_secret = os.environ.get("PINATA_SECRET_API_KEY") or props.get("PINATA_SECRET_API_KEY")

    boundary = "----BalddogPinataBoundary7MA4YWxkTrZu0gW"
    filename = file_path.name
    file_bytes = file_path.read_bytes()
    metadata = json.dumps({"name": f"cover-{filename}"})

    parts = [
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'
        f"Content-Type: image/webp\r\n\r\n".encode("utf-8"),
        file_bytes,
        f"\r\n--{boundary}\r\n"
        f'Content-Disposition: form-data; name="pinataMetadata"\r\n'
        f"Content-Type: application/json\r\n\r\n"
        f"{metadata}\r\n"
        f"--{boundary}--\r\n".encode("utf-8"),
    ]
    body = b"".join(parts)

    headers = {"Content-Type": f"multipart/form-data; boundary={boundary}"}
    if jwt:
        headers["Authorization"] = f"Bearer {jwt}"
    elif api_key and api_secret:
        headers["pinata_api_key"] = api_key
        headers["pinata_secret_api_key"] = api_secret
    else:
        raise RuntimeError("Faltan credenciales de Pinata (PINATA_JWT o PINATA_API_KEY + PINATA_SECRET_API_KEY)")

    req = urllib.request.Request(
        "https://api.pinata.cloud/pinning/pinFileToIPFS",
        data=body,
        headers=headers,
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        data = json.loads(resp.read().decode("utf-8"))
        cid = data["IpfsHash"]
        return f"https://gateway.pinata.cloud/ipfs/{cid}"


def main() -> int:
    props = load_props()
    webp_files = sorted(COVERS_DIR.glob("*.webp"))
    if not webp_files:
        print(f"No se encontraron archivos .webp en {COVERS_DIR}", file=sys.stderr)
        return 1

    print(f"Subiendo {len(webp_files)} portadas .webp a Pinata/IPFS...")
    sql_lines = [
        "-- Migración 035: Actualiza cover_url en public.games con las portadas .webp subidas a IPFS (Pinata)",
        "",
    ]

    for idx, fpath in enumerate(webp_files, 1):
        slug = fpath.stem
        url = upload_to_pinata(fpath, props)
        print(f"[{idx}/{len(webp_files)}] {slug} -> {url}")
        sql_lines.append(
            f"UPDATE public.games SET cover_url = '{url}' WHERE slug = '{slug}';"
        )

    MIGRATION_FILE.write_text("\n".join(sql_lines) + "\n", encoding="utf-8")
    print(f"\nMigración generada en {MIGRATION_FILE}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
