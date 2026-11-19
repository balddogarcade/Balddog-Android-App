use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

use oboe::{
    AudioOutputCallback, AudioOutputStreamSafe, AudioStream, AudioStreamBuilder,
    DataCallbackResult, PerformanceMode, SharingMode, Stereo,
};
use ringbuf::traits::{Consumer, Producer, Split};
use ringbuf::{HeapCons, HeapProd, HeapRb};

use crate::{record_log, CoreError};

struct ArcadeAudioCallback {
    consumer: HeapCons<i16>,
    paused: Arc<AtomicBool>,
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

        for frame in frames.iter_mut() {
            let left = self.consumer.try_pop().unwrap_or(0);
            let right = self.consumer.try_pop().unwrap_or(0);
            *frame = (left, right);
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

        let callback = ArcadeAudioCallback {
            consumer,
            paused: Arc::clone(&paused),
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
