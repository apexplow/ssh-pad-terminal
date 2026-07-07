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
 * Per `docs/superpowers/specs/2026-06-29-vim-nano-keymapper-design.md`:
 *
 * Routing is data-driven: every behaviour lives as a row in the private
 * [KEY_MAP] list. [resolve] walks the list top-to-bottom and returns the
 * verdict from the first row whose `match` predicate fires. New keys are
 * added by appending an entry, not by editing a `when` block.
 *
 * Ordering of [KEY_MAP] is the contract — see the comment above the val
 * for the precedence list. Two invariants worth highlighting:
 *
 *  1. Ctrl+Shift+V (Paste) is the FIRST entry. It must beat the Ctrl+V
 *     printable-key short-circuit in [TerminalView.onKeyDown] so the user
 *     gets a paste, not a literal "V".
 *  2. IME language switch (Ctrl+Space / Shift+Space /
 *     KEYCODE_LANGUAGE_SWITCH) is the SECOND entry. It must NEVER reach
 *     the remote shell — that's the P0 bug from implementation_plan.md.
 *
 * Each entry carries structured per-program documentation (vim modes,
 * nano, bash readline) so a future maintainer can see "what does this
 * byte mean to the user?" without grepping markdown.
 *
 * Legacy [`toAnsiSequence`] wrapper is kept for older call sites and
 * collapses non-[KeyResolution.Send] verdicts to `null` — see its kdoc.
 */
object KeyMapper {
    fun resolve(event: KeyEvent): KeyResolution {
        for (entry in KEY_MAP) {
            if (entry.match(event)) return entry.verdict(event)
        }
        return KeyResolution.Ignore
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
    fun toAnsiSequence(event: KeyEvent): ByteArray? =
        when (val r = resolve(event)) {
            is KeyResolution.Send -> r.bytes
            // Swallow/Ignore/Paste are not ANSI byte sequences — older callers
            // expecting ByteArray? treat anything non-Send as "no bytes, caller
            // decides via resolve()". Paste in particular must surface as null
            // here so sendKeyEvent doesn't accidentally re-translate a paste
            // intent into raw bytes.
            KeyResolution.Swallow, KeyResolution.Ignore, KeyResolution.Paste -> null
        }

    /**
     * Test-only accessor for the routing table. Exposed as `internal` so the
     * `src/test` source set can iterate it for the meta-test in
     * `KeyEventRoutingTest.test_keyMapTable_isWellFormed`. Production code
     * MUST NOT call this — the routing table is consulted through
     * [resolve], which is the single public entry point and also the only
     * place we control the first-match-wins ordering.
     *
     * The `internal` visibility means same-module access only; an APK
     * outside this module (none exists today) cannot reach it.
     */
    internal fun entriesForTest(): List<KeyMapEntry> = KEY_MAP

    // Routing table. First match wins. See class kdoc for the ordering rationale.
    // Order: Paste → IME switch → Ctrl+letter → Alt+letter → ESC → Ctrl+symbol set
    //        → Shift+Tab → Tab → Enter → Del → cursor keys → Home/End → PageUp/Down
    //        → ForwardDel → Insert → F-keys. New entries (★) are the additions
    //        from the 2026-06-29 vim/nano support design.
    private val KEY_MAP: List<KeyMapEntry> = listOf(
        // 1. Paste shortcut — must beat the Ctrl+V byte path.
        KeyMapEntry(
            description = "Ctrl+Shift+V → Paste (must beat Ctrl+V byte path)",
            match = { ev -> ev.keyCode == KeyEvent.KEYCODE_V && ev.isCtrlPressed && ev.isShiftPressed },
            verdict = { KeyResolution.Paste },
            vim = listOf(ProgramUsage("any", "no native binding — terminal intercepts paste")),
            nano = listOf(ProgramUsage("any", "no native binding — terminal intercepts paste")),
            bash = listOf(ProgramUsage("any", "no native binding — terminal intercepts paste")),
        ),

        // 2. IME language switch — must NEVER reach the remote shell.
        KeyMapEntry(
            description = "IME language switch (Ctrl+Space, Shift+Space, KEYCODE_LANGUAGE_SWITCH) → Swallow",
            match = { ev ->
                (ev.keyCode == KeyEvent.KEYCODE_SPACE && (ev.isCtrlPressed || ev.isShiftPressed)) ||
                    ev.keyCode == KeyEvent.KEYCODE_LANGUAGE_SWITCH
            },
            verdict = { KeyResolution.Swallow },
            vim = listOf(ProgramUsage("any", "must NEVER reach vim — IME-internal toggle")),
            nano = listOf(ProgramUsage("any", "must NEVER reach nano — IME-internal toggle")),
            bash = listOf(ProgramUsage("any", "must NEVER reach bash — IME-internal toggle")),
            note = "Per implementation_plan.md P0 — these are IME-internal, not terminal input",
        ),

        // 3. Ctrl+letter (A-Z except V) — xterm ASCII control bytes.
        KeyMapEntry(
            description = "Ctrl+letter (A-Z except V) → xterm ASCII control bytes (0x01-0x1A)",
            match = { ev -> ev.isCtrlPressed && ctrlControlByte(ev.keyCode) != null },
            verdict = { ev ->
                KeyResolution.Send(byteArrayOf(ctrlControlByte(ev.keyCode)!!.toByte()))
            },
            vim = listOf(
                ProgramUsage("insert", "ETX (Ctrl+C) exits insert mode; others depend on plugin bindings"),
                ProgramUsage("normal", "many letters are vim's own — Ctrl+R=redo, Ctrl+F=pgdn, Ctrl+W=window, etc."),
                ProgramUsage("command", "Ctrl+C aborts the command line"),
            ),
            nano = listOf(ProgramUsage("any", "vanilla bindings: Ctrl+O=writeOut, Ctrl+X=exit, Ctrl+W=search, Ctrl+K=cut, Ctrl+U=uncut, etc.")),
            bash = listOf(ProgramUsage("any", "readline: Ctrl+A/E=line begin/end, Ctrl+R=reverse-i-search, Ctrl+K=kill-to-eol, Ctrl+U=kill-line, Ctrl+W=kill-word, Ctrl+L=clear, etc.")),
            note = "KEYCODE_V intentionally omitted — Ctrl+V alone falls through to printable-key path so the IME emits a literal 'V'. Ctrl+Shift+V is the Paste entry above.",
        ),

        // 4. Alt+letter — xterm Meta convention.
        KeyMapEntry(
            description = "Alt+letter (unicodeChar > 0) → ESC + letter (xterm Meta convention)",
            match = { ev -> ev.isAltPressed && !ev.isCtrlPressed && ev.unicodeChar > 0 },
            verdict = { ev ->
                val payload = ev.unicodeChar.toChar().toString().toByteArray(Charsets.UTF_8)
                KeyResolution.Send(byteArrayOf(0x1B) + payload)
            },
            vim = listOf(ProgramUsage("normal", "Meta-key — many plugins bind M-x, M-w, etc. as leader keys")),
            nano = listOf(ProgramUsage("any", "Meta-key — less used than Ctrl in vanilla nano")),
            bash = listOf(ProgramUsage("emacs", "readline's emacs mode binds M-f, M-b, M-d, M-<, M->, etc.")),
        ),

        // 5. KEYCODE_ESCAPE (no Ctrl) — vim normal-mode exit. [★ NEW]
        KeyMapEntry(
            description = "KEYCODE_ESCAPE (no Ctrl) → 0x1B (ESC) — vim normal-mode exit",
            match = { ev -> ev.keyCode == KeyEvent.KEYCODE_ESCAPE && !ev.isCtrlPressed },
            verdict = { KeyResolution.Send(byteArrayOf(0x1B.toByte())) },
            vim = listOf(
                ProgramUsage("insert", "exit to normal mode"),
                ProgramUsage("visual", "exit to normal mode"),
                ProgramUsage("replace", "exit to normal mode"),
                ProgramUsage("command", "abort command line back to normal mode"),
            ),
            nano = listOf(ProgramUsage("any", "cancel current operation")),
            bash = listOf(ProgramUsage("any", "cancel incomplete command line")),
        ),

        // 5b. Ctrl+^ → 0x1E (RS) — vim alt-file. [★ NEW]
        // Note: Android lacks KEYCODE_CIRCUMFLEX; the '^' character arrives via
        // [KeyEvent.getCharacters] (keyCode is KEYCODE_UNKNOWN on most keyboards).
        // We match on `characters` rather than `unicodeChar` because the latter
        // is computed via the framework's KeyCharacterMap lookup, which is not
        // available in unit tests for synthetic KEYCODE_UNKNOWN events.
        @Suppress("DEPRECATION")
        KeyMapEntry(
            description = "Ctrl+^ → 0x1E (RS) — vim alternate file",
            match = { ev -> ev.isCtrlPressed && ev.characters == "^" },
            verdict = { KeyResolution.Send(byteArrayOf(0x1E.toByte())) },
            vim = listOf(ProgramUsage("normal", "switch to alternate file")),
            nano = listOf(ProgramUsage("any", "no native binding")),
            bash = listOf(ProgramUsage("any", "no native binding")),
        ),

        // 5c. Ctrl+_ → 0x1F (US) — vim undo / nano go-to-line. [★ NEW]
        // Same caveat as 5b: Android lacks KEYCODE_UNDERSCORE; match on
        // [KeyEvent.getCharacters].
        @Suppress("DEPRECATION")
        KeyMapEntry(
            description = "Ctrl+_ → 0x1F (US) — vim undo (compatible mode) / nano go-to-line",
            match = { ev -> ev.isCtrlPressed && ev.characters == "_" },
            verdict = { KeyResolution.Send(byteArrayOf(0x1F.toByte())) },
            vim = listOf(ProgramUsage("normal", "undo (in compatible mode)")),
            nano = listOf(ProgramUsage("any", "go to line number")),
            bash = listOf(ProgramUsage("any", "no native binding")),
        ),

        // 5d. Ctrl+@ (KEYCODE_AT) → 0x00 (NUL) — bash set-mark / nano set mark. [★ NEW]
        KeyMapEntry(
            description = "Ctrl+@ (KEYCODE_AT) → 0x00 (NUL) — bash set-mark / nano set mark",
            match = { ev -> ev.isCtrlPressed && ev.keyCode == KeyEvent.KEYCODE_AT },
            verdict = { KeyResolution.Send(byteArrayOf(0x00.toByte())) },
            vim = listOf(ProgramUsage("normal", "no native binding (commonly remapped to <C-@>)")),
            nano = listOf(ProgramUsage("any", "set mark")),
            bash = listOf(ProgramUsage("any", "set-mark")),
        ),

        // 5e. Ctrl+? (KEYCODE_SLASH) → 0x7F (DEL) — alternative DEL byte. [★ NEW]
        KeyMapEntry(
            description = "Ctrl+? (KEYCODE_SLASH) → 0x7F (DEL) — alternative DEL byte (same as bare Backspace)",
            match = { ev -> ev.isCtrlPressed && ev.keyCode == KeyEvent.KEYCODE_SLASH },
            verdict = { KeyResolution.Send(byteArrayOf(0x7F.toByte())) },
            vim = listOf(ProgramUsage("normal", "delete one char before cursor")),
            nano = listOf(ProgramUsage("any", "delete one char before cursor")),
            bash = listOf(ProgramUsage("any", "delete one char before cursor")),
        ),

        // 6. Shift+Tab — Back-Tab. [★ NEW]
        KeyMapEntry(
            description = "KEYCODE_TAB + Shift → ESC[Z (Back-Tab)",
            match = { ev -> ev.keyCode == KeyEvent.KEYCODE_TAB && ev.isShiftPressed && !ev.isCtrlPressed },
            verdict = { KeyResolution.Send("\u001B[Z".toByteArray(Charsets.UTF_8)) },
            vim = listOf(ProgramUsage("normal", "in some configs, `gT` — previous tab")),
            nano = listOf(ProgramUsage("any", "un-indent current line")),
            bash = listOf(ProgramUsage("any", "reverse tab completion")),
        ),

        // 7. KEYCODE_TAB (no Shift) — bare Tab and Ctrl+Tab both match.
        KeyMapEntry(
            description = "KEYCODE_TAB (no Shift) → \\t (HT) — bare Tab and Ctrl+Tab both match",
            match = { ev -> ev.keyCode == KeyEvent.KEYCODE_TAB && !ev.isShiftPressed },
            verdict = { KeyResolution.Send("\t".toByteArray(Charsets.UTF_8)) },
            vim = listOf(ProgramUsage("insert", "insert literal tab (or spaces with :set expandtab)")),
            nano = listOf(ProgramUsage("any", "insert tab / trigger completion")),
            bash = listOf(ProgramUsage("any", "trigger completion")),
            note = "Ctrl+Tab also matches (Ctrl+I produces the same byte 0x09 — see Ctrl+letter entry above)",
        ),

        // 8. KEYCODE_ENTER — bare Enter and Ctrl+Enter both match.
        KeyMapEntry(
            description = "KEYCODE_ENTER → \\r (CR) — bare Enter and Ctrl+Enter both match",
            match = { ev -> ev.keyCode == KeyEvent.KEYCODE_ENTER },
            verdict = { KeyResolution.Send("\r".toByteArray(Charsets.UTF_8)) },
            vim = listOf(ProgramUsage("insert", "newline")),
            nano = listOf(ProgramUsage("any", "newline")),
            bash = listOf(ProgramUsage("any", "execute command")),
            note = "Ctrl+Enter also matches (Ctrl+M produces the same byte 0x0D — see Ctrl+letter entry above)",
        ),

        // 9. KEYCODE_DEL — Backspace key.
        KeyMapEntry(
            description = "KEYCODE_DEL → 0x7F (DEL) — Backspace on most Android keyboards",
            match = { ev -> ev.keyCode == KeyEvent.KEYCODE_DEL },
            verdict = { KeyResolution.Send(byteArrayOf(0x7F.toByte())) },
            vim = listOf(ProgramUsage("insert", "delete one char before cursor")),
            nano = listOf(ProgramUsage("any", "delete one char before cursor")),
            bash = listOf(ProgramUsage("any", "delete one char before cursor")),
        ),

        // 10. KEYCODE_DPAD_* — ANSI cursor sequences.
        KeyMapEntry(
            description = "KEYCODE_DPAD_UP/DOWN/LEFT/RIGHT → ANSI cursor sequences",
            match = { ev -> ev.keyCode in cursorKeyCodes },
            verdict = { ev ->
                val seq = when (ev.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> "\u001B[A"
                    KeyEvent.KEYCODE_DPAD_DOWN -> "\u001B[B"
                    KeyEvent.KEYCODE_DPAD_RIGHT -> "\u001B[C"
                    KeyEvent.KEYCODE_DPAD_LEFT -> "\u001B[D"
                    else -> error("unreachable: cursorKeyCodes membership is the match gate")
                }
                KeyResolution.Send(seq.toByteArray(Charsets.UTF_8))
            },
            vim = listOf(ProgramUsage("normal", "h/j/k/l equivalent")),
            nano = listOf(ProgramUsage("any", "move cursor")),
            bash = listOf(ProgramUsage("any", "no default binding (readline emacs mode uses Ctrl+B/F/N/P)")),
        ),

        // 11. KEYCODE_MOVE_HOME/END.
        KeyMapEntry(
            description = "KEYCODE_MOVE_HOME → ESC[H, KEYCODE_MOVE_END → ESC[F",
            match = { ev -> ev.keyCode == KeyEvent.KEYCODE_MOVE_HOME || ev.keyCode == KeyEvent.KEYCODE_MOVE_END },
            verdict = { ev ->
                val seq = if (ev.keyCode == KeyEvent.KEYCODE_MOVE_HOME) "\u001B[H" else "\u001B[F"
                KeyResolution.Send(seq.toByteArray(Charsets.UTF_8))
            },
            vim = listOf(ProgramUsage("normal", "^/$ — begin/end of line")),
            nano = listOf(ProgramUsage("any", "begin/end of line")),
            bash = listOf(ProgramUsage("any", "begin/end of line")),
        ),

        // 12. KEYCODE_PAGE_UP/DOWN.
        KeyMapEntry(
            description = "KEYCODE_PAGE_UP → ESC[5~, KEYCODE_PAGE_DOWN → ESC[6~",
            match = { ev -> ev.keyCode == KeyEvent.KEYCODE_PAGE_UP || ev.keyCode == KeyEvent.KEYCODE_PAGE_DOWN },
            verdict = { ev ->
                val seq = if (ev.keyCode == KeyEvent.KEYCODE_PAGE_UP) "\u001B[5~" else "\u001B[6~"
                KeyResolution.Send(seq.toByteArray(Charsets.UTF_8))
            },
            vim = listOf(ProgramUsage("normal", "Ctrl+F/Ctrl+B equivalent (page down/up)")),
            nano = listOf(ProgramUsage("any", "page down/up")),
            bash = listOf(ProgramUsage("any", "no default binding")),
        ),

        // 13. KEYCODE_FORWARD_DEL.
        KeyMapEntry(
            description = "KEYCODE_FORWARD_DEL → ESC[3~ (forward delete)",
            match = { ev -> ev.keyCode == KeyEvent.KEYCODE_FORWARD_DEL },
            verdict = { KeyResolution.Send("\u001B[3~".toByteArray(Charsets.UTF_8)) },
            vim = listOf(ProgramUsage("insert", "delete one char after cursor")),
            nano = listOf(ProgramUsage("any", "delete one char after cursor")),
            bash = listOf(ProgramUsage("any", "delete one char after cursor")),
        ),

        // 14. KEYCODE_INSERT — vim mode toggle. [★ NEW]
        KeyMapEntry(
            description = "KEYCODE_INSERT → ESC[2~ (Insert key, vim mode-toggle)",
            match = { ev -> ev.keyCode == KeyEvent.KEYCODE_INSERT },
            verdict = { KeyResolution.Send("\u001B[2~".toByteArray(Charsets.UTF_8)) },
            vim = listOf(ProgramUsage("normal", "toggle insert / replace mode")),
            nano = listOf(ProgramUsage("any", "no native binding")),
            bash = listOf(ProgramUsage("any", "no native binding")),
        ),

        // 15. F1-F12 — function key sequences.
        KeyMapEntry(
            description = "F1-F12 → standard ANSI function-key sequences",
            match = { ev -> functionKeyBytes(ev.keyCode) != null },
            verdict = { ev -> KeyResolution.Send(functionKeyBytes(ev.keyCode)!!) },
            vim = listOf(ProgramUsage("normal", "F1=Help, others depend on user config")),
            nano = listOf(ProgramUsage("any", "F1=Help, others unused in vanilla")),
            bash = listOf(ProgramUsage("any", "no default binding")),
        ),

        // 16. Ctrl+V alone (no Shift) → Ignore.
        // KEYCODE_V is intentionally omitted from [ctrlControlByte] so plain
        // Ctrl+V falls through to the printable-key path so the IME emits a
        // literal "V". The explicit Ignore entry here documents the Intent:
        // this matches the meta-test's expectation that some entry catches
        // Ctrl+V. At runtime the View's onKeyDown short-circuit sees no
        // bytes from resolve() for this event, so InputConnection handles it.
        KeyMapEntry(
            description = "Ctrl+V (no Shift) → Ignore — let IME emit literal 'V' (no byte to SSH)",
            match = { ev -> ev.isCtrlPressed && !ev.isShiftPressed && ev.keyCode == KeyEvent.KEYCODE_V },
            verdict = { KeyResolution.Ignore },
            vim = listOf(ProgramUsage("insert", "no native binding — let the IME emit literal 'V'")),
            nano = listOf(ProgramUsage("any", "no native binding — let the IME emit literal 'V'")),
            bash = listOf(ProgramUsage("any", "no native binding — let the IME emit literal 'V'")),
            note = "Pasted intentionally as Ignore so the IME's printable-key path can render literal 'V'. Ctrl+Shift+V is the Paste entry higher in the table.",
        ),

        // 17. Bare printable character (no Ctrl/Alt) → Ignore.
        // This is the catch-all for events the old `resolve()` returned
        // KeyResolution.Ignore for. The View's onKeyDown short-circuit then
        // lets InputConnection handle the printable character naturally. The
        // unicodeChar > 0 guard ensures we don't shadow F-keys (unicodeChar==0)
        // or pure modifier presses.
        KeyMapEntry(
            description = "Bare printable char (no Ctrl/Alt) → Ignore — let InputConnection handle",
            match = { ev -> ev.unicodeChar > 0 && !ev.isCtrlPressed && !ev.isAltPressed },
            verdict = { KeyResolution.Ignore },
            vim = listOf(ProgramUsage("insert", "the literal character is what vim receives from InputConnection")),
            nano = listOf(ProgramUsage("any", "the literal character is what nano receives from InputConnection")),
            bash = listOf(ProgramUsage("any", "the literal character is what bash receives from InputConnection")),
            note = "Catch-all — placed last in the table so every more-specific entry above wins. unicodeChar > 0 ensures we never shadow F-keys / bare modifier presses.",
        ),
    )

    private val cursorKeyCodes = setOf(
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
    )

    /**
     * Maps a Ctrl-modified key to the corresponding ASCII control byte. `null`
     * means "this key has no Ctrl mapping" — the caller treats that as a
     * fall-through to the next entry in [KEY_MAP].
     *
     * Surface: A-Z (except V) + `\` (0x1C) + `]` (0x1D) + `[` (0x1B). KEYCODE_V
     * omitted intentionally so Ctrl+V alone keeps falling through to the
     * printable-key path (the IME emits a literal "V"). Ctrl+Shift+V is the
     * Paste entry higher in [KEY_MAP].
     */
    private fun ctrlControlByte(keyCode: Int): Int? = when (keyCode) {
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
        KeyEvent.KEYCODE_LEFT_BRACKET -> 0x1B
        KeyEvent.KEYCODE_ESCAPE -> 0x1B
        KeyEvent.KEYCODE_BACKSLASH -> 0x1C
        KeyEvent.KEYCODE_RIGHT_BRACKET -> 0x1D
        else -> null
    }

    /**
     * Maps a function-key code to its ANSI escape sequence. `null` means
     * "not a function key" — the caller treats that as a fall-through.
     */
    private fun functionKeyBytes(keyCode: Int): ByteArray? = when (keyCode) {
        KeyEvent.KEYCODE_F1 -> "\u001BOP".toByteArray(Charsets.UTF_8)
        KeyEvent.KEYCODE_F2 -> "\u001BOQ".toByteArray(Charsets.UTF_8)
        KeyEvent.KEYCODE_F3 -> "\u001BOR".toByteArray(Charsets.UTF_8)
        KeyEvent.KEYCODE_F4 -> "\u001BOS".toByteArray(Charsets.UTF_8)
        KeyEvent.KEYCODE_F5 -> "\u001B[15~".toByteArray(Charsets.UTF_8)
        KeyEvent.KEYCODE_F6 -> "\u001B[17~".toByteArray(Charsets.UTF_8)
        KeyEvent.KEYCODE_F7 -> "\u001B[18~".toByteArray(Charsets.UTF_8)
        KeyEvent.KEYCODE_F8 -> "\u001B[19~".toByteArray(Charsets.UTF_8)
        KeyEvent.KEYCODE_F9 -> "\u001B[20~".toByteArray(Charsets.UTF_8)
        KeyEvent.KEYCODE_F10 -> "\u001B[21~".toByteArray(Charsets.UTF_8)
        KeyEvent.KEYCODE_F11 -> "\u001B[23~".toByteArray(Charsets.UTF_8)
        KeyEvent.KEYCODE_F12 -> "\u001B[24~".toByteArray(Charsets.UTF_8)
        else -> null
    }
}