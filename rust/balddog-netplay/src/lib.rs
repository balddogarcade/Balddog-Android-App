use thiserror::Error;

#[derive(Debug, Error)]
pub enum NetplayError {
    #[error("Error de transporte de red: {0}")]
    Transport(String),

    #[error("Tiempo de espera agotado para reconexión")]
    ReconnectTimeout,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum NetplayMode {
    Local,
    Lan,
    OnlineP2p,
}
