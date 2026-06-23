package com.example.sshterminal.ssh.auth

import net.schmizz.sshj.SSHClient

/**
 * Strategy interface that lets [com.example.sshterminal.ssh.SshClient] apply
 * authentication without leaking SSHJ's method-based API to its callers.
 *
 * Each [Auth] variant has a corresponding [SshAuthProvider] implementation
 * that knows how to register itself with SSHJ's `auth()` driver.
 */
interface SshAuthProvider {
    fun authenticate(client: SSHClient, username: String, auth: Auth)
}
