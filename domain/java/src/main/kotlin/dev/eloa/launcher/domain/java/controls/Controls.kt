package dev.eloa.launcher.domain.java.controls

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * Game actions a control can send. Names follow GLFW key/mouse naming so a runtime backend can
 * translate them 1:1 into LWJGL input events.
 */
object GameKeys {
    val ALL = listOf(
        "KEY_W", "KEY_A", "KEY_S", "KEY_D", "KEY_SPACE", "KEY_LEFT_SHIFT", "KEY_LEFT_CONTROL", "KEY_E", "KEY_Q", "KEY_F",
        "KEY_T", "KEY_SLASH", "KEY_TAB", "KEY_ESCAPE", "KEY_F1", "KEY_F3", "KEY_F5", "KEY_1", "KEY_2", "KEY_3", "KEY_4",
        "KEY_5", "KEY_6", "KEY_7", "KEY_8", "KEY_9", "KEY_ENTER", "KEY_L", "KEY_C", "KEY_X", "KEY_Z",
        "MOUSE_LEFT", "MOUSE_RIGHT", "MOUSE_MIDDLE", "SCROLL_UP", "SCROLL_DOWN",
        // Launcher-side actions
        "SPECIAL_KEYBOARD", "SPECIAL_TOGGLE_CONTROLS", "SPECIAL_MOUSE_MODE",
    )
    fun isValid(k: String) = k in ALL
}

enum class ControlShape { ROUND, ROUNDED_RECT }
enum class ControlKind { BUTTON, JOYSTICK }

@Serializable
data class ControlButton(
    val id: String,
    val label: String,
    val keys: List<String>,
    /** Centre position, normalised 0..1 to the screen so layouts survive any resolution/aspect ratio. */
    val x: Float,
    val y: Float,
    /** Size in dp (multiplied by the layout scale). */
    val sizeDp: Float = 56f,
    val widthFactor: Float = 1f,
    val opacity: Float = 0.7f,
    val toggle: Boolean = false,
    val shape: ControlShape = ControlShape.ROUND,
    val kind: ControlKind = ControlKind.BUTTON,
)

@Serializable
data class ControlLayout(
    val name: String,
    val preset: String = "CUSTOM",
    val buttons: List<ControlButton>,
    val scale: Float = 1f,
    /** Camera look sensitivity for touch drags, 0.1..3. */
    val lookSensitivity: Float = 1f,
    val globalOpacity: Float = 1f,
    val formatVersion: Int = 1,
)

enum class ControlPreset { CLASSIC, COMPACT, PVP, SURVIVAL, CONTROLLER_LIKE, CUSTOM }

data class PixelRect(val left: Float, val top: Float, val width: Float, val height: Float)

class InvalidLayoutException(message: String) : IOException(message)

object ControlLayouts {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    const val MAX_BUTTONS = 64

    private fun b(id: String, label: String, key: String, x: Float, y: Float, size: Float = 56f, kind: ControlKind = ControlKind.BUTTON, toggle: Boolean = false, w: Float = 1f) =
        ControlButton(id, label, listOf(key), x, y, size, w, toggle = toggle, kind = kind, shape = if (w > 1f) ControlShape.ROUNDED_RECT else ControlShape.ROUND)

    fun preset(p: ControlPreset): ControlLayout = when (p) {
        ControlPreset.CLASSIC -> ControlLayout("Classic", p.name, listOf(
            b("move", "", "KEY_W", 0.14f, 0.72f, 150f, ControlKind.JOYSTICK),
            b("jump", "⤒", "KEY_SPACE", 0.90f, 0.72f, 64f),
            b("sneak", "⇩", "KEY_LEFT_SHIFT", 0.80f, 0.86f, 56f, toggle = true),
            b("attack", "L", "MOUSE_LEFT", 0.90f, 0.50f, 60f),
            b("use", "R", "MOUSE_RIGHT", 0.80f, 0.60f, 60f),
            b("inv", "E", "KEY_E", 0.95f, 0.10f, 44f),
            b("chat", "T", "KEY_T", 0.05f, 0.10f, 44f),
            b("esc", "☰", "KEY_ESCAPE", 0.50f, 0.06f, 44f),
            b("kbd", "Kb", "SPECIAL_KEYBOARD", 0.12f, 0.10f, 44f),
            b("drop", "Q", "KEY_Q", 0.88f, 0.10f, 44f),
            b("persp", "F5", "KEY_F5", 0.81f, 0.10f, 44f),
        ))
        ControlPreset.COMPACT -> ControlLayout("Compact", p.name, listOf(
            b("move", "", "KEY_W", 0.12f, 0.76f, 120f, ControlKind.JOYSTICK),
            b("jump", "⤒", "KEY_SPACE", 0.92f, 0.78f, 52f),
            b("sneak", "⇩", "KEY_LEFT_SHIFT", 0.84f, 0.88f, 46f, toggle = true),
            b("inv", "E", "KEY_E", 0.95f, 0.08f, 38f),
            b("esc", "☰", "KEY_ESCAPE", 0.05f, 0.08f, 38f),
        ), scale = 0.85f)
        ControlPreset.PVP -> ControlLayout("PVP", p.name, listOf(
            b("move", "", "KEY_W", 0.13f, 0.72f, 140f, ControlKind.JOYSTICK),
            b("attack", "L", "MOUSE_LEFT", 0.88f, 0.48f, 72f),
            b("use", "R", "MOUSE_RIGHT", 0.76f, 0.62f, 60f),
            b("jump", "⤒", "KEY_SPACE", 0.91f, 0.76f, 64f),
            b("sprint", "»", "KEY_LEFT_CONTROL", 0.78f, 0.84f, 52f, toggle = true),
            b("sneak", "⇩", "KEY_LEFT_SHIFT", 0.66f, 0.88f, 48f, toggle = true),
            b("hot1", "1", "KEY_1", 0.40f, 0.92f, 40f), b("hot2", "2", "KEY_2", 0.47f, 0.92f, 40f), b("hot3", "3", "KEY_3", 0.54f, 0.92f, 40f),
            b("esc", "☰", "KEY_ESCAPE", 0.50f, 0.06f, 40f),
        ))
        ControlPreset.SURVIVAL -> ControlLayout("Survival", p.name, preset(ControlPreset.CLASSIC).buttons + listOf(
            b("sprint", "»", "KEY_LEFT_CONTROL", 0.70f, 0.86f, 52f, toggle = true),
            b("scrollUp", "▲", "SCROLL_UP", 0.97f, 0.35f, 38f), b("scrollDown", "▼", "SCROLL_DOWN", 0.97f, 0.45f, 38f),
            b("map", "⌖", "KEY_TAB", 0.74f, 0.10f, 44f),
        ))
        ControlPreset.CONTROLLER_LIKE -> ControlLayout("Controller-like", p.name, listOf(
            b("move", "", "KEY_W", 0.15f, 0.65f, 140f, ControlKind.JOYSTICK),
            b("a", "A", "KEY_SPACE", 0.88f, 0.78f, 56f), b("b", "B", "KEY_LEFT_SHIFT", 0.95f, 0.64f, 56f, toggle = true),
            b("x", "X", "KEY_E", 0.81f, 0.64f, 56f), b("y", "Y", "KEY_Q", 0.88f, 0.50f, 56f),
            b("lt", "LT", "MOUSE_RIGHT", 0.08f, 0.15f, 52f, w = 1.6f), b("rt", "RT", "MOUSE_LEFT", 0.92f, 0.15f, 52f, w = 1.6f),
            b("start", "☰", "KEY_ESCAPE", 0.56f, 0.10f, 40f), b("select", "Kb", "SPECIAL_KEYBOARD", 0.44f, 0.10f, 40f),
        ))
        ControlPreset.CUSTOM -> ControlLayout("Custom", p.name, listOf(b("move", "", "KEY_W", 0.14f, 0.72f, 140f, ControlKind.JOYSTICK)))
    }

    fun validate(l: ControlLayout): ControlLayout {
        if (l.formatVersion != 1) throw InvalidLayoutException("Unsupported layout format ${l.formatVersion}")
        if (l.name.isBlank() || l.name.length > 60) throw InvalidLayoutException("Invalid layout name")
        if (l.buttons.size > MAX_BUTTONS) throw InvalidLayoutException("Too many controls (max $MAX_BUTTONS)")
        if (l.buttons.map { it.id }.toSet().size != l.buttons.size) throw InvalidLayoutException("Duplicate control ids")
        l.buttons.forEach { bt ->
            if (bt.keys.isEmpty() || bt.keys.any { !GameKeys.isValid(it) }) throw InvalidLayoutException("Control '${bt.id}' has an unknown key")
            if (bt.label.length > 12) throw InvalidLayoutException("Label of '${bt.id}' is too long")
        }
        return l.copy(
            scale = l.scale.coerceIn(0.5f, 2f), lookSensitivity = l.lookSensitivity.coerceIn(0.1f, 3f), globalOpacity = l.globalOpacity.coerceIn(0.1f, 1f),
            buttons = l.buttons.map { it.copy(x = it.x.coerceIn(0f, 1f), y = it.y.coerceIn(0f, 1f), sizeDp = it.sizeDp.coerceIn(24f, 220f), widthFactor = it.widthFactor.coerceIn(0.5f, 4f), opacity = it.opacity.coerceIn(0.05f, 1f)) },
        )
    }

    fun encode(l: ControlLayout): String = json.encodeToString(ControlLayout.serializer(), validate(l))

    fun decode(text: String): ControlLayout {
        if (text.length > 512 * 1024) throw InvalidLayoutException("Layout file is too large")
        val parsed = try { json.decodeFromString(ControlLayout.serializer(), text) } catch (e: Exception) { throw InvalidLayoutException("Not a control layout: ${e.message?.take(120)}") }
        return validate(parsed)
    }

    /** Pixel rectangle of a control on a concrete screen; always kept fully inside the screen. */
    fun place(bt: ControlButton, layoutScale: Float, screenW: Float, screenH: Float, density: Float): PixelRect {
        val h = bt.sizeDp * layoutScale * density
        val w = h * bt.widthFactor
        val left = (bt.x * screenW - w / 2).coerceIn(0f, (screenW - w).coerceAtLeast(0f))
        val top = (bt.y * screenH - h / 2).coerceIn(0f, (screenH - h).coerceAtLeast(0f))
        return PixelRect(left, top, w, h)
    }

    /** Inverse of [place]: stores a dragged position back as normalised centre. */
    fun normalise(centerX: Float, centerY: Float, screenW: Float, screenH: Float) = (centerX / screenW).coerceIn(0f, 1f) to (centerY / screenH).coerceIn(0f, 1f)
}

/** Per-controller mapping. Android key code names (e.g. BUTTON_A) → [GameKeys]. */
@Serializable
data class GamepadProfile(
    val descriptor: String,
    val name: String,
    val mapping: Map<String, String> = DEFAULT_MAPPING,
    val deadzone: Float = 0.15f,
    val lookSensitivity: Float = 1f,
    val invertY: Boolean = false,
) {
    companion object {
        val DEFAULT_MAPPING = linkedMapOf(
            "BUTTON_A" to "KEY_SPACE", "BUTTON_B" to "KEY_LEFT_SHIFT", "BUTTON_X" to "KEY_E", "BUTTON_Y" to "KEY_Q",
            "BUTTON_L1" to "SCROLL_UP", "BUTTON_R1" to "SCROLL_DOWN", "BUTTON_L2" to "MOUSE_RIGHT", "BUTTON_R2" to "MOUSE_LEFT",
            "BUTTON_THUMBL" to "KEY_LEFT_CONTROL", "BUTTON_THUMBR" to "KEY_F5", "BUTTON_START" to "KEY_ESCAPE", "BUTTON_SELECT" to "KEY_TAB",
            "DPAD_UP" to "KEY_F5", "DPAD_DOWN" to "KEY_Q", "DPAD_LEFT" to "SCROLL_UP", "DPAD_RIGHT" to "SCROLL_DOWN",
        )
        val BUTTONS = DEFAULT_MAPPING.keys.toList()
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun encode(p: GamepadProfile) = json.encodeToString(serializer(), p.validated())
        fun decode(text: String): GamepadProfile = try { json.decodeFromString(serializer(), text).validated() } catch (e: InvalidLayoutException) { throw e } catch (e: Exception) { throw InvalidLayoutException("Not a gamepad profile") }
    }

    fun validated(): GamepadProfile {
        if (mapping.values.any { !GameKeys.isValid(it) }) throw InvalidLayoutException("Unknown action in gamepad mapping")
        return copy(deadzone = deadzone.coerceIn(0f, 0.6f), lookSensitivity = lookSensitivity.coerceIn(0.1f, 3f), name = name.take(80))
    }

    /** Applies the dead zone to a stick axis value (-1..1), rescaling the remaining range. */
    fun applyDeadzone(v: Float): Float {
        val a = kotlin.math.abs(v)
        if (a < deadzone) return 0f
        return kotlin.math.sign(v) * ((a - deadzone) / (1f - deadzone)).coerceIn(0f, 1f)
    }
}
