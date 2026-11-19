#[cfg(target_os = "android")]
mod jni_surface;

use std::path::PathBuf;
use std::sync::{Mutex, OnceLock};

use balddog_core::session::EmulationSession;
use balddog_core::{engine_info, recent_logs, record_log, CoreError};
use thiserror::Error;

uniffi::setup_scaffolding!();

static ACTIVE_SESSION: OnceLock<Mutex<Option<EmulationSession>>> = OnceLock::new();

fn session_slot() -> &'static Mutex<Option<EmulationSession>> {
    ACTIVE_SESSION.get_or_init(|| Mutex::new(None))
}

pub(crate) fn with_active_session<R>(f: impl FnOnce(&mut EmulationSession) -> R) -> Option<R> {
    if let Ok(mut guard) = session_slot().lock() {
        guard.as_mut().map(f)
    } else {
        None
    }
}

#[derive(Debug, Error, uniffi::Error)]
pub enum EngineBridgeError {
    #[error("{detail}")]
    Core { detail: String },
}

impl From<CoreError> for EngineBridgeError {
    fn from(err: CoreError) -> Self {
        Self::Core {
            detail: err.to_string(),
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct EngineStatus {
    pub engine_version: String,
    pub target_arch: String,
    pub fbneo_ready: bool,
}

#[uniffi::export]
pub fn ping_engine(native_lib_dir: String) -> Result<EngineStatus, EngineBridgeError> {
    let info = engine_info(&native_lib_dir)?;
    Ok(EngineStatus {
        engine_version: info.engine_version,
        target_arch: info.target_arch,
        fbneo_ready: info.fbneo_ready,
    })
}

#[uniffi::export]
pub fn start_game(
    native_lib_dir: String,
    rom_path: String,
    system_dir: String,
    save_dir: String,
) -> Result<(), EngineBridgeError> {
    stop_game();
    let core_path = PathBuf::from(native_lib_dir).join("libfbneo_libretro.so");
    let session = EmulationSession::start(
        core_path,
        PathBuf::from(rom_path),
        PathBuf::from(system_dir),
        PathBuf::from(save_dir),
    )?;
    if let Ok(mut guard) = session_slot().lock() {
        *guard = Some(session);
    }
    Ok(())
}

#[uniffi::export]
pub fn stop_game() {
    if let Ok(mut guard) = session_slot().lock() {
        if let Some(mut session) = guard.take() {
            session.stop();
        }
    }
}

#[uniffi::export]
pub fn set_game_paused(paused: bool) {
    with_active_session(|s| s.set_paused(paused));
}

#[uniffi::export]
pub fn set_player_input(port: u8, mask: u16) {
    with_active_session(|s| s.set_input(port as usize, mask));
}

#[uniffi::export]
pub fn save_game_state(path: String) -> Result<(), EngineBridgeError> {
    let res = with_active_session(|s| s.save_state(PathBuf::from(path)));
    match res {
        Some(r) => Ok(r?),
        None => Err(EngineBridgeError::Core {
            detail: "No hay partida activa".into(),
        }),
    }
}

#[uniffi::export]
pub fn load_game_state(path: String) -> Result<(), EngineBridgeError> {
    let res = with_active_session(|s| s.load_state(PathBuf::from(path)));
    match res {
        Some(r) => Ok(r?),
        None => Err(EngineBridgeError::Core {
            detail: "No hay partida activa".into(),
        }),
    }
}

#[uniffi::export]
pub fn get_current_fps() -> f32 {
    with_active_session(|s| s.current_fps()).unwrap_or(0.0)
}

#[uniffi::export]
pub fn get_recent_logs() -> Vec<String> {
    recent_logs()
}

#[uniffi::export]
pub fn log_from_android(level: String, message: String) {
    record_log(&level, &message);
}
