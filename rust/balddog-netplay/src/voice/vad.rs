#[derive(Debug, Clone, Copy)]
pub struct VadResult {
    pub active: bool,
    pub rms: f32,
}

/// Detector de actividad de voz (VAD) por energía RMS con hangover anti-corte.
pub struct VoiceActivityDetector {
    rms_threshold: f32,
    hangover_frames: u16,
    remaining_hangover: u16,
}

impl Default for VoiceActivityDetector {
    fn default() -> Self {
        Self::new(320.0, 12) // 12 frames = 240 ms de hangover @ 20 ms/frame
    }
}

impl VoiceActivityDetector {
    pub fn new(rms_threshold: f32, hangover_frames: u16) -> Self {
        Self {
            rms_threshold: rms_threshold.max(1.0),
            hangover_frames,
            remaining_hangover: 0,
        }
    }

    pub fn reset(&mut self) {
        self.remaining_hangover = 0;
    }

    pub fn process(&mut self, pcm: &[i16]) -> VadResult {
        if pcm.is_empty() {
            return VadResult {
                active: false,
                rms: 0.0,
            };
        }

        let sum_sq: f64 = pcm.iter().map(|&s| f64::from(s) * f64::from(s)).sum();
        let rms = (sum_sq / pcm.len() as f64).sqrt() as f32;

        if rms >= self.rms_threshold {
            self.remaining_hangover = self.hangover_frames;
            VadResult { active: true, rms }
        } else if self.remaining_hangover > 0 {
            self.remaining_hangover -= 1;
            VadResult { active: true, rms }
        } else {
            VadResult { active: false, rms }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn silence_stays_inactive_and_speech_triggers_hangover() {
        let mut vad = VoiceActivityDetector::new(300.0, 3);
        let silence = [0i16; 320];
        assert!(!vad.process(&silence).active);

        let loud = [1200i16; 320];
        assert!(vad.process(&loud).active);

        // 3 frames de hangover activos, luego vuelve a inactivo
        assert!(vad.process(&silence).active);
        assert!(vad.process(&silence).active);
        assert!(vad.process(&silence).active);
        assert!(!vad.process(&silence).active);
    }
}
