#!/usr/bin/env python3
"""
Seeder para enriquecer public.games en Supabase con metadatos de Arcade Database
(adb.arcadeitalia.net) y subir sus imágenes a Pinata (IPFS).

Uso:
  python3 scripts/enrich-games-metadata.py [--dry-run] [--rom <slug>] [--skip-ipfs]
"""

import argparse
import gzip
import json
import os
import re
import sys
import urllib.error
import urllib.request
import uuid

IMAGE_FIELDS = [
    "url_image_ingame",
    "url_image_title",
    "url_image_marquee",
    "url_image_cabinet",
    "url_image_flyer",
    "url_icon",
]


def load_local_properties(path="local.properties"):
    props = {}
    if os.path.exists(path):
        with open(path, "r", encoding="utf-8") as f:
            for line in f:
                m = re.match(r"^([^#=]+)=(.*)$", line.strip())
                if m:
                    props[m.group(1).strip()] = m.group(2).strip()
    return props


def supabase_query(project_id, token, sql):
    req = urllib.request.Request(
        f"https://api.supabase.com/v1/projects/{project_id}/database/query",
        data=json.dumps({"query": sql}).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
            "User-Agent": "balddog-seeder/1.0",
        },
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def fetch_adb_metadata(rom_slug):
    url = f"https://adb.arcadeitalia.net/service_scraper.php?ajax=query_mame&game_name={rom_slug}"
    req = urllib.request.Request(url, headers={"User-Agent": "curl/8.0"})
    data = urllib.request.urlopen(req, timeout=20).read()
    text = gzip.decompress(data).decode("utf-8") if data[:2] == b"\x1f\x8b" else data.decode("utf-8")
    payload = json.loads(text)
    results = payload.get("result", [])
    return results[0] if results else None


def download_bytes(url):
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=25) as resp:
        content_type = resp.headers.get("Content-Type", "image/png")
        return resp.read(), content_type


def upload_to_pinata(file_bytes, filename, content_type, pinata_jwt=None, api_key=None, api_secret=None):
    boundary = f"----WebKitFormBoundary{uuid.uuid4().hex}"
    body = bytearray()

    # File part
    body.extend(f"--{boundary}\r\n".encode("utf-8"))
    body.extend(f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'.encode("utf-8"))
    body.extend(f"Content-Type: {content_type}\r\n\r\n".encode("utf-8"))
    body.extend(file_bytes)
    body.extend(b"\r\n")

    # Metadata part
    meta = json.dumps({"name": filename})
    body.extend(f"--{boundary}\r\n".encode("utf-8"))
    body.extend(b'Content-Disposition: form-data; name="pinataMetadata"\r\n\r\n')
    body.extend(meta.encode("utf-8"))
    body.extend(b"\r\n")
    body.extend(f"--{boundary}--\r\n".encode("utf-8"))

    headers = {"Content-Type": f"multipart/form-data; boundary={boundary}"}
    if pinata_jwt:
        headers["Authorization"] = f"Bearer {pinata_jwt}"
    elif api_key and api_secret:
        headers["pinata_api_key"] = api_key
        headers["pinata_secret_api_key"] = api_secret
    else:
        raise RuntimeError("Faltan credenciales de Pinata (PINATA_JWT o PINATA_API_KEY + PINATA_SECRET_API_KEY)")

    req = urllib.request.Request(
        "https://api.pinata.cloud/pinning/pinFileToIPFS",
        data=bytes(body),
        headers=headers,
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        res = json.loads(resp.read().decode("utf-8"))
        cid = res["IpfsHash"]
        return f"https://gateway.pinata.cloud/ipfs/{cid}"


def sql_literal(val):
    if val is None or val == "":
        return "NULL"
    if isinstance(val, int):
        return str(val)
    escaped = str(val).replace("'", "''")
    return f"'{escaped}'"


def to_int_or_none(val):
    try:
        return int(val) if val not in (None, "") else None
    except (ValueError, TypeError):
        return None


def main():
    parser = argparse.ArgumentParser(description="Enriquece public.games desde Arcade Database + Pinata IPFS")
    parser.add_argument("--dry-run", action="store_true", help="Simula sin subir a Pinata ni actualizar Supabase")
    parser.add_argument("--rom", type=str, help="Procesa únicamente la ROM indicada (slug)")
    parser.add_argument("--skip-ipfs", action="store_true", help="No sube imágenes a IPFS (deja URLs nulas)")
    args = parser.parse_args()

    props = load_local_properties()
    project_id = os.environ.get("SUPABASE_PROJECT_ID") or props.get("SUPABASE_PROJECT_ID")
    sb_token = os.environ.get("SUPABASE_ACCESS_TOKEN") or props.get("SUPABASE_ACCESS_TOKEN")
    pinata_jwt = os.environ.get("PINATA_JWT") or props.get("PINATA_JWT")
    pinata_key = os.environ.get("PINATA_API_KEY") or props.get("PINATA_API_KEY")
    pinata_secret = os.environ.get("PINATA_SECRET_API_KEY") or props.get("PINATA_SECRET_API_KEY")

    if not project_id or not sb_token:
        print("ERROR: Faltan SUPABASE_PROJECT_ID o SUPABASE_ACCESS_TOKEN en local.properties o entorno.", file=sys.stderr)
        sys.exit(1)

    if not args.dry_run and not args.skip_ipfs and not pinata_jwt and not (pinata_key and pinata_secret):
        print("ERROR: Faltan credenciales de Pinata (PINATA_JWT o PINATA_API_KEY + PINATA_SECRET_API_KEY).", file=sys.stderr)
        sys.exit(1)

    where_clause = f"WHERE slug = {sql_literal(args.rom)}" if args.rom else ""
    games = supabase_query(project_id, sb_token, f"SELECT id, slug, title FROM public.games {where_clause} ORDER BY slug;")
    print(f"ROMs a procesar: {len(games)}")

    ok_count, err_count = 0, 0
    for idx, g in enumerate(games, 1):
        slug = g["slug"]
        print(f"[{idx}/{len(games)}] Procesando '{slug}' ({g['title']})...")
        try:
            adb = fetch_adb_metadata(slug)
            if not adb:
                print(f"  -> SKIP: No se encontró '{slug}' en Arcade Database.")
                err_count += 1
                continue

            ipfs_urls = {}
            for field in IMAGE_FIELDS:
                img_url = adb.get(field)
                if not img_url:
                    ipfs_urls[field] = None
                    continue
                if args.dry_run or args.skip_ipfs:
                    ipfs_urls[field] = img_url if args.dry_run else None
                    continue
                try:
                    img_bytes, ctype = download_bytes(img_url)
                    if not img_bytes or b"<html" in img_bytes[:200].lower():
                        ipfs_urls[field] = None
                        continue
                    ext = "jpg" if "jpeg" in ctype or "jpg" in ctype else "png"
                    fname = f"{slug}_{field.replace('url_image_', '').replace('url_', '')}.{ext}"
                    ipfs_urls[field] = upload_to_pinata(
                        img_bytes, fname, ctype, pinata_jwt, pinata_key, pinata_secret
                    )
                except Exception as img_err:
                    print(f"  -> WARN imagen {field}: {img_err}")
                    ipfs_urls[field] = None

            update_sql = f"""
            UPDATE public.games SET
              game_name = {sql_literal(adb.get('game_name'))},
              adb_title = {sql_literal(adb.get('title'))},
              short_title = {sql_literal(adb.get('short_title'))},
              manufacturer = {sql_literal(adb.get('manufacturer'))},
              year = {sql_literal(adb.get('year'))},
              genre = {sql_literal(adb.get('genre'))},
              serie = {sql_literal(adb.get('serie'))},
              players = {sql_literal(to_int_or_none(adb.get('players')))},
              nplayers = {sql_literal(adb.get('nplayers'))},
              emulator_name = {sql_literal(adb.get('emulator_name'))},
              input_controls = {sql_literal(adb.get('input_controls'))},
              input_buttons = {sql_literal(to_int_or_none(adb.get('input_buttons')))},
              buttons_colors = {sql_literal(adb.get('buttons_colors'))},
              history = {sql_literal(adb.get('history'))},
              history_copyright_long = {sql_literal(adb.get('history_copyright_long'))},
              youtube_video_id = {sql_literal(adb.get('youtube_video_id'))},
              url_image_ingame = {sql_literal(ipfs_urls.get('url_image_ingame'))},
              url_image_title = {sql_literal(ipfs_urls.get('url_image_title'))},
              url_image_marquee = {sql_literal(ipfs_urls.get('url_image_marquee'))},
              url_image_cabinet = {sql_literal(ipfs_urls.get('url_image_cabinet'))},
              url_image_flyer = {sql_literal(ipfs_urls.get('url_image_flyer'))},
              url_icon = {sql_literal(ipfs_urls.get('url_icon'))}
            WHERE id = {sql_literal(g['id'])};
            """

            if args.dry_run:
                print(f"  -> DRY-RUN OK: {adb.get('title')} ({adb.get('year')}, {adb.get('manufacturer')})")
            else:
                supabase_query(project_id, sb_token, update_sql)
                imgs_ok = sum(1 for v in ipfs_urls.values() if v)
                print(f"  -> OK: actualizado en Supabase ({imgs_ok}/6 imágenes en IPFS)")
            ok_count += 1
        except Exception as e:
            print(f"  -> ERROR en '{slug}': {e}")
            err_count += 1

    print(f"\nResumen: {ok_count} OK, {err_count} errores/omitidos.")


if __name__ == "__main__":
    main()
