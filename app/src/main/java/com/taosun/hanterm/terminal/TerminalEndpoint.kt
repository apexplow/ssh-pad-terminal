package com.taosun.hanterm.terminal

fun interface TerminalEndpoint {
    fun write(bytes: ByteArray)
}
