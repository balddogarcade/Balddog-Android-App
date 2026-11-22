use crate::NetplayError;

pub const VOICE_MAGIC: [u8; 4] = *b"BDVX";
pub const VOICE_HEADER_LEN: usize = 20;
pub const VOICE_SAMPLE_RATE: u32 = 16_000;
pub const VOICE_FRAME_SAMPLES: usize = 320; // 20 ms @ 16 kHz
pub const MAX_VOICE_PAYLOAD_BYTES: usize = 512;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum VoicePacketType {
    Audio = 1,
    Keepalive = 2,
}

impl VoicePacketType {
    pub fn from_byte(b: u8) -> Option<Self> {
        match b {
            1 => Some(Self::Audio),
            2 => Some(Self::Keepalive),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct VoicePacketHeader {
    pub session_token: u64,
    pub msg_type: VoicePacketType,
    pub player_handle: u8,
    pub seq: u16,
    pub timestamp_ms: u32,
}

/// Deriva un token de sesión determinístico de 64 bits (FNV-1a) a partir del ID de sala.
pub fn derive_session_token(room_id: &str) -> u64 {
    let mut hash: u64 = 0xcbf29ce484222325;
    for byte in room_id.as_bytes() {
        hash ^= u64::from(*byte);
        hash = hash.wrapping_mul(0x100000001b3);
    }
    hash
}

/// Diferencia modular con signo entre secuencias u16 (`a - b`).
#[inline]
pub fn seq_diff(a: u16, b: u16) -> i32 {
    i32::from(a.wrapping_sub(b) as i16)
}

/// Serializa cabecera + payload en `out` (reutiliza el buffer).
pub fn encode_packet(
    header: &VoicePacketHeader,
    payload: &[u8],
    out: &mut Vec<u8>,
) -> Result<(), NetplayError> {
    if payload.len() > MAX_VOICE_PAYLOAD_BYTES {
        return Err(NetplayError::Transport(format!(
            "Payload de voz excede el máximo permitido ({} > {})",
            payload.len(),
            MAX_VOICE_PAYLOAD_BYTES
        )));
    }
    out.clear();
    out.reserve(VOICE_HEADER_LEN + payload.len());
    out.extend_from_slice(&VOICE_MAGIC);
    out.extend_from_slice(&header.session_token.to_be_bytes());
    out.push(header.msg_type as u8);
    out.push(header.player_handle);
    out.extend_from_slice(&header.seq.to_be_bytes());
    out.extend_from_slice(&header.timestamp_ms.to_be_bytes());
    out.extend_from_slice(payload);
    Ok(())
}

/// Decodifica y valida cabecera básica de un datagrama UDP de voz.
pub fn decode_packet(data: &[u8]) -> Option<(VoicePacketHeader, &[u8])> {
    if data.len() < VOICE_HEADER_LEN || data[0..4] != VOICE_MAGIC {
        return None;
    }
    let session_token = u64::from_be_bytes(data[4..12].try_into().ok()?);
    let msg_type = VoicePacketType::from_byte(data[12])?;
    let player_handle = data[13];
    let seq = u16::from_be_bytes([data[14], data[15]]);
    let timestamp_ms = u32::from_be_bytes(data[16..20].try_into().ok()?);
    let payload = &data[VOICE_HEADER_LEN..];
    if payload.len() > MAX_VOICE_PAYLOAD_BYTES {
        return None;
    }
    Some((
        VoicePacketHeader {
            session_token,
            msg_type,
            player_handle,
            seq,
            timestamp_ms,
        },
        payload,
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_audio_packet() {
        let token = derive_session_token("room-abc-123");
        let hdr = VoicePacketHeader {
            session_token: token,
            msg_type: VoicePacketType::Audio,
            player_handle: 2,
            seq: 65535,
            timestamp_ms: 42_000,
        };
        let payload = [0x11u8, 0x22, 0x33, 0x44];
        let mut buf = Vec::new();
        encode_packet(&hdr, &payload, &mut buf).unwrap();
        assert_eq!(buf.len(), VOICE_HEADER_LEN + payload.len());

        let (decoded_hdr, decoded_payload) = decode_packet(&buf).unwrap();
        assert_eq!(decoded_hdr, hdr);
        assert_eq!(decoded_payload, &payload);
    }

    #[test]
    fn reject_invalid_magic_and_oversize() {
        let mut buf = vec![0u8; VOICE_HEADER_LEN];
        assert!(decode_packet(&buf).is_none());

        let hdr = VoicePacketHeader {
            session_token: 1,
            msg_type: VoicePacketType::Keepalive,
            player_handle: 0,
            seq: 0,
            timestamp_ms: 0,
        };
        let too_big = vec![0u8; MAX_VOICE_PAYLOAD_BYTES + 1];
        assert!(encode_packet(&hdr, &too_big, &mut buf).is_err());
    }

    #[test]
    fn seq_wraparound_diff() {
        assert_eq!(seq_diff(0, 65535), 1);
        assert_eq!(seq_diff(65535, 0), -1);
        assert_eq!(seq_diff(10, 5), 5);
    }
}
