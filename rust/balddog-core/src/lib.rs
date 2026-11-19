pub mod audio;
pub mod retro;
pub mod session;
pub mod video;

use std::collections::VecDeque;
use std::sync::{OnceLock, RwLock};
use thiserror::Error;

const MAX_LOG_LINES: usize = 200;

static LOG_BUFFER: OnceLock<RwLock<VecDeque<String>>> = OnceLock::new();

fn log_buffer() -> &'static RwLock<VecDeque<String>> {
    LOG_BUFFER.get_or_init(|| RwLock::new(VecDeque::with_capacity(MAX_LOG_LINES)))
}

/// Registra una línea en el buffer circular interno sin hacer panic si el lock está envenenado.
pub fn record_log(level: &str, message: &str) {
    let entry = format!("[{level}] {message}");
    if let Ok(mut guard) = log_buffer().write() {
        if guard.len() >= MAX_LOG_LINES {
            guard.pop_front();
        }
        guard.push_back(entry);
    }
}

/// Devuelve las últimas líneas de log registradas en memoria.
pub fn recent_logs() -> Vec<String> {
    match log_buffer().read() {
        Ok(guard) => guard.iter().cloned().collect(),
        Err(_) => vec!["[ERROR] Log buffer lock poisoned".to_string()],
    }
}

#[derive(Debug, Error)]
pub enum CoreError {
    #[error("Error de inicialización del motor: {0}")]
    Init(String),

    #[error("Error al cargar el core libretro: {0}")]
    CoreLoad(String),

    #[error("Error de estado interno: {0}")]
    State(String),
}

#[derive(Debug, Clone)]
pub struct EngineInfo {
    pub engine_version: String,
    pub target_arch: String,
    pub fbneo_ready: bool,
}

pub fn engine_info(native_lib_dir: &str) -> Result<EngineInfo, CoreError> {
    let fbneo_path = std::path::Path::new(native_lib_dir).join("libfbneo_libretro.so");
    let fbneo_ready = fbneo_path.exists();
    record_log(
        "INFO",
        &format!(
            "Engine ping OK (arch={}, fbneo_ready={})",
            std::env::consts::ARCH,
            fbneo_ready
        ),
    );
    Ok(EngineInfo {
        engine_version: env!("CARGO_PKG_VERSION").to_string(),
        target_arch: std::env::consts::ARCH.to_string(),
        fbneo_ready,
    })
}
