use std::ffi::c_void;
use std::io::BufWriter;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, AtomicU8, Ordering};
use std::sync::Arc;
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use balddog_netplay::{
    build_p2p_session, NetplayHudStats, NetplaySessionConfig,
};
use crossbeam_channel::{bounded, unbounded, Sender};
use ggrs::{GgrsEvent, GgrsRequest, InputStatus, SessionState};

use crate::audio::engine::AudioEngine;
use crate::retro::core::LibretroCore;
use crate::retro::leaderboard::LeaderboardEngine;
use crate::retro::replay::{ReplayPlayer, ReplayRecorder};
use crate::video::renderer::WgpuRenderer;
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

pub(crate) const RETRO_COIN_MASK: u16 = 1 << 2;

/// Rastrea pulsaciones de COIN (bit 2 / SELECT) en los 4 puertos desde el frame 0,
/// incluyendo fichas precargadas antes de pulsar Start o insertadas en P2-P4.
#[derive(Debug, Clone, Default)]
pub(crate) struct CoinTracker {
    prev_coins: [bool; 4],
    coins_inserted: u32,
}

impl CoinTracker {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn observe_frame(&mut self, inputs: &[u16; 4]) -> u8 {
        for (port, &mask) in inputs.iter().enumerate() {
            let pressed = (mask & RETRO_COIN_MASK) != 0;
            if pressed && !self.prev_coins[port] {
                self.coins_inserted = self.coins_inserted.saturating_add(1);
            }
            self.prev_coins[port] = pressed;
        }
        self.credits_used()
    }

    #[cfg_attr(not(test), allow(dead_code))]
    pub fn coins_inserted(&self) -> u32 {
        self.coins_inserted
    }

    pub fn credits_used(&self) -> u8 {
        self.coins_inserted.max(1).min(u8::MAX as u32) as u8
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
    reconnect_secs_left: AtomicU32,
    session_ended: AtomicBool,
    ranking_eligible: AtomicBool,
    current_score: AtomicU64,
    best_session_score: AtomicU64,
    pending_submit_score: AtomicU64,
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
        Self::start_internal(core_path, rom_path, system_dir, save_dir, netplay, score_def, None)
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
        stats
            .ranking_eligible
            .store(netplay.is_none() && !is_replay_mode, Ordering::Release);
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
                if is_replay_mode || (netplay.is_none() && score_def.is_some()) {
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

                let mut ggrs_session = match netplay.as_ref() {
                    Some(cfg) => match build_p2p_session(cfg) {
                        Ok(s) => Some((s, cfg.local_player_handle, cfg.disconnect_timeout_secs)),
                        Err(e) => {
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

                let mut renderer = WgpuRenderer::new(av_info.geometry.aspect_ratio);
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
                let mut replay_player = initial_replay_player;
                let mut recorder = if netplay.is_none() && replay_player.is_none() && leaderboard.is_some() {
                    Some(ReplayRecorder::new(0))
                } else {
                    None
                };
                let mut replay_speed: u8 = 1;
                let mut last_frame_rgba: Option<(u32, u32, Vec<u8>)> = None;
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
                let mut disconnect_started: Option<Instant> = None;

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
                                    if core.load_game(&rom_path).is_ok() {
                                        p.restart();
                                        stats_clone.replay_current_frame.store(0, Ordering::Release);
                                        stats_clone.replay_finished.store(false, Ordering::Release);
                                        stats_clone.current_score.store(0, Ordering::Release);
                                    }
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
                                let res = match last_frame_rgba.as_ref() {
                                    Some((w, h, rgba)) => write_png_rgba(&path, *w, *h, rgba),
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

                    if let Some((ref mut p2p, local_handle, timeout_secs)) = ggrs_session {
                        p2p.poll_remote_clients();

                        for ev in p2p.events() {
                            match ev {
                                GgrsEvent::NetworkInterrupted { .. } => {
                                    disconnect_started.get_or_insert_with(Instant::now);
                                    stats_clone.reconnecting.store(true, Ordering::Release);
                                }
                                GgrsEvent::NetworkResumed { .. } | GgrsEvent::Synchronized { .. } => {
                                    disconnect_started = None;
                                    stats_clone.reconnecting.store(false, Ordering::Release);
                                    stats_clone.reconnect_secs_left.store(0, Ordering::Release);
                                }
                                GgrsEvent::Disconnected { .. } => {
                                    record_log("WARN", "Par remoto desconectado definitivamente");
                                    stats_clone.reconnecting.store(false, Ordering::Release);
                                    stats_clone.session_ended.store(true, Ordering::Release);
                                    running_clone.store(false, Ordering::Release);
                                }
                                _ => {}
                            }
                        }

                        if let Some(started) = disconnect_started {
                            let elapsed = started.elapsed().as_secs();
                            let left = timeout_secs.saturating_sub(elapsed) as u32;
                            stats_clone.reconnect_secs_left.store(left, Ordering::Relaxed);
                        }

                        if p2p.current_state() == SessionState::Running {
                            let local_input = inputs[0];
                            if p2p.add_local_input(local_handle, local_input).is_ok() {
                                match p2p.advance_frame() {
                                    Ok(requests) => {
                                        let mut rollbacks_now: u32 = 0;
                                        for req in requests {
                                            match req {
                                                GgrsRequest::SaveGameState { cell, frame } => {
                                                    if let Ok(bytes) = core.save_state_bytes() {
                                                        cell.save(frame, Some(bytes), None);
                                                    }
                                                }
                                                GgrsRequest::LoadGameState { cell, .. } => {
                                                    if let Some(bytes) = cell.load() {
                                                        let _ = core.load_state_bytes(&bytes);
                                                        rollbacks_now += 1;
                                                    }
                                                }
                                                GgrsRequest::AdvanceFrame { inputs: frame_inputs } => {
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
                                        if rollbacks_now > 0 {
                                            stats_clone.rollback_frames.store(rollbacks_now, Ordering::Relaxed);
                                        }
                                    }
                                    Err(e) => {
                                        record_log("WARN", &format!("GGRS advance_frame: {e}"));
                                    }
                                }
                            }

                            // Actualizar ping desde estadísticas de GGRS para pares remotos
                            for h in 0..p2p.num_players() {
                                if h != local_handle {
                                    if let Ok(ns) = p2p.network_stats(h) {
                                        stats_clone.ping_ms.store(ns.ping as u32, Ordering::Relaxed);
                                    }
                                }
                            }
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
                        let credits_used = coin_tracker.observe_frame(&inputs);
                        stats_clone
                            .credits_used
                            .store(credits_used, Ordering::Release);
                        if let Some(ref mut rec) = recorder {
                            rec.push_frame(inputs);
                        }
                        core.set_inputs(inputs);
                        core.run_frame();
                        if let Some(ref mut lb) = leaderboard {
                            if stats_clone.ranking_eligible.load(Ordering::Relaxed) {
                                if let Some(ram) = core.system_ram() {
                                    let out = lb.evaluate_frame(ram);
                                    stats_clone
                                        .current_score
                                        .store(out.current_score, Ordering::Relaxed);
                                    if out.current_score > 0 {
                                        stats_clone
                                            .best_session_score
                                            .fetch_max(out.current_score, Ordering::Relaxed);
                                    }
                                    if let Some(triggered) = out.triggered_score {
                                        if triggered > 0 {
                                            stats_clone
                                                .pending_submit_score
                                                .fetch_max(triggered, Ordering::Release);
                                        }
                                    }
                                }
                            }
                        }
                    }

                    core.take_video_frame(|w, h, rgba| {
                        last_frame_rgba = Some((w, h, rgba.to_vec()));
                        renderer.render_frame(w, h, rgba);
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
                Err(CoreError::Init("El hilo de emulación terminó inesperadamente".into()))
            }
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

    pub fn detach_surface_sync(&self) {
        let (ack_tx, ack_rx) = bounded(1);
        if self
            .cmd_tx
            .send(SessionCommand::DetachSurface { ack: ack_tx })
            .is_ok()
        {
            let _ = ack_rx.recv_timeout(Duration::from_secs(2));
        }
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
        ScoreHudStats {
            current_score: self.stats.current_score.load(Ordering::Relaxed),
            best_session_score: self.stats.best_session_score.load(Ordering::Relaxed),
            pending_submit_score: self.stats.pending_submit_score.load(Ordering::Acquire),
            ranking_eligible: self.stats.ranking_eligible.load(Ordering::Acquire),
            credits_used: self.stats.credits_used.load(Ordering::Acquire),
        }
    }

    pub fn acknowledge_score_submit(&self) {
        self.stats.pending_submit_score.store(0, Ordering::Release);
    }

    pub fn set_replay_speed(&self, speed: u8) {
        let _ = self.cmd_tx.send(SessionCommand::SetReplaySpeed(speed));
    }

    pub fn restart_replay(&self) {
        let _ = self.cmd_tx.send(SessionCommand::RestartReplay);
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
    use super::{CoinTracker, RETRO_COIN_MASK};

    #[test]
    fn single_coin_held_counts_as_one_credit() {
        let mut tracker = CoinTracker::new();
        assert_eq!(tracker.credits_used(), 1);
        assert_eq!(tracker.coins_inserted(), 0);

        // Held for 4 frames -> rising edge only once
        for _ in 0..4 {
            assert_eq!(tracker.observe_frame(&[RETRO_COIN_MASK, 0, 0, 0]), 1);
        }
        assert_eq!(tracker.coins_inserted(), 1);
        assert_eq!(tracker.observe_frame(&[0, 0, 0, 0]), 1);
    }

    #[test]
    fn preloaded_second_coin_disqualifies_1cc() {
        let mut tracker = CoinTracker::new();
        assert_eq!(tracker.observe_frame(&[RETRO_COIN_MASK, 0, 0, 0]), 1);
        assert_eq!(tracker.observe_frame(&[0, 0, 0, 0]), 1);
        // Second coin inserted before pressing Start
        assert_eq!(tracker.observe_frame(&[RETRO_COIN_MASK, 0, 0, 0]), 2);
        assert_eq!(tracker.coins_inserted(), 2);
    }

    #[test]
    fn p2_through_p4_coins_are_counted() {
        let mut tracker = CoinTracker::new();
        assert_eq!(tracker.observe_frame(&[RETRO_COIN_MASK, 0, 0, 0]), 1);
        assert_eq!(tracker.observe_frame(&[0, 0, 0, 0]), 1);
        assert_eq!(tracker.observe_frame(&[0, RETRO_COIN_MASK, 0, 0]), 2);
        assert_eq!(tracker.observe_frame(&[0, 0, RETRO_COIN_MASK, RETRO_COIN_MASK]), 4);
        assert_eq!(tracker.coins_inserted(), 4);
    }

    #[test]
    fn coin_counter_saturates_at_u8_max() {
        let mut tracker = CoinTracker::new();
        for _ in 0..300 {
            tracker.observe_frame(&[RETRO_COIN_MASK, 0, 0, 0]);
            tracker.observe_frame(&[0, 0, 0, 0]);
        }
        assert_eq!(tracker.coins_inserted(), 300);
        assert_eq!(tracker.credits_used(), u8::MAX);
    }
}
