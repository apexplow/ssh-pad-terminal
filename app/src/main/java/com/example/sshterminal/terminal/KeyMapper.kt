package com.example.sshterminal.terminal

import android.view.KeyEvent

/**
 * Verdict for a single physical-key event.
 *
 * - [Send]: forward these bytes to the SSH channel (and consume the event).
 * - [Swallow]: the event must be consumed but NOT forwarded — used for keys that
 *   belong to the IME (language switch, IME toggle). This is distinct from
 *   [Ignore] because the View must still return `true` from `onKeyDown` so the
 *   system stops dispatching the event to the IME.
 * - [Ignore]: no opinion — let the View return `false` so the system routes the
 *   event to InputConnection (printable characters) or whatever default handler.
 */
sealed class KeyResolution {
    data class Send(val bytes: ByteArray) : KeyResolution()
    data object Swallow : KeyResolution()
    data object Ignore : KeyResolution()
}

/**
 * Maps a [KeyEvent] to a [KeyResolution] for the SSH terminal.
 *
 * Per `implementation_plan.md` §"KeyEvent 路由规则表":
 *
 *  - Printable characters without Ctrl/Alt → [Ignore] (let InputConnection handle).
 *  - Ctrl / Alt / function / arrow keys → [Send] with the corresponding ANSI sequence.
 *  - Ctrl+Space, Shift+Space, KEYCODE_LANGUAGE_SWITCH → [Swallow] (IME-internal,
 *    MUST NOT leak to the SSH channel — see spec P0).
 *  - Ctrl+C / Ctrl+D / Ctrl+Z / Ctrl+[ / Escape → [Send] of the control byte.
 */
object KeyMapper {
    fun resolve(keyCode: Int, event: KeyEvent): KeyResolution {
        // IME-internal shortcuts: never transmit, always consume. These don't fit
        // the printable-character short-circuit below because they ARE modifier-
        // bearing events the system would otherwise happily route somewhere.
        if (isImeLanguageSwitch(event) || keyCode == KeyEvent.KEYCODE_LANGUAGE_SWITCH) {
            return KeyResolution.Swallow
        }

        if (event.isCtrlPressed) {
            ctrlSequence(keyCode)?.let { return KeyResolution.Send(it) }
        }

        if (event.isAltPressed && event.unicodeChar > 0) {
            val payload = event.unicodeChar.toChar().toString().toByteArray(Charsets.UTF_8)
            return KeyResolution.Send(byteArrayOf(0x1B) + payload)
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_DEL -> KeyResolution.Send(byteArrayOf(0x7F.toByte()))
            KeyEvent.KEYCODE_ENTER -> KeyResolution.Send("\r".toByteArray(Charsets.UTF_8))
            KeyEvent.KEYCODE_TAB -> KeyResolution.Send("\t".toByteArray(Charsets.UTF_8))
            KeyEvent.KEYCODE_DPAD_UP -> KeyResolution.Send("\u001B[A".toByteArray(Charsets.UTF_8))
            KeyEvent.KEYCODE_DPAD_DOWN -> KeyResolution.Send("\u001B[B".toByteArray(Charsets.UTF_8))
            KeyEvent.KEYCODE_DPAD_RIGHT -> KeyResolution.Send("\u001B[C".toByteArray(Charsets.UTF_8))
            KeyEvent.KEYCODE_DPAD_LEFT -> KeyResolution.Send("\u001B[D".toByteArray(Charsets.UTF_8))
            KeyEvent.KEYCODE_MOVE_HOME -> KeyResolution.Send("\u001B[H".toByteArray(Charsets.UTF_8))
            KeyEvent.KEYCODE_MOVE_END -> KeyResolution.Send("\u001B[F".toByteArray(Charsets.UTF_8))
            KeyEvent.KEYCODE_PAGE_UP -> KeyResolution.Send("\u001B[5~".toByteArray(Charsets.UTF_8))
            KeyEvent.KEYCODE_PAGE_DOWN -> KeyResolution.Send("\u001B[6~".toByteArray(Charsets.UTF_8))
            KeyEvent.KEYCODE_FORWARD_DEL -> KeyResolution.Send("\u001B[3~".toByteArray(Charsets.UTF_8))
            KeyEvent.KEYCODE_F1 -> KeyResolution.Send("\u001BOP".toByteArray(Charsets.UTF_8))
            KeyEvent.KEYCODE_F2 -> KeyResolution.Send("\u001BOQ".toByteArray(Charsets.UTF_8))
            KeyEvent.KEYCODE_F3 -> KeyResolution.Send("\u001BOR".toByteArray(Charsets.UTF_8))
            KeyEvent.KEYCODE_F4 -> KeyResolution.Send("\u001BOS".toByteArray(Charsets.UTF_8))
            KeyEvent.KEYCODE_F5 -> KeyResolution.Send("\u001B[15~".toByteArray(Charsets.UTF_8))
            KeyEvent.KEYCODE_F6 -> KeyResolution.Send("\u001B[17~".toByteArray(Charsets.UTF_8))
            KeyEvent.KEYCODE_F7 -> KeyResolution.Send("\u001B[18~".toByteArray(Charsets.UTF_8))
            KeyEvent.KEYCODE_F8 -> KeyResolution.Send("\u001B[19~".toByteArray(Charsets.UTF_8))
            KeyEvent.KEYCODE_F9 -> KeyResolution.Send("\u001B[20~".toByteArray(Charsets.UTF_8))
            KeyEvent.KEYCODE_F10 -> KeyResolution.Send("\u001B[21~".toByteArray(Charsets.UTF_8))
            KeyEvent.KEYCODE_F11 -> KeyResolution.Send("\u001B[23~".toByteArray(Charsets.UTF_8))
            KeyEvent.KEYCODE_F12 -> KeyResolution.Send("\u001B[24~".toByteArray(Charsets.UTF_8))
            // Escape is handled as a control byte (0x1B) by ctrlSequence() when
            // Ctrl is pressed. Without Ctrl, raw Escape has no useful meaning to
            // a remote shell, so we ignore it and let InputConnection's IME
            // decide whether to surface it.
            else -> KeyResolution.Ignore
        }
    }

    /**
     * Backwards-compatible wrapper used by older call sites and tests. Maps a
     * [KeyResolution] back to the [ByteArray]? contract:
     *  - [KeyResolution.Send]  → the bytes
     *  - [KeyResolution.Swallow] / [KeyResolution.Ignore] → `null` (let caller
     *    handle the verdict via [resolve]).
     *
     * New code should call [resolve] directly.
     */
    fun toAnsiSequence(keyCode: Int, event: KeyEvent): ByteArray? =
        when (val r = resolve(keyCode, event)) {
            is KeyResolution.Send -> r.bytes
            KeyResolution.Swallow, KeyResolution.Ignore -> null
        }

    /** A KeyEvent is "language switch" when it's Ctrl+Space or Shift+Space. */
    private fun isImeLanguageSwitch(event: KeyEvent): Boolean {
        val space = event.keyCode == KeyEvent.KEYCODE_SPACE
        if (!space) return false
        return event.isCtrlPressed || event.isShiftPressed
    }

    private fun ctrlSequence(keyCode: Int): ByteArray? {
        val control = when (keyCode) {
            KeyEvent.KEYCODE_C -> 0x03
            KeyEvent.KEYCODE_D -> 0x04
            KeyEvent.KEYCODE_Z -> 0x1A
            KeyEvent.KEYCODE_LEFT_BRACKET, KeyEvent.KEYCODE_ESCAPE -> 0x1B
            else -> return null
        }
        return byteArrayOf(control.toByte())
    }
}