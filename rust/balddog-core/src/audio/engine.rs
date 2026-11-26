#[cfg(any(target_os = "android", test))]
mod stereo_ring {
    use ringbuf::traits::{Consumer, Producer, Split};
    use ringbuf::{HeapCons, HeapProd, HeapRb};

    pub(crate) struct StereoAudioProducer {
        producer: HeapProd<(i16, i16)>,
    }

    pub(crate) struct StereoAudioConsumer {
        consumer: HeapCons<(i16, i16)>,
    }

    pub(crate) fn stereo_audio_ring(
        capacity_frames: usize,
    ) -> (StereoAudioProducer, StereoAudioConsumer) {
        let rb = HeapRb::<(i16, i16)>::new(capacity_frames.max(1));
        let (producer, consumer) = rb.split();
        (
            StereoAudioProducer { producer },
            StereoAudioConsumer { consumer },
        )
    }

    impl StereoAudioProducer {
        /// Encola únicamente frames estéreo completos `(L, R)` para impedir que un overrun impar
        /// o un slice impar desfase los canales L/R permanentemente.
        pub(crate) fn push_interleaved(&mut self, samples: &[i16]) -> usize {
            self.producer
                .push_iter(&mut samples.chunks_exact(2).map(|pair| (pair[0], pair[1])))
        }
    }

    impl StereoAudioConsumer {
        #[inline]
        pub(crate) fn pop_frame(&mut self) -> (i16, i16) {
            self.consumer.try_pop().unwrap_or((0, 0))
        }
    }
}

#[cfg(target_os = "android")]
mod android_audio {
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::Arc;

    use oboe::{
        AudioOutputCallback, AudioOutputStreamSafe, AudioStream, AudioStreamBuilder,
        DataCallbackResult, PerformanceMode, SharingMode, Stereo,
    };

    use super::stereo_ring::{stereo_audio_ring, StereoAudioConsumer, StereoAudioProducer};
    use crate::{record_log, CoreError};

    const GAIN_FULL_Q15: i32 = 32768;
    const GAIN_DUCKED_Q15: i32 = 13107; // ~40%
    const GAIN_RAMP_STEP_Q15: i32 = 16;
    const AUDIO_RING_FRAMES: usize = 8192;

    static GLOBAL_DUCKING_ENABLED: AtomicBool = AtomicBool::new(false);

    pub fn set_global_ducking(enabled: bool) {
        GLOBAL_DUCKING_ENABLED.store(enabled, Ordering::Release);
    }

    struct ArcadeAudioCallback {
        consumer: StereoAudioConsumer,
        paused: Arc<AtomicBool>,
        current_gain_q15: i32,
    }

    impl AudioOutputCallback for ArcadeAudioCallback {
        type FrameType = (i16, Stereo);

        fn on_audio_ready(
            &mut self,
            _stream: &mut dyn AudioOutputStreamSafe,
            frames: &mut [(i16, i16)],
        ) -> DataCallbackResult {
            if self.paused.load(Ordering::Relaxed) {
                frames.fill((0, 0));
                return DataCallbackResult::Continue;
            }

            let target_gain = if GLOBAL_DUCKING_ENABLED.load(Ordering::Relaxed) {
                GAIN_DUCKED_Q15
            } else {
                GAIN_FULL_Q15
            };

            for frame in frames.iter_mut() {
                if self.current_gain_q15 < target_gain {
                    self.current_gain_q15 =
                        (self.current_gain_q15 + GAIN_RAMP_STEP_Q15).min(target_gain);
                } else if self.current_gain_q15 > target_gain {
                    self.current_gain_q15 =
                        (self.current_gain_q15 - GAIN_RAMP_STEP_Q15).max(target_gain);
                }

                let (left, right) = self.consumer.pop_frame();
                let scaled_l = ((i32::from(left) * self.current_gain_q15) >> 15) as i16;
                let scaled_r = ((i32::from(right) * self.current_gain_q15) >> 15) as i16;
                *frame = (scaled_l, scaled_r);
            }
            DataCallbackResult::Continue
        }
    }

    pub struct AudioEngine {
        producer: StereoAudioProducer,
        paused: Arc<AtomicBool>,
        _stream: oboe::AudioStreamAsync<oboe::Output, ArcadeAudioCallback>,
    }

    impl AudioEngine {
        pub fn start(sample_rate: i32) -> Result<Self, CoreError> {
            let safe_rate = if sample_rate > 8000 {
                sample_rate
            } else {
                44100
            };
            let (producer, consumer) = stereo_audio_ring(AUDIO_RING_FRAMES);
            let paused = Arc::new(AtomicBool::new(false));

            let callback = ArcadeAudioCallback {
                consumer,
                paused: Arc::clone(&paused),
                current_gain_q15: GAIN_FULL_Q15,
            };

            let mut stream = AudioStreamBuilder::default()
                .set_performance_mode(PerformanceMode::LowLatency)
                .set_sharing_mode(SharingMode::Shared)
                .set_format::<i16>()
                .set_channel_count::<Stereo>()
                .set_sample_rate(safe_rate)
                .set_callback(callback)
                .open_stream()
                .map_err(|e| {
                    CoreError::Init(format!("Error abriendo stream Oboe/AAudio: {e:?}"))
                })?;

            stream.start().map_err(|e| {
                CoreError::Init(format!("Error iniciando stream Oboe/AAudio: {e:?}"))
            })?;

            record_log("INFO", &format!("Oboe/AAudio iniciado a {safe_rate} Hz"));

            Ok(Self {
                producer,
                paused,
                _stream: stream,
            })
        }

        pub fn push_samples(&mut self, samples: &[i16]) {
            if self.paused.load(Ordering::Relaxed) {
                return;
            }
            let _ = self.producer.push_interleaved(samples);
        }

        pub fn set_paused(&self, paused: bool) {
            self.paused.store(paused, Ordering::Release);
        }
    }
}

#[cfg(not(target_os = "android"))]
mod android_audio {
    use crate::CoreError;

    pub struct AudioEngine;

    impl AudioEngine {
        pub fn start(_sample_rate: i32) -> Result<Self, CoreError> {
            Ok(Self)
        }

        pub fn push_samples(&mut self, _samples: &[i16]) {}

        pub fn set_paused(&self, _paused: bool) {}
    }

    pub fn set_global_ducking(_enabled: bool) {}
}

pub use android_audio::{set_global_ducking, AudioEngine};

#[cfg(test)]
mod tests {
    use super::stereo_ring::stereo_audio_ring;

    #[test]
    fn interleaved_roundtrip_preserves_lr_channels() {
        let (mut prod, mut cons) = stereo_audio_ring(8);
        let pushed = prod.push_interleaved(&[10, -10, 20, -20, 30, -30]);
        assert_eq!(pushed, 3);
        assert_eq!(cons.pop_frame(), (10, -10));
        assert_eq!(cons.pop_frame(), (20, -20));
        assert_eq!(cons.pop_frame(), (30, -30));
        assert_eq!(cons.pop_frame(), (0, 0));
    }

    #[test]
    fn overrun_and_partial_drain_never_swaps_lr_channels() {
        let (mut prod, mut cons) = stereo_audio_ring(3);

        // Push 5 stereo frames into a 3-frame ring -> 3 accepted, 2 dropped cleanly as whole frames.
        let pushed = prod.push_interleaved(&[1, -1, 2, -2, 3, -3, 4, -4, 5, -5]);
        assert_eq!(pushed, 3);

        // Drain 1 frame and push more frames; L remains positive and R remains negative.
        assert_eq!(cons.pop_frame(), (1, -1));
        assert_eq!(prod.push_interleaved(&[6, -6, 7, -7]), 1);
        assert_eq!(cons.pop_frame(), (2, -2));
        assert_eq!(cons.pop_frame(), (3, -3));
        assert_eq!(cons.pop_frame(), (6, -6));
        assert_eq!(cons.pop_frame(), (0, 0));
    }

    #[test]
    fn odd_length_slice_drops_trailing_sample_without_channel_inversion() {
        let (mut prod, mut cons) = stereo_audio_ring(8);
        assert_eq!(prod.push_interleaved(&[100, -100, 999]), 1);
        assert_eq!(prod.push_interleaved(&[200, -200]), 1);
        assert_eq!(cons.pop_frame(), (100, -100));
        assert_eq!(cons.pop_frame(), (200, -200));
        assert_eq!(cons.pop_frame(), (0, 0));
    }
}
