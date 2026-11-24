//! Verificador headless determinista de replays `.bsv.gz` para auditoría Anti-Cheat.
//!
//! Uso:
//!   verify_replay --core <fbneo_libretro.so> --rom <rom.zip> --replay <file.bsv.gz> \
//!                 --lboard <expr> [--system-dir <dir>] [--save-dir <dir>] [--expected-score <u64>]

use std::path::PathBuf;
use std::process::ExitCode;

use balddog_core::retro::core::LibretroCore;
use balddog_core::retro::leaderboard::LeaderboardEngine;
use balddog_core::retro::replay::{compute_file_crc32, ReplayPlayer};
use balddog_core::session::{clear_deterministic_nvram, CoinTracker};

struct CliArgs {
    core: PathBuf,
    rom: PathBuf,
    replay: PathBuf,
    lboard: String,
    system_dir: Option<PathBuf>,
    save_dir: Option<PathBuf>,
    expected_score: Option<u64>,
}

fn parse_args() -> Result<CliArgs, String> {
    let mut args = std::env::args().skip(1);
    let mut core = None;
    let mut rom = None;
    let mut replay = None;
    let mut lboard = None;
    let mut system_dir = None;
    let mut save_dir = None;
    let mut expected_score = None;

    while let Some(flag) = args.next() {
        let val = args
            .next()
            .ok_or_else(|| format!("Falta valor para {flag}"))?;
        match flag.as_str() {
            "--core" => core = Some(PathBuf::from(val)),
            "--rom" => rom = Some(PathBuf::from(val)),
            "--replay" => replay = Some(PathBuf::from(val)),
            "--lboard" => lboard = Some(val),
            "--system-dir" => system_dir = Some(PathBuf::from(val)),
            "--save-dir" => save_dir = Some(PathBuf::from(val)),
            "--expected-score" => {
                let parsed = val
                    .parse::<u64>()
                    .map_err(|_| format!("--expected-score inválido: {val}"))?;
                expected_score = Some(parsed);
            }
            other => return Err(format!("Argumento desconocido: {other}")),
        }
    }

    Ok(CliArgs {
        core: core.ok_or("Falta --core")?,
        rom: rom.ok_or("Falta --rom")?,
        replay: replay.ok_or("Falta --replay")?,
        lboard: lboard.ok_or("Falta --lboard")?,
        system_dir,
        save_dir,
        expected_score,
    })
}

fn json_escape(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 2);
    out.push('"');
    for ch in s.chars() {
        match ch {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c => out.push(c),
        }
    }
    out.push('"');
    out
}

fn print_result(
    valid: bool,
    verified_score: u64,
    best_score: u64,
    triggered_score: u64,
    coins_inserted: u32,
    frames: u64,
    reason: Option<&str>,
) {
    let reason_json = match reason {
        Some(r) => json_escape(r),
        None => "null".to_string(),
    };
    println!(
        "{{\"valid\":{valid},\"verified_score\":{verified_score},\"best_score\":{best_score},\"triggered_score\":{triggered_score},\"coins_inserted\":{coins_inserted},\"frames\":{frames},\"reason\":{reason_json}}}"
    );
}

fn compute_expected_rom_crc(rom: &std::path::Path) -> Result<u32, String> {
    compute_file_crc32(rom).map_err(|e| format!("rom_load_failed: {e}"))
}

fn main() -> ExitCode {
    let args = match parse_args() {
        Ok(a) => a,
        Err(e) => {
            print_result(false, 0, 0, 0, 0, 0, Some(&e));
            return ExitCode::from(2);
        }
    };

    let expected_crc = match compute_expected_rom_crc(&args.rom) {
        Ok(crc) => Some(crc),
        Err(reason) => {
            print_result(false, 0, 0, 0, 0, 0, Some(&reason));
            return ExitCode::from(2);
        }
    };

    let own_temp_dir = args.save_dir.is_none();
    let save_dir = args.save_dir.unwrap_or_else(|| {
        std::env::temp_dir().join(format!("balddog_verify_{}", std::process::id()))
    });
    let system_dir = args.system_dir.unwrap_or_else(|| save_dir.clone());

    let _ = std::fs::create_dir_all(&save_dir);
    let _ = std::fs::create_dir_all(&system_dir);
    clear_deterministic_nvram(&save_dir, &args.rom);

    let mut player = match ReplayPlayer::from_gzip_file(&args.replay, expected_crc) {
        Ok(p) => p,
        Err(e) => {
            if own_temp_dir {
                let _ = std::fs::remove_dir_all(&save_dir);
            }
            print_result(false, 0, 0, 0, 0, 0, Some(&format!("invalid_replay: {e}")));
            return ExitCode::from(1);
        }
    };

    let mut leaderboard = match LeaderboardEngine::new(&args.lboard) {
        Ok(lb) => lb,
        Err(e) => {
            if own_temp_dir {
                let _ = std::fs::remove_dir_all(&save_dir);
            }
            print_result(false, 0, 0, 0, 0, 0, Some(&format!("invalid_lboard: {e}")));
            return ExitCode::from(2);
        }
    };

    let mut core = match LibretroCore::load(&args.core, &system_dir, &save_dir) {
        Ok(c) => c,
        Err(e) => {
            if own_temp_dir {
                let _ = std::fs::remove_dir_all(&save_dir);
            }
            print_result(false, 0, 0, 0, 0, 0, Some(&format!("core_load_failed: {e}")));
            return ExitCode::from(2);
        }
    };

    if let Err(e) = core.load_game(&args.rom) {
        if own_temp_dir {
            let _ = std::fs::remove_dir_all(&save_dir);
        }
        print_result(false, 0, 0, 0, 0, 0, Some(&format!("rom_load_failed: {e}")));
        return ExitCode::from(2);
    }

    if !player.initial_state().is_empty() {
        if let Err(e) = core.load_state_bytes(player.initial_state()) {
            if own_temp_dir {
                let _ = std::fs::remove_dir_all(&save_dir);
            }
            print_result(
                false,
                0,
                0,
                0,
                0,
                0,
                Some(&format!("initial_state_failed: {e}")),
            );
            return ExitCode::from(1);
        }
    }

    let mut coin_tracker = CoinTracker::new();
    let mut best_score: u64 = 0;
    let mut triggered_score: u64 = 0;
    let mut frames: u64 = 0;

    while let Some(frame_inputs) = player.next_frame() {
        core.set_inputs(frame_inputs);
        core.run_frame();
        frames += 1;
        if let Some(ram) = core.system_ram() {
            let out = leaderboard.evaluate_frame(ram);
            coin_tracker.observe_frame(out.active, &frame_inputs);
            if coin_tracker.has_player_started() {
                if out.current_score > best_score {
                    best_score = out.current_score;
                }
                if let Some(trig) = out.triggered_score {
                    if trig > triggered_score {
                        triggered_score = trig;
                    }
                }
            }
        } else {
            coin_tracker.observe_frame(false, &frame_inputs);
        }
    }

    if own_temp_dir {
        let _ = std::fs::remove_dir_all(&save_dir);
    }

    let verified_score = best_score.max(triggered_score);
    let coins = coin_tracker.coins_inserted();

    let (valid, reason) = if coins > 1 {
        (false, Some("multiple_credits_detected"))
    } else if let Some(expected) = args.expected_score {
        if verified_score != expected {
            (false, Some("score_mismatch"))
        } else {
            (true, None)
        }
    } else {
        (true, None)
    };

    print_result(
        valid,
        verified_score,
        best_score,
        triggered_score,
        coins,
        frames,
        reason,
    );

    if valid {
        ExitCode::SUCCESS
    } else {
        ExitCode::from(1)
    }
}

#[cfg(test)]
mod tests {
    use std::path::Path;

    use super::compute_expected_rom_crc;

    #[test]
    fn compute_expected_rom_crc_returns_rom_load_failed_on_missing_or_dir() {
        let manifest_dir = Path::new(env!("CARGO_MANIFEST_DIR"));
        let missing = manifest_dir.join("missing_rom_for_verify_test.zip");
        let err = compute_expected_rom_crc(&missing).unwrap_err();
        assert!(
            err.starts_with("rom_load_failed: "),
            "unexpected error format: {err}"
        );

        let dir_err = compute_expected_rom_crc(manifest_dir).unwrap_err();
        assert!(
            dir_err.starts_with("rom_load_failed: "),
            "unexpected dir error format: {dir_err}"
        );

        let valid = manifest_dir.join("Cargo.toml");
        assert!(compute_expected_rom_crc(&valid).is_ok());
    }
}
