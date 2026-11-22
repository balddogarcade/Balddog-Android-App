//! Utilidad de desarrollo para inspeccionar `RETRO_MEMORY_SYSTEM_RAM` en `libfbneo_libretro.so`
//! y validar expresiones `rcheevos` contra una ROM real.

use std::path::PathBuf;

use balddog_core::retro::core::LibretroCore;
use balddog_core::retro::leaderboard::LeaderboardEngine;

const BTN_B: u16 = 1 << 0;
const BTN_SELECT: u16 = 1 << 2;
const BTN_START: u16 = 1 << 3;
const BTN_DOWN: u16 = 1 << 5;
const BTN_RIGHT: u16 = 1 << 7;

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
