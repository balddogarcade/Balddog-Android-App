use std::net::{SocketAddr, UdpSocket};
use std::time::Duration;

use ggrs::{Config, P2PSession, PlayerType, SessionBuilder, UdpNonBlockingSocket};
use thiserror::Error;

#[derive(Debug, Error)]
pub enum NetplayError {
    #[error("Error de transporte de red: {0}")]
    Transport(String),

    #[error("Configuración de sala inválida: {0}")]
    Config(String),

    #[error("Tiempo de espera agotado para reconexión")]
    ReconnectTimeout,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum NetplayMode {
    Local,
    Lan,
    OnlineP2p,
}

#[derive(Debug, Clone)]
pub struct ArcadeGgrsConfig;

impl Config for ArcadeGgrsConfig {
    type Input = u16;
    type State = Vec<u8>;
    type Address = SocketAddr;
}

#[derive(Debug, Clone)]
pub struct PeerEndpoint {
    pub player_handle: usize,
    pub addr: SocketAddr,
}

#[derive(Debug, Clone)]
pub struct NetplaySessionConfig {
    pub local_port: u16,
    pub local_player_handle: usize,
    pub num_players: usize,
    pub peers: Vec<PeerEndpoint>,
    pub input_delay: usize,
    pub disconnect_timeout_secs: u64,
}

#[derive(Debug, Clone, Copy, Default)]
pub struct NetplayHudStats {
    pub ping_ms: u32,
    pub rollback_frames: u32,
    pub reconnecting: bool,
    pub reconnect_secs_left: u32,
    pub session_ended: bool,
}

pub fn build_p2p_session(
    cfg: &NetplaySessionConfig,
) -> Result<P2PSession<ArcadeGgrsConfig>, NetplayError> {
    if cfg.num_players < 2 || cfg.num_players > 4 {
        return Err(NetplayError::Config(
            "num_players debe estar entre 2 y 4".into(),
        ));
    }
    if cfg.local_player_handle >= cfg.num_players {
        return Err(NetplayError::Config(
            "local_player_handle fuera de rango".into(),
        ));
    }

    let socket = UdpNonBlockingSocket::bind_to_port(cfg.local_port)
        .map_err(|e| NetplayError::Transport(format!("No se pudo abrir UDP {}: {e}", cfg.local_port)))?;

    let mut builder = SessionBuilder::<ArcadeGgrsConfig>::new()
        .with_num_players(cfg.num_players)
        .with_input_delay(cfg.input_delay)
        .with_disconnect_timeout(Duration::from_secs(cfg.disconnect_timeout_secs.max(5)))
        .with_disconnect_notify_delay(Duration::from_secs(2));

    builder = builder
        .add_player(PlayerType::Local, cfg.local_player_handle)
        .map_err(|e| NetplayError::Config(format!("Error agregando jugador local: {e}")))?;

    for peer in &cfg.peers {
        builder = builder
            .add_player(PlayerType::Remote(peer.addr), peer.player_handle)
            .map_err(|e| NetplayError::Config(format!("Error agregando par remoto: {e}")))?;
    }

    builder
        .start_p2p_session(socket)
        .map_err(|e| NetplayError::Transport(format!("Error iniciando sesión GGRS: {e}")))
}

/// Realiza una petición STUN Binding Request (RFC 5389) para descubrir IP:puerto público.
pub fn discover_stun_endpoint(local_port: u16) -> Result<String, NetplayError> {
    let socket = UdpSocket::bind(("0.0.0.0", local_port))
        .map_err(|e| NetplayError::Transport(format!("Bind UDP STUN falló: {e}")))?;
    socket
        .set_read_timeout(Some(Duration::from_secs(3)))
        .map_err(|e| NetplayError::Transport(format!("Timeout UDP falló: {e}")))?;

    let stun_servers = ["stun.cloudflare.com:3478", "stun.l.google.com:19302"];

    // RFC 5389 Binding Request (20 bytes)
    let mut req = [0u8; 20];
    req[0] = 0x00;
    req[1] = 0x01; // Binding Request
    req[2] = 0x00;
    req[3] = 0x00; // Length = 0
    // Magic cookie 0x2112A442
    req[4..8].copy_from_slice(&[0x21, 0x12, 0xA4, 0x42]);
    // Transaction ID (12 bytes pseudo-aleatorios)
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0x12345678);
    let tid_bytes = nanos.to_le_bytes();
    req[8..20].copy_from_slice(&tid_bytes[0..12]);

    let mut buf = [0u8; 256];
    for server in stun_servers {
        if socket.send_to(&req, server).is_err() {
            continue;
        }
        if let Ok((len, _)) = socket.recv_from(&mut buf) {
            if let Some(addr) = parse_stun_xor_mapped(&buf[..len]) {
                return Ok(addr.to_string());
            }
        }
    }

    Err(NetplayError::Transport(
        "No se recibió respuesta de servidores STUN".into(),
    ))
}

fn parse_stun_xor_mapped(pkt: &[u8]) -> Option<SocketAddr> {
    if pkt.len() < 20 || pkt[0] != 0x01 || pkt[1] != 0x01 {
        return None;
    }
    let msg_len = u16::from_be_bytes([pkt[2], pkt[3]]) as usize;
    let end = (20 + msg_len).min(pkt.len());
    let mut pos = 20;
    while pos + 4 <= end {
        let attr_type = u16::from_be_bytes([pkt[pos], pkt[pos + 1]]);
        let attr_len = u16::from_be_bytes([pkt[pos + 2], pkt[pos + 3]]) as usize;
        let val_start = pos + 4;
        let val_end = val_start + attr_len;
        if val_end > end {
            break;
        }
        // 0x0020 = XOR-MAPPED-ADDRESS, IPv4 family = 0x01
        if attr_type == 0x0020 && attr_len >= 8 && pkt[val_start + 1] == 0x01 {
            let xport = u16::from_be_bytes([pkt[val_start + 2], pkt[val_start + 3]]) ^ 0x2112;
            let ip = std::net::Ipv4Addr::new(
                pkt[val_start + 4] ^ 0x21,
                pkt[val_start + 5] ^ 0x12,
                pkt[val_start + 6] ^ 0xA4,
                pkt[val_start + 7] ^ 0x42,
            );
            return Some(SocketAddr::new(std::net::IpAddr::V4(ip), xport));
        }
        let padded = (attr_len + 3) & !3;
        pos = val_start + padded;
    }
    None
}
