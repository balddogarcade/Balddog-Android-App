//! Grabador y reproductor de replays deterministas en formato estándar
//! Libretro `.bsv` v1 (`BSV1`) comprimido con `gzip`.

use std::fs::File;
use std::io::{Read, Write};
use std::path::Path;

use flate2::read::GzDecoder;
use flate2::write::GzEncoder;
use flate2::Compression;
use thiserror::Error;

const BSV_MAGIC: [u8; 4] = *b"BSV1";
const BSV_HEADER_LEN: usize = 16;
const BYTES_PER_FRAME: usize = 8;
const MAX_DECOMPRESSED_BSV_BYTES: usize = 16 * 1024 * 1024; // 16 MiB cap against gzip bombs // [u16; 4] little-endian

/// Calcula el CRC32 IEEE-802.3 leyendo en streaming desde cualquier `Read` (chunks de 64 KiB).
pub fn compute_crc32_reader<R: Read>(mut reader: R) -> Result<u32, std::io::Error> {
    let mut crc = flate2::Crc::new();
    let mut buf = [0u8; 64 * 1024];
    loop {
        let n = reader.read(&mut buf)?;
        if n == 0 {
            break;
        }
        crc.update(&buf[..n]);
    }
    Ok(crc.sum())
}

/// Calcula el CRC32 IEEE-802.3 de un archivo en streaming (chunks de 64 KiB).
pub fn compute_file_crc32(path: &Path) -> Result<u32, std::io::Error> {
    let file = File::open(path)?;
    compute_crc32_reader(file)
}

#[derive(Debug, Error)]
pub enum ReplayError {
    #[error("Error de E/S en replay: {0}")]
    Io(#[from] std::io::Error),
    #[error("Cabecera BSV1 inválida o archivo corrupto")]
    InvalidHeader,
    #[error("CRC32 de ROM no coincide: esperado 0x{expected:08x}, archivo 0x{actual:08x}")]
    RomCrcMismatch { expected: u32, actual: u32 },
    #[error("Datos de frames truncados en archivo .bsv")]
    TruncatedFrames,
}

/// Graba los inputs `[u16; 4]` frame a frame para exportarlos en `.bsv.gz`.
#[derive(Debug, Clone)]
pub struct ReplayRecorder {
    rom_crc32: u32,
    frames: Vec<[u16; 4]>,
}

impl ReplayRecorder {
    pub fn new(rom_crc32: u32) -> Self {
        Self {
            rom_crc32,
            frames: Vec::with_capacity(60 * 60 * 5),
        }
    }

    pub fn push_frame(&mut self, inputs: [u16; 4]) {
        self.frames.push(inputs);
    }

    pub fn frame_count(&self) -> u64 {
        self.frames.len() as u64
    }

    pub fn rom_crc32(&self) -> u32 {
        self.rom_crc32
    }

    pub fn to_bsv_bytes(&self) -> Vec<u8> {
        let mut raw = Vec::with_capacity(BSV_HEADER_LEN + self.frames.len() * BYTES_PER_FRAME);
        raw.extend_from_slice(&BSV_MAGIC);
        raw.extend_from_slice(&self.rom_crc32.to_le_bytes());
        raw.extend_from_slice(&0u32.to_le_bytes()); // state_size = 0 (arranque limpio)
        raw.extend_from_slice(&0u32.to_le_bytes()); // reserved / flags = 0
        for frame in &self.frames {
            for port in frame {
                raw.extend_from_slice(&port.to_le_bytes());
            }
        }
        raw
    }

    pub fn to_gzip_bytes(&self) -> Result<Vec<u8>, ReplayError> {
        let raw = self.to_bsv_bytes();
        let mut encoder = GzEncoder::new(Vec::new(), Compression::default());
        encoder.write_all(&raw)?;
        Ok(encoder.finish()?)
    }

    pub fn write_gzip_file(&self, path: &Path) -> Result<usize, ReplayError> {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let gz = self.to_gzip_bytes()?;
        let mut file = File::create(path)?;
        file.write_all(&gz)?;
        Ok(gz.len())
    }

    pub fn to_zstd_bytes(&self) -> Result<Vec<u8>, ReplayError> {
        let raw = self.to_bsv_bytes();
        let mut encoder = zstd::stream::write::Encoder::new(Vec::new(), 3)?;
        encoder.write_all(&raw)?;
        Ok(encoder.finish()?)
    }

    pub fn write_zstd_file(&self, path: &Path) -> Result<usize, ReplayError> {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let zst = self.to_zstd_bytes()?;
        let mut file = File::create(path)?;
        file.write_all(&zst)?;
        Ok(zst.len())
    }
}

/// Reproduce un stream `.bsv` v1 (`BSV1`) descomprimido desde `.bsv.gz`.
#[derive(Debug, Clone)]
pub struct ReplayPlayer {
    rom_crc32: u32,
    initial_state: Vec<u8>,
    frames: Vec<[u16; 4]>,
    cursor: usize,
}

impl ReplayPlayer {
    pub fn from_bsv_bytes(
        raw: &[u8],
        expected_rom_crc32: Option<u32>,
    ) -> Result<Self, ReplayError> {
        if raw.len() < BSV_HEADER_LEN || raw[0..4] != BSV_MAGIC {
            return Err(ReplayError::InvalidHeader);
        }
        let rom_crc32 = u32::from_le_bytes([raw[4], raw[5], raw[6], raw[7]]);
        if let Some(expected) = expected_rom_crc32 {
            if expected != 0 && rom_crc32 != 0 && rom_crc32 != expected {
                return Err(ReplayError::RomCrcMismatch {
                    expected,
                    actual: rom_crc32,
                });
            }
        }
        let state_size = u32::from_le_bytes([raw[8], raw[9], raw[10], raw[11]]) as usize;
        let payload_start = BSV_HEADER_LEN
            .checked_add(state_size)
            .ok_or(ReplayError::InvalidHeader)?;
        if raw.len() < payload_start {
            return Err(ReplayError::TruncatedFrames);
        }
        let initial_state = raw[BSV_HEADER_LEN..payload_start].to_vec();
        let frame_bytes = &raw[payload_start..];
        if frame_bytes.len() % BYTES_PER_FRAME != 0 {
            return Err(ReplayError::TruncatedFrames);
        }
        let mut frames = Vec::with_capacity(frame_bytes.len() / BYTES_PER_FRAME);
        for chunk in frame_bytes.chunks_exact(BYTES_PER_FRAME) {
            frames.push([
                u16::from_le_bytes([chunk[0], chunk[1]]),
                u16::from_le_bytes([chunk[2], chunk[3]]),
                u16::from_le_bytes([chunk[4], chunk[5]]),
                u16::from_le_bytes([chunk[6], chunk[7]]),
            ]);
        }
        Ok(Self {
            rom_crc32,
            initial_state,
            frames,
            cursor: 0,
        })
    }

    pub fn from_compressed_bytes(
        data: &[u8],
        expected_rom_crc32: Option<u32>,
    ) -> Result<Self, ReplayError> {
        let raw = if data.starts_with(&[0x28, 0xB5, 0x2F, 0xFD]) {
            let mut decoder = zstd::stream::read::Decoder::new(data)?
                .take((MAX_DECOMPRESSED_BSV_BYTES + 1) as u64);
            let mut buf = Vec::new();
            decoder.read_to_end(&mut buf)?;
            buf
        } else if data.starts_with(&[0x1F, 0x8B]) {
            let mut decoder = GzDecoder::new(data).take((MAX_DECOMPRESSED_BSV_BYTES + 1) as u64);
            let mut buf = Vec::new();
            decoder.read_to_end(&mut buf)?;
            buf
        } else if data.starts_with(&BSV_MAGIC) {
            if data.len() > MAX_DECOMPRESSED_BSV_BYTES {
                return Err(ReplayError::InvalidHeader);
            }
            return Self::from_bsv_bytes(data, expected_rom_crc32);
        } else {
            return Err(ReplayError::InvalidHeader);
        };

        if raw.len() > MAX_DECOMPRESSED_BSV_BYTES {
            return Err(ReplayError::InvalidHeader);
        }
        Self::from_bsv_bytes(&raw, expected_rom_crc32)
    }

    pub fn from_gzip_bytes(
        gz: &[u8],
        expected_rom_crc32: Option<u32>,
    ) -> Result<Self, ReplayError> {
        Self::from_compressed_bytes(gz, expected_rom_crc32)
    }

    pub fn from_gzip_file_strict(
        path: &Path,
        expected_rom_crc32: u32,
    ) -> Result<Self, ReplayError> {
        let player = Self::from_gzip_file(path, Some(expected_rom_crc32))?;
        if !player.initial_state.is_empty() {
            return Err(ReplayError::InvalidHeader);
        }
        if expected_rom_crc32 != 0 && player.rom_crc32 != expected_rom_crc32 {
            return Err(ReplayError::RomCrcMismatch {
                expected: expected_rom_crc32,
                actual: player.rom_crc32,
            });
        }
        Ok(player)
    }

    pub fn from_gzip_file(
        path: &Path,
        expected_rom_crc32: Option<u32>,
    ) -> Result<Self, ReplayError> {
        let gz = std::fs::read(path)?;
        Self::from_gzip_bytes(&gz, expected_rom_crc32)
    }

    pub fn next_frame(&mut self) -> Option<[u16; 4]> {
        if self.cursor >= self.frames.len() {
            return None;
        }
        let f = self.frames[self.cursor];
        self.cursor += 1;
        Some(f)
    }

    pub fn restart(&mut self) {
        self.cursor = 0;
    }

    pub fn seek_to_frame(&mut self, frame: u64) {
        self.cursor = (frame as usize).min(self.frames.len());
    }

    pub fn current_frame(&self) -> u64 {
        self.cursor as u64
    }

    pub fn total_frames(&self) -> u64 {
        self.frames.len() as u64
    }

    pub fn is_finished(&self) -> bool {
        self.cursor >= self.frames.len()
    }

    pub fn rom_crc32(&self) -> u32 {
        self.rom_crc32
    }

    pub fn initial_state(&self) -> &[u8] {
        &self.initial_state
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_bsv_gzip_compression_and_playback() {
        let mut rec = ReplayRecorder::new(0xDEAD_BEEF);
        for i in 0..3600u16 {
            let mask = if i % 60 < 10 { 0x0081 } else { 0x0000 };
            rec.push_frame([mask, 0, 0, 0]);
        }
        let gz = rec.to_gzip_bytes().expect("gzip encode");
        assert!(
            gz.len() < 1500,
            "gzip debe comprimir fuertemente (len={})",
            gz.len()
        );

        let mut player =
            ReplayPlayer::from_gzip_bytes(&gz, Some(0xDEAD_BEEF)).expect("gzip decode");
        assert_eq!(player.total_frames(), 3600);
        assert_eq!(player.rom_crc32(), 0xDEAD_BEEF);
        assert_eq!(player.next_frame(), Some([0x0081, 0, 0, 0]));
        assert_eq!(player.current_frame(), 1);
        player.restart();
        assert_eq!(player.current_frame(), 0);
        while player.next_frame().is_some() {}
        assert!(player.is_finished());
        assert_eq!(player.next_frame(), None);
    }

    #[test]
    fn rejects_crc_mismatch_and_corrupt_header() {
        let mut rec = ReplayRecorder::new(0x1122_3344);
        rec.push_frame([1, 2, 3, 4]);
        let gz = rec.to_gzip_bytes().unwrap();
        let err = ReplayPlayer::from_gzip_bytes(&gz, Some(0x9999_9999)).unwrap_err();
        assert!(matches!(err, ReplayError::RomCrcMismatch { .. }));

        let bad = ReplayPlayer::from_bsv_bytes(b"NOPE1234", None).unwrap_err();
        assert!(matches!(bad, ReplayError::InvalidHeader));
    }

    #[test]
    fn compute_file_crc32_matches_known_vector() {
        let crc = compute_crc32_reader(std::io::Cursor::new(b"123456789")).unwrap();
        assert_eq!(crc, 0xCBF4_3926);

        let manifest_path = Path::new(env!("CARGO_MANIFEST_DIR")).join("Cargo.toml");
        let file_crc_1 = compute_file_crc32(&manifest_path).unwrap();
        let file_crc_2 = compute_file_crc32(&manifest_path).unwrap();
        assert_ne!(file_crc_1, 0);
        assert_eq!(file_crc_1, file_crc_2);
    }

    #[test]
    fn roundtrip_bsv_zstd_compression_and_dual_playback() {
        let mut rec = ReplayRecorder::new(0xCAFE_BABE);
        for i in 0..3600u16 {
            let mask = if i % 60 < 10 { 0x0081 } else { 0x0000 };
            rec.push_frame([mask, 0, 0, 0]);
        }
        let zst = rec.to_zstd_bytes().expect("zstd encode");
        assert!(zst.starts_with(&[0x28, 0xB5, 0x2F, 0xFD]));
        assert!(
            zst.len() < 1500,
            "zstd debe comprimir fuertemente (len={})",
            zst.len()
        );

        let mut player =
            ReplayPlayer::from_compressed_bytes(&zst, Some(0xCAFE_BABE)).expect("zstd decode");
        assert_eq!(player.total_frames(), 3600);
        assert_eq!(player.next_frame(), Some([0x0081, 0, 0, 0]));

        // También debe aceptar gzip y BSV1 crudo
        let gz = rec.to_gzip_bytes().expect("gzip encode");
        let player_gz =
            ReplayPlayer::from_compressed_bytes(&gz, Some(0xCAFE_BABE)).expect("gzip decode");
        assert_eq!(player_gz.total_frames(), 3600);

        let raw = rec.to_bsv_bytes();
        let player_raw =
            ReplayPlayer::from_compressed_bytes(&raw, Some(0xCAFE_BABE)).expect("raw decode");
        assert_eq!(player_raw.total_frames(), 3600);
    }

    #[test]
    fn from_bsv_bytes_parses_initial_state_and_frames() {
        let state = b"STATE";
        let mut raw = Vec::new();
        raw.extend_from_slice(&BSV_MAGIC);
        raw.extend_from_slice(&0xCAFE_BABEu32.to_le_bytes());
        raw.extend_from_slice(&(state.len() as u32).to_le_bytes());
        raw.extend_from_slice(&0u32.to_le_bytes());
        raw.extend_from_slice(state);
        for port in [0x0001u16, 0x0002, 0x0003, 0x0004] {
            raw.extend_from_slice(&port.to_le_bytes());
        }
        for port in [0x0010u16, 0x0020, 0x0030, 0x0040] {
            raw.extend_from_slice(&port.to_le_bytes());
        }

        let mut player = ReplayPlayer::from_bsv_bytes(&raw, Some(0xCAFE_BABE)).unwrap();
        assert_eq!(player.rom_crc32(), 0xCAFE_BABE);
        assert_eq!(player.initial_state(), state);
        assert_eq!(player.total_frames(), 2);
        assert_eq!(player.next_frame(), Some([0x0001, 0x0002, 0x0003, 0x0004]));
        assert_eq!(player.next_frame(), Some([0x0010, 0x0020, 0x0030, 0x0040]));
        assert_eq!(player.next_frame(), None);
    }

    #[test]
    fn legacy_zero_rom_crc_is_accepted_when_expected_crc_provided() {
        let mut rec = ReplayRecorder::new(0);
        rec.push_frame([0x0008, 0, 0, 0]);
        let gz = rec.to_gzip_bytes().unwrap();

        let mut player = ReplayPlayer::from_gzip_bytes(&gz, Some(0x1234_5678)).unwrap();
        assert_eq!(player.rom_crc32(), 0);
        assert_eq!(player.next_frame(), Some([0x0008, 0, 0, 0]));
    }
}
