use std::ffi::{c_char, CString};
use std::os::raw::{c_uint, c_void};
use std::path::Path;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Mutex, MutexGuard};

use libloading::Library;

use crate::retro::types::*;
use crate::{record_log, CoreError};

static NEXT_CORE_INSTANCE_ID: AtomicU64 = AtomicU64::new(1);

/// Contexto activo para los callbacks C de Libretro.
/// Se protege con `Mutex` global (en lugar de `thread_local!`) porque `LibretroCore` es `Send`
/// y puede inicializarse, ejecutarse y liberarse en hilos distintos (ej. `OfflineReplayRenderer`).
struct CallbackContext {
    instance_id: u64,
    system_dir_cstr: CString,
    save_dir_cstr: CString,
    pixel_format: c_uint,
    rotation: c_uint,
    frame_rgba: Vec<u8>,
    frame_width: u32,
    frame_height: u32,
    frame_dirty: bool,
    audio_scratch: Vec<i16>,
    inputs: [u16; 4],
}

static ACTIVE_CTX: Mutex<Option<CallbackContext>> = Mutex::new(None);

fn lock_ctx() -> MutexGuard<'static, Option<CallbackContext>> {
    match ACTIVE_CTX.lock() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    }
}

fn reset_transient_ctx_state(ctx: &mut CallbackContext) {
    ctx.pixel_format = RETRO_PIXEL_FORMAT_0RGB1555;
    ctx.rotation = 0;
    ctx.frame_rgba.clear();
    ctx.frame_width = 0;
    ctx.frame_height = 0;
    ctx.frame_dirty = false;
    ctx.audio_scratch.clear();
    ctx.inputs = [0; 4];
}

fn with_ctx<R>(f: impl FnOnce(&mut CallbackContext) -> R) -> Option<R> {
    let mut guard = lock_ctx();
    guard.as_mut().map(f)
}

/// Callback de entorno Libretro.
///
/// # Safety
/// Invocado únicamente desde el hilo de emulación por el core C de FBNeo.
/// Todos los punteros `data` se validan contra null antes de desreferenciarlos.
unsafe extern "C" fn environment_cb(cmd: c_uint, data: *mut c_void) -> bool {
    match cmd {
        RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY => {
            if data.is_null() {
                return false;
            }
            with_ctx(|ctx| {
                // SAFETY: `data` es `*mut *const c_char` según la especificación Libretro.
                unsafe {
                    *(data as *mut *const c_char) = ctx.system_dir_cstr.as_ptr();
                }
                true
            })
            .unwrap_or(false)
        }
        RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY => {
            if data.is_null() {
                return false;
            }
            with_ctx(|ctx| {
                // SAFETY: `data` es `*mut *const c_char` según la especificación Libretro.
                unsafe {
                    *(data as *mut *const c_char) = ctx.save_dir_cstr.as_ptr();
                }
                true
            })
            .unwrap_or(false)
        }
        RETRO_ENVIRONMENT_SET_PIXEL_FORMAT => {
            if data.is_null() {
                return false;
            }
            // SAFETY: `data` apunta a un `c_uint` válido con el formato solicitado.
            let fmt = unsafe { *(data as *const c_uint) };
            match fmt {
                RETRO_PIXEL_FORMAT_0RGB1555
                | RETRO_PIXEL_FORMAT_XRGB8888
                | RETRO_PIXEL_FORMAT_RGB565 => {
                    with_ctx(|ctx| {
                        ctx.pixel_format = fmt;
                        true
                    })
                    .unwrap_or(false)
                }
                _ => false,
            }
        }
        RETRO_ENVIRONMENT_SET_ROTATION => {
            if data.is_null() {
                return false;
            }
            // SAFETY: `data` apunta a un `c_uint` válido (0, 1, 2, 3 * 90 grados CCW).
            let rot = unsafe { *(data as *const c_uint) };
            if rot > 3 {
                return false;
            }
            with_ctx(|ctx| {
                ctx.rotation = rot;
                true
            })
            .unwrap_or(false)
        }
        RETRO_ENVIRONMENT_GET_CAN_DUPE => {
            if data.is_null() {
                return false;
            }
            // SAFETY: `data` apunta a un `bool` válido.
            unsafe {
                *(data as *mut bool) = true;
            }
            true
        }
        RETRO_ENVIRONMENT_GET_INPUT_BITMASKS => true,
        RETRO_ENVIRONMENT_GET_OVERSCAN => {
            if !data.is_null() {
                // SAFETY: `data` apunta a un `bool` válido.
                unsafe {
                    *(data as *mut bool) = false;
                }
            }
            true
        }
        _ => false,
    }
}

const MAX_FRAME_DIM: usize = 8192;

pub(crate) fn validated_frame_src_len(
    pixel_format: c_uint,
    width: usize,
    height: usize,
    pitch: usize,
) -> Option<(usize, usize)> {
    if width == 0 || height == 0 || width > MAX_FRAME_DIM || height > MAX_FRAME_DIM {
        return None;
    }
    let bpp = match pixel_format {
        RETRO_PIXEL_FORMAT_XRGB8888 => 4usize,
        _ => 2usize,
    };
    let min_row_bytes = width.checked_mul(bpp)?;
    if pitch < min_row_bytes {
        return None;
    }
    let _dst_len = width.checked_mul(height)?.checked_mul(4)?;
    let src_needed = (height - 1).checked_mul(pitch)?.checked_add(min_row_bytes)?;
    Some((min_row_bytes, src_needed))
}

pub(crate) fn convert_frame_to_rgba(
    pixel_format: c_uint,
    width: usize,
    height: usize,
    pitch: usize,
    src: &[u8],
    dst_rgba: &mut Vec<u8>,
) -> bool {
    let Some((min_row_bytes, src_needed)) =
        validated_frame_src_len(pixel_format, width, height, pitch)
    else {
        return false;
    };
    if src.len() < src_needed {
        return false;
    }
    let dst_needed = width * height * 4;
    if dst_rgba.len() != dst_needed {
        dst_rgba.resize(dst_needed, 0xFF);
    }

    match pixel_format {
        RETRO_PIXEL_FORMAT_RGB565 => {
            for y in 0..height {
                let src_row = &src[y * pitch..y * pitch + min_row_bytes];
                let dst_row = &mut dst_rgba[y * width * 4..(y + 1) * width * 4];
                for (x, bytes) in src_row.chunks_exact(2).enumerate() {
                    let px = u16::from_ne_bytes([bytes[0], bytes[1]]);
                    let r = ((px >> 11) & 0x1F) as u8;
                    let g = ((px >> 5) & 0x3F) as u8;
                    let b = (px & 0x1F) as u8;
                    let idx = x * 4;
                    dst_row[idx] = (r << 3) | (r >> 2);
                    dst_row[idx + 1] = (g << 2) | (g >> 4);
                    dst_row[idx + 2] = (b << 3) | (b >> 2);
                    dst_row[idx + 3] = 0xFF;
                }
            }
        }
        RETRO_PIXEL_FORMAT_XRGB8888 => {
            for y in 0..height {
                let src_row = &src[y * pitch..y * pitch + min_row_bytes];
                let dst_row = &mut dst_rgba[y * width * 4..(y + 1) * width * 4];
                for (x, bytes) in src_row.chunks_exact(4).enumerate() {
                    let px = u32::from_ne_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]);
                    let idx = x * 4;
                    dst_row[idx] = ((px >> 16) & 0xFF) as u8;
                    dst_row[idx + 1] = ((px >> 8) & 0xFF) as u8;
                    dst_row[idx + 2] = (px & 0xFF) as u8;
                    dst_row[idx + 3] = 0xFF;
                }
            }
        }
        _ => {
            // RETRO_PIXEL_FORMAT_0RGB1555
            for y in 0..height {
                let src_row = &src[y * pitch..y * pitch + min_row_bytes];
                let dst_row = &mut dst_rgba[y * width * 4..(y + 1) * width * 4];
                for (x, bytes) in src_row.chunks_exact(2).enumerate() {
                    let px = u16::from_ne_bytes([bytes[0], bytes[1]]);
                    let r = ((px >> 10) & 0x1F) as u8;
                    let g = ((px >> 5) & 0x1F) as u8;
                    let b = (px & 0x1F) as u8;
                    let idx = x * 4;
                    dst_row[idx] = (r << 3) | (r >> 2);
                    dst_row[idx + 1] = (g << 3) | (g >> 2);
                    dst_row[idx + 2] = (b << 3) | (b >> 2);
                    dst_row[idx + 3] = 0xFF;
                }
            }
        }
    }
    true
}

/// Callback de video Libretro: convierte el framebuffer nativo a RGBA8888 sin allocs repetidas.
///
/// # Safety
/// `data` puede ser null (frame duplicado). Si no es null, debe apuntar a al menos
/// `(height - 1) * pitch + width * bpp` bytes válidos en el formato `ctx.pixel_format`.
unsafe extern "C" fn video_refresh_cb(
    data: *const c_void,
    width: c_uint,
    height: c_uint,
    pitch: usize,
) {
    if data.is_null() || width == 0 || height == 0 {
        return;
    }

    with_ctx(|ctx| {
        let w = width as usize;
        let h = height as usize;
        let Some((_, src_needed)) = validated_frame_src_len(ctx.pixel_format, w, h, pitch) else {
            return;
        };
        // SAFETY: `validated_frame_src_len` validó dimensiones, `pitch >= w * bpp` y ausencia de overflow.
        let src = unsafe { std::slice::from_raw_parts(data as *const u8, src_needed) };
        if convert_frame_to_rgba(ctx.pixel_format, w, h, pitch, src, &mut ctx.frame_rgba) {
            ctx.frame_width = width;
            ctx.frame_height = height;
            ctx.frame_dirty = true;
        }
    });
}

unsafe extern "C" fn audio_sample_cb(left: i16, right: i16) {
    with_ctx(|ctx| {
        ctx.audio_scratch.push(left);
        ctx.audio_scratch.push(right);
    });
}

/// Callback batch de audio Libretro.
///
/// # Safety
/// `data` debe apuntar a `frames * 2` muestras `i16` estéreo válidas cuando `data` no es null.
unsafe extern "C" fn audio_sample_batch_cb(data: *const i16, frames: usize) -> usize {
    if data.is_null() || frames == 0 {
        return 0;
    }
    with_ctx(|ctx| {
        // SAFETY: Libretro garantiza `frames * 2` elementos `i16` contiguos.
        let slice = unsafe { std::slice::from_raw_parts(data, frames.saturating_mul(2)) };
        ctx.audio_scratch.extend_from_slice(slice);
        frames
    })
    .unwrap_or(0)
}

unsafe extern "C" fn input_poll_cb() {}

unsafe extern "C" fn input_state_cb(
    port: c_uint,
    device: c_uint,
    _index: c_uint,
    id: c_uint,
) -> i16 {
    if (device & RETRO_DEVICE_MASK) != RETRO_DEVICE_JOYPAD || port >= 4 {
        return 0;
    }
    with_ctx(|ctx| {
        let mask = ctx.inputs[port as usize];
        if id == RETRO_DEVICE_ID_JOYPAD_MASK {
            mask as i16
        } else if id < 16 {
            if (mask & (1u16 << id)) != 0 {
                1
            } else {
                0
            }
        } else {
            0
        }
    })
    .unwrap_or(0)
}

#[allow(dead_code)]
pub struct LibretroCore {
    _lib: Option<Library>,
    instance_id: u64,
    retro_init: unsafe extern "C" fn(),
    retro_deinit: unsafe extern "C" fn(),
    retro_set_environment: unsafe extern "C" fn(RetroEnvironmentFn),
    retro_set_video_refresh: unsafe extern "C" fn(RetroVideoRefreshFn),
    retro_set_audio_sample: unsafe extern "C" fn(RetroAudioSampleFn),
    retro_set_audio_sample_batch: unsafe extern "C" fn(RetroAudioSampleBatchFn),
    retro_set_input_poll: unsafe extern "C" fn(RetroInputPollFn),
    retro_set_input_state: unsafe extern "C" fn(RetroInputStateFn),
    retro_get_system_av_info: unsafe extern "C" fn(*mut RetroSystemAvInfo),
    retro_set_controller_port_device: unsafe extern "C" fn(c_uint, c_uint),
    retro_load_game: unsafe extern "C" fn(*const RetroGameInfo) -> bool,
    retro_unload_game: unsafe extern "C" fn(),
    retro_run: unsafe extern "C" fn(),
    retro_serialize_size: unsafe extern "C" fn() -> usize,
    retro_serialize: unsafe extern "C" fn(*mut c_void, usize) -> bool,
    retro_unserialize: unsafe extern "C" fn(*const c_void, usize) -> bool,
    retro_get_memory_data: unsafe extern "C" fn(c_uint) -> *mut c_void,
    retro_get_memory_size: unsafe extern "C" fn(c_uint) -> usize,
    game_loaded: bool,
}

impl LibretroCore {
    /// Carga `libfbneo_libretro.so` e inicializa el contexto de callbacks Libretro.
    pub fn load(core_path: &Path, system_dir: &Path, save_dir: &Path) -> Result<Self, CoreError> {
        let system_dir_cstr = CString::new(system_dir.to_string_lossy().as_ref())
            .map_err(|e| CoreError::Init(format!("Ruta system_dir inválida: {e}")))?;
        let save_dir_cstr = CString::new(save_dir.to_string_lossy().as_ref())
            .map_err(|e| CoreError::Init(format!("Ruta save_dir inválida: {e}")))?;

        // SAFETY: `core_path` apunta al `.so` compilado en `nativeLibraryDir` del APK.
        let lib = unsafe { Library::new(core_path) }
            .map_err(|e| CoreError::CoreLoad(format!("No se pudo abrir {:?}: {e}", core_path)))?;

        macro_rules! load_sym {
            ($name:expr, $ty:ty) => {{
                // SAFETY: El símbolo pertenece al ABI estándar de Libretro y la librería permanece viva en `Self`.
                let sym: libloading::Symbol<$ty> = unsafe { lib.get($name) }.map_err(|e| {
                    CoreError::CoreLoad(format!(
                        "Símbolo Libretro faltante {}: {e}",
                        String::from_utf8_lossy($name)
                    ))
                })?;
                *sym
            }};
        }

        let retro_init = load_sym!(b"retro_init\0", unsafe extern "C" fn());
        let retro_deinit = load_sym!(b"retro_deinit\0", unsafe extern "C" fn());
        let retro_set_environment =
            load_sym!(b"retro_set_environment\0", unsafe extern "C" fn(RetroEnvironmentFn));
        let retro_set_video_refresh =
            load_sym!(b"retro_set_video_refresh\0", unsafe extern "C" fn(RetroVideoRefreshFn));
        let retro_set_audio_sample =
            load_sym!(b"retro_set_audio_sample\0", unsafe extern "C" fn(RetroAudioSampleFn));
        let retro_set_audio_sample_batch = load_sym!(
            b"retro_set_audio_sample_batch\0",
            unsafe extern "C" fn(RetroAudioSampleBatchFn)
        );
        let retro_set_input_poll =
            load_sym!(b"retro_set_input_poll\0", unsafe extern "C" fn(RetroInputPollFn));
        let retro_set_input_state =
            load_sym!(b"retro_set_input_state\0", unsafe extern "C" fn(RetroInputStateFn));
        let retro_get_system_av_info = load_sym!(
            b"retro_get_system_av_info\0",
            unsafe extern "C" fn(*mut RetroSystemAvInfo)
        );
        let retro_set_controller_port_device = load_sym!(
            b"retro_set_controller_port_device\0",
            unsafe extern "C" fn(c_uint, c_uint)
        );
        let retro_load_game = load_sym!(
            b"retro_load_game\0",
            unsafe extern "C" fn(*const RetroGameInfo) -> bool
        );
        let retro_unload_game = load_sym!(b"retro_unload_game\0", unsafe extern "C" fn());
        let retro_run = load_sym!(b"retro_run\0", unsafe extern "C" fn());
        let retro_serialize_size =
            load_sym!(b"retro_serialize_size\0", unsafe extern "C" fn() -> usize);
        let retro_serialize = load_sym!(
            b"retro_serialize\0",
            unsafe extern "C" fn(*mut c_void, usize) -> bool
        );
        let retro_unserialize = load_sym!(
            b"retro_unserialize\0",
            unsafe extern "C" fn(*const c_void, usize) -> bool
        );
        let retro_get_memory_data = load_sym!(
            b"retro_get_memory_data\0",
            unsafe extern "C" fn(c_uint) -> *mut c_void
        );
        let retro_get_memory_size = load_sym!(
            b"retro_get_memory_size\0",
            unsafe extern "C" fn(c_uint) -> usize
        );

        let instance_id = NEXT_CORE_INSTANCE_ID.fetch_add(1, Ordering::Relaxed);
        {
            let mut guard = lock_ctx();
            *guard = Some(CallbackContext {
                instance_id,
                system_dir_cstr,
                save_dir_cstr,
                pixel_format: RETRO_PIXEL_FORMAT_0RGB1555,
                rotation: 0,
                frame_rgba: Vec::with_capacity(320 * 240 * 4),
                frame_width: 320,
                frame_height: 240,
                frame_dirty: false,
                audio_scratch: Vec::with_capacity(4096),
                inputs: [0; 4],
            });
        }

        // SAFETY: Callbacks estáticos válidos mientras `ACTIVE_CTX` esté inicializado.
        unsafe {
            retro_set_environment(environment_cb);
            retro_set_video_refresh(video_refresh_cb);
            retro_set_audio_sample(audio_sample_cb);
            retro_set_audio_sample_batch(audio_sample_batch_cb);
            retro_set_input_poll(input_poll_cb);
            retro_set_input_state(input_state_cb);
            retro_init();
        }

        record_log("INFO", "FBNeo Libretro core inicializado");

        Ok(Self {
            _lib: Some(lib),
            instance_id,
            retro_init,
            retro_deinit,
            retro_set_environment,
            retro_set_video_refresh,
            retro_set_audio_sample,
            retro_set_audio_sample_batch,
            retro_set_input_poll,
            retro_set_input_state,
            retro_get_system_av_info,
            retro_set_controller_port_device,
            retro_load_game,
            retro_unload_game,
            retro_run,
            retro_serialize_size,
            retro_serialize,
            retro_unserialize,
            retro_get_memory_data,
            retro_get_memory_size,
            game_loaded: false,
        })
    }

    pub(crate) fn validate_rom_readable(rom_path: &Path) -> Result<(), CoreError> {
        let file = std::fs::File::open(rom_path).map_err(|e| {
            CoreError::CoreLoad(format!("No se pudo abrir la ROM {:?}: {e}", rom_path))
        })?;
        let meta = file.metadata().map_err(|e| {
            CoreError::CoreLoad(format!(
                "No se pudo leer metadatos de ROM {:?}: {e}",
                rom_path
            ))
        })?;
        if !meta.is_file() {
            return Err(CoreError::CoreLoad(format!(
                "La ruta de ROM {:?} no es un archivo regular",
                rom_path
            )));
        }
        Ok(())
    }

    pub fn load_game(&mut self, rom_path: &Path) -> Result<RetroSystemAvInfo, CoreError> {
        Self::validate_rom_readable(rom_path)?;
        let rom_cstr = CString::new(rom_path.to_string_lossy().as_ref())
            .map_err(|e| CoreError::CoreLoad(format!("Ruta ROM inválida: {e}")))?;

        if self.game_loaded {
            // SAFETY: Descargar el juego previo antes de recargar evita fugas y corrupción en FBNeo.
            unsafe {
                (self.retro_unload_game)();
            }
            self.game_loaded = false;
        }
        with_ctx(reset_transient_ctx_state);

        let game_info = RetroGameInfo {
            path: rom_cstr.as_ptr(),
            data: std::ptr::null(),
            size: 0,
            meta: std::ptr::null(),
        };

        // SAFETY: `game_info.path` apunta a `rom_cstr` vivo durante toda la llamada `retro_load_game`.
        let ok = unsafe { (self.retro_load_game)(&game_info) };
        if !ok {
            return Err(CoreError::CoreLoad(format!(
                "FBNeo rechazó la ROM {:?} (verifica BIOS o nombre de archivo zip)",
                rom_path
            )));
        }
        self.game_loaded = true;

        let mut av_info = RetroSystemAvInfo::default();
        // SAFETY: `av_info` es una estructura válida en stack.
        unsafe {
            (self.retro_get_system_av_info)(&mut av_info);
            for port in 0..4 {
                (self.retro_set_controller_port_device)(port, RETRO_DEVICE_JOYPAD);
            }
        }

        record_log(
            "INFO",
            &format!(
                "Juego cargado: {}x{} @ {:.2} fps, audio {:.0} Hz",
                av_info.geometry.base_width,
                av_info.geometry.base_height,
                av_info.timing.fps,
                av_info.timing.sample_rate
            ),
        );

        Ok(av_info)
    }

    pub fn set_inputs(&self, inputs: [u16; 4]) {
        with_ctx(|ctx| {
            ctx.inputs = inputs;
        });
    }

    pub fn run_frame(&self) {
        if !self.game_loaded {
            return;
        }
        with_ctx(|ctx| {
            ctx.frame_dirty = false;
            ctx.audio_scratch.clear();
        });
        // SAFETY: `game_loaded` es true y los callbacks están registrados en este hilo.
        unsafe {
            (self.retro_run)();
        }
    }

    pub fn rotation(&self) -> u32 {
        with_ctx(|ctx| ctx.rotation % 4).unwrap_or(0)
    }

    pub fn take_video_frame<R>(&self, f: impl FnOnce(u32, u32, &[u8]) -> R) -> Option<R> {
        with_ctx(|ctx| {
            if ctx.frame_dirty && ctx.frame_width > 0 && ctx.frame_height > 0 {
                ctx.frame_dirty = false;
                Some(f(ctx.frame_width, ctx.frame_height, &ctx.frame_rgba))
            } else {
                None
            }
        })
        .flatten()
    }

    pub fn take_audio_samples<R>(&self, f: impl FnOnce(&[i16]) -> R) -> Option<R> {
        with_ctx(|ctx| {
            if !ctx.audio_scratch.is_empty() {
                Some(f(&ctx.audio_scratch))
            } else {
                None
            }
        })
        .flatten()
    }

    pub fn save_state_bytes(&self) -> Result<Vec<u8>, CoreError> {
        if !self.game_loaded {
            return Err(CoreError::State("No hay juego cargado".into()));
        }
        // SAFETY: Llamado desde el hilo de emulación con el juego cargado.
        let size = unsafe { (self.retro_serialize_size)() };
        if size == 0 {
            return Err(CoreError::State("El core reportó serialize_size = 0".into()));
        }
        let mut buf = vec![0u8; size];
        // SAFETY: `buf` tiene exactamente `size` bytes asignados.
        let ok = unsafe { (self.retro_serialize)(buf.as_mut_ptr() as *mut c_void, size) };
        if !ok {
            return Err(CoreError::State("retro_serialize falló".into()));
        }
        Ok(buf)
    }

    pub fn load_state_bytes(&self, data: &[u8]) -> Result<(), CoreError> {
        if !self.game_loaded {
            return Err(CoreError::State("No hay juego cargado".into()));
        }
        // SAFETY: `data` es un slice válido de bytes de savestate.
        let ok = unsafe { (self.retro_unserialize)(data.as_ptr() as *const c_void, data.len()) };
        if !ok {
            return Err(CoreError::State("retro_unserialize falló".into()));
        }
        Ok(())
    }

    /// Devuelve una vista de solo lectura de una región de memoria del core Libretro.
    pub fn memory_slice(&self, id: c_uint) -> Option<&[u8]> {
        if !self.game_loaded {
            return None;
        }
        // SAFETY: Llamado desde el hilo de emulación mientras el juego está cargado.
        let size = unsafe { (self.retro_get_memory_size)(id) };
        if size == 0 {
            return None;
        }
        // SAFETY: El core garantiza un puntero válido de `size` bytes mientras no se descargue el juego.
        let ptr = unsafe { (self.retro_get_memory_data)(id) } as *const u8;
        if ptr.is_null() {
            return None;
        }
        Some(unsafe { std::slice::from_raw_parts(ptr, size) })
    }

    /// Devuelve la RAM principal del sistema emulado (`RETRO_MEMORY_SYSTEM_RAM`).
    pub fn system_ram(&self) -> Option<&[u8]> {
        self.memory_slice(RETRO_MEMORY_SYSTEM_RAM)
    }
}

/// Rota un framebuffer RGBA8888 según la convención Libretro CCW (`0=0°, 1=90° CCW, 2=180°, 3=270° CCW`).
pub fn rotate_rgba(width: u32, height: u32, src: &[u8], rotation: u32) -> (u32, u32, Vec<u8>) {
    let rot = rotation % 4;
    let w = width as usize;
    let h = height as usize;
    let expected_len = w.saturating_mul(h).saturating_mul(4);
    if rot == 0 || w == 0 || h == 0 || src.len() < expected_len {
        return (width, height, src.to_vec());
    }

    let (out_w, out_h) = if rot % 2 == 1 {
        (height, width)
    } else {
        (width, height)
    };
    let out_w_usize = out_w as usize;
    let mut dst = vec![0u8; expected_len];

    for sy in 0..h {
        for sx in 0..w {
            let (dx, dy) = match rot {
                1 => (sy, w - 1 - sx),
                2 => (w - 1 - sx, h - 1 - sy),
                3 => (h - 1 - sy, sx),
                _ => (sx, sy),
            };
            let src_idx = (sy * w + sx) * 4;
            let dst_idx = (dy * out_w_usize + dx) * 4;
            dst[dst_idx..dst_idx + 4].copy_from_slice(&src[src_idx..src_idx + 4]);
        }
    }

    (out_w, out_h, dst)
}

impl Drop for LibretroCore {
    fn drop(&mut self) {
        let owns_active_ctx = lock_ctx()
            .as_ref()
            .is_some_and(|ctx| ctx.instance_id == self.instance_id);
        if owns_active_ctx {
            // SAFETY: Esta instancia es la dueña vigente de `ACTIVE_CTX`; el lock ya fue
            // liberado para evitar deadlocks en callbacks durante `retro_unload_game`/`retro_deinit`.
            unsafe {
                if self.game_loaded {
                    (self.retro_unload_game)();
                }
                (self.retro_deinit)();
            }
            let mut guard = lock_ctx();
            if guard
                .as_ref()
                .is_some_and(|ctx| ctx.instance_id == self.instance_id)
            {
                *guard = None;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    static TEST_CTX_LOCK: Mutex<()> = Mutex::new(());

    struct CtxResetGuard<'a> {
        _lock: MutexGuard<'a, ()>,
    }

    impl<'a> CtxResetGuard<'a> {
        fn acquire() -> Self {
            let lock = match TEST_CTX_LOCK.lock() {
                Ok(g) => g,
                Err(p) => p.into_inner(),
            };
            Self { _lock: lock }
        }
    }

    impl Drop for CtxResetGuard<'_> {
        fn drop(&mut self) {
            *lock_ctx() = None;
        }
    }

    #[test]
    fn active_ctx_is_accessible_across_threads_and_recovers_from_poison() {
        let _reset = CtxResetGuard::acquire();

        {
            let mut guard = lock_ctx();
            *guard = Some(CallbackContext {
                instance_id: 1,
                system_dir_cstr: CString::new("/tmp/sys").unwrap(),
                save_dir_cstr: CString::new("/tmp/save").unwrap(),
                pixel_format: RETRO_PIXEL_FORMAT_0RGB1555,
                rotation: 0,
                frame_rgba: Vec::new(),
                frame_width: 0,
                frame_height: 0,
                frame_dirty: false,
                audio_scratch: Vec::new(),
                inputs: [0; 4],
            });
        }

        // Simula hilo de worker (como Dispatchers.IO en OfflineReplayRenderer) mutando inputs y video.
        std::thread::spawn(|| {
            with_ctx(|ctx| {
                ctx.inputs[0] = 0b1010;
            });
            let state = unsafe { input_state_cb(0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_MASK) };
            assert_eq!(state, 0b1010);

            unsafe {
                audio_sample_cb(123, -456);
            }
        })
        .join()
        .unwrap();

        let audio = with_ctx(|ctx| ctx.audio_scratch.clone()).unwrap();
        assert_eq!(audio, vec![123, -456]);

        // Simula pánico mientras se sostiene ACTIVE_CTX para verificar recuperación de poison.
        let _ = std::thread::spawn(|| {
            let _guard = lock_ctx();
            panic!("simulated callback panic");
        })
        .join();

        let recovered = with_ctx(|ctx| ctx.inputs[0]);
        assert_eq!(recovered, Some(0b1010));

        // Simula Drop en otro hilo (UniffiCleaner) limpiando ACTIVE_CTX.
        std::thread::spawn(|| {
            *lock_ctx() = None;
        })
        .join()
        .unwrap();

        assert!(with_ctx(|_| ()).is_none());
    }

    #[test]
    fn reset_transient_ctx_state_clears_frame_audio_inputs_and_rotation() {
        let mut ctx = CallbackContext {
            instance_id: 1,
            system_dir_cstr: CString::new("/tmp/sys").unwrap(),
            save_dir_cstr: CString::new("/tmp/save").unwrap(),
            pixel_format: RETRO_PIXEL_FORMAT_XRGB8888,
            rotation: 3,
            frame_rgba: vec![1, 2, 3, 4],
            frame_width: 320,
            frame_height: 240,
            frame_dirty: true,
            audio_scratch: vec![10, -20],
            inputs: [0xFFFF, 1, 2, 3],
        };

        reset_transient_ctx_state(&mut ctx);

        assert_eq!(ctx.system_dir_cstr.to_str().unwrap(), "/tmp/sys");
        assert_eq!(ctx.save_dir_cstr.to_str().unwrap(), "/tmp/save");
        assert_eq!(ctx.pixel_format, RETRO_PIXEL_FORMAT_0RGB1555);
        assert_eq!(ctx.rotation, 0);
        assert!(ctx.frame_rgba.is_empty());
        assert_eq!(ctx.frame_width, 0);
        assert_eq!(ctx.frame_height, 0);
        assert!(!ctx.frame_dirty);
        assert!(ctx.audio_scratch.is_empty());
        assert_eq!(ctx.inputs, [0; 4]);
    }

    #[test]
    fn environment_cb_validates_rotation_bounds() {
        let _reset = CtxResetGuard::acquire();
        {
            let mut guard = lock_ctx();
            *guard = Some(CallbackContext {
                instance_id: 1,
                system_dir_cstr: CString::new("/tmp/sys").unwrap(),
                save_dir_cstr: CString::new("/tmp/save").unwrap(),
                pixel_format: RETRO_PIXEL_FORMAT_0RGB1555,
                rotation: 0,
                frame_rgba: Vec::new(),
                frame_width: 0,
                frame_height: 0,
                frame_dirty: false,
                audio_scratch: Vec::new(),
                inputs: [0; 4],
            });
        }

        let mut rot: c_uint = 1;
        assert!(unsafe {
            environment_cb(
                RETRO_ENVIRONMENT_SET_ROTATION,
                (&mut rot as *mut c_uint) as *mut c_void,
            )
        });
        assert_eq!(with_ctx(|ctx| ctx.rotation), Some(1));

        let mut invalid_rot: c_uint = 4;
        assert!(!unsafe {
            environment_cb(
                RETRO_ENVIRONMENT_SET_ROTATION,
                (&mut invalid_rot as *mut c_uint) as *mut c_void,
            )
        });
        assert_eq!(with_ctx(|ctx| ctx.rotation), Some(1));
    }

    #[test]
    fn rotate_rgba_dimensions_and_corner_pixels() {
        // 2x3 image:
        // (0,0)=R  (1,0)=G
        // (0,1)=B  (1,1)=W
        // (0,2)=Y  (1,2)=C
        let r = [255, 0, 0, 255];
        let g = [0, 255, 0, 255];
        let b = [0, 0, 255, 255];
        let w = [255, 255, 255, 255];
        let y = [255, 255, 0, 255];
        let c = [0, 255, 255, 255];
        let src: Vec<u8> = [r, g, b, w, y, c].concat();

        let (w0, h0, out0) = rotate_rgba(2, 3, &src, 0);
        assert_eq!((w0, h0), (2, 3));
        assert_eq!(out0, src);

        // 90 deg CCW -> 3x2:
        // top row:    G, W, C
        // bottom row: R, B, Y
        let (w1, h1, out1) = rotate_rgba(2, 3, &src, 1);
        assert_eq!((w1, h1), (3, 2));
        assert_eq!(out1, [g, w, c, r, b, y].concat());

        // 180 deg -> 2x3:
        // C, Y / W, B / G, R
        let (w2, h2, out2) = rotate_rgba(2, 3, &src, 2);
        assert_eq!((w2, h2), (2, 3));
        assert_eq!(out2, [c, y, w, b, g, r].concat());

        // 270 deg CCW -> 3x2:
        // top row:    Y, B, R
        // bottom row: C, W, G
        let (w3, h3, out3) = rotate_rgba(2, 3, &src, 3);
        assert_eq!((w3, h3), (3, 2));
        assert_eq!(out3, [y, b, r, c, w, g].concat());

        // Rotating 90 CCW four times returns to original
        let mut cur_w = 2;
        let mut cur_h = 3;
        let mut cur = src.clone();
        for _ in 0..4 {
            let (nw, nh, next) = rotate_rgba(cur_w, cur_h, &cur, 1);
            cur_w = nw;
            cur_h = nh;
            cur = next;
        }
        assert_eq!((cur_w, cur_h), (2, 3));
        assert_eq!(cur, src);
    }

    #[test]
    fn convert_frame_rgb565_xrgb8888_and_0rgb1555_with_odd_pitch() {
        // Odd pitch (width=2, pitch=5 bytes for 2-byte formats -> unaligned row 1 + padding byte)
        let mut dst = Vec::new();
        let px565_a = 0xF800u16.to_ne_bytes(); // pure red
        let px565_b = 0x07E0u16.to_ne_bytes(); // pure green
        let px565_c = 0x001Fu16.to_ne_bytes(); // pure blue
        let px565_d = 0xFFFFu16.to_ne_bytes(); // white
        let src565 = [
            px565_a[0], px565_a[1], px565_b[0], px565_b[1], 0xAA, // row 0 + pad
            px565_c[0], px565_c[1], px565_d[0], px565_d[1], // row 1 (no trailing pad needed)
        ];
        assert!(convert_frame_to_rgba(
            RETRO_PIXEL_FORMAT_RGB565,
            2,
            2,
            5,
            &src565,
            &mut dst
        ));
        assert_eq!(
            dst,
            vec![
                255, 0, 0, 255, 0, 255, 0, 255, 0, 0, 255, 255, 255, 255, 255, 255
            ]
        );

        // XRGB8888 with odd pitch = 9 bytes (width=2 -> 8 bytes + 1 pad byte)
        let p0 = 0x0012_3456u32.to_ne_bytes();
        let p1 = 0x00AB_CDEFu32.to_ne_bytes();
        let src8888 = [
            p0[0], p0[1], p0[2], p0[3], p1[0], p1[1], p1[2], p1[3], 0x99,
            p1[0], p1[1], p1[2], p1[3], p0[0], p0[1], p0[2], p0[3],
        ];
        assert!(convert_frame_to_rgba(
            RETRO_PIXEL_FORMAT_XRGB8888,
            2,
            2,
            9,
            &src8888,
            &mut dst
        ));
        assert_eq!(
            dst,
            vec![
                0x12, 0x34, 0x56, 0xFF, 0xAB, 0xCD, 0xEF, 0xFF,
                0xAB, 0xCD, 0xEF, 0xFF, 0x12, 0x34, 0x56, 0xFF
            ]
        );

        // 0RGB1555 (5-bit white = 0x7FFF)
        let w1555 = 0x7FFFu16.to_ne_bytes();
        let src1555 = [w1555[0], w1555[1]];
        assert!(convert_frame_to_rgba(
            RETRO_PIXEL_FORMAT_0RGB1555,
            1,
            1,
            2,
            &src1555,
            &mut dst
        ));
        assert_eq!(dst, vec![255, 255, 255, 255]);
    }

    #[test]
    fn convert_frame_rejects_zero_dims_short_pitch_and_overflow() {
        let mut dst = Vec::new();
        let buf = [0u8; 64];
        assert!(!convert_frame_to_rgba(RETRO_PIXEL_FORMAT_RGB565, 0, 2, 4, &buf, &mut dst));
        assert!(!convert_frame_to_rgba(RETRO_PIXEL_FORMAT_RGB565, 2, 0, 4, &buf, &mut dst));
        assert!(!convert_frame_to_rgba(RETRO_PIXEL_FORMAT_RGB565, 4, 2, 6, &buf, &mut dst));
        assert!(!convert_frame_to_rgba(RETRO_PIXEL_FORMAT_XRGB8888, 4, 2, 12, &buf, &mut dst));
        assert!(!convert_frame_to_rgba(RETRO_PIXEL_FORMAT_RGB565, 9000, 2, 18000, &buf, &mut dst));
        assert!(!convert_frame_to_rgba(RETRO_PIXEL_FORMAT_RGB565, 2, 2, 4, &buf[..6], &mut dst));
    }

    static DUMMY_DEINIT_CALLS: AtomicU64 = AtomicU64::new(0);
    static DUMMY_UNLOAD_CALLS: AtomicU64 = AtomicU64::new(0);
    static DUMMY_RUN_CALLS: AtomicU64 = AtomicU64::new(0);
    static DUMMY_SERIALIZE_CALLS: AtomicU64 = AtomicU64::new(0);
    static DUMMY_UNSERIALIZE_CALLS: AtomicU64 = AtomicU64::new(0);

    unsafe extern "C" fn dummy_void() {}
    unsafe extern "C" fn dummy_deinit() {
        DUMMY_DEINIT_CALLS.fetch_add(1, Ordering::Relaxed);
    }
    unsafe extern "C" fn dummy_unload() {
        DUMMY_UNLOAD_CALLS.fetch_add(1, Ordering::Relaxed);
    }
    unsafe extern "C" fn dummy_run() {
        DUMMY_RUN_CALLS.fetch_add(1, Ordering::Relaxed);
    }
    unsafe extern "C" fn dummy_set_env(_: RetroEnvironmentFn) {}
    unsafe extern "C" fn dummy_set_video(_: RetroVideoRefreshFn) {}
    unsafe extern "C" fn dummy_set_audio(_: RetroAudioSampleFn) {}
    unsafe extern "C" fn dummy_set_audio_batch(_: RetroAudioSampleBatchFn) {}
    unsafe extern "C" fn dummy_set_poll(_: RetroInputPollFn) {}
    unsafe extern "C" fn dummy_set_input(_: RetroInputStateFn) {}
    unsafe extern "C" fn dummy_av_info(_: *mut RetroSystemAvInfo) {}
    unsafe extern "C" fn dummy_port_dev(_: c_uint, _: c_uint) {}
    unsafe extern "C" fn dummy_load_game(_: *const RetroGameInfo) -> bool {
        false
    }
    unsafe extern "C" fn dummy_serialize_size() -> usize {
        DUMMY_SERIALIZE_CALLS.fetch_add(1, Ordering::Relaxed);
        0
    }
    unsafe extern "C" fn dummy_serialize(_: *mut c_void, _: usize) -> bool {
        DUMMY_SERIALIZE_CALLS.fetch_add(1, Ordering::Relaxed);
        false
    }
    unsafe extern "C" fn dummy_unserialize(_: *const c_void, _: usize) -> bool {
        DUMMY_UNSERIALIZE_CALLS.fetch_add(1, Ordering::Relaxed);
        false
    }
    unsafe extern "C" fn dummy_mem_data(_: c_uint) -> *mut c_void {
        std::ptr::null_mut()
    }
    unsafe extern "C" fn dummy_mem_size(_: c_uint) -> usize {
        0
    }

    fn dummy_core_with_id(instance_id: u64, game_loaded: bool) -> LibretroCore {
        LibretroCore {
            _lib: None,
            instance_id,
            retro_init: dummy_void,
            retro_deinit: dummy_deinit,
            retro_set_environment: dummy_set_env,
            retro_set_video_refresh: dummy_set_video,
            retro_set_audio_sample: dummy_set_audio,
            retro_set_audio_sample_batch: dummy_set_audio_batch,
            retro_set_input_poll: dummy_set_poll,
            retro_set_input_state: dummy_set_input,
            retro_get_system_av_info: dummy_av_info,
            retro_set_controller_port_device: dummy_port_dev,
            retro_load_game: dummy_load_game,
            retro_unload_game: dummy_unload,
            retro_run: dummy_run,
            retro_serialize_size: dummy_serialize_size,
            retro_serialize: dummy_serialize,
            retro_unserialize: dummy_unserialize,
            retro_get_memory_data: dummy_mem_data,
            retro_get_memory_size: dummy_mem_size,
            game_loaded,
        }
    }

    fn make_test_ctx(instance_id: u64) -> CallbackContext {
        CallbackContext {
            instance_id,
            system_dir_cstr: CString::new("/tmp/sys").unwrap(),
            save_dir_cstr: CString::new("/tmp/save").unwrap(),
            pixel_format: RETRO_PIXEL_FORMAT_0RGB1555,
            rotation: 0,
            frame_rgba: Vec::new(),
            frame_width: 0,
            frame_height: 0,
            frame_dirty: false,
            audio_scratch: Vec::new(),
            inputs: [0; 4],
        }
    }

    #[test]
    fn validate_rom_readable_rejects_missing_and_directory_and_accepts_file() {
        let manifest_dir = Path::new(env!("CARGO_MANIFEST_DIR"));
        let missing = manifest_dir.join("does_not_exist_rom.zip");
        assert!(matches!(
            LibretroCore::validate_rom_readable(&missing),
            Err(CoreError::CoreLoad(_))
        ));

        // Passing a directory rather than a regular file must fail.
        assert!(matches!(
            LibretroCore::validate_rom_readable(manifest_dir),
            Err(CoreError::CoreLoad(_))
        ));

        let valid_file = manifest_dir.join("Cargo.toml");
        assert!(LibretroCore::validate_rom_readable(&valid_file).is_ok());
    }

    #[test]
    fn stale_core_drop_does_not_wipe_newer_active_ctx_or_deinit() {
        let _reset = CtxResetGuard::acquire();
        DUMMY_DEINIT_CALLS.store(0, Ordering::SeqCst);
        DUMMY_UNLOAD_CALLS.store(0, Ordering::SeqCst);

        let core1 = dummy_core_with_id(101, true);
        let core2 = dummy_core_with_id(102, true);
        *lock_ctx() = Some(make_test_ctx(102));

        // Dropping stale core1 must NOT call retro_unload_game/retro_deinit or clear core2's ctx.
        drop(core1);
        assert_eq!(DUMMY_DEINIT_CALLS.load(Ordering::SeqCst), 0);
        assert_eq!(DUMMY_UNLOAD_CALLS.load(Ordering::SeqCst), 0);
        assert_eq!(
            lock_ctx().as_ref().map(|c| c.instance_id),
            Some(102)
        );

        // Dropping owning core2 MUST call retro_unload_game + retro_deinit and clear ACTIVE_CTX.
        drop(core2);
        assert_eq!(DUMMY_UNLOAD_CALLS.load(Ordering::SeqCst), 1);
        assert_eq!(DUMMY_DEINIT_CALLS.load(Ordering::SeqCst), 1);
        assert!(lock_ctx().is_none());
    }

    #[test]
    fn unloaded_core_guards_run_frame_and_savestate_calls() {
        let _reset = CtxResetGuard::acquire();
        DUMMY_RUN_CALLS.store(0, Ordering::SeqCst);
        DUMMY_SERIALIZE_CALLS.store(0, Ordering::SeqCst);
        DUMMY_UNSERIALIZE_CALLS.store(0, Ordering::SeqCst);

        let core = dummy_core_with_id(201, false);
        core.run_frame();
        assert!(matches!(core.save_state_bytes(), Err(CoreError::State(_))));
        assert!(matches!(core.load_state_bytes(&[1, 2, 3]), Err(CoreError::State(_))));
        assert!(core.system_ram().is_none());

        assert_eq!(DUMMY_RUN_CALLS.load(Ordering::SeqCst), 0);
        assert_eq!(DUMMY_SERIALIZE_CALLS.load(Ordering::SeqCst), 0);
        assert_eq!(DUMMY_UNSERIALIZE_CALLS.load(Ordering::SeqCst), 0);

        // Ensure load_game rejects missing ROM without invoking C load_game or unload_game.
        let mut core_mut = core;
        let missing = Path::new("/nonexistent_balddog_rom_test/missing.zip");
        assert!(matches!(core_mut.load_game(missing), Err(CoreError::CoreLoad(_))));
        assert!(!core_mut.game_loaded);
    }
}
