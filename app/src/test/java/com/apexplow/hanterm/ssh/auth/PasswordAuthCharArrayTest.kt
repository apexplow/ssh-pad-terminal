package com.apexplow.hanterm.ssh.auth

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.userauth.UserAuthException
import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * Pins the secure-handling contract for [Auth.PasswordAuth].
 *
 * - The password travels as a [CharArray] so it can be zeroed after use.
 * - [PasswordAuthProvider.authenticate] clears the array in `finally`,
 *   both on success and on failure.
 *
 * Pure JUnit: only mocks sshj's [SSHClient] and touches no Android framework
 * classes, per the repo's test conventions.
 */
class PasswordAuthCharArrayTest {

    @Test
    fun authenticate_zeroesPasswordAfterSuccess() {
        val client = mockk<SSHClient>(relaxed = true)
        val password = "hunter2".toCharArray()
        val auth = Auth.PasswordAuth(password)

        PasswordAuthProvider.authenticate(client, "ops", auth, isDebug = false)

        verify { client.authPassword("ops", password) }
        assertArrayEquals("password must be zeroed after auth", CharArray(7), password)
    }

    @Test
    fun authenticate_zeroesPasswordAfterFailure() {
        val client = mockk<SSHClient>(relaxed = true)
        every { client.authPassword(any<String>(), any<CharArray>()) } throws UserAuthException("boom")
        val password = "hunter2".toCharArray()
        val auth = Auth.PasswordAuth(password)

        kotlin.runCatching {
            PasswordAuthProvider.authenticate(client, "ops", auth, isDebug = false)
        }

        assertArrayEquals("password must be zeroed even when auth fails", CharArray(7), password)
    }
}
