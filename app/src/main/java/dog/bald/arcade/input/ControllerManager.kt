package dog.bald.arcade.input

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs

/**
 * Gestiona hasta 4 mandos Bluetooth/USB simultáneos y mantiene las máscaras de entrada por puerto.
 */
class ControllerManager(
    context: Context,
    private val onControllerPresenceChanged: (Boolean) -> Unit,
    private val onPortMaskChanged: (port: Int, mask: Int) -> Unit
) {
    private val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
    private val deviceToPort = mutableMapOf<Int, Int>()
    private val portKeyMasks = IntArray(4)
    private val portAxisMasks = IntArray(4)

    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = refreshDevices()
        override fun onInputDeviceRemoved(deviceId: Int) = refreshDevices()
        override fun onInputDeviceChanged(deviceId: Int) = refreshDevices()
    }

    fun start() {
        inputManager.registerInputDeviceListener(deviceListener, null)
        refreshDevices()
    }

    fun stop() {
        inputManager.unregisterInputDeviceListener(deviceListener)
    }

    fun refreshDevices() {
        val prevCount = deviceToPort.size
        deviceToPort.clear()
        var nextPort = 0
        for (id in InputDevice.getDeviceIds()) {
            val dev = InputDevice.getDevice(id) ?: continue
            if (!dev.isEnabled || dev.isVirtual) continue
            val sources = dev.sources
            val hasGamepad = (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
            val hasJoystick = (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK &&
                dev.getMotionRange(MotionEvent.AXIS_X) != null &&
                dev.getMotionRange(MotionEvent.AXIS_Y) != null
            if ((hasGamepad || hasJoystick) && nextPort < 4) {
                deviceToPort[id] = nextPort
                nextPort++
            }
        }
        val hasAny = deviceToPort.isNotEmpty()
        if ((prevCount > 0) != hasAny || prevCount != deviceToPort.size) {
            onControllerPresenceChanged(hasAny)
        }
    }

    fun hasPhysicalController(): Boolean = deviceToPort.isNotEmpty()

    fun handleKeyEvent(event: KeyEvent): Boolean {
        val port = deviceToPort[event.deviceId] ?: return false
        val bit = mapKeyCodeToBit(event.keyCode)
        if (bit == 0) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> portKeyMasks[port] = portKeyMasks[port] or bit
            KeyEvent.ACTION_UP -> portKeyMasks[port] = portKeyMasks[port] and bit.inv()
            else -> return true
        }
        emitCombined(port)
        return true
    }

    fun handleMotionEvent(event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK &&
            (event.source and InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD
        ) {
            return false
        }
        val port = deviceToPort[event.deviceId] ?: return false
        var axisMask = 0

        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val stickX = event.getAxisValue(MotionEvent.AXIS_X)
        val stickY = event.getAxisValue(MotionEvent.AXIS_Y)

        val x = if (abs(hatX) > 0.4f) hatX else stickX
        val y = if (abs(hatY) > 0.4f) hatY else stickY

        if (x < -0.35f) axisMask = axisMask or RetroJoypad.LEFT
        if (x > 0.35f) axisMask = axisMask or RetroJoypad.RIGHT
        if (y < -0.35f) axisMask = axisMask or RetroJoypad.UP
        if (y > 0.35f) axisMask = axisMask or RetroJoypad.DOWN

        if (portAxisMasks[port] != axisMask) {
            portAxisMasks[port] = axisMask
            emitCombined(port)
        }
        return true
    }

    private fun emitCombined(port: Int) {
        onPortMaskChanged(port, portKeyMasks[port] or portAxisMasks[port])
    }

    private fun mapKeyCodeToBit(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> RetroJoypad.B
        KeyEvent.KEYCODE_BUTTON_B -> RetroJoypad.A
        KeyEvent.KEYCODE_BUTTON_X -> RetroJoypad.Y
        KeyEvent.KEYCODE_BUTTON_Y -> RetroJoypad.X
        KeyEvent.KEYCODE_BUTTON_L1 -> RetroJoypad.L
        KeyEvent.KEYCODE_BUTTON_R1 -> RetroJoypad.R
        KeyEvent.KEYCODE_BUTTON_START -> RetroJoypad.START
        KeyEvent.KEYCODE_BUTTON_SELECT,
        KeyEvent.KEYCODE_BUTTON_MODE -> RetroJoypad.COIN
        KeyEvent.KEYCODE_DPAD_UP -> RetroJoypad.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> RetroJoypad.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> RetroJoypad.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> RetroJoypad.RIGHT
        else -> 0
    }
}
