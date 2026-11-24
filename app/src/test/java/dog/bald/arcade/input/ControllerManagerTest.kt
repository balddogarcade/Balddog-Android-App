package dog.bald.arcade.input

import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerManagerTest {

    private class FakeDeviceBackend(
        var activeIds: List<Int> = emptyList()
    ) : ControllerManager.DeviceBackend {
        var registeredListener: InputManager.InputDeviceListener? = null
            private set
        var unregisteredCount: Int = 0
            private set

        override fun registerListener(listener: InputManager.InputDeviceListener) {
            registeredListener = listener
        }

        override fun unregisterListener(listener: InputManager.InputDeviceListener) {
            if (registeredListener === listener) {
                registeredListener = null
            }
            unregisteredCount++
        }

        override fun getActiveGamepadDeviceIds(): List<Int> = activeIds
    }

    @Test
    fun disconnectingDeviceResetsPortKeyAndAxisMasksAndPresence() {
        val backend = FakeDeviceBackend(listOf(10))
        val presenceEvents = mutableListOf<Boolean>()
        val maskEvents = mutableListOf<Pair<Int, Int>>()

        val manager = ControllerManager(
            onControllerPresenceChanged = { presenceEvents += it },
            onPortMaskChanged = { port, mask -> maskEvents += port to mask },
            backend = backend
        )

        manager.start()
        assertTrue(manager.hasPhysicalController())
        assertEquals(listOf(true), presenceEvents)
        assertEquals(listOf(0 to 0), maskEvents)

        maskEvents.clear()
        assertTrue(manager.applyKeyState(deviceId = 10, keyCode = KeyEvent.KEYCODE_BUTTON_A, action = KeyEvent.ACTION_DOWN))
        assertTrue(
            manager.applyAxisState(
                deviceId = 10,
                source = InputDevice.SOURCE_JOYSTICK,
                hatX = 0f,
                hatY = 0f,
                stickX = -0.9f,
                stickY = 0f
            )
        )
        assertEquals(
            listOf(
                0 to RetroJoypad.B,
                0 to (RetroJoypad.B or RetroJoypad.LEFT)
            ),
            maskEvents
        )

        maskEvents.clear()
        backend.activeIds = emptyList()
        backend.registeredListener?.onInputDeviceRemoved(10)

        assertFalse(manager.hasPhysicalController())
        assertEquals(listOf(true, false), presenceEvents)
        assertEquals(listOf(0 to 0), maskEvents)
    }

    @Test
    fun portShiftResetsChangedPortsWithoutClearingUnchangedPorts() {
        val backend = FakeDeviceBackend(listOf(10, 20))
        val maskEvents = mutableListOf<Pair<Int, Int>>()

        val manager = ControllerManager(
            onControllerPresenceChanged = {},
            onPortMaskChanged = { port, mask -> maskEvents += port to mask },
            backend = backend
        )

        manager.start()
        assertTrue(manager.applyKeyState(10, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.ACTION_DOWN))
        assertTrue(manager.applyKeyState(20, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_DOWN))
        maskEvents.clear()

        // Adding a 3rd controller keeps devices 10 & 20 on ports 0 & 1 intact
        backend.activeIds = listOf(10, 20, 30)
        manager.refreshDevices()
        assertEquals(listOf(2 to 0), maskEvents)

        maskEvents.clear()
        // Removing device 10 shifts 20 -> port 0, 30 -> port 1, and leaves port 2 empty
        backend.activeIds = listOf(20, 30)
        manager.refreshDevices()
        assertEquals(listOf(0 to 0, 1 to 0, 2 to 0), maskEvents)

        // Subsequent input from device 20 affects port 0 without stale START bit from device 10
        maskEvents.clear()
        assertTrue(manager.applyKeyState(20, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.ACTION_DOWN))
        assertEquals(listOf(0 to RetroJoypad.A), maskEvents)
    }

    @Test
    fun stopUnregistersListenerAndClearsAllPortMasks() {
        val backend = FakeDeviceBackend(listOf(10))
        val presenceEvents = mutableListOf<Boolean>()
        val maskEvents = mutableListOf<Pair<Int, Int>>()

        val manager = ControllerManager(
            onControllerPresenceChanged = { presenceEvents += it },
            onPortMaskChanged = { port, mask -> maskEvents += port to mask },
            backend = backend
        )

        manager.start()
        assertTrue(manager.applyKeyState(10, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.ACTION_DOWN))
        maskEvents.clear()

        manager.stop()

        assertEquals(1, backend.unregisteredCount)
        assertFalse(manager.hasPhysicalController())
        assertEquals(listOf(0 to 0), maskEvents)
        assertEquals(listOf(true, false), presenceEvents)

        // Idempotent second stop
        maskEvents.clear()
        manager.stop()
        assertTrue(maskEvents.isEmpty())
    }
}
