package com.example.sshterminal.terminal

fun interface TerminalEndpoint {
    fun write(bytes: ByteArray)
}
