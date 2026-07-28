package com.apexplow.hanterm.terminal

fun interface TerminalEndpoint {
    fun write(bytes: ByteArray)
}
