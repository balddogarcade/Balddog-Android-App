use std::collections::VecDeque;

use super::protocol::seq_diff;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum JitterPop {
    Frame(Vec<u8>),
    Lost,
    Buffering,
}

#[derive(Debug, Clone)]
struct BufferedPacket {
    seq: u16,
    payload: Vec<u8>,
}

/// Jitter buffer compacto para frames Opus de 20 ms con reordenamiento y PLC.
pub struct JitterBuffer {
    target_frames: usize,
    max_frames: usize,
    packets: VecDeque<BufferedPacket>,
    next_seq: Option<u16>,
    primed: bool,
    consecutive_empty: usize,
}

impl Default for JitterBuffer {
    fn default() -> Self {
        Self::new(2, 12) // 2 frames = 40 ms de latencia objetivo
    }
}

impl JitterBuffer {
    pub fn new(target_frames: usize, max_frames: usize) -> Self {
        Self {
            target_frames: target_frames.max(1),
            max_frames: max_frames.max(4),
            packets: VecDeque::with_capacity(max_frames.max(4)),
            next_seq: None,
            primed: false,
            consecutive_empty: 0,
        }
    }

    pub fn reset(&mut self) {
        self.packets.clear();
        self.next_seq = None;
        self.primed = false;
        self.consecutive_empty = 0;
    }

    pub fn push(&mut self, seq: u16, payload: Vec<u8>) {
        if payload.is_empty() {
            return;
        }

        // Si ya estamos reproduciendo, descartar paquetes tardíos o saltos extremos
        if let Some(expected) = self.next_seq {
            let diff = seq_diff(seq, expected);
            if self.primed {
                if diff < 0 {
                    return;
                }
            } else if diff < -4 {
                return;
            }
            if diff > 100 {
                // Salto tras pausa larga o reinicio de secuencia en el par
                self.reset();
            }
        }

        // Insertar ordenado y evitar duplicados
        let mut insert_idx = self.packets.len();
        for (idx, pkt) in self.packets.iter().enumerate() {
            let d = seq_diff(seq, pkt.seq);
            if d == 0 {
                return; // Duplicado
            }
            if d < 0 {
                insert_idx = idx;
                break;
            }
        }
        self.packets.insert(insert_idx, BufferedPacket { seq, payload });

        while self.packets.len() > self.max_frames {
            if let Some(dropped) = self.packets.pop_front() {
                let next = dropped.seq.wrapping_add(1);
                if self.next_seq.map_or(true, |cur| seq_diff(next, cur) > 0) {
                    self.next_seq = Some(next);
                }
            }
        }

        if self.next_seq.is_none() {
            self.next_seq = self.packets.front().map(|p| p.seq);
        }
    }

    pub fn pop(&mut self) -> JitterPop {
        if !self.primed {
            if self.packets.len() >= self.target_frames {
                self.primed = true;
                self.next_seq = self.packets.front().map(|p| p.seq);
            } else {
                return JitterPop::Buffering;
            }
        }

        let Some(expected) = self.next_seq else {
            self.primed = false;
            return JitterPop::Buffering;
        };

        while let Some(front) = self.packets.front() {
            let diff = seq_diff(front.seq, expected);
            if diff < 0 {
                // Paquete obsoleto ya superado por el cursor de reproducción
                self.packets.pop_front();
                continue;
            }
            if diff == 0 {
                let pkt = self.packets.pop_front().unwrap();
                self.next_seq = Some(pkt.seq.wrapping_add(1));
                self.consecutive_empty = 0;
                return JitterPop::Frame(pkt.payload);
            }
            // Hay paquetes futuros en cola pero falta el `expected`: pérdida puntual
            self.next_seq = Some(expected.wrapping_add(1));
            self.consecutive_empty = 0;
            return JitterPop::Lost;
        }

        // Cola vacía tras estar primed: hasta 2 frames de PLC antes de volver a buffering
        self.consecutive_empty += 1;
        if self.consecutive_empty <= 2 {
            self.next_seq = Some(expected.wrapping_add(1));
            JitterPop::Lost
        } else {
            self.reset();
            JitterPop::Buffering
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reorders_and_signals_plc_on_gap() {
        let mut jb = JitterBuffer::new(2, 8);
        jb.push(12, vec![12]);
        assert_eq!(jb.pop(), JitterPop::Buffering);

        jb.push(10, vec![10]);
        // Ya hay 2 paquetes (10 y 12), está primed
        assert_eq!(jb.pop(), JitterPop::Frame(vec![10]));
        assert_eq!(jb.pop(), JitterPop::Lost); // Falta seq 11
        assert_eq!(jb.pop(), JitterPop::Frame(vec![12]));
    }

    #[test]
    fn drops_late_packets_without_rewinding_next_seq_after_primed() {
        let mut jb = JitterBuffer::new(2, 8);
        jb.push(10, vec![10]);
        jb.push(11, vec![11]);
        assert_eq!(jb.pop(), JitterPop::Frame(vec![10]));
        assert_eq!(jb.pop(), JitterPop::Frame(vec![11]));

        // Cursor is now at 12. Late packet seq=9 or seq=10 must be dropped without rewinding.
        jb.push(10, vec![10]);
        jb.push(12, vec![12]);
        assert_eq!(jb.pop(), JitterPop::Frame(vec![12]));
    }

    #[test]
    fn handles_u16_seq_wraparound_from_65534_to_2() {
        let mut jb = JitterBuffer::new(2, 8);
        jb.push(65534, vec![1]);
        jb.push(65535, vec![2]);
        jb.push(0, vec![3]);
        jb.push(2, vec![5]);

        assert_eq!(jb.pop(), JitterPop::Frame(vec![1]));
        assert_eq!(jb.pop(), JitterPop::Frame(vec![2]));
        assert_eq!(jb.pop(), JitterPop::Frame(vec![3]));
        assert_eq!(jb.pop(), JitterPop::Lost); // seq 1 missing
        assert_eq!(jb.pop(), JitterPop::Frame(vec![5]));
    }

    #[test]
    fn resets_on_large_forward_jump() {
        let mut jb = JitterBuffer::new(2, 8);
        jb.push(10, vec![10]);
        jb.push(11, vec![11]);
        assert_eq!(jb.pop(), JitterPop::Frame(vec![10]));

        jb.push(500, vec![20]);
        assert_eq!(jb.pop(), JitterPop::Buffering);
        jb.push(501, vec![21]);
        assert_eq!(jb.pop(), JitterPop::Frame(vec![20]));
        assert_eq!(jb.pop(), JitterPop::Frame(vec![21]));
    }
}
