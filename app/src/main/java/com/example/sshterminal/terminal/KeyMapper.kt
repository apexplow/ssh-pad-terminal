package com.example.sshterminal.terminal

import android.view.KeyEvent

object KeyMapper {
    fun toAnsiSequence(keyCode: Int, event: KeyEvent): ByteArray? {
        if (event.isCtrlPressed) {
            ctrlSequence(keyCode)?.let { return it }
        }

        if (event.isAltPressed && event.unicodeChar > 0) {
            return byteArrayOf(0x1B) + event.unicodeChar.toChar().toString().toByteArray(Charsets.UTF_8)
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_DEL -> byteArrayOf(0x7F.toByte())
            KeyEvent.KEYCODE_ENTER -> "\r".toByteArray(Charsets.UTF_8)
            KeyEvent.KEYCODE_ESCAPE -> byteArrayOf(0x1B)
            KeyEvent.KEYCODE_TAB -> "\t".toByteArray(Charsets.UTF_8)
            KeyEvent.KEYCODE_DPAD_UP -> "\u001B[A".toByteArray(Charsets.UTF_8)
            KeyEvent.KEYCODE_DPAD_DOWN -> "\u001B[B".toByteArray(Charsets.UTF_8)
            KeyEvent.KEYCODE_DPAD_RIGHT -> "\u001B[C".toByteArray(Charsets.UTF_8)
            KeyEvent.KEYCODE_DPAD_LEFT -> "\u001B[D".toByteArray(Charsets.UTF_8)
            KeyEvent.KEYCODE_MOVE_HOME -> "\u001B[H".toByteArray(Charsets.UTF_8)
            KeyEvent.KEYCODE_MOVE_END -> "\u001B[F".toByteArray(Charsets.UTF_8)
            KeyEvent.KEYCODE_PAGE_UP -> "\u001B[5~".toByteArray(Charsets.UTF_8)
            KeyEvent.KEYCODE_PAGE_DOWN -> "\u001B[6~".toByteArray(Charsets.UTF_8)
            KeyEvent.KEYCODE_FORWARD_DEL -> "\u001B[3~".toByteArray(Charsets.UTF_8)
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
