package dog.bald.arcade.input

/**
 * Máscaras de bits estándar de Libretro Joypad (deben coincidir con `retro/types.rs`).
 */
object RetroJoypad {
    const val B = 1 shl 0
    const val Y = 1 shl 1
    const val COIN = 1 shl 2 // RETRO_DEVICE_ID_JOYPAD_SELECT
    const val START = 1 shl 3
    const val UP = 1 shl 4
    const val DOWN = 1 shl 5
    const val LEFT = 1 shl 6
    const val RIGHT = 1 shl 7
    const val A = 1 shl 8
    const val X = 1 shl 9
    const val L = 1 shl 10
    const val R = 1 shl 11
}
