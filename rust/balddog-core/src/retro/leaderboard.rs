//! Wrapper seguro en Rust sobre `rc_runtime` de `rcheevos` para evaluar leaderboards desde RAM.

use std::cell::RefCell;
use std::ffi::CString;
use std::os::raw::{c_char, c_int, c_void};

use crate::CoreError;

const RC_OK: c_int = 0;
const RC_RUNTIME_EVENT_LBOARD_STARTED: u8 = 5;
const RC_RUNTIME_EVENT_LBOARD_CANCELED: u8 = 6;
const RC_RUNTIME_EVENT_LBOARD_UPDATED: u8 = 7;
const RC_RUNTIME_EVENT_LBOARD_TRIGGERED: u8 = 8;

#[repr(C)]
struct RcRuntimeEvent {
    id: u32,
    value: i32,
    event_type: u8,
}

#[repr(C)]
struct RcRuntimeOpaque {
    _unused: [u8; 0],
}

type ReadMemoryFn =
    unsafe extern "C" fn(address: u32, buffer: *mut u8, num_bytes: u32, ud: *mut c_void) -> u32;
type EventHandlerFn = unsafe extern "C" fn(runtime_event: *const RcRuntimeEvent);

extern "C" {
    fn rc_runtime_alloc() -> *mut RcRuntimeOpaque;
    fn rc_runtime_destroy(runtime: *mut RcRuntimeOpaque);
    fn rc_runtime_activate_lboard(
        runtime: *mut RcRuntimeOpaque,
        id: u32,
        memaddr: *const c_char,
        unused_l: *mut c_void,
        unused_funcs_idx: c_int,
    ) -> c_int;
    fn rc_runtime_do_frame(
        runtime: *mut RcRuntimeOpaque,
        event_handler: EventHandlerFn,
        read_memory: ReadMemoryFn,
        ud: *mut c_void,
        unused_l: *mut c_void,
    );
    fn rc_runtime_reset(runtime: *mut RcRuntimeOpaque);
}

thread_local! {
    static FRAME_EVENTS: RefCell<Vec<(u8, i32)>> = const { RefCell::new(Vec::new()) };
}

unsafe extern "C" fn on_runtime_event(ev: *const RcRuntimeEvent) {
    if ev.is_null() {
        return;
    }
    let ev_ref = unsafe { &*ev };
    FRAME_EVENTS.with(|buf| {
        buf.borrow_mut().push((ev_ref.event_type, ev_ref.value));
    });
}

unsafe extern "C" fn on_read_memory(
    address: u32,
    buffer: *mut u8,
    num_bytes: u32,
    ud: *mut c_void,
) -> u32 {
    if buffer.is_null() || ud.is_null() || num_bytes == 0 {
        return 0;
    }
    let ram = unsafe { &*(ud as *const &[u8]) };
    let start = address as usize;
    let count = num_bytes as usize;
    let end = match start.checked_add(count) {
        Some(e) if e <= ram.len() => e,
        _ => {
            unsafe { std::ptr::write_bytes(buffer, 0, count) };
            return 0;
        }
    };
    unsafe {
        std::ptr::copy_nonoverlapping(ram[start..end].as_ptr(), buffer, count);
    }
    num_bytes
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct LeaderboardFrameOutput {
    pub active: bool,
    pub current_score: u64,
    pub triggered_score: Option<u64>,
}

/// Evaluador de un leaderboard `rcheevos` sobre `RETRO_MEMORY_SYSTEM_RAM`.
pub struct LeaderboardEngine {
    runtime: *mut RcRuntimeOpaque,
    active: bool,
    current_score: u64,
}

// SAFETY: `LeaderboardEngine` solo se usa en el hilo de emulación que lo posee.
unsafe impl Send for LeaderboardEngine {}

impl LeaderboardEngine {
    pub fn new(lboard_def: &str) -> Result<Self, CoreError> {
        let c_def = CString::new(lboard_def)
            .map_err(|e| CoreError::Init(format!("Definición de leaderboard inválida: {e}")))?;

        let runtime = unsafe { rc_runtime_alloc() };
        if runtime.is_null() {
            return Err(CoreError::Init("No se pudo asignar rc_runtime".into()));
        }

        let rc = unsafe {
            rc_runtime_activate_lboard(runtime, 1, c_def.as_ptr(), std::ptr::null_mut(), 0)
        };
        if rc != RC_OK {
            unsafe { rc_runtime_destroy(runtime) };
            return Err(CoreError::Init(format!(
                "Error activando leaderboard rcheevos (código {rc})"
            )));
        }

        Ok(Self {
            runtime,
            active: false,
            current_score: 0,
        })
    }

    pub fn reset(&mut self) {
        unsafe { rc_runtime_reset(self.runtime) };
        self.active = false;
        self.current_score = 0;
    }

    pub fn evaluate_frame(&mut self, system_ram: &[u8]) -> LeaderboardFrameOutput {
        FRAME_EVENTS.with(|buf| buf.borrow_mut().clear());

        let mut ram_slice: &[u8] = system_ram;
        let ud = (&mut ram_slice as *mut &[u8]) as *mut c_void;
        unsafe {
            rc_runtime_do_frame(
                self.runtime,
                on_runtime_event,
                on_read_memory,
                ud,
                std::ptr::null_mut(),
            );
        }

        let mut triggered_score = None;
        FRAME_EVENTS.with(|buf| {
            for (ev_type, value) in buf.borrow().iter().copied() {
                let score = value.max(0) as u64;
                match ev_type {
                    RC_RUNTIME_EVENT_LBOARD_STARTED => {
                        self.active = true;
                        self.current_score = score;
                    }
                    RC_RUNTIME_EVENT_LBOARD_UPDATED => {
                        self.current_score = score;
                    }
                    RC_RUNTIME_EVENT_LBOARD_TRIGGERED => {
                        self.active = false;
                        self.current_score = score;
                        triggered_score = Some(score);
                    }
                    RC_RUNTIME_EVENT_LBOARD_CANCELED => {
                        self.active = false;
                    }
                    _ => {}
                }
            }
        });

        LeaderboardFrameOutput {
            active: self.active,
            current_score: self.current_score,
            triggered_score,
        }
    }
}

impl Drop for LeaderboardEngine {
    fn drop(&mut self) {
        if !self.runtime.is_null() {
            unsafe { rc_runtime_destroy(self.runtime) };
            self.runtime = std::ptr::null_mut();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn evaluates_start_update_and_trigger_from_ram() {
        // STA cuando RAM[0]==1, CAN cuando RAM[0]==2, SUB cuando RAM[0]==3, VAL = uint16 LE en 0x04
        let mut engine =
            LeaderboardEngine::new("STA:0xH00=1::CAN:0xH00=2::SUB:0xH00=3::VAL:0x 04")
                .expect("debe parsear leaderboard válido");

        let mut ram = [0u8; 16];
        // Frame 1: estado idle
        let out = engine.evaluate_frame(&ram);
        assert!(!out.active);
        assert_eq!(out.triggered_score, None);

        // Frame 2: inicia partida con 250 puntos
        ram[0] = 1;
        ram[4] = 250;
        let out = engine.evaluate_frame(&ram);
        assert!(out.active);
        assert_eq!(out.current_score, 250);

        // Frame 3: sube puntaje a 500
        ram[0] = 0;
        ram[4] = 0xF4;
        ram[5] = 0x01;
        let out = engine.evaluate_frame(&ram);
        assert!(out.active);
        assert_eq!(out.current_score, 500);

        // Frame 4: Game Over -> submit
        ram[0] = 3;
        let out = engine.evaluate_frame(&ram);
        assert!(!out.active);
        assert_eq!(out.triggered_score, Some(500));
    }
}
