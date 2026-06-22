package com.example.sshterminal.terminal

interface TerminalEndpoint {
    fun write(bytes: ByteArray)
}
