package com.apexplow.hanterm.terminal

class MockEchoSession(
    private val onEcho: (ByteArray) -> Unit = {},
) : TerminalEndpoint {
    private val writtenBytes = mutableListOf<Byte>()

    override fun write(bytes: ByteArray) {
        writtenBytes += bytes.toList()
        onEcho(bytes.copyOf())
    }

    fun bytesWritten(): ByteArray = writtenBytes.toByteArray()

    /** Drops everything recorded so far. Test-only convenience. */
    fun clear() {
        writtenBytes.clear()
    }
}
