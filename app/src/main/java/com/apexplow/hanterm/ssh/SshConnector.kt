package com.apexplow.hanterm.ssh

import com.apexplow.hanterm.ssh.auth.Auth

/**
 * Narrow seam between the UI/ViewModel and the SSH transport.
 *
 * [SshClient] implements this interface; tests and previews can inject a fake
 * to drive connection state without opening real sockets.
 */
interface SshConnector {
    suspend fun connect(
        host: String,
        port: Int,
        username: String,
        auth: Auth,
    ): Result<SshConnectResult>

    fun disconnect(userInitiated: Boolean = false)
}
