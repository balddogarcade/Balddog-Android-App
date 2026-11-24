use std::sync::{Mutex, MutexGuard};

use balddog_core::session::SessionControl;
use jni::objects::{JClass, JObject};
use jni::sys::jint;
use jni::JNIEnv;

use crate::clone_session_control;

struct ActiveSurface {
    window: *mut ndk_sys::ANativeWindow,
    width: u32,
    height: u32,
}

// SAFETY: `ANativeWindow*` tiene conteo de referencias thread-safe en Android NDK y su
// uso/liberación queda serializado por `SURFACE_STATE` y `DetachSurface` síncrono.
unsafe impl Send for ActiveSurface {}

static SURFACE_STATE: Mutex<Option<ActiveSurface>> = Mutex::new(None);

fn lock_surface_state() -> MutexGuard<'static, Option<ActiveSurface>> {
    match SURFACE_STATE.lock() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    }
}

fn detach_active_session_sync() {
    if let Some(ctrl) = clone_session_control() {
        ctrl.detach_surface_sync();
    }
}

pub(crate) fn attach_current_surface(ctrl: &SessionControl) {
    let guard = lock_surface_state();
    if let Some(active) = guard.as_ref() {
        ctrl.attach_surface(active.window as usize, active.width, active.height);
    }
}

#[no_mangle]
pub extern "system" fn Java_dog_bald_arcade_engine_NativeSurfaceBridge_attachSurface(
    env: JNIEnv,
    _class: JClass,
    surface: JObject,
    width: jint,
    height: jint,
) {
    // SAFETY: `env` y `surface` son objetos JNI válidos proporcionados por Android SurfaceHolder.
    let win = unsafe { ndk_sys::ANativeWindow_fromSurface(env.get_raw(), surface.as_raw()) };
    if win.is_null() {
        return;
    }

    let w = width.max(1) as u32;
    let h = height.max(1) as u32;

    let mut guard = lock_surface_state();
    if let Some(prev) = guard.take() {
        detach_active_session_sync();
        // SAFETY: `wgpu::Surface` ya fue destruida síncronamente y `prev.window` fue retenido.
        unsafe {
            ndk_sys::ANativeWindow_release(prev.window);
        }
    }

    *guard = Some(ActiveSurface {
        window: win,
        width: w,
        height: h,
    });

    if let Some(ctrl) = clone_session_control() {
        ctrl.attach_surface(win as usize, w, h);
    }
}

#[no_mangle]
pub extern "system" fn Java_dog_bald_arcade_engine_NativeSurfaceBridge_resizeSurface(
    _env: JNIEnv,
    _class: JClass,
    width: jint,
    height: jint,
) {
    let w = width.max(1) as u32;
    let h = height.max(1) as u32;
    let mut guard = lock_surface_state();
    if let Some(active) = guard.as_mut() {
        active.width = w;
        active.height = h;
    }
    if let Some(ctrl) = clone_session_control() {
        ctrl.resize_surface(w, h);
    }
}

#[no_mangle]
pub extern "system" fn Java_dog_bald_arcade_engine_NativeSurfaceBridge_detachSurface(
    _env: JNIEnv,
    _class: JClass,
) {
    let mut guard = lock_surface_state();
    detach_active_session_sync();
    if let Some(prev) = guard.take() {
        // SAFETY: `wgpu::Surface` ya fue destruida síncronamente en `detach_active_session_sync`.
        unsafe {
            ndk_sys::ANativeWindow_release(prev.window);
        }
    }
}
