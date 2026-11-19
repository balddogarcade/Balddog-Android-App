use balddog_core::{engine_info, recent_logs, record_log, CoreError};
use thiserror::Error;

uniffi::setup_scaffolding!();

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
pub fn get_recent_logs() -> Vec<String> {
    recent_logs()
}

#[uniffi::export]
pub fn log_from_android(level: String, message: String) {
    record_log(&level, &message);
}
