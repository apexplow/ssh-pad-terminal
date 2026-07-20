package com.taosun.hanterm.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.taosun.hanterm.data.prefs.AppPreferences
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConnectionDraftTest {

    private lateinit var prefs: AppPreferences

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = AppPreferences(context).also { it.clear() }
    }

    @Test
    fun test_applyDraftForConnect_writesHostPortUsername() {
        prefs.setEncryptedPassword(byteArrayOf(1, 2, 3))

        applyDraftForConnect(
            prefs,
            ConnectionDraft(
                host = " 192.168.1.10 ",
                port = "2222",
                username = " ops ",
                password = "",
                privateKeyName = "",
            ),
        )

        assertEquals("192.168.1.10", prefs.host)
        assertEquals(2222, prefs.port)
        assertEquals("ops", prefs.username)
        assertTrue(prefs.hasUsableCredentials())
    }

    @Test
    fun test_applyDraftForConnect_emptyPassword_doesNotWipeStoredBlob() {
        val blob = byteArrayOf(9, 8, 7, 6)
        prefs.host = "old.host"
        prefs.username = "olduser"
        prefs.setEncryptedPassword(blob)

        applyDraftForConnect(
            prefs,
            ConnectionDraft(
                host = "new.host",
                port = "22",
                username = "newuser",
                password = "",
                privateKeyName = "",
            ),
        )

        assertEquals("new.host", prefs.host)
        assertEquals("newuser", prefs.username)
        assertArrayEquals(blob, prefs.getEncryptedPassword())
    }
}
