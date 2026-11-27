use std::ffi::c_void;
use std::io::BufWriter;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, AtomicU8, Ordering};
use std::sync::Arc;
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use balddog_netplay::{build_p2p_session, NetplayHudStats, NetplaySessionConfig};
use crossbeam_channel::{bounded, unbounded, Sender};
use ggrs::{GgrsEvent, GgrsRequest, InputStatus, SessionState};

use crate::audio::engine::AudioEngine;
use crate::retro::core::{rotate_rgba, LibretroCore};
use crate::retro::leaderboard::{LeaderboardEngine, LeaderboardFrameOutput};
use crate::retro::replay::{compute_file_crc32, ReplayPlayer, ReplayRecorder};
use crate::video::renderer::{effective_game_aspect, WgpuRenderer};
use crate::{record_log, CoreError};

pub enum SessionCommand {
    AttachSurface {
        window_ptr: usize,
        width: u32,
        height: u32,
    },
    ResizeSurface {
        width: u32,
        height: u32,
    },
    DetachSurface {
        ack: Sender<()>,
    },
    SetPaused(bool),
    SetInput {
        port: usize,
        mask: u16,
    },
    SaveState {
        path: PathBuf,
        ack: Sender<Result<(), CoreError>>,
    },
    LoadState {
        path: PathBuf,
        ack: Sender<Result<(), CoreError>>,
    },
    SetReplaySpeed(u8),
    RestartReplay,
    SeekReplay(u64),
    ExportReplay {
        path: PathBuf,
        ack: Sender<Result<u64, CoreError>>,
    },
    CaptureScreenshot {
        path: PathBuf,
        ack: Sender<Result<(), CoreError>>,
    },
    Stop,
}

#[derive(Debug, Clone, Copy, Default)]
pub struct ScoreHudStats {
    pub current_score: u64,
    pub best_session_score: u64,
    pub pending_submit_score: u64,
    pub ranking_eligible: bool,
    pub credits_used: u8,
}

pub const RETRO_COIN_MASK: u16 = 1 << 2;
pub const RETRO_START_MASK: u16 = 1 << 3;

/// Rastrea créditos 1CC desde el frame 0 a nivel de bitmasks Libretro (`RETRO_COIN_MASK` y `RETRO_START_MASK`):
/// - Cuenta toda inserción de moneda (`P1..P4`) desde el arranque, pantalla de título y selección de personaje,
///   descalificando de inmediato si se cargan 2 o más créditos antes o durante la partida.
/// - No habilita la lectura de puntaje hasta que P1 pulsa START al menos una vez (`player_started == true`),
///   evitando que valores de attract mode contaminen el historial.
/// - Una vez iniciada la 1.ª partida (`runs_started >= 1`), cualquier moneda adicional (`post_first_run_coins`)
///   o un continue mediante `START` tras Game Over (`runs_started > 1`) descalifica de forma permanente.
#[derive(Debug, Clone, Default)]
pub struct CoinTracker {
    prev_coins: [bool; 4],
    prev_starts: [bool; 4],
    prev_active: bool,
    player_started: bool,
    saw_continue_input_since_inactive: bool,
    runs_started: u32,
    total_coins: u32,
    post_first_run_coins: u32,
}

impl CoinTracker {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn has_player_started(&self) -> bool {
        self.player_started
    }

    pub fn observe_frame(&mut self, active: bool, inputs: &[u16; 4]) -> u8 {
        if (inputs[0] & RETRO_START_MASK) != 0 {
            self.player_started = true;
        }

        let mut any_continue_edge = false;
        for (port, &mask) in inputs.iter().enumerate() {
            let coin_pressed = (mask & RETRO_COIN_MASK) != 0;
            let start_pressed = (mask & RETRO_START_MASK) != 0;
            let coin_rising = coin_pressed && !self.prev_coins[port];
            let start_rising = start_pressed && !self.prev_starts[port];

            if coin_rising {
                self.total_coins = self.total_coins.saturating_add(1);
                if self.runs_started >= 1 {
                    self.post_first_run_coins = self.post_first_run_coins.saturating_add(1);
                }
            }

            if self.player_started && !active && (coin_rising || start_rising) {
                any_continue_edge = true;
            }

            self.prev_coins[port] = coin_pressed;
            self.prev_starts[port] = start_pressed;
        }

        if !self.player_started {
            self.prev_active = false;
            return self.credits_used();
        }

        if !active && any_continue_edge {
            self.saw_continue_input_since_inactive = true;
        }

        if active && !self.prev_active {
            if self.runs_started == 0 || self.saw_continue_input_since_inactive {
                self.runs_started = self.runs_started.saturating_add(1);
            }
            self.saw_continue_input_since_inactive = false;
        }
        self.prev_active = active;

        self.credits_used()
    }

    pub fn coins_inserted(&self) -> u32 {
        self.total_coins.max(
            self.runs_started
                .max(1)
                .saturating_add(self.post_first_run_coins),
        )
    }

    pub fn credits_used(&self) -> u8 {
        self.coins_inserted().min(u8::MAX as u32) as u8
    }
}

#[derive(Debug, Clone, Copy, Default)]
pub struct ReplayHudStats {
    pub is_replay: bool,
    pub current_frame: u64,
    pub total_frames: u64,
    pub speed: u8,
    pub paused: bool,
    pub finished: bool,
}

#[derive(Default)]
struct SharedStats {
    fps_x100: AtomicU32,
    ping_ms: AtomicU32,
    rollback_frames: AtomicU32,
    reconnecting: AtomicBool,
    netplay_synchronized: AtomicBool,
    reconnect_secs_left: AtomicU32,
    session_ended: AtomicBool,
    ranking_eligible: AtomicBool,
    current_score: AtomicU64,
    best_session_score: AtomicU64,
    pending_submit_score: AtomicU64,
    last_read_pending_submit: AtomicU64,
    credits_used: AtomicU8,
    is_replay: AtomicBool,
    replay_current_frame: AtomicU64,
    replay_total_frames: AtomicU64,
    replay_speed: AtomicU8,
    replay_paused: AtomicBool,
    replay_finished: AtomicBool,
}

pub struct EmulationSession {
    cmd_tx: Sender<SessionCommand>,
    running: Arc<AtomicBool>,
    stats: Arc<SharedStats>,
    thread: Option<JoinHandle<()>>,
}

#[derive(Clone)]
pub struct SessionControl {
    cmd_tx: Sender<SessionCommand>,
}

impl SessionControl {
    pub fn channel_pair() -> (Self, crossbeam_channel::Receiver<SessionCommand>) {
        let (cmd_tx, cmd_rx) = unbounded();
        (Self { cmd_tx }, cmd_rx)
    }

    pub fn attach_surface(&self, window_ptr: usize, width: u32, height: u32) {
        let _ = self.cmd_tx.send(SessionCommand::AttachSurface {
            window_ptr,
            width,
            height,
        });
    }

    pub fn resize_surface(&self, width: u32, height: u32) {
        let _ = self
            .cmd_tx
            .send(SessionCommand::ResizeSurface { width, height });
    }

    pub fn detach_surface_sync(&self) {
        EmulationSession::detach_surface_sync_on(&self.cmd_tx);
    }
}

fn initial_ranking_eligible(
    has_netplay: bool,
    is_replay_mode: bool,
    has_leaderboard: bool,
) -> bool {
    !has_netplay && !is_replay_mode && has_leaderboard
}

fn apply_leaderboard_frame(
    out: LeaderboardFrameOutput,
    inputs: &[u16; 4],
    coin_tracker: &mut CoinTracker,
    stats: &SharedStats,
) {
    if !stats.ranking_eligible.load(Ordering::Acquire) {
        return;
    }
    let credits_used = coin_tracker.observe_frame(out.active, inputs);
    stats.credits_used.store(credits_used, Ordering::Release);
    if credits_used > 1 {
        stats.ranking_eligible.store(false, Ordering::Release);
        record_log(
            "INFO",
            &format!(
                "Continue detectado (credits_used={credits_used}): ranking deshabilitado y puntaje congelado"
            ),
        );
        return;
    }
    if coin_tracker.has_player_started() {
        stats
            .current_score
            .store(out.current_score, Ordering::Relaxed);
        if out.current_score > 0 {
            stats
                .best_session_score
                .fetch_max(out.current_score, Ordering::Relaxed);
        }
        if let Some(triggered) = out.triggered_score {
            if triggered > 0 {
                stats
                    .pending_submit_score
                    .fetch_max(triggered, Ordering::Release);
            }
        }
    }
}

#[derive(Default)]
struct LastFrameBuffer {
    width: u32,
    height: u32,
    rotation: u32,
    rgba: Vec<u8>,
    valid: bool,
}

impl LastFrameBuffer {
    fn update(&mut self, width: u32, height: u32, rotation: u32, src: &[u8]) {
        if self.rgba.len() != src.len() {
            self.rgba.resize(src.len(), 0);
        }
        self.rgba.copy_from_slice(src);
        self.width = width;
        self.height = height;
        self.rotation = rotation % 4;
        self.valid = true;
    }

    fn invalidate(&mut self) {
        self.valid = false;
    }

    fn as_frame(&self) -> Option<(u32, u32, u32, &[u8])> {
        if self.valid && self.width > 0 && self.height > 0 && !self.rgba.is_empty() {
            Some((self.width, self.height, self.rotation, &self.rgba))
        } else {
            None
        }
    }
}

fn compute_rollback_frames(saw_load_state: bool, advance_count: u32) -> u32 {
    if saw_load_state {
        advance_count.saturating_sub(1)
    } else {
        0
    }
}

fn compute_reconnect_secs_left(budget: Duration, elapsed: Duration) -> u32 {
    let remaining_ms = budget.saturating_sub(elapsed).as_millis();
    remaining_ms
        .saturating_add(999)
        .checked_div(1000)
        .unwrap_or(0)
        .min(u32::MAX as u128) as u32
}

fn aggregate_remote_ping_ms(pings: impl IntoIterator<Item = u128>) -> u32 {
    pings
        .into_iter()
        .max()
        .map(|p| p.min(u32::MAX as u128) as u32)
        .unwrap_or(0)
}

fn reset_replay_stats(stats: &SharedStats, coin_tracker: &mut CoinTracker) {
    stats.replay_current_frame.store(0, Ordering::Release);
    stats.replay_finished.store(false, Ordering::Release);
    stats.current_score.store(0, Ordering::Release);
    stats.best_session_score.store(0, Ordering::Release);
    *coin_tracker = CoinTracker::new();
}

pub fn clear_deterministic_nvram(save_dir: &Path, rom_path: &Path) {
    let Some(stem) = rom_path.file_stem().and_then(|s| s.to_str()) else {
        return;
    };
    let fbneo_dir = save_dir.join("fbneo");
    for dir in [save_dir, fbneo_dir.as_path()] {
        for ext in ["nv", "eeprom", "fs", "hi"] {
            let p = dir.join(format!("{stem}.{ext}"));
            if p.exists() {
                let _ = std::fs::remove_file(&p);
            }
        }
    }
}

fn write_png_rgba(path: &Path, width: u32, height: u32, rgba: &[u8]) -> Result<(), CoreError> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)
            .map_err(|e| CoreError::State(format!("No se pudo crear directorio PNG: {e}")))?;
    }
    let file = std::fs::File::create(path)
        .map_err(|e| CoreError::State(format!("No se pudo crear PNG: {e}")))?;
    let writer = BufWriter::new(file);
    let mut encoder = png::Encoder::new(writer, width, height);
    encoder.set_color(png::ColorType::Rgba);
    encoder.set_depth(png::BitDepth::Eight);
    let mut png_writer = encoder
        .write_header()
        .map_err(|e| CoreError::State(format!("Error header PNG: {e}")))?;
    png_writer
        .write_image_data(rgba)
        .map_err(|e| CoreError::State(format!("Error datos PNG: {e}")))?;
    Ok(())
}

impl EmulationSession {
    pub fn start(
        core_path: PathBuf,
        rom_path: PathBuf,
        system_dir: PathBuf,
        save_dir: PathBuf,
        netplay: Option<NetplaySessionConfig>,
        score_def: Option<String>,
    ) -> Result<Self, CoreError> {
        Self::start_internal(
            core_path, rom_path, system_dir, save_dir, netplay, score_def, None,
        )
    }

    pub fn start_replay(
        core_path: PathBuf,
        rom_path: PathBuf,
        system_dir: PathBuf,
        save_dir: PathBuf,
        replay_path: PathBuf,
        score_def: Option<String>,
    ) -> Result<Self, CoreError> {
        Self::start_internal(
            core_path,
            rom_path,
            system_dir,
            save_dir,
            None,
            score_def,
            Some(replay_path),
        )
    }

    fn start_internal(
        core_path: PathBuf,
        rom_path: PathBuf,
        system_dir: PathBuf,
        save_dir: PathBuf,
        netplay: Option<NetplaySessionConfig>,
        score_def: Option<String>,
        replay_path: Option<PathBuf>,
    ) -> Result<Self, CoreError> {
        let (cmd_tx, cmd_rx) = unbounded::<SessionCommand>();
        let (init_tx, init_rx) = bounded::<Result<(), CoreError>>(1);

        let is_replay_mode = replay_path.is_some();
        let initial_replay_player = match replay_path {
            Some(ref rp) => Some(
                ReplayPlayer::from_gzip_file(rp, None)
                    .map_err(|e| CoreError::Init(format!("Replay inválido: {e}")))?,
            ),
            None => None,
        };

        let running = Arc::new(AtomicBool::new(true));
        let stats = Arc::new(SharedStats::default());
        stats.ranking_eligible.store(false, Ordering::Release);
        stats.credits_used.store(1, Ordering::Release);
        stats.is_replay.store(is_replay_mode, Ordering::Release);
        stats.replay_speed.store(1, Ordering::Release);
        if let Some(ref p) = initial_replay_player {
            stats
                .replay_total_frames
                .store(p.total_frames(), Ordering::Release);
        }
        let running_clone = Arc::clone(&running);
        let stats_clone = Arc::clone(&stats);

        let thread = std::thread::Builder::new()
            .name("balddog-emu".to_string())
            .spawn(move || {
                if is_replay_mode || netplay.is_some() || score_def.is_some() {
                    clear_deterministic_nvram(&save_dir, &rom_path);
                }
                let mut core = match LibretroCore::load(&core_path, &system_dir, &save_dir) {
                    Ok(c) => c,
                    Err(e) => {
                        let _ = init_tx.send(Err(e));
                        return;
                    }
                };

                let av_info = match core.load_game(&rom_path) {
                    Ok(info) => info,
                    Err(e) => {
                        let _ = init_tx.send(Err(e));
                        return;
                    }
                };

                if let Some(ref p) = initial_replay_player {
                    if !p.initial_state().is_empty() {
                        if let Err(e) = core.load_state_bytes(p.initial_state()) {
                            let _ = init_tx.send(Err(e));
                            return;
                        }
                    }
                }

                let mut ggrs_session = match netplay.as_ref() {
                    Some(cfg) => match build_p2p_session(cfg) {
                        Ok(s) => {
                            record_log(
                                "INFO",
                                &format!(
                                    "GGRS sesión iniciada: local_port={}, local_handle={}, num_players={}, peers={:?}",
                                    cfg.local_port, cfg.local_player_handle, cfg.num_players, cfg.peers
                                ),
                            );
                            Some((s, cfg.local_player_handle))
                        }
                        Err(e) => {
                            record_log("ERROR", &format!("GGRS build_p2p_session falló: {e}"));
                            let _ = init_tx.send(Err(CoreError::Init(e.to_string())));
                            return;
                        }
                    },
                    None => None,
                };

                let mut audio = match AudioEngine::start(av_info.timing.sample_rate as i32) {
                    Ok(a) => Some(a),
                    Err(e) => {
                        record_log("WARN", &format!("Audio deshabilitado: {e}"));
                        None
                    }
                };

                let game_aspect = effective_game_aspect(
                    av_info.geometry.aspect_ratio,
                    av_info.geometry.base_width,
                    av_info.geometry.base_height,
                    core.rotation(),
                );
                let mut renderer = WgpuRenderer::new(game_aspect);
                let mut leaderboard = if netplay.is_none() {
                    score_def
                        .as_deref()
                        .map(str::trim)
                        .filter(|s| !s.is_empty())
                        .and_then(|def| match LeaderboardEngine::new(def) {
                            Ok(lb) => Some(lb),
                            Err(e) => {
                                record_log("WARN", &format!("Leaderboard deshabilitado: {e}"));
                                None
                            }
                        })
                } else {
                    None
                };
                stats_clone.ranking_eligible.store(
                    initial_ranking_eligible(
                        netplay.is_some(),
                        is_replay_mode,
                        leaderboard.is_some(),
                    ),
                    Ordering::Release,
                );
                let mut replay_player = initial_replay_player;
                let mut recorder = if netplay.is_none() && replay_player.is_none() && leaderboard.is_some() {
                    let rom_crc = compute_file_crc32(&rom_path).unwrap_or(0);
                    Some(ReplayRecorder::new(rom_crc))
                } else {
                    None
                };
                let mut replay_speed: u8 = 1;
                let mut last_frame_rgba = LastFrameBuffer::default();
                let _ = init_tx.send(Ok(()));

                let target_fps = if av_info.timing.fps > 10.0 {
                    av_info.timing.fps
                } else {
                    60.0
                };
                let frame_duration = Duration::from_secs_f64(1.0 / target_fps);
                let mut paused = false;
                let mut inputs = [0u16; 4];
                let mut coin_tracker = CoinTracker::new();
                let mut next_frame_at = Instant::now();
                let mut fps_window_start = Instant::now();
                let mut frames_in_window: u32 = 0;
                let mut disconnect_started: Option<(Instant, Duration)> = None;
                let mut last_sync_log = Instant::now();
                let mut first_frame_rendered = false;

                while running_clone.load(Ordering::Relaxed) {
                    while let Ok(cmd) = cmd_rx.try_recv() {
                        match cmd {
                            SessionCommand::AttachSurface {
                                window_ptr,
                                width,
                                height,
                            } => {
                                // SAFETY: `window_ptr` fue obtenido con `ANativeWindow_fromSurface`.
                                if let Err(e) =
                                    unsafe { renderer.attach(window_ptr as *mut c_void, width, height) }
                                {
                                    record_log("ERROR", &format!("attach surface: {e}"));
                                }
                            }
                            SessionCommand::ResizeSurface { width, height } => {
                                renderer.resize(width, height);
                            }
                            SessionCommand::DetachSurface { ack } => {
                                renderer.detach();
                                let _ = ack.send(());
                            }
                            SessionCommand::SetPaused(p) => {
                                // En netplay no pausamos la simulación para no desincronizar el reloj
                                if ggrs_session.is_none() {
                                    paused = p;
                                    stats_clone.replay_paused.store(p, Ordering::Release);
                                }
                                if let Some(a) = &audio {
                                    a.set_paused(p);
                                }
                                next_frame_at = Instant::now();
                            }
                            SessionCommand::SetReplaySpeed(spd) => {
                                replay_speed = match spd {
                                    4 => 4,
                                    2 => 2,
                                    _ => 1,
                                };
                                stats_clone.replay_speed.store(replay_speed, Ordering::Release);
                            }
                            SessionCommand::RestartReplay => {
                                if let Some(ref mut p) = replay_player {
                                    clear_deterministic_nvram(&save_dir, &rom_path);
                                    match core.load_game(&rom_path) {
                                        Ok(_) => {
                                            let state_ok = if !p.initial_state().is_empty() {
                                                match core.load_state_bytes(p.initial_state()) {
                                                    Ok(()) => true,
                                                    Err(e) => {
                                                        record_log(
                                                            "ERROR",
                                                            &format!("Error cargando initial_state en RestartReplay: {e}"),
                                                        );
                                                        false
                                                    }
                                                }
                                            } else {
                                                true
                                            };
                                            if state_ok {
                                                p.restart();
                                                if let Some(ref mut lb) = leaderboard {
                                                    lb.reset();
                                                }
                                                reset_replay_stats(&stats_clone, &mut coin_tracker);
                                                last_frame_rgba.invalidate();
                                            } else {
                                                running_clone.store(false, Ordering::Release);
                                                break;
                                            }
                                        }
                                        Err(e) => {
                                            record_log(
                                                "ERROR",
                                                &format!("Error recargando ROM en RestartReplay: {e}"),
                                            );
                                            running_clone.store(false, Ordering::Release);
                                            break;
                                        }
                                    }
                                }
                            }
                            SessionCommand::SeekReplay(target_frame) => {
                                if let Some(ref mut p) = replay_player {
                                    let max_frame = p.total_frames();
                                    let target = target_frame.min(max_frame);
                                    let curr = p.current_frame();
                                    if target < curr {
                                        clear_deterministic_nvram(&save_dir, &rom_path);
                                        if core.load_game(&rom_path).is_ok() {
                                            let state_ok = if !p.initial_state().is_empty() {
                                                core.load_state_bytes(p.initial_state()).is_ok()
                                            } else {
                                                true
                                            };
                                            if state_ok {
                                                p.restart();
                                                if let Some(ref mut lb) = leaderboard {
                                                    lb.reset();
                                                }
                                                reset_replay_stats(&stats_clone, &mut coin_tracker);
                                                last_frame_rgba.invalidate();
                                            }
                                        }
                                    }
                                    while p.current_frame() < target && !p.is_finished() {
                                        let Some(frame_inputs) = p.next_frame() else {
                                            break;
                                        };
                                        core.set_inputs(frame_inputs);
                                        core.run_frame();
                                        if let Some(ref mut lb) = leaderboard {
                                            if let Some(ram) = core.system_ram() {
                                                let out = lb.evaluate_frame(ram);
                                                coin_tracker.observe_frame(out.active, &frame_inputs);
                                                stats_clone.current_score.store(out.current_score, Ordering::Relaxed);
                                                if out.current_score > 0 {
                                                    stats_clone.best_session_score.fetch_max(out.current_score, Ordering::Relaxed);
                                                }
                                            }
                                        }
                                    }
                                    core.take_audio_samples(|_| {});
                                    stats_clone
                                        .replay_current_frame
                                        .store(p.current_frame(), Ordering::Relaxed);
                                    let finished = p.is_finished();
                                    stats_clone.replay_finished.store(finished, Ordering::Release);
                                    let rot = core.rotation();
                                    core.take_video_frame(|w, h, rgba| {
                                        last_frame_rgba.update(w, h, rot, rgba);
                                        renderer.render_frame(w, h, rgba, rot);
                                    });
                                }
                            }
                            SessionCommand::ExportReplay { path, ack } => {
                                let res = match recorder.as_ref() {
                                    Some(rec) => rec
                                        .write_gzip_file(&path)
                                        .map(|bytes| bytes as u64)
                                        .map_err(|e| CoreError::State(format!("Error exportando replay: {e}"))),
                                    None => Err(CoreError::State("No hay grabación de replay activa".into())),
                                };
                                let _ = ack.send(res);
                            }
                            SessionCommand::CaptureScreenshot { path, ack } => {
                                let res = match last_frame_rgba.as_frame() {
                                    Some((w, h, rot, rgba)) => {
                                        let (out_w, out_h, rotated) =
                                            rotate_rgba(w, h, rgba, rot);
                                        write_png_rgba(&path, out_w, out_h, &rotated)
                                    }
                                    None => Err(CoreError::State("Aún no hay frame renderizado".into())),
                                };
                                let _ = ack.send(res);
                            }
                            SessionCommand::SetInput { port, mask } => {
                                if port < 4 {
                                    inputs[port] = mask;
                                }
                            }
                            SessionCommand::SaveState { path, ack } => {
                                let res = if ggrs_session.is_some() {
                                    Err(CoreError::State("SaveState deshabilitado en Netplay".into()))
                                } else {
                                    core.save_state_bytes().and_then(|bytes| {
                                        std::fs::write(&path, bytes).map_err(|e| {
                                            CoreError::State(format!("Error escribiendo savestate: {e}"))
                                        })
                                    })
                                };
                                let _ = ack.send(res);
                            }
                            SessionCommand::LoadState { path, ack } => {
                                let res = if ggrs_session.is_some() {
                                    Err(CoreError::State("LoadState deshabilitado en Netplay".into()))
                                } else {
                                    std::fs::read(&path)
                                        .map_err(|e| {
                                            CoreError::State(format!("Error leyendo savestate: {e}"))
                                        })
                                        .and_then(|bytes| core.load_state_bytes(&bytes))
                                };
                                if res.is_ok() {
                                    stats_clone
                                        .ranking_eligible
                                        .store(false, Ordering::Release);
                                    record_log(
                                        "INFO",
                                        "SaveState cargado: ranking deshabilitado para esta sesión",
                                    );
                                }
                                let _ = ack.send(res);
                            }
                            SessionCommand::Stop => {
                                running_clone.store(false, Ordering::Release);
                                break;
                            }
                        }
                    }

                    if !running_clone.load(Ordering::Relaxed) {
                        break;
                    }

                    if paused {
                        std::thread::sleep(Duration::from_millis(16));
                        continue;
                    }

                    if let Some((ref mut p2p, local_handle)) = ggrs_session {
                        p2p.poll_remote_clients();

                        for ev in p2p.events() {
                            match ev {
                                GgrsEvent::Synchronizing { addr, total, count } => {
                                    record_log(
                                        "INFO",
                                        &format!("GGRS Synchronizing {addr}: {count}/{total}"),
                                    );
                                }
                                GgrsEvent::Synchronized { addr } => {
                                    record_log(
                                        "INFO",
                                        &format!("GGRS Synchronized con par {addr}! Partida iniciando simulación"),
                                    );
                                    disconnect_started = None;
                                    stats_clone.netplay_synchronized.store(true, Ordering::Release);
                                    stats_clone.reconnecting.store(false, Ordering::Release);
                                    stats_clone.reconnect_secs_left.store(0, Ordering::Release);
                                }
                                GgrsEvent::NetworkInterrupted { disconnect_timeout, .. } => {
                                    record_log("WARN", "GGRS NetworkInterrupted: conexión P2P interrumpida");
                                    let ms = disconnect_timeout.min(u64::MAX as u128) as u64;
                                    disconnect_started
                                        .get_or_insert_with(|| (Instant::now(), Duration::from_millis(ms)));
                                    stats_clone.reconnecting.store(true, Ordering::Release);
                                }
                                GgrsEvent::NetworkResumed { .. } => {
                                    record_log("INFO", "GGRS NetworkResumed: conexión P2P reanudada");
                                    disconnect_started = None;
                                    stats_clone.reconnecting.store(false, Ordering::Release);
                                    stats_clone.reconnect_secs_left.store(0, Ordering::Release);
                                }
                                GgrsEvent::Disconnected { .. } => {
                                    record_log("WARN", "GGRS Disconnected: Par remoto desconectado definitivamente");
                                    stats_clone.reconnecting.store(false, Ordering::Release);
                                    stats_clone.session_ended.store(true, Ordering::Release);
                                    running_clone.store(false, Ordering::Release);
                                }
                                _ => {}
                            }
                        }

                        if let Some((started, budget)) = disconnect_started {
                            let left = compute_reconnect_secs_left(budget, started.elapsed());
                            stats_clone.reconnect_secs_left.store(left, Ordering::Relaxed);
                        }

                        if p2p.current_state() == SessionState::Synchronizing {
                            if last_sync_log.elapsed() >= Duration::from_secs(2) {
                                record_log(
                                    "INFO",
                                    &format!(
                                        "GGRS esperando sincronización P2P... local_handle={local_handle}, estado={:?}",
                                        p2p.current_state()
                                    ),
                                );
                                last_sync_log = Instant::now();
                            }
                        }

                        if p2p.current_state() == SessionState::Running {
                            let local_input = inputs[0];
                            if p2p.add_local_input(local_handle, local_input).is_ok() {
                                match p2p.advance_frame() {
                                    Ok(requests) => {
                                        let mut saw_load_state = false;
                                        let mut advance_count: u32 = 0;
                                        for req in requests {
                                            match req {
                                                GgrsRequest::SaveGameState { cell, frame } => {
                                                    let state_opt = match core.save_state_bytes() {
                                                        Ok(bytes) => Some(bytes),
                                                        Err(e) => {
                                                            record_log(
                                                                "ERROR",
                                                                &format!("GGRS save_state_bytes frame {frame}: {e}"),
                                                            );
                                                            None
                                                        }
                                                    };
                                                    cell.save(frame, state_opt, None);
                                                }
                                                GgrsRequest::LoadGameState { cell, frame } => {
                                                    saw_load_state = true;
                                                    match cell.load() {
                                                        Some(bytes) => {
                                                            if let Err(e) = core.load_state_bytes(&bytes) {
                                                                record_log(
                                                                    "ERROR",
                                                                    &format!("GGRS load_state_bytes frame {frame}: {e}"),
                                                                );
                                                            }
                                                        }
                                                        None => {
                                                            record_log(
                                                                "ERROR",
                                                                &format!("GGRS LoadGameState celda vacía en frame {frame}"),
                                                            );
                                                        }
                                                    }
                                                }
                                                GgrsRequest::AdvanceFrame { inputs: frame_inputs } => {
                                                    advance_count = advance_count.saturating_add(1);
                                                    let mut masks = [0u16; 4];
                                                    for (i, (inp, status)) in frame_inputs.iter().enumerate() {
                                                        if i < 4 && *status != InputStatus::Disconnected {
                                                            masks[i] = *inp;
                                                        }
                                                    }
                                                    core.set_inputs(masks);
                                                    core.run_frame();
                                                }
                                            }
                                        }
                                        let rolled_back =
                                            compute_rollback_frames(saw_load_state, advance_count);
                                        stats_clone
                                            .rollback_frames
                                            .store(rolled_back, Ordering::Relaxed);
                                    }
                                    Err(e) => {
                                        record_log("WARN", &format!("GGRS advance_frame: {e}"));
                                    }
                                }
                            }

                            // Actualizar ping máximo entre pares remotos desde estadísticas de GGRS
                            let max_ping = aggregate_remote_ping_ms((0..p2p.num_players()).filter_map(
                                |h| {
                                    if h != local_handle {
                                        p2p.network_stats(h).ok().map(|ns| ns.ping)
                                    } else {
                                        None
                                    }
                                },
                            ));
                            stats_clone.ping_ms.store(max_ping, Ordering::Relaxed);
                        }
                    } else if let Some(ref mut player) = replay_player {
                        let steps = if player.is_finished() { 0 } else { replay_speed.max(1) };
                        for step_idx in 0..steps {
                            let Some(frame_inputs) = player.next_frame() else {
                                stats_clone.replay_finished.store(true, Ordering::Release);
                                break;
                            };
                            core.set_inputs(frame_inputs);
                            core.run_frame();
                            if let Some(ref mut lb) = leaderboard {
                                if let Some(ram) = core.system_ram() {
                                    let out = lb.evaluate_frame(ram);
                                    stats_clone.current_score.store(out.current_score, Ordering::Relaxed);
                                    if out.current_score > 0 {
                                        stats_clone.best_session_score.fetch_max(out.current_score, Ordering::Relaxed);
                                    }
                                }
                            }
                            if let Some(a) = &mut audio {
                                core.take_audio_samples(|samples| {
                                    if replay_speed == 1 && step_idx == 0 {
                                        a.push_samples(samples);
                                    }
                                });
                            }
                        }
                        stats_clone
                            .replay_current_frame
                            .store(player.current_frame(), Ordering::Relaxed);
                        if player.is_finished() {
                            stats_clone.replay_finished.store(true, Ordering::Release);
                        }
                    } else {
                        let eligible = stats_clone.ranking_eligible.load(Ordering::Acquire);
                        if eligible {
                            if let Some(ref mut rec) = recorder {
                                rec.push_frame(inputs);
                            }
                        }
                        core.set_inputs(inputs);
                        core.run_frame();
                        if let Some(ref mut lb) = leaderboard {
                            if eligible {
                                if let Some(ram) = core.system_ram() {
                                    let out = lb.evaluate_frame(ram);
                                    apply_leaderboard_frame(
                                        out,
                                        &inputs,
                                        &mut coin_tracker,
                                        &stats_clone,
                                    );
                                }
                            }
                        }
                    }

                    let rot = core.rotation();
                    core.take_video_frame(|w, h, rgba| {
                        if !first_frame_rendered {
                            first_frame_rendered = true;
                            record_log("INFO", &format!("Primer frame de emulación renderizado: {w}x{h}"));
                        }
                        last_frame_rgba.update(w, h, rot, rgba);
                        renderer.render_frame(w, h, rgba, rot);
                    });

                    if !is_replay_mode {
                        if let Some(a) = &mut audio {
                            core.take_audio_samples(|samples| {
                                a.push_samples(samples);
                            });
                        }
                    }

                    frames_in_window += 1;
                    let elapsed_fps = fps_window_start.elapsed();
                    if elapsed_fps >= Duration::from_secs(1) {
                        let fps = (frames_in_window as f64 / elapsed_fps.as_secs_f64()) * 100.0;
                        stats_clone.fps_x100.store(fps as u32, Ordering::Relaxed);
                        frames_in_window = 0;
                        fps_window_start = Instant::now();
                    }

                    next_frame_at += frame_duration;
                    let now = Instant::now();
                    if next_frame_at > now {
                        std::thread::sleep(next_frame_at - now);
                    } else if now.duration_since(next_frame_at) > frame_duration * 4 {
                        next_frame_at = now;
                    }
                }

                renderer.detach();
                record_log("INFO", "Hilo de emulación detenido limpiamente");
            })
            .map_err(|e| CoreError::Init(format!("No se pudo lanzar hilo de emulación: {e}")))?;

        match init_rx.recv() {
            Ok(Ok(())) => Ok(Self {
                cmd_tx,
                running,
                stats,
                thread: Some(thread),
            }),
            Ok(Err(e)) => {
                let _ = thread.join();
                Err(e)
            }
            Err(_) => {
                let _ = thread.join();
                Err(CoreError::Init(
                    "El hilo de emulación terminó inesperadamente".into(),
                ))
            }
        }
    }

    pub fn control(&self) -> SessionControl {
        SessionControl {
            cmd_tx: self.cmd_tx.clone(),
        }
    }

    pub fn attach_surface(&self, window_ptr: usize, width: u32, height: u32) {
        let _ = self.cmd_tx.send(SessionCommand::AttachSurface {
            window_ptr,
            width,
            height,
        });
    }

    pub fn resize_surface(&self, width: u32, height: u32) {
        let _ = self
            .cmd_tx
            .send(SessionCommand::ResizeSurface { width, height });
    }

    pub fn detach_surface_sync_on(cmd_tx: &Sender<SessionCommand>) {
        let (ack_tx, ack_rx) = bounded(1);
        if cmd_tx
            .send(SessionCommand::DetachSurface { ack: ack_tx })
            .is_ok()
        {
            // Esperar de forma incondicional hasta que el hilo de emulación destruya `wgpu::Surface`
            // (o cierre `cmd_rx` tras ejecutar `renderer.detach()` al salir del bucle).
            let _ = ack_rx.recv();
        }
    }

    pub fn detach_surface_sync(&self) {
        Self::detach_surface_sync_on(&self.cmd_tx);
    }

    pub fn set_paused(&self, paused: bool) {
        let _ = self.cmd_tx.send(SessionCommand::SetPaused(paused));
    }

    pub fn set_input(&self, port: usize, mask: u16) {
        let _ = self.cmd_tx.send(SessionCommand::SetInput { port, mask });
    }

    pub fn save_state(&self, path: PathBuf) -> Result<(), CoreError> {
        let (ack_tx, ack_rx) = bounded(1);
        self.cmd_tx
            .send(SessionCommand::SaveState { path, ack: ack_tx })
            .map_err(|_| CoreError::State("Sesión detenida".into()))?;
        ack_rx
            .recv_timeout(Duration::from_secs(3))
            .map_err(|_| CoreError::State("Timeout guardando savestate".into()))?
    }

    pub fn load_state(&self, path: PathBuf) -> Result<(), CoreError> {
        let (ack_tx, ack_rx) = bounded(1);
        self.cmd_tx
            .send(SessionCommand::LoadState { path, ack: ack_tx })
            .map_err(|_| CoreError::State("Sesión detenida".into()))?;
        ack_rx
            .recv_timeout(Duration::from_secs(3))
            .map_err(|_| CoreError::State("Timeout cargando savestate".into()))?
    }

    pub fn is_ranking_eligible(&self) -> bool {
        self.stats.ranking_eligible.load(Ordering::Acquire)
    }

    pub fn score_hud_stats(&self) -> ScoreHudStats {
        let pending = self.stats.pending_submit_score.load(Ordering::Acquire);
        self.stats
            .last_read_pending_submit
            .store(pending, Ordering::Release);
        ScoreHudStats {
            current_score: self.stats.current_score.load(Ordering::Relaxed),
            best_session_score: self.stats.best_session_score.load(Ordering::Relaxed),
            pending_submit_score: pending,
            ranking_eligible: self.stats.ranking_eligible.load(Ordering::Acquire),
            credits_used: self.stats.credits_used.load(Ordering::Acquire),
        }
    }

    pub fn acknowledge_score_submit(&self) {
        let expected = self.stats.last_read_pending_submit.load(Ordering::Acquire);
        if expected > 0 {
            let _ = self.stats.pending_submit_score.compare_exchange(
                expected,
                0,
                Ordering::AcqRel,
                Ordering::Acquire,
            );
        }
    }

    pub fn set_replay_speed(&self, speed: u8) {
        let _ = self.cmd_tx.send(SessionCommand::SetReplaySpeed(speed));
    }

    pub fn restart_replay(&self) {
        let _ = self.cmd_tx.send(SessionCommand::RestartReplay);
    }

    pub fn seek_replay(&self, target_frame: u64) {
        let _ = self.cmd_tx.send(SessionCommand::SeekReplay(target_frame));
    }

    pub fn replay_hud_stats(&self) -> ReplayHudStats {
        ReplayHudStats {
            is_replay: self.stats.is_replay.load(Ordering::Acquire),
            current_frame: self.stats.replay_current_frame.load(Ordering::Relaxed),
            total_frames: self.stats.replay_total_frames.load(Ordering::Relaxed),
            speed: self.stats.replay_speed.load(Ordering::Relaxed),
            paused: self.stats.replay_paused.load(Ordering::Relaxed),
            finished: self.stats.replay_finished.load(Ordering::Acquire),
        }
    }

    pub fn export_recorded_replay(&self, path: PathBuf) -> Result<u64, CoreError> {
        let (ack_tx, ack_rx) = bounded(1);
        self.cmd_tx
            .send(SessionCommand::ExportReplay { path, ack: ack_tx })
            .map_err(|_| CoreError::State("Sesión detenida".into()))?;
        ack_rx
            .recv_timeout(Duration::from_secs(5))
            .map_err(|_| CoreError::State("Timeout exportando replay".into()))?
    }

    pub fn capture_screenshot_png(&self, path: PathBuf) -> Result<(), CoreError> {
        let (ack_tx, ack_rx) = bounded(1);
        self.cmd_tx
            .send(SessionCommand::CaptureScreenshot { path, ack: ack_tx })
            .map_err(|_| CoreError::State("Sesión detenida".into()))?;
        ack_rx
            .recv_timeout(Duration::from_secs(3))
            .map_err(|_| CoreError::State("Timeout capturando pantalla".into()))?
    }

    pub fn current_fps(&self) -> f32 {
        self.stats.fps_x100.load(Ordering::Relaxed) as f32 / 100.0
    }

    pub fn netplay_stats(&self) -> NetplayHudStats {
        NetplayHudStats {
            ping_ms: self.stats.ping_ms.load(Ordering::Relaxed),
            rollback_frames: self.stats.rollback_frames.load(Ordering::Relaxed),
            reconnecting: self.stats.reconnecting.load(Ordering::Acquire),
            reconnect_secs_left: self.stats.reconnect_secs_left.load(Ordering::Relaxed),
            session_ended: self.stats.session_ended.load(Ordering::Acquire),
            synchronized: self.stats.netplay_synchronized.load(Ordering::Acquire),
        }
    }

    pub fn stop(&mut self) {
        self.running.store(false, Ordering::Release);
        let _ = self.cmd_tx.send(SessionCommand::Stop);
        if let Some(handle) = self.thread.take() {
            let _ = handle.join();
        }
    }
}

impl Drop for EmulationSession {
    fn drop(&mut self) {
        self.stop();
    }
}

#[cfg(test)]
mod tests {
    use super::{CoinTracker, RETRO_COIN_MASK, RETRO_START_MASK};

    #[test]
    fn single_coin_before_start_or_after_stray_start_allows_1cc() {
        let mut tracker = CoinTracker::new();
        assert_eq!(tracker.credits_used(), 1);
        assert!(!tracker.has_player_started());

        // 1st COIN during boot/title screen -> still 1 credit (valid 1CC)
        assert_eq!(tracker.observe_frame(false, &[RETRO_COIN_MASK, 0, 0, 0]), 1);
        assert_eq!(tracker.observe_frame(false, &[0, 0, 0, 0]), 1);

        // Player presses START -> character select (active = false) -> gameplay (active = true)
        assert_eq!(
            tracker.observe_frame(false, &[RETRO_START_MASK, 0, 0, 0]),
            1
        );
        assert!(tracker.has_player_started());
        assert_eq!(tracker.observe_frame(true, &[0, 0, 0, 0]), 1);
        assert_eq!(tracker.coins_inserted(), 1);

        // Stray START before inserting the 1st coin also must not double-count
        let mut stray = CoinTracker::new();
        assert_eq!(stray.observe_frame(false, &[RETRO_START_MASK, 0, 0, 0]), 1);
        assert_eq!(stray.observe_frame(false, &[RETRO_COIN_MASK, 0, 0, 0]), 1);
        assert_eq!(stray.observe_frame(false, &[RETRO_START_MASK, 0, 0, 0]), 1);
        assert_eq!(stray.observe_frame(true, &[0, 0, 0, 0]), 1);
        assert_eq!(stray.coins_inserted(), 1);
    }

    #[test]
    fn multiple_coins_at_boot_or_character_select_disqualify_immediately() {
        use std::sync::atomic::Ordering;

        // Case A: 2 coins inserted on boot/title screen before START
        let stats = super::SharedStats::default();
        stats.ranking_eligible.store(true, Ordering::Release);
        stats.credits_used.store(1, Ordering::Release);
        let mut tracker = CoinTracker::new();

        super::apply_leaderboard_frame(
            crate::retro::leaderboard::LeaderboardFrameOutput {
                active: false,
                current_score: 0,
                triggered_score: None,
            },
            &[RETRO_COIN_MASK, 0, 0, 0],
            &mut tracker,
            &stats,
        );
        assert!(stats.ranking_eligible.load(Ordering::Acquire));
        assert_eq!(stats.credits_used.load(Ordering::Acquire), 1);

        super::apply_leaderboard_frame(
            crate::retro::leaderboard::LeaderboardFrameOutput {
                active: false,
                current_score: 0,
                triggered_score: None,
            },
            &[0, 0, 0, 0],
            &mut tracker,
            &stats,
        );
        // 2nd coin on title screen -> immediate disqualification
        super::apply_leaderboard_frame(
            crate::retro::leaderboard::LeaderboardFrameOutput {
                active: false,
                current_score: 0,
                triggered_score: None,
            },
            &[RETRO_COIN_MASK, 0, 0, 0],
            &mut tracker,
            &stats,
        );
        assert!(!stats.ranking_eligible.load(Ordering::Acquire));
        assert_eq!(stats.credits_used.load(Ordering::Acquire), 2);

        // Case B: 1 coin on title, START, then 2nd coin on character select (active = false)
        let stats_b = super::SharedStats::default();
        stats_b.ranking_eligible.store(true, Ordering::Release);
        stats_b.credits_used.store(1, Ordering::Release);
        let mut tracker_b = CoinTracker::new();

        for inputs in [
            [RETRO_COIN_MASK, 0, 0, 0],
            [0, 0, 0, 0],
            [RETRO_START_MASK, 0, 0, 0],
            [0, 0, 0, 0],
        ] {
            super::apply_leaderboard_frame(
                crate::retro::leaderboard::LeaderboardFrameOutput::default(),
                &inputs,
                &mut tracker_b,
                &stats_b,
            );
        }
        assert!(stats_b.ranking_eligible.load(Ordering::Acquire));

        super::apply_leaderboard_frame(
            crate::retro::leaderboard::LeaderboardFrameOutput::default(),
            &[RETRO_COIN_MASK, 0, 0, 0],
            &mut tracker_b,
            &stats_b,
        );
        assert!(!stats_b.ranking_eligible.load(Ordering::Acquire));
        assert_eq!(stats_b.credits_used.load(Ordering::Acquire), 2);
    }

    #[test]
    fn preloaded_coins_used_to_continue_disqualify_1cc() {
        let mut tracker = CoinTracker::new();
        assert_eq!(tracker.observe_frame(true, &[RETRO_START_MASK, 0, 0, 0]), 1);
        assert_eq!(tracker.observe_frame(true, &[0, 0, 0, 0]), 1);
        // Game Over / Continue screen (active = false), player presses START to continue
        assert_eq!(
            tracker.observe_frame(false, &[RETRO_START_MASK, 0, 0, 0]),
            1
        );
        // Second run started using preloaded credits (active = true again)
        assert_eq!(tracker.observe_frame(true, &[0, 0, 0, 0]), 2);
        assert_eq!(tracker.coins_inserted(), 2);
    }

    #[test]
    fn p1_through_p4_in_game_coins_are_counted() {
        let mut tracker = CoinTracker::new();
        assert_eq!(tracker.observe_frame(true, &[RETRO_START_MASK, 0, 0, 0]), 1);
        assert_eq!(tracker.observe_frame(true, &[RETRO_COIN_MASK, 0, 0, 0]), 2);
        assert_eq!(tracker.observe_frame(true, &[0, RETRO_COIN_MASK, 0, 0]), 3);
        assert_eq!(
            tracker.observe_frame(true, &[0, 0, RETRO_COIN_MASK, RETRO_COIN_MASK]),
            5
        );
        assert_eq!(tracker.coins_inserted(), 5);
    }

    #[test]
    fn coin_counter_saturates_at_u8_max() {
        let mut tracker = CoinTracker::new();
        assert_eq!(tracker.observe_frame(true, &[RETRO_START_MASK, 0, 0, 0]), 1);
        for _ in 0..300 {
            tracker.observe_frame(true, &[RETRO_COIN_MASK, 0, 0, 0]);
            tracker.observe_frame(true, &[0, 0, 0, 0]);
        }
        assert_eq!(tracker.coins_inserted(), 301);
        assert_eq!(tracker.credits_used(), u8::MAX);
    }

    #[test]
    fn detach_surface_sync_waits_for_ack_and_unblocks_on_channel_close() {
        use std::sync::atomic::{AtomicBool, Ordering};
        use std::sync::Arc;
        use std::time::Duration;

        let (tx, rx) = crossbeam_channel::unbounded();
        let ctrl = super::SessionControl { cmd_tx: tx };
        let detached = Arc::new(AtomicBool::new(false));
        let detached_worker = Arc::clone(&detached);

        let worker = std::thread::spawn(move || {
            if let Ok(super::SessionCommand::DetachSurface { ack }) = rx.recv() {
                std::thread::sleep(Duration::from_millis(30));
                detached_worker.store(true, Ordering::Release);
                let _ = ack.send(());
            }
        });

        ctrl.detach_surface_sync();
        assert!(detached.load(Ordering::Acquire));
        worker.join().unwrap();

        // Also unblocks cleanly if the receiver closes without sending ack (thread shutdown).
        let (tx2, rx2) = crossbeam_channel::unbounded();
        let ctrl2 = super::SessionControl { cmd_tx: tx2 };
        let worker2 = std::thread::spawn(move || {
            let _cmd = rx2.recv();
            drop(rx2);
        });
        ctrl2.detach_surface_sync();
        worker2.join().unwrap();
    }

    #[test]
    fn initial_ranking_eligible_requires_solo_live_and_leaderboard() {
        assert!(super::initial_ranking_eligible(false, false, true));
        assert!(!super::initial_ranking_eligible(false, false, false));
        assert!(!super::initial_ranking_eligible(true, false, true));
        assert!(!super::initial_ranking_eligible(false, true, true));
        assert!(!super::initial_ranking_eligible(true, true, false));
    }

    #[test]
    fn in_game_continue_coin_disqualifies_and_freezes_scores() {
        use std::sync::atomic::Ordering;

        let stats = super::SharedStats::default();
        stats.ranking_eligible.store(true, Ordering::Release);
        stats.credits_used.store(1, Ordering::Release);
        let mut tracker = CoinTracker::new();

        // Start game with 1 credit and score 12_500
        super::apply_leaderboard_frame(
            crate::retro::leaderboard::LeaderboardFrameOutput {
                active: true,
                current_score: 12_500,
                triggered_score: None,
            },
            &[RETRO_START_MASK, 0, 0, 0],
            &mut tracker,
            &stats,
        );
        assert!(stats.ranking_eligible.load(Ordering::Acquire));
        assert_eq!(stats.credits_used.load(Ordering::Acquire), 1);
        assert_eq!(stats.current_score.load(Ordering::Relaxed), 12_500);
        assert_eq!(stats.best_session_score.load(Ordering::Relaxed), 12_500);

        // Insert 2nd coin while active -> immediate disqualification & score freeze
        super::apply_leaderboard_frame(
            crate::retro::leaderboard::LeaderboardFrameOutput {
                active: true,
                current_score: 99_999,
                triggered_score: Some(99_999),
            },
            &[RETRO_COIN_MASK, 0, 0, 0],
            &mut tracker,
            &stats,
        );
        assert!(!stats.ranking_eligible.load(Ordering::Acquire));
        assert_eq!(stats.credits_used.load(Ordering::Acquire), 2);
        assert_eq!(stats.current_score.load(Ordering::Relaxed), 12_500);
        assert_eq!(stats.best_session_score.load(Ordering::Relaxed), 12_500);
        assert_eq!(stats.pending_submit_score.load(Ordering::Acquire), 0);

        // Subsequent frames keep scores frozen
        super::apply_leaderboard_frame(
            crate::retro::leaderboard::LeaderboardFrameOutput {
                active: true,
                current_score: 150_000,
                triggered_score: Some(150_000),
            },
            &[0, 0, 0, 0],
            &mut tracker,
            &stats,
        );
        assert_eq!(stats.current_score.load(Ordering::Relaxed), 12_500);
        assert_eq!(stats.best_session_score.load(Ordering::Relaxed), 12_500);
        assert_eq!(stats.pending_submit_score.load(Ordering::Acquire), 0);
    }

    #[test]
    fn preloaded_continue_after_game_over_disqualifies_and_freezes_scores() {
        use std::sync::atomic::Ordering;

        let stats = super::SharedStats::default();
        stats.ranking_eligible.store(true, Ordering::Release);
        stats.credits_used.store(1, Ordering::Release);
        let mut tracker = CoinTracker::new();

        // Run 1: start and earn 5_000, then Game Over triggers 5_000
        super::apply_leaderboard_frame(
            crate::retro::leaderboard::LeaderboardFrameOutput {
                active: true,
                current_score: 5_000,
                triggered_score: None,
            },
            &[RETRO_START_MASK, 0, 0, 0],
            &mut tracker,
            &stats,
        );
        super::apply_leaderboard_frame(
            crate::retro::leaderboard::LeaderboardFrameOutput {
                active: false,
                current_score: 5_000,
                triggered_score: Some(5_000),
            },
            &[0, 0, 0, 0],
            &mut tracker,
            &stats,
        );
        assert!(stats.ranking_eligible.load(Ordering::Acquire));
        assert_eq!(stats.pending_submit_score.load(Ordering::Acquire), 5_000);

        // Run 2: continue using preloaded credit (player presses START while inactive, then active becomes true)
        super::apply_leaderboard_frame(
            crate::retro::leaderboard::LeaderboardFrameOutput {
                active: false,
                current_score: 5_000,
                triggered_score: None,
            },
            &[RETRO_START_MASK, 0, 0, 0],
            &mut tracker,
            &stats,
        );
        super::apply_leaderboard_frame(
            crate::retro::leaderboard::LeaderboardFrameOutput {
                active: true,
                current_score: 80_000,
                triggered_score: Some(80_000),
            },
            &[0, 0, 0, 0],
            &mut tracker,
            &stats,
        );
        assert!(!stats.ranking_eligible.load(Ordering::Acquire));
        assert_eq!(stats.credits_used.load(Ordering::Acquire), 2);
        assert_eq!(stats.current_score.load(Ordering::Relaxed), 5_000);
        assert_eq!(stats.best_session_score.load(Ordering::Relaxed), 5_000);
        assert_eq!(stats.pending_submit_score.load(Ordering::Acquire), 5_000);
    }

    #[test]
    fn ram_oscillation_without_continue_input_does_not_disqualify() {
        use std::sync::atomic::Ordering;

        let stats = super::SharedStats::default();
        stats.ranking_eligible.store(true, Ordering::Release);
        stats.credits_used.store(1, Ordering::Release);
        let mut tracker = CoinTracker::new();

        // Start game with 1 credit
        super::apply_leaderboard_frame(
            crate::retro::leaderboard::LeaderboardFrameOutput {
                active: true,
                current_score: 1_400,
                triggered_score: None,
            },
            &[RETRO_START_MASK, 0, 0, 0],
            &mut tracker,
            &stats,
        );

        // RAM flag flickers 1 -> 0 -> 1 without any START or COIN input
        super::apply_leaderboard_frame(
            crate::retro::leaderboard::LeaderboardFrameOutput {
                active: false,
                current_score: 1_400,
                triggered_score: Some(1_400),
            },
            &[0, 0, 0, 0],
            &mut tracker,
            &stats,
        );
        super::apply_leaderboard_frame(
            crate::retro::leaderboard::LeaderboardFrameOutput {
                active: true,
                current_score: 2_800,
                triggered_score: None,
            },
            &[0, 0, 0, 0],
            &mut tracker,
            &stats,
        );

        assert!(stats.ranking_eligible.load(Ordering::Acquire));
        assert_eq!(stats.credits_used.load(Ordering::Acquire), 1);
        assert_eq!(stats.current_score.load(Ordering::Relaxed), 2_800);
    }

    #[test]
    fn reset_replay_stats_clears_progress_scores_and_coin_tracker() {
        use std::sync::atomic::Ordering;

        let stats = super::SharedStats::default();
        stats.replay_current_frame.store(1200, Ordering::Release);
        stats.replay_finished.store(true, Ordering::Release);
        stats.current_score.store(45_000, Ordering::Release);
        stats.best_session_score.store(90_000, Ordering::Release);

        let mut tracker = CoinTracker::new();
        assert_eq!(tracker.observe_frame(true, &[RETRO_START_MASK, 0, 0, 0]), 1);
        assert_eq!(tracker.observe_frame(true, &[RETRO_COIN_MASK, 0, 0, 0]), 2);
        assert!(tracker.has_player_started());

        super::reset_replay_stats(&stats, &mut tracker);

        assert_eq!(stats.replay_current_frame.load(Ordering::Acquire), 0);
        assert!(!stats.replay_finished.load(Ordering::Acquire));
        assert_eq!(stats.current_score.load(Ordering::Acquire), 0);
        assert_eq!(stats.best_session_score.load(Ordering::Acquire), 0);
        assert!(!tracker.has_player_started());
        assert_eq!(tracker.credits_used(), 1);
    }

    #[test]
    fn last_frame_buffer_reuses_allocation_and_supports_restart_invalidate() {
        let mut buf = super::LastFrameBuffer::default();
        let frame1 = vec![1u8; 320 * 240 * 4];
        buf.update(320, 240, 1, &frame1);
        let ptr1 = buf.rgba.as_ptr();
        let cap1 = buf.rgba.capacity();
        assert_eq!(
            buf.as_frame().map(|(w, h, rot, _)| (w, h, rot)),
            Some((320, 240, 1))
        );

        let frame2 = vec![2u8; 320 * 240 * 4];
        buf.update(320, 240, 1, &frame2);
        assert_eq!(buf.rgba.as_ptr(), ptr1);
        assert_eq!(buf.rgba.capacity(), cap1);

        buf.invalidate();
        assert!(buf.as_frame().is_none());
        assert_eq!(buf.rgba.capacity(), cap1);
    }

    #[test]
    fn compute_rollback_frames_resets_when_no_load_state() {
        assert_eq!(super::compute_rollback_frames(false, 1), 0);
        assert_eq!(super::compute_rollback_frames(false, 0), 0);
        assert_eq!(super::compute_rollback_frames(true, 1), 0);
        assert_eq!(super::compute_rollback_frames(true, 4), 3);
    }

    #[test]
    fn compute_reconnect_secs_left_accounts_for_notify_delay() {
        use std::time::Duration;
        // 8s disconnect timeout with 2s notify delay -> GGRS delivers 6000ms budget
        let budget = Duration::from_millis(6000);
        assert_eq!(
            super::compute_reconnect_secs_left(budget, Duration::from_millis(0)),
            6
        );
        assert_eq!(
            super::compute_reconnect_secs_left(budget, Duration::from_millis(500)),
            6
        );
        assert_eq!(
            super::compute_reconnect_secs_left(budget, Duration::from_millis(1000)),
            5
        );
        assert_eq!(
            super::compute_reconnect_secs_left(budget, Duration::from_millis(5900)),
            1
        );
        assert_eq!(
            super::compute_reconnect_secs_left(budget, Duration::from_millis(6000)),
            0
        );
        assert_eq!(
            super::compute_reconnect_secs_left(budget, Duration::from_millis(8000)),
            0
        );
    }

    #[test]
    fn aggregate_remote_ping_takes_max_across_peers() {
        assert_eq!(super::aggregate_remote_ping_ms([]), 0);
        assert_eq!(super::aggregate_remote_ping_ms([24, 110, 48]), 110);
        assert_eq!(super::aggregate_remote_ping_ms([75]), 75);
    }
}
