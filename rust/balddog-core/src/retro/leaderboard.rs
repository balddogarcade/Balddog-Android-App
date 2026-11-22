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

    #[test]
    fn evaluates_captcomm_bcd_word_swapped_leaderboard() {
        let mut engine = LeaderboardEngine::new(
            "STA:0xHA855=1_0xHAA7D>0::CAN:0=1::SUB:0xHA855=0::VAL:A:b0x AA74*10000_M:b0x AA76",
        )
        .expect("captcomm lboard válido");

        let mut ram = vec![0u8; 0x10000];
        // Boot idle
        let out = engine.evaluate_frame(&ram);
        assert!(!out.active);

        // Stage 1 active: P1 active=1, lives=2, score=12,340 -> [0x01, 0x00, 0x40, 0x23]
        ram[0xA855] = 1;
        ram[0xAA7D] = 2;
        ram[0xAA74] = 0x01;
        ram[0xAA75] = 0x00;
        ram[0xAA76] = 0x40;
        ram[0xAA77] = 0x23;
        let out = engine.evaluate_frame(&ram);
        assert!(out.active);
        assert_eq!(out.current_score, 12340);

        // Game Over: P1 active=0
        ram[0xA855] = 0;
        let out = engine.evaluate_frame(&ram);
        assert!(!out.active);
        assert_eq!(out.triggered_score, Some(12340));
    }

    #[test]
    fn parses_all_cps_family_leaderboard_expressions() {
        let defs = [
            "STA:0xHB345=1::CAN:0=1::SUB:0xHB345=0::VAL:A:b0x B374*10000_M:b0x B376",
            "STA:0xH8568=1::CAN:0=1::SUB:0xH8568=0::VAL:A:b0x 85EC*10000_M:b0x 85EE",
            "STA:0xHAC54=1::CAN:0=1::SUB:0xHAC54=0::VAL:A:b0x AC8C*10000_M:b0x AC8E",
            "STA:0xH125A=1::CAN:0=1::SUB:0xH125A=0::VAL:A:b0x 12C2*10000_M:b0x 12C4",
            "STA:0xH5AE1=1::CAN:0=1::SUB:0xH5AE1=0::VAL:A:b0x 5BAA*10000_M:b0x 5BAC",
            "STA:0xH86C8=1::CAN:0=1::SUB:0xH86C8=0::VAL:A:b0x 8748*10000_M:b0x 874A",
            "STA:0xH8155=1::CAN:0=1::SUB:0xH8155=0::VAL:A:b0x 81B6*10000_M:b0x 81B8",
            "STA:0xH0036=1::CAN:0=1::SUB:0xH0036=0::VAL:A:0x 03FC*65536_M:0x 03FE",
            "STA:0xH004C=1::CAN:0=1::SUB:0xH004C=0::VAL:A:b0x 0206*10000_M:b0x 0208",
            "STA:0xH0820=1::CAN:0=1::SUB:0xH0820=0::VAL:A:b0x 2000*10000_M:b0x 2002",
            "STA:0xH000B=1::CAN:0=1::SUB:0xH000B=0::VAL:A:b0x 62CE*10000_M:b0x 62D0",
            "STA:0xH2453=1::CAN:0=1::SUB:0xH2453=0::VAL:A:b0x 135B2*10000_M:b0x 135B4",
            "STA:0xH834A=1::CAN:0=1::SUB:0xH834A=0::VAL:A:b0x 834C*10000_M:b0x 834E",
            "STA:0xH040D=1::CAN:0=1::SUB:0xH040D=0::VAL:A:b0x 3D02*10000_M:b0x 3D04",
            "STA:0xH02D4=1::CAN:0=1::SUB:0xH02D4=0::VAL:A:b0x 07F2*10000_M:b0x 07F4",
            "STA:0xH2137=1::CAN:0=1::SUB:0xH2137=0::VAL:A:b0xH14E37*1000000_A:b0xH14E36*10000_A:b0xH14E35*100_M:b0xH14E34",
            "STA:0xH000F=1::CAN:0=1::SUB:0xH000F=0::VAL:A:b0xH03EE*1000000_A:b0xH03ED*10000_A:b0xH03EC*100_M:b0xH03EB",
            "STA:0xH1050=1::CAN:0=1::SUB:0xH1050=0::VAL:0xX1B04",
            "STA:0xH04A1=1::CAN:0=1::SUB:0xH04A1=0::VAL:A:b0x 3214*10000_M:b0x 3216",
            "STA:0xHED5C=1::CAN:0=1::SUB:0xHED5C=0::VAL:A:b0x ED18*10000_M:b0x ED1A",
            "STA:0xH0053=1::CAN:0=1::SUB:0xH0053=0::VAL:A:b0x 0810*10000_M:b0x 0812",
        ];
        for def in defs {
            LeaderboardEngine::new(def).unwrap_or_else(|e| panic!("Falló {def}: {e}"));
        }
    }
}
