use std::ffi::c_void;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::Arc;
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use crossbeam_channel::{bounded, unbounded, Sender};

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

pub struct EmulationSession {
    cmd_tx: Sender<SessionCommand>,
    running: Arc<AtomicBool>,
    fps_x100: Arc<AtomicU32>,
    thread: Option<JoinHandle<()>>,
}

impl EmulationSession {
    pub fn start(
        core_path: PathBuf,
        rom_path: PathBuf,
        system_dir: PathBuf,
        save_dir: PathBuf,
    ) -> Result<Self, CoreError> {
        let (cmd_tx, cmd_rx) = unbounded::<SessionCommand>();
        let (init_tx, init_rx) = bounded::<Result<(), CoreError>>(1);

        let running = Arc::new(AtomicBool::new(true));
        let fps_x100 = Arc::new(AtomicU32::new(0));
        let running_clone = Arc::clone(&running);
        let fps_clone = Arc::clone(&fps_x100);

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

                while running_clone.load(Ordering::Relaxed) {
                    while let Ok(cmd) = cmd_rx.try_recv() {
                        match cmd {
                            SessionCommand::AttachSurface {
                                window_ptr,
                                width,
                                height,
                            } => {
                                // SAFETY: `window_ptr` fue obtenido con `ANativeWindow_fromSurface` y sigue retenido.
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
                                paused = p;
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
                                let res = core.save_state_bytes().and_then(|bytes| {
                                    std::fs::write(&path, bytes).map_err(|e| {
                                        CoreError::State(format!("Error escribiendo savestate: {e}"))
                                    })
                                });
                                let _ = ack.send(res);
                            }
                            SessionCommand::LoadState { path, ack } => {
                                let res = std::fs::read(&path)
                                    .map_err(|e| {
                                        CoreError::State(format!("Error leyendo savestate: {e}"))
                                    })
                                    .and_then(|bytes| core.load_state_bytes(&bytes));
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

                    core.set_inputs(inputs);
                    core.run_frame();

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
                        fps_clone.store(fps as u32, Ordering::Relaxed);
                        frames_in_window = 0;
                        fps_window_start = Instant::now();
                    }

                    next_frame_at += frame_duration;
                    let now = Instant::now();
                    if next_frame_at > now {
                        std::thread::sleep(next_frame_at - now);
                    } else if now.duration_since(next_frame_at) > frame_duration * 4 {
                        // Evitar espiral de retraso tras pausa o throttling del SO
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
                fps_x100,
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

    pub fn current_fps(&self) -> f32 {
        self.fps_x100.load(Ordering::Relaxed) as f32 / 100.0
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
