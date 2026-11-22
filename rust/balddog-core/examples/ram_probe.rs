//! Utilidad de desarrollo para inspeccionar `RETRO_MEMORY_SYSTEM_RAM` en `libfbneo_libretro.so`
//! y validar expresiones `rcheevos` contra una ROM real.

use std::path::PathBuf;

use balddog_core::retro::core::LibretroCore;
use balddog_core::retro::leaderboard::LeaderboardEngine;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 5 {
        eprintln!(
            "Uso: ram_probe <core.so> <rom.zip> <system_dir> <save_dir> [frames] [lboard_def]"
        );
        std::process::exit(2);
    }

    let core_path = PathBuf::from(&args[1]);
    let rom_path = PathBuf::from(&args[2]);
    let system_dir = PathBuf::from(&args[3]);
    let save_dir = PathBuf::from(&args[4]);
    let frames: u32 = args.get(5).and_then(|s| s.parse().ok()).unwrap_or(600);
    let lboard_def = args.get(6).map(String::as_str);

    let mut core = LibretroCore::load(&core_path, &system_dir, &save_dir)
        .expect("No se pudo cargar el core Libretro");
    core.load_game(&rom_path).expect("No se pudo cargar la ROM");

    let ram_len = core.system_ram().map(|r| r.len()).unwrap_or(0);
    println!("ROM cargada. RETRO_MEMORY_SYSTEM_RAM = {ram_len} bytes");

    let mut lb = lboard_def.map(|def| {
        LeaderboardEngine::new(def).expect("Definición rcheevos inválida")
    });

    for f in 0..frames {
        core.run_frame();
        if let (Some(ref mut engine), Some(ram)) = (&mut lb, core.system_ram()) {
            let out = engine.evaluate_frame(ram);
            if out.triggered_score.is_some() || (f % 120 == 0 && out.active) {
                println!("frame={f} active={} score={} triggered={:?}", out.active, out.current_score, out.triggered_score);
            }
        }
    }

    if let Some(ram) = core.system_ram() {
        let non_zero = ram.iter().filter(|&&b| b != 0).count();
        println!("Tras {frames} frames: {non_zero}/{} bytes distintos de cero", ram.len());
    }
}
