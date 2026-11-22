use std::ffi::c_void;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
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
    Stop,
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
}

pub struct EmulationSession {
    cmd_tx: Sender<SessionCommand>,
    running: Arc<AtomicBool>,
    stats: Arc<SharedStats>,
    thread: Option<JoinHandle<()>>,
}

impl EmulationSession {
    pub fn start(
        core_path: PathBuf,
        rom_path: PathBuf,
        system_dir: PathBuf,
        save_dir: PathBuf,
        netplay: Option<NetplaySessionConfig>,
    ) -> Result<Self, CoreError> {
        let (cmd_tx, cmd_rx) = unbounded::<SessionCommand>();
        let (init_tx, init_rx) = bounded::<Result<(), CoreError>>(1);

        let running = Arc::new(AtomicBool::new(true));
        let stats = Arc::new(SharedStats::default());
        stats
            .ranking_eligible
            .store(netplay.is_none(), Ordering::Release);
        let running_clone = Arc::clone(&running);
        let stats_clone = Arc::clone(&stats);

        let thread = std::thread::Builder::new()
            .name("balddog-emu".to_string())
            .spawn(move || {
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
                let _ = init_tx.send(Ok(()));

                let target_fps = if av_info.timing.fps > 10.0 {
                    av_info.timing.fps
                } else {
                    60.0
                };
                let frame_duration = Duration::from_secs_f64(1.0 / target_fps);
                let mut paused = false;
                let mut inputs = [0u16; 4];
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
                                }
                                if let Some(a) = &audio {
                                    a.set_paused(p);
                                }
                                next_frame_at = Instant::now();
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
                    } else {
                        core.set_inputs(inputs);
                        core.run_frame();
                    }

                    core.take_video_frame(|w, h, rgba| {
                        renderer.render_frame(w, h, rgba);
                    });

                    if let Some(a) = &mut audio {
                        core.take_audio_samples(|samples| {
                            a.push_samples(samples);
                        });
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
