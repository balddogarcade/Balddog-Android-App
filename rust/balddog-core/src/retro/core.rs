use std::ffi::{c_char, CString};
use std::os::raw::{c_uint, c_void};
use std::path::Path;
use std::sync::{Mutex, MutexGuard};

use libloading::Library;

use crate::retro::types::*;
use crate::{record_log, CoreError};

/// Contexto activo para los callbacks C de Libretro.
/// Se protege con `Mutex` global (en lugar de `thread_local!`) porque `LibretroCore` es `Send`
/// y puede inicializarse, ejecutarse y liberarse en hilos distintos (ej. `OfflineReplayRenderer`).
struct CallbackContext {
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
            // SAFETY: `data` apunta a un `c_uint` válido (0, 1, 2, 3 * 90 grados).
            let rot = unsafe { *(data as *const c_uint) };
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

/// Callback de video Libretro: convierte el framebuffer nativo a RGBA8888 sin allocs repetidas.
///
/// # Safety
/// `data` puede ser null (frame duplicado). Si no es null, debe apuntar a al menos
/// `height * pitch` bytes válidos en el formato `ctx.pixel_format`.
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
        let needed = w.saturating_mul(h).saturating_mul(4);
        if ctx.frame_rgba.len() != needed {
            ctx.frame_rgba.resize(needed, 0xFF);
        }
        ctx.frame_width = width;
        ctx.frame_height = height;
        ctx.frame_dirty = true;

        let src_bytes = data as *const u8;

        match ctx.pixel_format {
            RETRO_PIXEL_FORMAT_RGB565 => {
                for y in 0..h {
                    // SAFETY: cada fila comienza en `y * pitch` dentro del framebuffer de Libretro.
                    let row_ptr = unsafe { src_bytes.add(y * pitch) } as *const u16;
                    let dst_row = &mut ctx.frame_rgba[y * w * 4..(y + 1) * w * 4];
                    for x in 0..w {
                        // SAFETY: `x < w` y `w * 2 <= pitch`.
                        let px = unsafe { *row_ptr.add(x) };
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
                for y in 0..h {
                    // SAFETY: cada fila comienza en `y * pitch` dentro del framebuffer de Libretro.
                    let row_ptr = unsafe { src_bytes.add(y * pitch) } as *const u32;
                    let dst_row = &mut ctx.frame_rgba[y * w * 4..(y + 1) * w * 4];
                    for x in 0..w {
                        // SAFETY: `x < w` y `w * 4 <= pitch`.
                        let px = unsafe { *row_ptr.add(x) };
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
                for y in 0..h {
                    // SAFETY: cada fila comienza en `y * pitch` dentro del framebuffer de Libretro.
                    let row_ptr = unsafe { src_bytes.add(y * pitch) } as *const u16;
                    let dst_row = &mut ctx.frame_rgba[y * w * 4..(y + 1) * w * 4];
                    for x in 0..w {
                        // SAFETY: `x < w` y `w * 2 <= pitch`.
                        let px = unsafe { *row_ptr.add(x) };
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
    _lib: Library,
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

        {
            let mut guard = lock_ctx();
            *guard = Some(CallbackContext {
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
            _lib: lib,
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

    pub fn load_game(&mut self, rom_path: &Path) -> Result<RetroSystemAvInfo, CoreError> {
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
        with_ctx(|ctx| {
            ctx.frame_dirty = false;
            ctx.audio_scratch.clear();
        });
        // SAFETY: `game_loaded` es true y los callbacks están registrados en este hilo.
        unsafe {
            (self.retro_run)();
        }
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

impl Drop for LibretroCore {
    fn drop(&mut self) {
        // SAFETY: Se descarga el juego antes de desinicializar el core y limpiar `ACTIVE_CTX`.
        unsafe {
            if self.game_loaded {
                (self.retro_unload_game)();
            }
            (self.retro_deinit)();
        }
        *lock_ctx() = None;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct CtxResetGuard;

    impl Drop for CtxResetGuard {
        fn drop(&mut self) {
            *lock_ctx() = None;
        }
    }

    #[test]
    fn active_ctx_is_accessible_across_threads_and_recovers_from_poison() {
        let _reset = CtxResetGuard;

        {
            let mut guard = lock_ctx();
            *guard = Some(CallbackContext {
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
}
