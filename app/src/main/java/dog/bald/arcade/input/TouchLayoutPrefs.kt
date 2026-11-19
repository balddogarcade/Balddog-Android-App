package dog.bald.arcade.input

import android.content.Context
import androidx.compose.runtime.Immutable

@Immutable
data class TouchLayoutConfig(
    val opacity: Float = 0.65f,
    val scale: Float = 1.0f,
    val dpadOffsetX: Float = 0f,
    val dpadOffsetY: Float = 0f,
    val buttonsOffsetX: Float = 0f,
    val buttonsOffsetY: Float = 0f,
    val forceShowTouch: Boolean = true
)

object TouchLayoutPrefs {
    private const val PREFS_NAME = "balddog_touch_layout"
    private const val KEY_OPACITY = "opacity"
    private const val KEY_SCALE = "scale"
    private const val KEY_DPAD_X = "dpad_x"
    private const val KEY_DPAD_Y = "dpad_y"
    private const val KEY_BTN_X = "btn_x"
    private const val KEY_BTN_Y = "btn_y"
    private const val KEY_FORCE_SHOW = "force_show"

    fun load(context: Context): TouchLayoutConfig {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return TouchLayoutConfig(
            opacity = p.getFloat(KEY_OPACITY, 0.65f).coerceIn(0.2f, 1.0f),
            scale = p.getFloat(KEY_SCALE, 1.0f).coerceIn(0.75f, 1.4f),
            dpadOffsetX = p.getFloat(KEY_DPAD_X, 0f),
            dpadOffsetY = p.getFloat(KEY_DPAD_Y, 0f),
            buttonsOffsetX = p.getFloat(KEY_BTN_X, 0f),
            buttonsOffsetY = p.getFloat(KEY_BTN_Y, 0f),
            forceShowTouch = p.getBoolean(KEY_FORCE_SHOW, true)
        )
    }

    fun save(context: Context, config: TouchLayoutConfig) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_OPACITY, config.opacity)
            .putFloat(KEY_SCALE, config.scale)
            .putFloat(KEY_DPAD_X, config.dpadOffsetX)
            .putFloat(KEY_DPAD_Y, config.dpadOffsetY)
            .putFloat(KEY_BTN_X, config.buttonsOffsetX)
            .putFloat(KEY_BTN_Y, config.buttonsOffsetY)
            .putBoolean(KEY_FORCE_SHOW, config.forceShowTouch)
            .apply()
    }
}
