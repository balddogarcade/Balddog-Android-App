use std::sync::atomic::{AtomicUsize, Ordering};

use jni::objects::{JClass, JObject};
use jni::sys::jint;
use jni::JNIEnv;

use crate::with_active_session;

static CURRENT_WINDOW: AtomicUsize = AtomicUsize::new(0);

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

    let prev = CURRENT_WINDOW.swap(win as usize, Ordering::AcqRel);
    if prev != 0 {
        with_active_session(|s| s.detach_surface_sync());
        // SAFETY: `prev` fue retenido previamente con `ANativeWindow_fromSurface`.
        unsafe {
            ndk_sys::ANativeWindow_release(prev as *mut ndk_sys::ANativeWindow);
        }
    }

    let w = width.max(1) as u32;
    let h = height.max(1) as u32;
    with_active_session(|s| s.attach_surface(win as usize, w, h));
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
    with_active_session(|s| s.resize_surface(w, h));
}

#[no_mangle]
pub extern "system" fn Java_dog_bald_arcade_engine_NativeSurfaceBridge_detachSurface(
    _env: JNIEnv,
    _class: JClass,
) {
    with_active_session(|s| s.detach_surface_sync());
    let prev = CURRENT_WINDOW.swap(0, Ordering::AcqRel);
    if prev != 0 {
        // SAFETY: `wgpu::Surface` ya fue destruida síncronamente en `detach_surface_sync`.
        unsafe {
            ndk_sys::ANativeWindow_release(prev as *mut ndk_sys::ANativeWindow);
        }
    }
}
