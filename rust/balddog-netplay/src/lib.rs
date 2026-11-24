use std::net::{SocketAddr, UdpSocket};
use std::time::Duration;

use ggrs::{Config, P2PSession, PlayerType, SessionBuilder, UdpNonBlockingSocket};
use thiserror::Error;

pub mod voice;

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

pub const DISCONNECT_NOTIFY_DELAY_SECS: u64 = 3;

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
        .with_disconnect_timeout(Duration::from_secs(cfg.disconnect_timeout_secs.max(20)))
        .with_disconnect_notify_delay(Duration::from_secs(DISCONNECT_NOTIFY_DELAY_SECS));

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

const STUN_MAGIC_COOKIE: [u8; 4] = [0x21, 0x12, 0xA4, 0x42];
const STUN_MAGIC_COOKIE_U16: u16 = 0x2112;

fn build_stun_binding_request() -> ([u8; 12], [u8; 20]) {
    use std::collections::hash_map::RandomState;
    use std::hash::{BuildHasher, Hasher};
    use std::sync::atomic::{AtomicU64, Ordering};

    static TID_COUNTER: AtomicU64 = AtomicU64::new(1);
    let seq = TID_COUNTER.fetch_add(1, Ordering::Relaxed);
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0x1234_5678_9ABC_DEF0);

    let mut h1 = RandomState::new().build_hasher();
    h1.write_u64(seq);
    h1.write_u128(nanos);
    let p1 = h1.finish().to_le_bytes();

    let mut h2 = RandomState::new().build_hasher();
    h2.write_u64(seq.wrapping_mul(0x9E37_79B9_7F4A_7C15));
    h2.write_u128(nanos.rotate_left(17));
    let p2 = h2.finish().to_le_bytes();

    let mut tid = [0u8; 12];
    tid[..8].copy_from_slice(&p1);
    tid[8..12].copy_from_slice(&p2[..4]);

    // RFC 5389 Binding Request (20 bytes)
    let mut req = [0u8; 20];
    req[0] = 0x00;
    req[1] = 0x01; // Binding Request
    req[2] = 0x00;
    req[3] = 0x00; // Length = 0
    req[4..8].copy_from_slice(&STUN_MAGIC_COOKIE);
    req[8..20].copy_from_slice(&tid);
    (tid, req)
}

/// Realiza una petición STUN Binding Request (RFC 5389) para descubrir IP:puerto público.
pub fn discover_stun_endpoint(local_port: u16) -> Result<String, NetplayError> {
    let socket = UdpSocket::bind(("0.0.0.0", local_port))
        .map_err(|e| NetplayError::Transport(format!("Bind UDP STUN falló: {e}")))?;
    socket
        .set_read_timeout(Some(Duration::from_secs(3)))
        .map_err(|e| NetplayError::Transport(format!("Timeout UDP falló: {e}")))?;

    let stun_servers = ["stun.cloudflare.com:3478", "stun.l.google.com:19302"];

    let mut buf = [0u8; 256];
    for server in stun_servers {
        use std::net::ToSocketAddrs;
        let Ok(addrs) = server.to_socket_addrs() else {
            continue;
        };
        // Priorizar y requerir IPv4 para coincidir con el socket IPv4 (0.0.0.0) de GGRS
        let Some(target) = addrs.into_iter().find(|a| a.is_ipv4()) else {
            continue;
        };
        let (tid, req) = build_stun_binding_request();
        if socket.send_to(&req, target).is_err() {
            continue;
        }
        for _ in 0..4 {
            match socket.recv_from(&mut buf) {
                Ok((len, _)) => {
                    if let Some(addr) = parse_stun_xor_mapped(&buf[..len], &tid) {
                        if addr.is_ipv4() {
                            return Ok(addr.to_string());
                        }
                    }
                }
                Err(_) => break,
            }
        }
    }

    Err(NetplayError::Transport(
        "No se recibió respuesta de servidores STUN".into(),
    ))
}

fn parse_stun_xor_mapped(pkt: &[u8], expected_tid: &[u8; 12]) -> Option<SocketAddr> {
    if pkt.len() < 20 || pkt[0] != 0x01 || pkt[1] != 0x01 {
        return None;
    }
    if pkt[4..8] != STUN_MAGIC_COOKIE || pkt[8..20] != *expected_tid {
        return None;
    }
    let msg_len = u16::from_be_bytes([pkt[2], pkt[3]]) as usize;
    let end = 20usize.checked_add(msg_len)?;
    if end > pkt.len() {
        return None;
    }
    let mut pos = 20;
    while pos + 4 <= end {
        let attr_type = u16::from_be_bytes([pkt[pos], pkt[pos + 1]]);
        let attr_len = u16::from_be_bytes([pkt[pos + 2], pkt[pos + 3]]) as usize;
        let val_start = pos + 4;
        let val_end = val_start.checked_add(attr_len)?;
        if val_end > end {
            break;
        }
        // 0x0020 = XOR-MAPPED-ADDRESS
        if attr_type == 0x0020 && attr_len >= 4 {
            let family = pkt[val_start + 1];
            let xport = u16::from_be_bytes([pkt[val_start + 2], pkt[val_start + 3]])
                ^ STUN_MAGIC_COOKIE_U16;
            if family == 0x01 && attr_len >= 8 {
                let ip = std::net::Ipv4Addr::new(
                    pkt[val_start + 4] ^ STUN_MAGIC_COOKIE[0],
                    pkt[val_start + 5] ^ STUN_MAGIC_COOKIE[1],
                    pkt[val_start + 6] ^ STUN_MAGIC_COOKIE[2],
                    pkt[val_start + 7] ^ STUN_MAGIC_COOKIE[3],
                );
                return Some(SocketAddr::new(std::net::IpAddr::V4(ip), xport));
            }
            // IPv6 (family == 0x02) se descarta deliberadamente: el backend GGRS opera sobre socket IPv4 0.0.0.0
            if family == 0x02 {
                return None;
            }
        }
        let padded = (attr_len + 3) & !3;
        pos = val_start.checked_add(padded)?;
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};

    fn make_stun_response(tid: &[u8; 12], attr: &[u8]) -> Vec<u8> {
        let mut pkt = Vec::with_capacity(20 + attr.len());
        pkt.extend_from_slice(&[0x01, 0x01]);
        pkt.extend_from_slice(&(attr.len() as u16).to_be_bytes());
        pkt.extend_from_slice(&STUN_MAGIC_COOKIE);
        pkt.extend_from_slice(tid);
        pkt.extend_from_slice(attr);
        pkt
    }

    #[test]
    fn build_stun_request_has_valid_header_and_distinct_tids() {
        let (tid1, req1) = build_stun_binding_request();
        let (tid2, req2) = build_stun_binding_request();
        assert_eq!(&req1[0..4], &[0x00, 0x01, 0x00, 0x00]);
        assert_eq!(&req1[4..8], &STUN_MAGIC_COOKIE);
        assert_eq!(&req1[8..20], &tid1);
        assert_ne!(tid1, tid2);
        assert_ne!(req1, req2);
    }

    #[test]
    fn parse_stun_parses_ipv4_xor_mapped_address() {
        let tid = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12];
        let port: u16 = 54321;
        let ip = Ipv4Addr::new(203, 0, 113, 42);
        let xport = (port ^ STUN_MAGIC_COOKIE_U16).to_be_bytes();
        let oct = ip.octets();
        let attr = [
            0x00, 0x20, 0x00, 0x08, // XOR-MAPPED-ADDRESS, len 8
            0x00, 0x01, xport[0], xport[1], // family IPv4
            oct[0] ^ STUN_MAGIC_COOKIE[0],
            oct[1] ^ STUN_MAGIC_COOKIE[1],
            oct[2] ^ STUN_MAGIC_COOKIE[2],
            oct[3] ^ STUN_MAGIC_COOKIE[3],
        ];
        let pkt = make_stun_response(&tid, &attr);
        assert_eq!(
            parse_stun_xor_mapped(&pkt, &tid),
            Some(SocketAddr::new(IpAddr::V4(ip), port))
        );
    }

    #[test]
    fn parse_stun_rejects_ipv6_for_ipv4_ggrs_compatibility() {
        let tid = [0xAA, 0xBB, 0xCC, 0xDD, 1, 2, 3, 4, 5, 6, 7, 8];
        let port: u16 = 40000;
        let ip = Ipv6Addr::new(0x2001, 0x0db8, 0x85a3, 0, 0, 0x8a2e, 0x0370, 0x7334);
        let xport = (port ^ STUN_MAGIC_COOKIE_U16).to_be_bytes();
        let mut key = [0u8; 16];
        key[..4].copy_from_slice(&STUN_MAGIC_COOKIE);
        key[4..16].copy_from_slice(&tid);
        let oct = ip.octets();
        let mut attr = vec![0x00, 0x20, 0x00, 0x14, 0x00, 0x02, xport[0], xport[1]];
        for i in 0..16 {
            attr.push(oct[i] ^ key[i]);
        }
        let pkt = make_stun_response(&tid, &attr);
        assert_eq!(
            parse_stun_xor_mapped(&pkt, &tid),
            None
        );
    }

    #[test]
    fn parse_stun_rejects_bad_cookie_mismatched_tid_and_truncated_len() {
        let tid = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12];
        let xport = (12345u16 ^ STUN_MAGIC_COOKIE_U16).to_be_bytes();
        let attr = [
            0x00, 0x20, 0x00, 0x08, 0x00, 0x01, xport[0], xport[1],
            1 ^ STUN_MAGIC_COOKIE[0],
            2 ^ STUN_MAGIC_COOKIE[1],
            3 ^ STUN_MAGIC_COOKIE[2],
            4 ^ STUN_MAGIC_COOKIE[3],
        ];
        let valid = make_stun_response(&tid, &attr);

        // Bad magic cookie
        let mut bad_cookie = valid.clone();
        bad_cookie[4] ^= 0xFF;
        assert_eq!(parse_stun_xor_mapped(&bad_cookie, &tid), None);

        // Mismatched TID
        let wrong_tid = [9u8; 12];
        assert_eq!(parse_stun_xor_mapped(&valid, &wrong_tid), None);

        // Truncated message length (header claims more bytes than packet length)
        let mut truncated = valid.clone();
        truncated[2] = 0x00;
        truncated[3] = 0x40;
        assert_eq!(parse_stun_xor_mapped(&truncated, &tid), None);
    }
}
