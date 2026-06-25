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
 * - [Paste]: read the system clipboard and write its text contents to the SSH
 *   channel (consume the event). Triggered by Ctrl+Shift+V on a hardware
 *   keyboard — the standard desktop "paste" chord that users expect on an SSH
 *   terminal. Lives in the routing table (rather than a separate flag) so the
 *   dual-link dedup logic in [TerminalView.onKeyDown] sees it like any other
 *   modifier-bearing event.
 */
sealed class KeyResolution {
    data class Send(val bytes: ByteArray) : KeyResolution()
    data object Swallow : KeyResolution()
    data object Ignore : KeyResolution()
    data object Paste : KeyResolution()
}

/**
 * Maps a [KeyEvent] to a [KeyResolution] for the SSH terminal.
 *
 * Per `implementation_plan.md` §"KeyEvent 路由规则表":
 *
 *  - Printable characters without Ctrl/Alt → [Ignore] (let InputConnection handle).
 *  - Ctrl / Alt / function / arrow keys → [Send] with the corresponding ANSI sequence.
 *  - Ctrl+letter (A-Z) and Ctrl+`\` / Ctrl+`]` → [Send] of the corresponding
 *    ASCII control byte (xterm convention; covers tmux prefix Ctrl+B, bash
 *    readline Ctrl+A/E/F/K/L/N/P/R/U/W, less Ctrl+G/Q, telnet escape Ctrl+],
 *    SIGQUIT Ctrl+\, etc.). Ctrl+V is intentionally NOT mapped — it falls
 *    through to the printable-key path so the IME can produce a literal "V".
 *    See [ctrlSequence] for the full table.
 *  - Ctrl+Space, Shift+Space, KEYCODE_LANGUAGE_SWITCH → [Swallow] (IME-internal,
 *    MUST NOT leak to the SSH channel — see spec P0).
 *  - Ctrl+Shift+V → [Paste] (read system clipboard, write UTF-8 bytes to the
 *    SSH channel — desktop-style paste chord for hardware keyboards). Wins
 *    over the Ctrl+V byte path because the Paste verdict is checked first in
 *    [resolve] — see [isPasteShortcut].
 */
object KeyMapper {
    fun resolve(keyCode: Int, event: KeyEvent): KeyResolution {
        // Ctrl+Shift+V — desktop-style "paste from clipboard". Checked first
        // (ahead of IME-language-switch and Ctrl+V routing) so the chord
        // always wins: the user has no other use for Ctrl+Shift+V inside the
        // terminal, and the IME would otherwise see Ctrl as a modifier on the
        // letter "V" with no useful binding.
        if (isPasteShortcut(keyCode, event)) {
            return KeyResolution.Paste
        }

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
            // Swallow/Ignore/Paste are not ANSI byte sequences — older callers
            // expecting ByteArray? treat anything non-Send as "no bytes, caller
            // decides via resolve()". Paste in particular must surface as null
            // here so sendKeyEvent doesn't accidentally re-translate a paste
            // intent into raw bytes.
            KeyResolution.Swallow, KeyResolution.Ignore, KeyResolution.Paste -> null
        }

    /** A KeyEvent is "language switch" when it's Ctrl+Space or Shift+Space. */
    private fun isImeLanguageSwitch(event: KeyEvent): Boolean {
        val space = event.keyCode == KeyEvent.KEYCODE_SPACE
        if (!space) return false
        return event.isCtrlPressed || event.isShiftPressed
    }

    /**
     * Ctrl+Shift+V — the conventional desktop "paste from clipboard" chord.
     *
     * Both modifiers are required: KEYCODE_V is intentionally NOT in
     * [ctrlSequence] (Ctrl+V alone falls through to the printable-key path
     * so the IME can emit a literal "V"), but we still want Ctrl+Shift+V to
     * win over whatever the IME might do with Ctrl alone, so this is checked
     * before the IME-language-switch branch in [resolve].
     *
     * Shift+V alone is left to the printable-character short-circuit in
     * TerminalView.onKeyDown so it produces a literal "V" (or whatever the
     * IME would type).
     */
    private fun isPasteShortcut(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_V) return false
        return event.isCtrlPressed && event.isShiftPressed
    }

    /**
     * Maps a Ctrl-modified key to the corresponding ASCII control byte (0x01-0x1A
     * for letters, plus 0x1C for `\` and 0x1D for `]`). Matches xterm / iTerm /
     * gnome-terminal semantics so standard readline / tmux / less / telnet
     * chords reach the remote shell unmodified.
     *
     * Notes on the chosen surface:
     *  - KEYCODE_SPACE is NOT here — Ctrl+Space is the IME language switch and
     *    is handled upstream as a `Swallow` verdict in [resolve]. (NUL = 0x00
     *    is also not useful to a remote shell.)
     *  - Ctrl+H = 0x08 (BS). The bare `KEYCODE_DEL` path (no Ctrl) still
     *    produces 0x7F (DEL), preserving the standard "Backspace is DEL"
     *    terminal convention. The two paths are keyed off different
     *    `keyCode`s so they never collide.
     *  - Ctrl+I = 0x09 (HT) and Ctrl+M = 0x0D (CR) converge with the
     *    `KEYCODE_TAB` / `KEYCODE_ENTER` rows in the `when (keyCode)` block
     *    of [resolve]: when Android delivers these chords as the letter
     *    keycode + META_CTRL_ON this branch fires; when it delivers them as
     *    the bare TAB/ENTER keycode with the Ctrl bit set the `when` block
     *    fires. Both produce the same byte.
     *  - KEYCODE_V is intentionally omitted so Ctrl+V (no Shift) keeps
     *    falling through to the printable-key path and the IME emits a
     *    literal "V" — defended by `test_ctrlV_alone_doesNotResolveToPaste`.
     *    Ctrl+Shift+V still hits [isPasteShortcut] upstream and is unaffected.
     *  - Ctrl+0..9 / Ctrl+@ / Ctrl+^ / Ctrl+_ / Ctrl+? are NOT mapped here
     *    (conservative scope: tmux and bash readline don't bind them, no
     *    documented user need; easy to add later if a regression surfaces).
     */
    private fun ctrlSequence(keyCode: Int): ByteArray? {
        val control = when (keyCode) {
            KeyEvent.KEYCODE_A -> 0x01
            KeyEvent.KEYCODE_B -> 0x02
            KeyEvent.KEYCODE_C -> 0x03
            KeyEvent.KEYCODE_D -> 0x04
            KeyEvent.KEYCODE_E -> 0x05
            KeyEvent.KEYCODE_F -> 0x06
            KeyEvent.KEYCODE_G -> 0x07
            KeyEvent.KEYCODE_H -> 0x08
            KeyEvent.KEYCODE_I -> 0x09
            KeyEvent.KEYCODE_J -> 0x0A
            KeyEvent.KEYCODE_K -> 0x0B
            KeyEvent.KEYCODE_L -> 0x0C
            KeyEvent.KEYCODE_M -> 0x0D
            KeyEvent.KEYCODE_N -> 0x0E
            KeyEvent.KEYCODE_O -> 0x0F
            KeyEvent.KEYCODE_P -> 0x10
            KeyEvent.KEYCODE_Q -> 0x11
            KeyEvent.KEYCODE_R -> 0x12
            KeyEvent.KEYCODE_S -> 0x13
            KeyEvent.KEYCODE_T -> 0x14
            KeyEvent.KEYCODE_U -> 0x15
            KeyEvent.KEYCODE_W -> 0x17
            KeyEvent.KEYCODE_X -> 0x18
            KeyEvent.KEYCODE_Y -> 0x19
            KeyEvent.KEYCODE_Z -> 0x1A
            KeyEvent.KEYCODE_LEFT_BRACKET, KeyEvent.KEYCODE_ESCAPE -> 0x1B
            KeyEvent.KEYCODE_BACKSLASH -> 0x1C
            KeyEvent.KEYCODE_RIGHT_BRACKET -> 0x1D
            else -> return null
        }
        return byteArrayOf(control.toByte())
    }
}