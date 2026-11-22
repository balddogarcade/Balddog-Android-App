#[cfg(target_os = "android")]
mod jni_surface;

use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::{Mutex, OnceLock};

use balddog_core::audio::engine::set_global_ducking;
use balddog_core::session::EmulationSession;
use balddog_core::{engine_info, recent_logs, record_log, CoreError};
use balddog_netplay::voice::{VoicePeerEndpoint, VoiceSession, VoiceSessionConfig};
use balddog_netplay::{
    discover_stun_endpoint, NetplayError, NetplaySessionConfig, PeerEndpoint,
};
use thiserror::Error;

uniffi::setup_scaffolding!();

static ACTIVE_SESSION: OnceLock<Mutex<Option<EmulationSession>>> = OnceLock::new();
static ACTIVE_VOICE: OnceLock<Mutex<Option<VoiceSession>>> = OnceLock::new();

fn session_slot() -> &'static Mutex<Option<EmulationSession>> {
    ACTIVE_SESSION.get_or_init(|| Mutex::new(None))
}

fn voice_slot() -> &'static Mutex<Option<VoiceSession>> {
    ACTIVE_VOICE.get_or_init(|| Mutex::new(None))
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

impl From<NetplayError> for EngineBridgeError {
    fn from(err: NetplayError) -> Self {
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

#[derive(Debug, Clone, uniffi::Record)]
pub struct NetplayHudInfo {
    pub ping_ms: u32,
    pub rollback_frames: u32,
    pub reconnecting: bool,
    pub reconnect_secs_left: u32,
    pub session_ended: bool,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct VoiceHudInfo {
    pub local_speaking: bool,
    pub remote_speaking_mask: u8,
    pub active_peers: u8,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ScoreHudInfo {
    pub current_score: u64,
    pub best_session_score: u64,
    pub pending_submit_score: u64,
    pub ranking_eligible: bool,
    pub credits_used: u8,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ReplayHudInfo {
    pub is_replay: bool,
    pub current_frame: u64,
    pub total_frames: u64,
    pub speed: u8,
    pub paused: bool,
    pub finished: bool,
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
    start_game_with_score(native_lib_dir, rom_path, system_dir, save_dir, None)
}

#[uniffi::export]
pub fn start_game_with_score(
    native_lib_dir: String,
    rom_path: String,
    system_dir: String,
    save_dir: String,
    score_def: Option<String>,
) -> Result<(), EngineBridgeError> {
    stop_game();
    let core_path = PathBuf::from(native_lib_dir).join("libfbneo_libretro.so");
    let session = EmulationSession::start(
        core_path,
        PathBuf::from(rom_path),
        PathBuf::from(system_dir),
        PathBuf::from(save_dir),
        None,
        score_def,
    )?;
    if let Ok(mut guard) = session_slot().lock() {
        *guard = Some(session);
    }
    Ok(())
}

#[uniffi::export]
pub fn start_netplay_game(
    native_lib_dir: String,
    rom_path: String,
    system_dir: String,
    save_dir: String,
    local_port: u16,
    local_player_handle: u8,
    num_players: u8,
    peer_handles: Vec<u8>,
    peer_addrs: Vec<String>,
    input_delay: u8,
) -> Result<(), EngineBridgeError> {
    stop_game();
    if peer_handles.len() != peer_addrs.len() {
        return Err(EngineBridgeError::Core {
            detail: "peer_handles y peer_addrs deben tener el mismo tamaño".into(),
        });
    }

    let mut peers = Vec::with_capacity(peer_addrs.len());
    for (h, raw_addr) in peer_handles.into_iter().zip(peer_addrs.into_iter()) {
        let addr: SocketAddr = raw_addr.parse().map_err(|e| EngineBridgeError::Core {
            detail: format!("Dirección de par inválida '{raw_addr}': {e}"),
        })?;
        peers.push(PeerEndpoint {
            player_handle: h as usize,
            addr,
        });
    }

    let netplay_cfg = NetplaySessionConfig {
        local_port,
        local_player_handle: local_player_handle as usize,
        num_players: num_players as usize,
        peers,
        input_delay: input_delay as usize,
        disconnect_timeout_secs: 8,
    };

    let core_path = PathBuf::from(native_lib_dir).join("libfbneo_libretro.so");
    let session = EmulationSession::start(
        core_path,
        PathBuf::from(rom_path),
        PathBuf::from(system_dir),
        PathBuf::from(save_dir),
        Some(netplay_cfg),
        None,
    )?;
    if let Ok(mut guard) = session_slot().lock() {
        *guard = Some(session);
    }
    Ok(())
}

#[uniffi::export]
pub fn discover_public_udp_endpoint(local_port: u16) -> Result<String, EngineBridgeError> {
    Ok(discover_stun_endpoint(local_port)?)
}

#[uniffi::export]
pub fn stop_game() {
    stop_voice_session();
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
pub fn get_netplay_hud_stats() -> NetplayHudInfo {
    with_active_session(|s| {
        let st = s.netplay_stats();
        NetplayHudInfo {
            ping_ms: st.ping_ms,
            rollback_frames: st.rollback_frames,
            reconnecting: st.reconnecting,
            reconnect_secs_left: st.reconnect_secs_left,
            session_ended: st.session_ended,
        }
    })
    .unwrap_or(NetplayHudInfo {
        ping_ms: 0,
        rollback_frames: 0,
        reconnecting: false,
        reconnect_secs_left: 0,
        session_ended: false,
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

#[uniffi::export]
pub fn start_voice_session(
    local_port: u16,
    room_id: String,
    local_player_handle: u8,
    peer_handles: Vec<u8>,
    peer_addrs: Vec<String>,
) -> Result<(), EngineBridgeError> {
    stop_voice_session();
    if peer_handles.len() != peer_addrs.len() {
        return Err(EngineBridgeError::Core {
            detail: "peer_handles y peer_addrs deben tener el mismo tamaño".into(),
        });
    }

    let mut peers = Vec::with_capacity(peer_addrs.len());
    for (h, raw_addr) in peer_handles.into_iter().zip(peer_addrs.into_iter()) {
        let addr: SocketAddr = raw_addr.parse().map_err(|e| EngineBridgeError::Core {
            detail: format!("Dirección de voz inválida '{raw_addr}': {e}"),
        })?;
        peers.push(VoicePeerEndpoint {
            player_handle: h,
            addr,
        });
    }

    let cfg = VoiceSessionConfig {
        local_port,
        room_id,
        local_player_handle,
        peers,
    };
    let session = VoiceSession::start(cfg)?;
    if let Ok(mut guard) = voice_slot().lock() {
        *guard = Some(session);
    }
    Ok(())
}

#[uniffi::export]
pub fn stop_voice_session() {
    set_global_ducking(false);
    if let Ok(mut guard) = voice_slot().lock() {
        if let Some(mut session) = guard.take() {
            session.stop();
        }
    }
}

#[uniffi::export]
pub fn push_voice_capture_pcm(samples: Vec<i16>) {
    if let Ok(guard) = voice_slot().lock() {
        if let Some(session) = guard.as_ref() {
            session.push_capture_pcm(&samples);
        }
    }
}

#[uniffi::export]
pub fn pull_voice_playback_pcm(max_samples: u32) -> Vec<i16> {
    if let Ok(guard) = voice_slot().lock() {
        if let Some(session) = guard.as_ref() {
            return session.pull_playback_pcm(max_samples as usize);
        }
    }
    Vec::new()
}

#[uniffi::export]
pub fn set_voice_controls(mic_enabled: bool, listen_enabled: bool, volume: f32, paused: bool) {
    if let Ok(guard) = voice_slot().lock() {
        if let Some(session) = guard.as_ref() {
            session.set_controls(mic_enabled, listen_enabled, volume, paused);
        }
    }
    if !listen_enabled {
        set_global_ducking(false);
    }
}

#[uniffi::export]
pub fn get_voice_hud_state() -> VoiceHudInfo {
    if let Ok(guard) = voice_slot().lock() {
        if let Some(session) = guard.as_ref() {
            let st = session.hud_state();
            return VoiceHudInfo {
                local_speaking: st.local_speaking,
                remote_speaking_mask: st.remote_speaking_mask,
                active_peers: st.active_peers,
            };
        }
    }
    VoiceHudInfo {
        local_speaking: false,
        remote_speaking_mask: 0,
        active_peers: 0,
    }
}

#[uniffi::export]
pub fn set_audio_ducking(enabled: bool) {
    set_global_ducking(enabled);
}

#[uniffi::export]
pub fn get_score_hud_info() -> ScoreHudInfo {
    with_active_session(|s| {
        let st = s.score_hud_stats();
        ScoreHudInfo {
            current_score: st.current_score,
            best_session_score: st.best_session_score,
            pending_submit_score: st.pending_submit_score,
            ranking_eligible: st.ranking_eligible,
            credits_used: st.credits_used,
        }
    })
    .unwrap_or(ScoreHudInfo {
        current_score: 0,
        best_session_score: 0,
        pending_submit_score: 0,
        ranking_eligible: false,
        credits_used: 1,
    })
}

#[uniffi::export]
pub fn acknowledge_score_submit() {
    with_active_session(|s| s.acknowledge_score_submit());
}

#[uniffi::export]
pub fn start_replay_game(
    native_lib_dir: String,
    rom_path: String,
    system_dir: String,
    save_dir: String,
    replay_path: String,
    score_def: Option<String>,
) -> Result<(), EngineBridgeError> {
    stop_game();
    let core_path = PathBuf::from(native_lib_dir).join("libfbneo_libretro.so");
    let session = EmulationSession::start_replay(
        core_path,
        PathBuf::from(rom_path),
        PathBuf::from(system_dir),
        PathBuf::from(save_dir),
        PathBuf::from(replay_path),
        score_def,
    )?;
    if let Ok(mut guard) = session_slot().lock() {
        *guard = Some(session);
    }
    Ok(())
}

#[uniffi::export]
pub fn set_replay_speed(speed: u8) {
    with_active_session(|s| s.set_replay_speed(speed));
}

#[uniffi::export]
pub fn restart_replay() {
    with_active_session(|s| s.restart_replay());
}

#[uniffi::export]
pub fn get_replay_hud_info() -> ReplayHudInfo {
    with_active_session(|s| {
        let st = s.replay_hud_stats();
        ReplayHudInfo {
            is_replay: st.is_replay,
            current_frame: st.current_frame,
            total_frames: st.total_frames,
            speed: st.speed,
            paused: st.paused,
            finished: st.finished,
        }
    })
    .unwrap_or(ReplayHudInfo {
        is_replay: false,
        current_frame: 0,
        total_frames: 0,
        speed: 1,
        paused: false,
        finished: false,
    })
}

#[uniffi::export]
pub fn export_recorded_replay(dest_path: String) -> Result<u64, EngineBridgeError> {
    let res = with_active_session(|s| s.export_recorded_replay(PathBuf::from(dest_path)));
    match res {
        Some(r) => Ok(r?),
        None => Err(EngineBridgeError::Core {
            detail: "No hay partida activa".into(),
        }),
    }
}

#[uniffi::export]
pub fn capture_screenshot_png(dest_path: String) -> Result<(), EngineBridgeError> {
    let res = with_active_session(|s| s.capture_screenshot_png(PathBuf::from(dest_path)));
    match res {
        Some(r) => Ok(r?),
        None => Err(EngineBridgeError::Core {
            detail: "No hay partida activa".into(),
        }),
    }
}
