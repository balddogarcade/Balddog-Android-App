#[cfg(target_os = "android")]
mod jni_surface;

use std::net::SocketAddr;
use std::path::{Path, PathBuf};
use std::sync::{Mutex, MutexGuard, Once, OnceLock};

use balddog_core::audio::engine::set_global_ducking;
use balddog_core::retro::core::{rotate_rgba, LibretroCore};
use balddog_core::retro::replay::{compute_file_crc32, ReplayPlayer};
use balddog_core::session::{clear_deterministic_nvram, EmulationSession, SessionControl};
use balddog_core::{engine_info, recent_logs, record_log, CoreError};
use balddog_netplay::voice::{VoicePeerEndpoint, VoiceSession, VoiceSessionConfig};
use balddog_netplay::{discover_stun_endpoint, NetplayError, NetplaySessionConfig, PeerEndpoint};
use thiserror::Error;

uniffi::setup_scaffolding!();

static SESSION_LIFECYCLE_LOCK: OnceLock<Mutex<()>> = OnceLock::new();
static ACTIVE_SESSION: OnceLock<Mutex<Option<EmulationSession>>> = OnceLock::new();
static STOPPING_SESSION_CTRL: OnceLock<Mutex<Option<SessionControl>>> = OnceLock::new();
static ACTIVE_VOICE: OnceLock<Mutex<Option<VoiceSession>>> = OnceLock::new();

fn lifecycle_lock() -> &'static Mutex<()> {
    SESSION_LIFECYCLE_LOCK.get_or_init(|| Mutex::new(()))
}

fn lock_unpoisoned<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    match m.lock() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    }
}

fn session_slot() -> &'static Mutex<Option<EmulationSession>> {
    ACTIVE_SESSION.get_or_init(|| Mutex::new(None))
}

fn stopping_session_slot() -> &'static Mutex<Option<SessionControl>> {
    STOPPING_SESSION_CTRL.get_or_init(|| Mutex::new(None))
}

fn voice_slot() -> &'static Mutex<Option<VoiceSession>> {
    ACTIVE_VOICE.get_or_init(|| Mutex::new(None))
}

#[cfg(any(target_os = "android", test))]
pub(crate) fn clone_session_control() -> Option<SessionControl> {
    if let Some(ctrl) = lock_unpoisoned(session_slot())
        .as_ref()
        .map(|s| s.control())
    {
        return Some(ctrl);
    }
    lock_unpoisoned(stopping_session_slot()).clone()
}

pub(crate) fn with_active_session<R>(f: impl FnOnce(&mut EmulationSession) -> R) -> Option<R> {
    let mut guard = lock_unpoisoned(session_slot());
    guard.as_mut().map(f)
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
    pub synchronized: bool,
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

static PANIC_HOOK_INIT: Once = Once::new();

#[cfg(target_os = "android")]
fn write_android_panic_log(msg: &str) {
    use std::ffi::CString;
    extern "C" {
        fn __android_log_write(
            prio: std::os::raw::c_int,
            tag: *const std::os::raw::c_char,
            text: *const std::os::raw::c_char,
        ) -> std::os::raw::c_int;
    }
    const ANDROID_LOG_FATAL: std::os::raw::c_int = 7;
    if let (Ok(tag), Ok(text)) = (CString::new("balddog-panic"), CString::new(msg)) {
        unsafe {
            __android_log_write(ANDROID_LOG_FATAL, tag.as_ptr(), text.as_ptr());
        }
    }
}

#[uniffi::export]
pub fn init_native_crash_reporting() {
    PANIC_HOOK_INIT.call_once(|| {
        std::panic::set_hook(Box::new(|info| {
            let payload = if let Some(s) = info.payload().downcast_ref::<&str>() {
                (*s).to_string()
            } else if let Some(s) = info.payload().downcast_ref::<String>() {
                s.clone()
            } else {
                "unknown panic payload".to_string()
            };
            let location = info
                .location()
                .map(|l| format!("{}:{}:{}", l.file(), l.line(), l.column()))
                .unwrap_or_else(|| "unknown location".to_string());
            let msg = format!("RUST PANIC at {location}: {payload}");
            record_log("ERROR", &msg);
            #[cfg(target_os = "android")]
            write_android_panic_log(&msg);
            std::process::abort();
        }));
    });
}

#[uniffi::export]
pub fn ping_engine(native_lib_dir: String) -> Result<EngineStatus, EngineBridgeError> {
    init_native_crash_reporting();
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
    let _lifecycle = lock_unpoisoned(lifecycle_lock());
    stop_game_inner();
    let core_path = PathBuf::from(native_lib_dir).join("libfbneo_libretro.so");
    let session = EmulationSession::start(
        core_path,
        PathBuf::from(rom_path),
        PathBuf::from(system_dir),
        PathBuf::from(save_dir),
        None,
        score_def,
    )?;
    #[cfg(target_os = "android")]
    let ctrl = session.control();
    {
        let mut guard = lock_unpoisoned(session_slot());
        *guard = Some(session);
    }
    #[cfg(target_os = "android")]
    jni_surface::attach_current_surface(&ctrl);
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
    let _lifecycle = lock_unpoisoned(lifecycle_lock());
    stop_game_inner();
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
        disconnect_timeout_secs: 20,
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
    #[cfg(target_os = "android")]
    let ctrl = session.control();
    {
        let mut guard = lock_unpoisoned(session_slot());
        *guard = Some(session);
    }
    #[cfg(target_os = "android")]
    jni_surface::attach_current_surface(&ctrl);
    Ok(())
}

#[uniffi::export]
pub fn discover_public_udp_endpoint(local_port: u16) -> Result<String, EngineBridgeError> {
    Ok(discover_stun_endpoint(local_port)?)
}

fn stop_game_inner() {
    stop_voice_session();
    let session_opt = {
        let mut guard = lock_unpoisoned(session_slot());
        let taken = guard.take();
        if let Some(ref session) = taken {
            *lock_unpoisoned(stopping_session_slot()) = Some(session.control());
        }
        taken
    };
    if let Some(mut session) = session_opt {
        session.stop();
        *lock_unpoisoned(stopping_session_slot()) = None;
    }
}

#[uniffi::export]
pub fn stop_game() {
    let _lifecycle = lock_unpoisoned(lifecycle_lock());
    stop_game_inner();
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
            synchronized: st.synchronized,
        }
    })
    .unwrap_or(NetplayHudInfo {
        ping_ms: 0,
        rollback_frames: 0,
        reconnecting: false,
        reconnect_secs_left: 0,
        session_ended: false,
        synchronized: false,
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
    let _lifecycle = lock_unpoisoned(lifecycle_lock());
    stop_game_inner();
    let core_path = PathBuf::from(native_lib_dir).join("libfbneo_libretro.so");
    let session = EmulationSession::start_replay(
        core_path,
        PathBuf::from(rom_path),
        PathBuf::from(system_dir),
        PathBuf::from(save_dir),
        PathBuf::from(replay_path),
        score_def,
    )?;
    #[cfg(target_os = "android")]
    let ctrl = session.control();
    {
        let mut guard = lock_unpoisoned(session_slot());
        *guard = Some(session);
    }
    #[cfg(target_os = "android")]
    jni_surface::attach_current_surface(&ctrl);
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
pub fn seek_replay(target_frame: u64) {
    with_active_session(|s| s.seek_replay(target_frame));
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

#[derive(Debug, Clone, uniffi::Record)]
pub struct OfflineReplayMeta {
    pub width: u32,
    pub height: u32,
    pub fps: f64,
    pub sample_rate: u32,
    pub total_frames: u64,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct OfflineVideoFrame {
    pub width: u32,
    pub height: u32,
    pub rgba: Vec<u8>,
    pub frame_index: u64,
}

struct OfflineRendererInner {
    core: LibretroCore,
    player: ReplayPlayer,
    meta: OfflineReplayMeta,
    last_w: u32,
    last_h: u32,
    last_rgba: Vec<u8>,
}

#[derive(uniffi::Object)]
pub struct OfflineReplayRenderer {
    inner: Mutex<OfflineRendererInner>,
}

#[uniffi::export]
impl OfflineReplayRenderer {
    pub fn metadata(&self) -> OfflineReplayMeta {
        self.inner
            .lock()
            .map(|g| g.meta.clone())
            .unwrap_or(OfflineReplayMeta {
                width: 384,
                height: 224,
                fps: 60.0,
                sample_rate: 44100,
                total_frames: 0,
            })
    }

    pub fn step_frame(&self) -> Option<OfflineVideoFrame> {
        let mut guard = self.inner.lock().ok()?;
        let frame_inputs = guard.player.next_frame()?;
        let frame_index = guard.player.current_frame();
        guard.core.set_inputs(frame_inputs);
        guard.core.run_frame();
        let rot = guard.core.rotation();
        let mut out_w = guard.last_w;
        let mut out_h = guard.last_h;
        let mut out_rgba = Vec::new();
        guard.core.take_video_frame(|w, h, rgba| {
            let (rw, rh, rotated) = rotate_rgba(w, h, rgba, rot);
            out_w = rw;
            out_h = rh;
            out_rgba = rotated;
        });
        guard.core.take_audio_samples(|_| {});
        if !out_rgba.is_empty() {
            guard.last_w = out_w;
            guard.last_h = out_h;
            guard.last_rgba = out_rgba.clone();
        } else {
            out_rgba = guard.last_rgba.clone();
        }
        if out_rgba.is_empty() {
            let w = guard.meta.width.max(2);
            let h = guard.meta.height.max(2);
            out_w = w;
            out_h = h;
            out_rgba = vec![0u8; (w * h * 4) as usize];
        }
        Some(OfflineVideoFrame {
            width: out_w,
            height: out_h,
            rgba: out_rgba,
            frame_index,
        })
    }
}

#[uniffi::export]
pub fn open_offline_replay_renderer(
    native_lib_dir: String,
    rom_path: String,
    system_dir: String,
    save_dir: String,
    replay_path: String,
) -> Result<std::sync::Arc<OfflineReplayRenderer>, EngineBridgeError> {
    let _lifecycle = lock_unpoisoned(lifecycle_lock());
    stop_game_inner();
    let rom_pb = PathBuf::from(&rom_path);
    let save_pb = PathBuf::from(&save_dir);
    clear_deterministic_nvram(&save_pb, &rom_pb);

    let expected_crc = compute_file_crc32(&rom_pb).map_err(|e| EngineBridgeError::Core {
        detail: format!("No se pudo leer la ROM {:?}: {e}", rom_pb),
    })?;
    let player = ReplayPlayer::from_gzip_file(Path::new(&replay_path), Some(expected_crc))
        .map_err(|e| EngineBridgeError::Core {
            detail: format!("Replay inválido: {e}"),
        })?;
    let core_path = PathBuf::from(native_lib_dir).join("libfbneo_libretro.so");
    let mut core = LibretroCore::load(&core_path, Path::new(&system_dir), &save_pb)?;
    let av = core.load_game(&rom_pb)?;
    if !player.initial_state().is_empty() {
        core.load_state_bytes(player.initial_state())?;
    }
    let fps = if av.timing.fps > 10.0 {
        av.timing.fps
    } else {
        60.0
    };
    let rot = core.rotation();
    let raw_w = av.geometry.base_width.max(2);
    let raw_h = av.geometry.base_height.max(2);
    let (meta_w, meta_h) = if rot % 2 == 1 {
        (raw_h, raw_w)
    } else {
        (raw_w, raw_h)
    };
    let meta = OfflineReplayMeta {
        width: meta_w,
        height: meta_h,
        fps,
        sample_rate: av.timing.sample_rate.max(8000.0) as u32,
        total_frames: player.total_frames(),
    };
    Ok(std::sync::Arc::new(OfflineReplayRenderer {
        inner: Mutex::new(OfflineRendererInner {
            core,
            player,
            meta,
            last_w: 0,
            last_h: 0,
            last_rgba: Vec::new(),
        }),
    }))
}

#[cfg(test)]
mod tests {
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::Arc;
    use std::time::Duration;

    use balddog_core::session::{SessionCommand, SessionControl};

    use super::{clone_session_control, lock_unpoisoned, session_slot, stopping_session_slot};

    #[test]
    fn stopping_session_ctrl_keeps_detach_surface_sync_synchronized() {
        // Ensure clean slot state.
        *lock_unpoisoned(session_slot()) = None;
        *lock_unpoisoned(stopping_session_slot()) = None;
        assert!(clone_session_control().is_none());

        // Case 1: While STOPPING_SESSION_CTRL holds a control, clone_session_control()
        // returns it and detach_surface_sync() blocks until DetachSurface ack arrives.
        let (ctrl, cmd_rx) = SessionControl::channel_pair();
        *lock_unpoisoned(stopping_session_slot()) = Some(ctrl);

        let cloned =
            clone_session_control().expect("stopping session control must remain reachable");
        let completed = Arc::new(AtomicBool::new(false));
        let completed_bg = Arc::clone(&completed);

        let join = std::thread::spawn(move || {
            cloned.detach_surface_sync();
            completed_bg.store(true, Ordering::Release);
        });

        let ack_sender = match cmd_rx
            .recv_timeout(Duration::from_secs(2))
            .expect("expected DetachSurface command")
        {
            SessionCommand::DetachSurface { ack } => ack,
            _ => panic!("unexpected command received"),
        };

        std::thread::sleep(Duration::from_millis(30));
        assert!(
            !completed.load(Ordering::Acquire),
            "detach_surface_sync must remain blocked until DetachSurface ack is sent"
        );

        ack_sender.send(()).expect("ack send failed");
        join.join().expect("detach thread panicked");
        assert!(completed.load(Ordering::Acquire));

        // Case 2: Dropping the channel receiver (emulation thread exit) also unblocks detach.
        let cloned2 = clone_session_control().expect("stopping session control still reachable");
        let completed2 = Arc::new(AtomicBool::new(false));
        let completed2_bg = Arc::clone(&completed2);

        let join2 = std::thread::spawn(move || {
            cloned2.detach_surface_sync();
            completed2_bg.store(true, Ordering::Release);
        });

        let _pending_cmd = cmd_rx
            .recv_timeout(Duration::from_secs(2))
            .expect("expected second DetachSurface command");
        std::thread::sleep(Duration::from_millis(30));
        assert!(!completed2.load(Ordering::Acquire));

        drop(_pending_cmd);
        drop(cmd_rx);
        join2.join().expect("second detach thread panicked");
        assert!(completed2.load(Ordering::Acquire));

        *lock_unpoisoned(stopping_session_slot()) = None;
        assert!(clone_session_control().is_none());
    }
}
