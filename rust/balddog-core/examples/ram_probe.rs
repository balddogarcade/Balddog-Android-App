//! Utilidad de desarrollo para inspeccionar `RETRO_MEMORY_SYSTEM_RAM` en `libfbneo_libretro.so`
//! y validar expresiones `rcheevos` contra una ROM real.

use std::path::PathBuf;

use balddog_core::retro::core::LibretroCore;
use balddog_core::retro::leaderboard::LeaderboardEngine;

const BTN_B: u16 = 1 << 0;
const BTN_Y: u16 = 1 << 1;
const BTN_SELECT: u16 = 1 << 2;
const BTN_START: u16 = 1 << 3;
const BTN_UP: u16 = 1 << 4;
const BTN_DOWN: u16 = 1 << 5;
const BTN_LEFT: u16 = 1 << 6;
const BTN_RIGHT: u16 = 1 << 7;
const BTN_A: u16 = 1 << 8;

fn press_simple(core: &mut LibretroCore, mask: u16, hold: u32, wait: u32) {
    for _ in 0..hold {
        core.set_inputs([mask, 0, 0, 0]);
        core.run_frame();
    }
    core.set_inputs([0, 0, 0, 0]);
    for _ in 0..wait {
        core.run_frame();
    }
}

fn decode_bcd_be(bytes: &[u8]) -> Option<u64> {
    let mut val = 0u64;
    for &b in bytes {
        let hi = (b >> 4) & 0x0F;
        let lo = b & 0x0F;
        if hi > 9 || lo > 9 {
            return None;
        }
        val = val * 100 + (hi as u64) * 10 + (lo as u64);
    }
    Some(val)
}

fn run_scan(core: &mut LibretroCore, snap_path: Option<&str>) {
    press_simple(core, 0, 0, 1400);
fn run_scan(core: &mut LibretroCore, snap_path: Option<&str>, boot_frames: u32, combat_steps: u32) {
    press_simple(core, 0, 0, boot_frames);
    let ram_boot = core.system_ram().unwrap_or(&[]).to_vec();
    press_simple(core, BTN_SELECT, 6, 30);
    press_simple(core, BTN_SELECT, 6, 30);
    let ram_coin = core.system_ram().unwrap_or(&[]).to_vec();
    press_simple(core, BTN_START, 6, 180);
    press_simple(core, BTN_START, 6, 120);
    press_simple(core, BTN_A, 6, 60);
    press_simple(core, BTN_B, 6, 60);
    press_simple(core, BTN_Y, 6, 60);
    press_simple(core, BTN_B, 6, 60);
    press_simple(core, BTN_START, 6, 420);
    let ram_s0 = core.system_ram().unwrap_or(&[]).to_vec();

    print!("CREDITS(0->2->0):");
    for i in 0..ram_boot.len().min(ram_s0.len()) {
        if ram_boot[i] == 0 && ram_coin[i] >= 1 && ram_s0[i] == 0 {
            print!(" 0x{i:04X}");
        }
    }
    println!();

    print!("P1_ACTIVE(0->0->1):");
    for i in 0..ram_boot.len().min(ram_s0.len()) {
        if ram_boot[i] == 0 && ram_coin[i] == 0 && ram_s0[i] == 1 {
            print!(" 0x{i:04X}");
        }
    }
    println!();

    let start_off = if ram_s0.len() == 65536 { 0x8000 } else { 0 };
    for step in 0..70 {
    for step in 0..combat_steps {
        press_simple(core, BTN_RIGHT, 8, 0);
        press_simple(core, BTN_A, 4, 3);
        press_simple(core, BTN_B, 4, 3);
        press_simple(core, BTN_Y, 4, 3);
        if step % 10 == 5 {
            press_simple(core, BTN_DOWN | BTN_B, 6, 4);
        }
    }
    let ram_s1 = core.system_ram().unwrap_or(&[]).to_vec();
    for step in 0..70 {
    for step in 0..combat_steps {
        let dir = if step % 12 < 8 { BTN_RIGHT } else { BTN_LEFT };
        let v = if step % 8 == 0 { BTN_UP } else if step % 8 == 4 { BTN_DOWN } else { 0 };
        press_simple(core, dir | v, 6, 0);
        press_simple(core, BTN_A, 4, 3);
        press_simple(core, BTN_B, 4, 3);
        press_simple(core, BTN_Y, 4, 3);
    }
    let ram_s2 = core.system_ram().unwrap_or(&[]).to_vec();
    if let Some(val_str) = snap_path.and_then(|s| s.strip_prefix("find:")) {
        let target: u64 = val_str.parse().unwrap_or(0);
        println!("FIND {target} in 0x0000..0x{:X}:", ram_s2.len());
        for i in 0..ram_s2.len().saturating_sub(4) {
            if i % 2 == 0 {
                let sw2 = [ram_s2[i + 1], ram_s2[i], ram_s2[i + 3], ram_s2[i + 2]];
                if decode_bcd_be(&sw2) == Some(target) {
                    println!("  bcd4_sw @ 0x{i:04X} raw={:02X?}", &ram_s2[i..i + 4]);
                }
                if u32::from_be_bytes(sw2) as u64 == target {
                    println!("  u32_sw  @ 0x{i:04X} raw={:02X?}", &ram_s2[i..i + 4]);
                }
            }
            let b = &ram_s2[i..i + 4];
            if decode_bcd_be(b) == Some(target) {
                println!("  bcd4_be @ 0x{i:04X} raw={b:02X?}");
            }
            let le = [b[3], b[2], b[1], b[0]];
            if decode_bcd_be(&le) == Some(target) {
                println!("  bcd4_le @ 0x{i:04X} raw={b:02X?}");
            }
            if u32::from_be_bytes([b[0], b[1], b[2], b[3]]) as u64 == target {
                println!("  u32_be  @ 0x{i:04X} raw={b:02X?}");
            }
            if u32::from_le_bytes([b[0], b[1], b[2], b[3]]) as u64 == target {
                println!("  u32_le  @ 0x{i:04X} raw={b:02X?}");
            }
        }
        return;
    }
    if let Some(path) = snap_path {
        core.take_video_frame(|w, h, rgba| {
            let header = format!("P6\n{w} {h}\n255\n");
            let mut out = header.into_bytes();
            for chunk in rgba.chunks_exact(4) {
                out.extend_from_slice(&chunk[..3]);
            }
            let _ = std::fs::write(path, out);
        });
    }

    println!("SCORE_CANDIDATES(0 <= s1 <= s2, s2 > 0):");
    for i in (start_off..ram_s0.len().saturating_sub(4)).step_by(2) {
        let sw0 = [ram_s0[i + 1], ram_s0[i], ram_s0[i + 3], ram_s0[i + 2]];
        let sw1 = [ram_s1[i + 1], ram_s1[i], ram_s1[i + 3], ram_s1[i + 2]];
        let sw2 = [ram_s2[i + 1], ram_s2[i], ram_s2[i + 3], ram_s2[i + 2]];
        if let (Some(b0), Some(b1), Some(b2)) = (decode_bcd_be(&sw0), decode_bcd_be(&sw1), decode_bcd_be(&sw2)) {
            if b0 == 0 && b1 <= b2 && b2 >= 100 && b2 <= 100_000 && b2 % 10 == 0 && b1 % 10 == 0 {
                println!("  bcd4_sw @ 0x{i:04X}: 0 -> {b1} -> {b2} raw={:02X?}", &ram_s2[i..i + 4]);
            }
        }
        let u0 = u32::from_be_bytes(sw0);
        let u1 = u32::from_be_bytes(sw1);
        let u2 = u32::from_be_bytes(sw2);
        if u0 == 0 && u1 <= u2 && u2 >= 100 && u2 <= 100_000 && u2 % 10 == 0 && u1 % 10 == 0 {
            println!("  u32_sw  @ 0x{i:04X}: 0 -> {u1} -> {u2} raw={:02X?}", &ram_s2[i..i + 4]);
        }
    }
}

fn run_auto_probe(core: &mut LibretroCore) {
    let mut lb = LeaderboardEngine::new(
        "STA:0xHA855=1_0xHAA7D>0::CAN:0=1::SUB:0xHA855=0::VAL:A:b0x AA74*10000_M:b0x AA76",
    )
    .expect("captcomm lboard válido");

    let step_press = |core: &mut LibretroCore, lb: &mut LeaderboardEngine, mask: u16, hold: u32, wait: u32| {
        for _ in 0..hold {
            core.set_inputs([mask, 0, 0, 0]);
            core.run_frame();
            if let Some(ram) = core.system_ram() {
                let out = lb.evaluate_frame(ram);
                if let Some(s) = out.triggered_score {
                    println!("TRIGGERED! score={s}");
                }
            }
        }
        core.set_inputs([0, 0, 0, 0]);
        for _ in 0..wait {
            core.run_frame();
            if let Some(ram) = core.system_ram() {
                let out = lb.evaluate_frame(ram);
                if let Some(s) = out.triggered_score {
                    println!("TRIGGERED! score={s}");
                }
            }
        }
    };

    step_press(core, &mut lb, 0, 0, 500);
    step_press(core, &mut lb, BTN_SELECT, 6, 30);
    step_press(core, &mut lb, BTN_START, 6, 180);
    step_press(core, &mut lb, BTN_B, 6, 60);
    step_press(core, &mut lb, BTN_B, 6, 60);
    step_press(core, &mut lb, BTN_START, 6, 420);

    for _ in 0..55 {
        step_press(core, &mut lb, BTN_RIGHT, 6, 0);
        step_press(core, &mut lb, BTN_B, 4, 4);
    }
    step_press(core, &mut lb, BTN_DOWN, 22, 0);
    for _ in 0..15 {
        step_press(core, &mut lb, BTN_B, 4, 6);
    }
    if let Some(ram) = core.system_ram() {
        let out = lb.evaluate_frame(ram);
        println!(
            "After combat: active={} current_score={} AA74={:02X?} A855={} AA7D={}",
            out.active, out.current_score, &ram[0xAA74..0xAA78], ram[0xA855], ram[0xAA7D]
        );
    }
    for step in 0..12 {
        step_press(core, &mut lb, 0, 0, 600);
        if let Some(ram) = core.system_ram() {
            println!("Idle +{}f: A855={} AA7D={} AA74={:02X?}", (step + 1) * 600, ram[0xA855], ram[0xAA7D], &ram[0xAA74..0xAA78]);
        }
    }
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 5 {
        eprintln!(
            "Uso: ram_probe <core.so> <rom.zip> <system_dir> <save_dir> [--probe | frames [lboard_def]]"
        );
        std::process::exit(2);
    }

    let core_path = PathBuf::from(&args[1]);
    let rom_path = PathBuf::from(&args[2]);
    let system_dir = PathBuf::from(&args[3]);
    let save_dir = PathBuf::from(&args[4]);

    let mut core = LibretroCore::load(&core_path, &system_dir, &save_dir)
        .expect("No se pudo cargar el core Libretro");
    core.load_game(&rom_path).expect("No se pudo cargar la ROM");

    let ram_len = core.system_ram().map(|r| r.len()).unwrap_or(0);
    println!("ROM cargada. RETRO_MEMORY_SYSTEM_RAM = {ram_len} bytes");

    if args.get(5).map(String::as_str) == Some("--probe") {
        run_auto_probe(&mut core);
        return;
    }
    if args.get(5).map(String::as_str) == Some("--scan") {
        run_scan(&mut core, args.get(6).map(String::as_str));
        run_scan(&mut core, args.get(6).map(String::as_str), 1400, 70);
        return;
    }
    if args.get(5).map(String::as_str) == Some("--scan-at") {
        let boot: u32 = args.get(6).and_then(|s| s.parse().ok()).unwrap_or(1400);
        let steps: u32 = args.get(7).and_then(|s| s.parse().ok()).unwrap_or(70);
        run_scan(&mut core, args.get(8).map(String::as_str), boot, steps);
        return;
    }

    let frames: u32 = args.get(5).and_then(|s| s.parse().ok()).unwrap_or(600);
    let lboard_def = args.get(6).map(String::as_str);

    let mut lb = lboard_def.map(|def| {
        LeaderboardEngine::new(def).expect("Definición rcheevos inválida")
    });

    for f in 0..frames {
        core.run_frame();
        if let (Some(ref mut engine), Some(ram)) = (&mut lb, core.system_ram()) {
            let out = engine.evaluate_frame(ram);
            if out.triggered_score.is_some() || (f % 120 == 0 && out.active) {
                println!(
                    "frame={f} active={} score={} triggered={:?}",
                    out.active, out.current_score, out.triggered_score
                );
            }
        }
    }

    if let Some(ram) = core.system_ram() {
        let non_zero = ram.iter().filter(|&&b| b != 0).count();
        println!("Tras {frames} frames: {non_zero}/{} bytes distintos de cero", ram.len());
    }
}
