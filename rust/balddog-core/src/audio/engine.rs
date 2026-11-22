#[cfg(target_os = "android")]
mod android_audio {
    use std::sync::atomic::{AtomicBool, AtomicI32, Ordering};
    use std::sync::Arc;

    use oboe::{
        AudioOutputCallback, AudioOutputStreamSafe, AudioStream, AudioStreamBuilder,
        DataCallbackResult, PerformanceMode, SharingMode, Stereo,
    };
    use ringbuf::traits::{Consumer, Producer, Split};
    use ringbuf::{HeapCons, HeapProd, HeapRb};

    use crate::{record_log, CoreError};

    const GAIN_FULL_Q15: i32 = 32768;
    const GAIN_DUCKED_Q15: i32 = 13107; // ~40%
    const GAIN_RAMP_STEP_Q15: i32 = 16;

    static GLOBAL_DUCKING_ENABLED: AtomicBool = AtomicBool::new(false);

    pub fn set_global_ducking(enabled: bool) {
        GLOBAL_DUCKING_ENABLED.store(enabled, Ordering::Release);
    }

    struct ArcadeAudioCallback {
        consumer: HeapCons<i16>,
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

                let left = self.consumer.try_pop().unwrap_or(0);
                let right = self.consumer.try_pop().unwrap_or(0);
                let scaled_l = ((i32::from(left) * self.current_gain_q15) >> 15) as i16;
                let scaled_r = ((i32::from(right) * self.current_gain_q15) >> 15) as i16;
                *frame = (scaled_l, scaled_r);
            }
            DataCallbackResult::Continue
        }
    }

    pub struct AudioEngine {
        producer: HeapProd<i16>,
        paused: Arc<AtomicBool>,
        _stream: oboe::AudioStreamAsync<oboe::Output, ArcadeAudioCallback>,
    }

    impl AudioEngine {
        pub fn start(sample_rate: i32) -> Result<Self, CoreError> {
            let safe_rate = if sample_rate > 8000 { sample_rate } else { 44100 };
            let rb = HeapRb::<i16>::new(16384);
            let (producer, consumer) = rb.split();
            let paused = Arc::new(AtomicBool::new(false));
            let _ = AtomicI32::new(GAIN_FULL_Q15);

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
                .map_err(|e| CoreError::Init(format!("Error abriendo stream Oboe/AAudio: {e:?}")))?;

            stream
                .start()
                .map_err(|e| CoreError::Init(format!("Error iniciando stream Oboe/AAudio: {e:?}")))?;

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
            let _ = self.producer.push_slice(samples);
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
