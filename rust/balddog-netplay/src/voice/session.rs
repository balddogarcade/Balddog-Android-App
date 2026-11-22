use std::collections::{HashMap, VecDeque};
use std::net::{SocketAddr, UdpSocket};
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicU64, AtomicU8, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

use opus::{Application, Bitrate, Channels, Decoder, Encoder};

use crate::NetplayError;

use super::jitter::{JitterBuffer, JitterPop};
use super::protocol::{
    decode_packet, derive_session_token, encode_packet, VoicePacketHeader, VoicePacketType,
    MAX_VOICE_PAYLOAD_BYTES, VOICE_FRAME_SAMPLES, VOICE_SAMPLE_RATE,
};
use super::vad::VoiceActivityDetector;

const KEEPALIVE_INTERVAL: Duration = Duration::from_millis(2000);
const MIX_TICK_INTERVAL: Duration = Duration::from_millis(20);
const SPEAKING_HOLD_MS: u64 = 350;
const MAX_PLAYBACK_QUEUE_SAMPLES: usize = VOICE_FRAME_SAMPLES * 5; // 100 ms máx

#[derive(Debug, Clone)]
pub struct VoicePeerEndpoint {
    pub player_handle: u8,
    pub addr: SocketAddr,
}

#[derive(Debug, Clone)]
pub struct VoiceSessionConfig {
    pub local_port: u16,
    pub room_id: String,
    pub local_player_handle: u8,
    pub peers: Vec<VoicePeerEndpoint>,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct VoiceHudState {
    pub local_speaking: bool,
    pub remote_speaking_mask: u8,
    pub active_peers: u8,
}

struct RemotePeerState {
    addr: SocketAddr,
    jitter: JitterBuffer,
    decoder: Decoder,
    last_speaking_ms: u64,
}

struct SharedControls {
    mic_enabled: AtomicBool,
    listen_enabled: AtomicBool,
    paused: AtomicBool,
    volume_q15: AtomicI32,
    local_speaking_until_ms: AtomicU64,
    remote_speaking_mask: AtomicU8,
    active_peers: AtomicU8,
}

impl Default for SharedControls {
    fn default() -> Self {
        Self {
            mic_enabled: AtomicBool::new(false),
            listen_enabled: AtomicBool::new(false),
            paused: AtomicBool::new(false),
            volume_q15: AtomicI32::new(32768),
            local_speaking_until_ms: AtomicU64::new(0),
            remote_speaking_mask: AtomicU8::new(0),
            active_peers: AtomicU8::new(0),
        }
    }
}

struct CaptureState {
    encoder: Encoder,
    vad: VoiceActivityDetector,
    seq: u16,
    packet_buf: Vec<u8>,
    opus_buf: Vec<u8>,
}

pub struct VoiceSession {
    socket: UdpSocket,
    session_token: u64,
    local_player_handle: u8,
    started_at: Instant,
    running: Arc<AtomicBool>,
    controls: Arc<SharedControls>,
    peer_addrs: Arc<Mutex<Vec<VoicePeerEndpoint>>>,
    capture: Mutex<CaptureState>,
    playback_queue: Arc<Mutex<VecDeque<i16>>>,
    thread: Option<JoinHandle<()>>,
}

impl VoiceSession {
    pub fn start(cfg: VoiceSessionConfig) -> Result<Self, NetplayError> {
        let socket = UdpSocket::bind(("0.0.0.0", cfg.local_port)).map_err(|e| {
            NetplayError::Transport(format!("No se pudo abrir UDP voz {}: {e}", cfg.local_port))
        })?;
        socket.set_nonblocking(true).map_err(|e| {
            NetplayError::Transport(format!("No se pudo configurar UDP voz nonblocking: {e}"))
        })?;

        let mut encoder = Encoder::new(VOICE_SAMPLE_RATE, Channels::Mono, Application::Voip)
            .map_err(|e| NetplayError::Transport(format!("Error iniciando Opus encoder: {e}")))?;
        let _ = encoder.set_bitrate(Bitrate::Bits(20_000));

        let session_token = derive_session_token(&cfg.room_id);
        let started_at = Instant::now();
        let running = Arc::new(AtomicBool::new(true));
        let controls = Arc::new(SharedControls::default());
        controls
            .active_peers
            .store(cfg.peers.len() as u8, Ordering::Relaxed);

        let mut remote_peers: HashMap<u8, RemotePeerState> = HashMap::new();
        for peer in &cfg.peers {
            if peer.player_handle == cfg.local_player_handle {
                continue;
            }
            let decoder = Decoder::new(VOICE_SAMPLE_RATE, Channels::Mono).map_err(|e| {
                NetplayError::Transport(format!("Error iniciando Opus decoder: {e}"))
            })?;
            remote_peers.insert(
                peer.player_handle,
                RemotePeerState {
                    addr: peer.addr,
                    jitter: JitterBuffer::default(),
                    decoder,
                    last_speaking_ms: 0,
                },
            );
        }

        let peer_addrs = Arc::new(Mutex::new(cfg.peers));
        let playback_queue = Arc::new(Mutex::new(VecDeque::with_capacity(
            MAX_PLAYBACK_QUEUE_SAMPLES,
        )));

        let rx_socket = socket
            .try_clone()
            .map_err(|e| NetplayError::Transport(format!("Error clonando socket voz: {e}")))?;
        let running_rx = Arc::clone(&running);
        let controls_rx = Arc::clone(&controls);
        let peer_addrs_rx = Arc::clone(&peer_addrs);
        let playback_rx = Arc::clone(&playback_queue);
        let local_handle = cfg.local_player_handle;

        let thread = thread::Builder::new()
            .name("balddog-voice".to_string())
            .spawn(move || {
                run_voice_loop(
                    rx_socket,
                    session_token,
                    local_handle,
                    started_at,
                    running_rx,
                    controls_rx,
                    peer_addrs_rx,
                    remote_peers,
                    playback_rx,
                );
            })
            .map_err(|e| NetplayError::Transport(format!("Error creando hilo de voz: {e}")))?;

        Ok(Self {
            socket,
            session_token,
            local_player_handle: cfg.local_player_handle,
            started_at,
            running,
            controls,
            peer_addrs,
            capture: Mutex::new(CaptureState {
                encoder,
                vad: VoiceActivityDetector::default(),
                seq: 0,
                packet_buf: Vec::with_capacity(64 + MAX_VOICE_PAYLOAD_BYTES),
                opus_buf: vec![0u8; MAX_VOICE_PAYLOAD_BYTES],
            }),
            playback_queue,
            thread: Some(thread),
        })
    }

    pub fn set_controls(&self, mic_enabled: bool, listen_enabled: bool, volume: f32, paused: bool) {
        self.controls.mic_enabled.store(mic_enabled, Ordering::Release);
        self.controls
            .listen_enabled
            .store(listen_enabled, Ordering::Release);
        self.controls.paused.store(paused, Ordering::Release);
        let clamped = volume.clamp(0.0, 1.5);
        let q15 = (clamped * 32768.0) as i32;
        self.controls.volume_q15.store(q15, Ordering::Release);

        if !listen_enabled {
            if let Ok(mut q) = self.playback_queue.lock() {
                q.clear();
            }
        }
    }

    pub fn push_capture_pcm(&self, samples: &[i16]) {
        if samples.len() != VOICE_FRAME_SAMPLES {
            return;
        }
        let mic_on = self.controls.mic_enabled.load(Ordering::Acquire);
        let paused = self.controls.paused.load(Ordering::Acquire);
        if !mic_on || paused {
            if let Ok(mut cap) = self.capture.lock() {
                cap.vad.reset();
            }
            self.controls
                .local_speaking_until_ms
                .store(0, Ordering::Release);
            return;
        }

        let now_ms = self.started_at.elapsed().as_millis() as u64;
        let encoded_len = {
            let Ok(mut cap) = self.capture.lock() else {
                return;
            };
            let vad = cap.vad.process(samples);
            if !vad.active {
                return;
            }
            self.controls
                .local_speaking_until_ms
                .store(now_ms + SPEAKING_HOLD_MS, Ordering::Release);

            let cap_mut = &mut *cap;
            let Ok(len) = cap_mut.encoder.encode(samples, &mut cap_mut.opus_buf) else {
                return;
            };
            let hdr = VoicePacketHeader {
                session_token: self.session_token,
                msg_type: VoicePacketType::Audio,
                player_handle: self.local_player_handle,
                seq: cap_mut.seq,
                timestamp_ms: now_ms as u32,
            };
            cap_mut.seq = cap_mut.seq.wrapping_add(1);
            if encode_packet(&hdr, &cap_mut.opus_buf[..len], &mut cap_mut.packet_buf).is_err() {
                return;
            }
            if let Ok(addrs) = self.peer_addrs.lock() {
                for peer in addrs.iter() {
                    let _ = self.socket.send_to(&cap_mut.packet_buf, peer.addr);
                }
            }
            len
        };
        let _ = encoded_len;
    }

    pub fn pull_playback_pcm(&self, max_samples: usize) -> Vec<i16> {
        if !self.controls.listen_enabled.load(Ordering::Acquire) || max_samples == 0 {
            return Vec::new();
        }
        let Ok(mut q) = self.playback_queue.lock() else {
            return Vec::new();
        };
        let count = max_samples.min(q.len());
        let mut out = Vec::with_capacity(count);
        for _ in 0..count {
            if let Some(s) = q.pop_front() {
                out.push(s);
            }
        }
        out
    }

    pub fn hud_state(&self) -> VoiceHudState {
        let now_ms = self.started_at.elapsed().as_millis() as u64;
        let local_until = self
            .controls
            .local_speaking_until_ms
            .load(Ordering::Acquire);
        let mic_on = self.controls.mic_enabled.load(Ordering::Acquire)
            && !self.controls.paused.load(Ordering::Acquire);
        let listen_on = self.controls.listen_enabled.load(Ordering::Acquire);
        VoiceHudState {
            local_speaking: mic_on && now_ms <= local_until,
            remote_speaking_mask: if listen_on {
                self.controls.remote_speaking_mask.load(Ordering::Acquire)
            } else {
                0
            },
            active_peers: self.controls.active_peers.load(Ordering::Relaxed),
        }
    }

    pub fn stop(&mut self) {
        self.running.store(false, Ordering::Release);
        if let Some(handle) = self.thread.take() {
            let _ = handle.join();
        }
    }
}

impl Drop for VoiceSession {
    fn drop(&mut self) {
        self.stop();
    }
}

#[allow(clippy::too_many_arguments)]
fn run_voice_loop(
    socket: UdpSocket,
    session_token: u64,
    local_handle: u8,
    started_at: Instant,
    running: Arc<AtomicBool>,
    controls: Arc<SharedControls>,
    peer_addrs: Arc<Mutex<Vec<VoicePeerEndpoint>>>,
    mut remote_peers: HashMap<u8, RemotePeerState>,
    playback_queue: Arc<Mutex<VecDeque<i16>>>,
) {
    let mut rx_buf = [0u8; 1024];
    let mut keepalive_buf = Vec::with_capacity(32);
    let mut decode_buf = [0i16; VOICE_FRAME_SAMPLES];
    let mut mix_acc = [0i32; VOICE_FRAME_SAMPLES];
    let mut keepalive_seq: u16 = 0;

    let mut last_keepalive = Instant::now() - KEEPALIVE_INTERVAL;
    let mut next_mix_at = Instant::now() + MIX_TICK_INTERVAL;

    while running.load(Ordering::Acquire) {
        // 1. Drenar todos los datagramas UDP pendientes
        loop {
            match socket.recv_from(&mut rx_buf) {
                Ok((len, src_addr)) => {
                    if let Some((hdr, payload)) = decode_packet(&rx_buf[..len]) {
                        if hdr.session_token != session_token || hdr.player_handle == local_handle {
                            continue;
                        }
                        if let Some(peer) = remote_peers.get_mut(&hdr.player_handle) {
                            if peer.addr != src_addr {
                                peer.addr = src_addr;
                                if let Ok(mut addrs) = peer_addrs.lock() {
                                    if let Some(entry) = addrs
                                        .iter_mut()
                                        .find(|p| p.player_handle == hdr.player_handle)
                                    {
                                        entry.addr = src_addr;
                                    }
                                }
                            }
                            if hdr.msg_type == VoicePacketType::Audio && !payload.is_empty() {
                                peer.jitter.push(hdr.seq, payload.to_vec());
                            }
                        }
                    }
                }
                Err(ref e) if e.kind() == std::io::ErrorKind::WouldBlock => break,
                Err(_) => break,
            }
        }

        // 2. Keepalive periódico para sostener NAT hole-punching
        let now = Instant::now();
        if now.duration_since(last_keepalive) >= KEEPALIVE_INTERVAL {
            last_keepalive = now;
            let hdr = VoicePacketHeader {
                session_token,
                msg_type: VoicePacketType::Keepalive,
                player_handle: local_handle,
                seq: keepalive_seq,
                timestamp_ms: started_at.elapsed().as_millis() as u32,
            };
            keepalive_seq = keepalive_seq.wrapping_add(1);
            if encode_packet(&hdr, &[], &mut keepalive_buf).is_ok() {
                for peer in remote_peers.values() {
                    let _ = socket.send_to(&keepalive_buf, peer.addr);
                }
            }
        }

        // 3. Tick de mezcla cada 20 ms
        if now >= next_mix_at {
            next_mix_at += MIX_TICK_INTERVAL;
            if next_mix_at < now {
                next_mix_at = now + MIX_TICK_INTERVAL;
            }

            let listen_on = controls.listen_enabled.load(Ordering::Acquire);
            let vol_q15 = controls.volume_q15.load(Ordering::Acquire);
            let now_ms = started_at.elapsed().as_millis() as u64;

            mix_acc.fill(0);
            let mut any_audio = false;
            let mut speaking_mask: u8 = 0;

            for (&handle, peer) in remote_peers.iter_mut() {
                match peer.jitter.pop() {
                    JitterPop::Frame(opus_pkt) => {
                        if let Ok(decoded) = peer.decoder.decode(&opus_pkt, &mut decode_buf, false) {
                            let n = decoded.min(VOICE_FRAME_SAMPLES);
                            for i in 0..n {
                                mix_acc[i] += i32::from(decode_buf[i]);
                            }
                            any_audio = true;
                            peer.last_speaking_ms = now_ms;
                        }
                    }
                    JitterPop::Lost => {
                        if let Ok(decoded) = peer.decoder.decode(&[], &mut decode_buf, false) {
                            let n = decoded.min(VOICE_FRAME_SAMPLES);
                            for i in 0..n {
                                mix_acc[i] += i32::from(decode_buf[i]);
                            }
                            any_audio = true;
                        }
                    }
                    JitterPop::Buffering => {}
                }

                if now_ms.saturating_sub(peer.last_speaking_ms) <= SPEAKING_HOLD_MS
                    && peer.last_speaking_ms > 0
                    && handle < 8
                {
                    speaking_mask |= 1 << handle;
                }
            }

            controls
                .remote_speaking_mask
                .store(speaking_mask, Ordering::Release);

            if listen_on && any_audio {
                if let Ok(mut q) = playback_queue.lock() {
                    while q.len() + VOICE_FRAME_SAMPLES > MAX_PLAYBACK_QUEUE_SAMPLES {
                        q.pop_front();
                    }
                    for &acc in mix_acc.iter() {
                        let scaled = (acc * vol_q15) >> 15;
                        let clamped = scaled.clamp(i32::from(i16::MIN), i32::from(i16::MAX)) as i16;
                        q.push_back(clamped);
                    }
                }
            }
        }

        thread::sleep(Duration::from_millis(4));
    }
}
