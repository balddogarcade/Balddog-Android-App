#!/usr/bin/env python3
"""
Headless Anti-Cheat Replay Verification Worker for bald.dog ARCADE.

Polls Supabase `claim_pending_replay_verifications`, downloads `.bsv.gz` replays,
verifies ROM & replay SHA-256 digests, executes `verify_replay` headlessly against
`fbneo_libretro.so`, and reports the verdict via `complete_replay_verification`.

Required environment variables:
  SUPABASE_URL              e.g. https://<project>.supabase.co
  SUPABASE_SERVICE_ROLE_KEY Supabase service_role JWT (never commit!)
  FBNEO_CORE_PATH           Path to Linux fbneo_libretro.so
  ROMS_DIR                  Directory containing verified .zip ROMs (and neogeo.zip)
  VERIFIER_BIN              Path to compiled `verify_replay` binary
Optional:
  POLL_INTERVAL_SECS        Default: 15
  BATCH_SIZE                Default: 5
  VERIFIER_VERSION          Default: fbneo-headless-v1
  ONCE                      Set to "1" to process one batch and exit
"""

import hashlib
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


def env_required(name: str) -> str:
    val = os.environ.get(name, "").strip()
    if not val:
        print(f"[ERROR] Missing required env var: {name}", file=sys.stderr)
        sys.exit(2)
    return val


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest().lower()


def rpc_call(base_url: str, service_key: str, fn_name: str, payload: dict):
    url = f"{base_url.rstrip('/')}/rest/v1/rpc/{fn_name}"
    body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=body,
        method="POST",
        headers={
            "apikey": service_key,
            "Authorization": f"Bearer {service_key}",
            "Content-Type": "application/json",
        },
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        raw = resp.read().decode("utf-8").strip()
        return json.loads(raw) if raw else None


def download_replay(base_url: str, service_key: str, replay_path: str, dest: Path) -> None:
    clean_path = replay_path.lstrip("/")
    url = f"{base_url.rstrip('/')}/storage/v1/object/replays/{clean_path}"
    req = urllib.request.Request(
        url,
        headers={
            "apikey": service_key,
            "Authorization": f"Bearer {service_key}",
        },
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        dest.write_bytes(resp.read())


def lookup_game_rom_info(base_url: str, service_key: str, rom_filename: str) -> dict | None:
    encoded = urllib.parse.quote(rom_filename, safe="")
    url = f"{base_url.rstrip('/')}/rest/v1/games?rom_filename=eq.{encoded}&select=ipfs_cid,system&limit=1"
    req = urllib.request.Request(
        url,
        headers={
            "apikey": service_key,
            "Authorization": f"Bearer {service_key}",
        },
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        rows = json.loads(resp.read().decode("utf-8"))
        return rows[0] if rows else None


def download_file(url: str, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp_dest = dest.with_suffix(dest.suffix + ".part")
    req = urllib.request.Request(url, headers={"User-Agent": "balddog-replay-verifier/1.0"})
    with urllib.request.urlopen(req, timeout=180) as resp, tmp_dest.open("wb") as out:
        while True:
            chunk = resp.read(1024 * 1024)
            if not chunk:
                break
            out.write(chunk)
    tmp_dest.replace(dest)


def download_from_ipfs(cid_or_url: str, dest: Path) -> None:
    raw = cid_or_url.strip()
    if raw.startswith("http://") or raw.startswith("https://"):
        download_file(raw, dest)
        return

    gateways = [
        f"https://gateway.pinata.cloud/ipfs/{raw}",
        f"https://ipfs.io/ipfs/{raw}",
        f"https://dweb.link/ipfs/{raw}",
    ]
    last_err: Exception | None = None
    for gw_url in gateways:
        try:
            download_file(gw_url, dest)
            return
        except Exception as e:
            last_err = e
    if last_err is not None:
        raise last_err


def ensure_rom_available(
    base_url: str,
    service_key: str,
    roms_dir: Path,
    rom_filename: str,
) -> Path | None:
    rom_path = roms_dir / rom_filename
    if rom_path.exists():
        return rom_path

    info = lookup_game_rom_info(base_url, service_key, rom_filename)
    if not info or not info.get("ipfs_cid"):
        return None

    download_from_ipfs(info["ipfs_cid"], rom_path)

    system = (info.get("system") or "").lower()
    neogeo_zip = roms_dir / "neogeo.zip"
    neogeo_url = os.environ.get("NEOGEO_BIOS_URL", "").strip()
    if "neo" in system and not neogeo_zip.exists() and neogeo_url:
        download_file(neogeo_url, neogeo_zip)

    return rom_path if rom_path.exists() else None


def verify_item(
    item: dict,
    base_url: str,
    service_key: str,
    core_path: Path,
    roms_dir: Path,
    verifier_bin: Path,
    verifier_version: str,
) -> None:
    score_id = item["score_id"]
    rom_filename = item["rom_filename"]
    expected_rom_sha = (item.get("rom_sha256") or "").lower()
    lboard_expr = item["lboard_expr"]
    claimed_score = int(item["claimed_score"])
    replay_path = item["replay_path"]
    expected_replay_sha = (item.get("replay_sha256") or "").lower()

    rom_path = roms_dir / rom_filename
    if not rom_path.exists():
        try:
            downloaded = ensure_rom_available(base_url, service_key, roms_dir, rom_filename)
            if downloaded is not None:
                rom_path = downloaded
        except Exception as e:
            print(f"[WARN] Failed to download ROM {rom_filename}: {e}", file=sys.stderr)

    if not rom_path.exists():
        rpc_call(
            base_url,
            service_key,
            "complete_replay_verification",
            {
                "p_score_id": score_id,
                "p_status": "error",
                "p_verified_score": None,
                "p_verifier_version": verifier_version,
                "p_error": f"ROM missing on worker: {rom_filename}",
            },
        )
        return

    actual_rom_sha = sha256_file(rom_path)
    if expected_rom_sha and actual_rom_sha != expected_rom_sha:
        rpc_call(
            base_url,
            service_key,
            "complete_replay_verification",
            {
                "p_score_id": score_id,
                "p_status": "error",
                "p_verified_score": None,
                "p_verifier_version": verifier_version,
                "p_error": "Worker ROM SHA-256 mismatch",
            },
        )
        return

    with tempfile.TemporaryDirectory(prefix="balddog_verify_") as tmp:
        tmp_dir = Path(tmp)
        ext = ".bsv.zst" if replay_path.endswith(".bsv.zst") else ".bsv.gz"
        local_replay = tmp_dir / f"replay{ext}"
        try:
            download_replay(base_url, service_key, replay_path, local_replay)
        except Exception as e:
            rpc_call(
                base_url,
                service_key,
                "complete_replay_verification",
                {
                    "p_score_id": score_id,
                    "p_status": "error",
                    "p_verified_score": None,
                    "p_verifier_version": verifier_version,
                    "p_error": f"download_failed: {str(e)[:200]}",
                },
            )
            return

        if expected_replay_sha:
            actual_replay_sha = sha256_file(local_replay)
            if actual_replay_sha != expected_replay_sha:
                rpc_call(
                    base_url,
                    service_key,
                    "complete_replay_verification",
                    {
                        "p_score_id": score_id,
                        "p_status": "rejected",
                        "p_verified_score": 0,
                        "p_verifier_version": verifier_version,
                        "p_error": "replay_sha256_mismatch",
                    },
                )
                return

        try:
            sys_dir = tmp_dir / "system"
            sys_dir.mkdir(parents=True, exist_ok=True)
            proc = subprocess.run(
                [
                    str(verifier_bin),
                    "--core",
                    str(core_path),
                    "--rom",
                    str(rom_path),
                    "--replay",
                    str(local_replay),
                    "--lboard",
                    lboard_expr,
                    "--system-dir",
                    str(sys_dir),
                    "--save-dir",
                    str(tmp_dir / "saves"),
                    "--expected-score",
                    str(claimed_score),
                ],
                capture_output=True,
                text=True,
                timeout=180,
            )

            stdout = proc.stdout.strip().splitlines()[-1] if proc.stdout.strip() else "{}"
            verdict = json.loads(stdout)
            verified_score = int(verdict.get("verified_score", 0))
            reason = verdict.get("reason")

            if proc.returncode == 2:
                status = "error"
                verified_score = None
            elif verdict.get("valid") is True:
                status = "verified"
            else:
                status = "rejected"
        except subprocess.TimeoutExpired:
            status = "error"
            verified_score = None
            reason = "timeout_exceeded"
        except Exception as e:
            status = "error"
            verified_score = None
            reason = f"verifier_error: {str(e)[:200]}"

        accepted = rpc_call(
            base_url,
            service_key,
            "complete_replay_verification",
            {
                "p_score_id": score_id,
                "p_status": status,
                "p_verified_score": verified_score,
                "p_verifier_version": verifier_version,
                "p_error": reason,
                "p_expected_replay_path": replay_path,
                "p_replay_sha256": expected_replay_sha or None,
            },
        )
        print(
            f"[{status.upper()}] score_id={score_id} claimed={claimed_score} "
            f"verified={verified_score} accepted={accepted} reason={reason}"
        )


def main() -> None:
    base_url = env_required("SUPABASE_URL")
    service_key = env_required("SUPABASE_SERVICE_ROLE_KEY")
    core_path = Path(env_required("FBNEO_CORE_PATH"))
    roms_dir = Path(env_required("ROMS_DIR"))
    verifier_bin = Path(env_required("VERIFIER_BIN"))

    poll_secs = int(os.environ.get("POLL_INTERVAL_SECS", "15"))
    batch_size = int(os.environ.get("BATCH_SIZE", "1"))
    verifier_version = os.environ.get("VERIFIER_VERSION", "fbneo-headless-v1")
    run_once = os.environ.get("ONCE", "0") == "1"

    while True:
        try:
            items = rpc_call(
                base_url,
                service_key,
                "claim_pending_replay_verifications",
                {"p_limit": batch_size},
            ) or []
            for item in items:
                try:
                    verify_item(
                        item,
                        base_url,
                        service_key,
                        core_path,
                        roms_dir,
                        verifier_bin,
                        verifier_version,
                    )
                except Exception as exc:
                    print(f"[ERROR] Failed verifying {item.get('score_id')}: {exc}", file=sys.stderr)
                    try:
                        rpc_call(
                            base_url,
                            service_key,
                            "complete_replay_verification",
                            {
                                "p_score_id": item["score_id"],
                                "p_status": "error",
                                "p_verified_score": None,
                                "p_verifier_version": verifier_version,
                                "p_error": str(exc)[:400],
                            },
                        )
                    except Exception:
                        pass
            if run_once:
                break
            if not items:
                time.sleep(poll_secs)
        except Exception as exc:
            print(f"[ERROR] Poll loop error: {exc}", file=sys.stderr)
            if run_once:
                sys.exit(1)
            time.sleep(poll_secs)


if __name__ == "__main__":
    main()
