package com.apexplow.hanterm.terminal.selection

import android.app.Application
import android.content.Context
import android.content.Intent
import android.view.Menu
import android.view.MenuItem
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Integration tests for the extension items appended to Termux's
 * floating text-selection toolbar (Share / Open URL / Search web).
 *
 * Why this lives here:
 *  - the production code is `internal` so unit tests in the same module
 *    can call `addSelectionMenuExtensions` / `handleSelectionMenuItemClick`
 *    directly without reflection;
 *  - `Menu` and `Intent` need an Android runtime, so these tests use
 *    Robolectric (`@Config(sdk = [36])`) rather than pure JUnit;
 *  - the test exercises the same public Android APIs (`Menu.add`,
 *    `Menu.findItem`, `startActivity`) the production hook uses, so a
 *    platform change to either surfaces as a test failure here, not as
 *    a tablet bug report.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SelectionMenuExtensionsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    /**
     * `ShadowApplication.nextStartedActivity` is the canonical Robolectric
     * seam for verifying `startActivity` calls. `shadowOf(Context)`
     * doesn't exist — must pass the application context cast to
     * [Application].
     */
    private fun nextStartedActivity(): Intent? =
        shadowOf(context.applicationContext as Application).nextStartedActivity

    // --- addSelectionMenuExtensions ---------------------------------------

    @Test
    fun add_emptyText_addsNothing() {
        val menu = newMenu()
        addSelectionMenuExtensions(menu, SelectionMenuConfig(context, "   "))
        assertEquals(0, countExtensionItems(menu))
    }

    @Test
    fun add_plainText_addsShareAndSearchWeb_noOpenUrl() {
        val menu = newMenu()
        addSelectionMenuExtensions(menu, SelectionMenuConfig(context, "hello world"))
        assertNotNull("Share must be added for any non-empty selection",
            menu.findItem(SelectionMenuItemIds.SHARE))
        assertNotNull("Search web must be added for any non-empty selection",
            menu.findItem(SelectionMenuItemIds.SEARCH_WEB))
        assertNull("Open URL must NOT be added when selection is not a URL",
            menu.findItem(SelectionMenuItemIds.OPEN_URL))
    }

    @Test
    fun add_httpsUrl_addsAllThreeItems() {
        val menu = newMenu()
        addSelectionMenuExtensions(menu,
            SelectionMenuConfig(context, "https://example.com/path"))
        assertNotNull(menu.findItem(SelectionMenuItemIds.SHARE))
        assertNotNull(menu.findItem(SelectionMenuItemIds.OPEN_URL))
        assertNotNull(menu.findItem(SelectionMenuItemIds.SEARCH_WEB))
    }

    @Test
    fun add_httpUrl_addsOpenUrl() {
        val menu = newMenu()
        addSelectionMenuExtensions(menu,
            SelectionMenuConfig(context, "http://example.com"))
        assertNotNull(menu.findItem(SelectionMenuItemIds.OPEN_URL))
    }

    @Test
    fun add_ftpUrl_addsOpenUrl() {
        val menu = newMenu()
        addSelectionMenuExtensions(menu,
            SelectionMenuConfig(context, "ftp://files.example.org/dir/"))
        assertNotNull(menu.findItem(SelectionMenuItemIds.OPEN_URL))
    }

    @Test
    fun add_urlWithSurroundingWhitespace_stillAddsOpenUrl() {
        // Users routinely select a URL with a trailing newline or leading
        // space; the trim() in addSelectionMenuExtensions must still
        // recognise it.
        val menu = newMenu()
        addSelectionMenuExtensions(menu,
            SelectionMenuConfig(context, "  https://example.com  \n"))
        assertNotNull(menu.findItem(SelectionMenuItemIds.OPEN_URL))
    }

    @Test
    fun add_nonUrlWithUrlSubstring_doesNotAddOpenUrl() {
        // "see https://x.com for more" is NOT an exact URL — the user
        // would expect Share / Search web but not Open URL.
        val menu = newMenu()
        addSelectionMenuExtensions(menu,
            SelectionMenuConfig(context, "see https://x.com for more"))
        assertNotNull(menu.findItem(SelectionMenuItemIds.SHARE))
        assertNull(menu.findItem(SelectionMenuItemIds.OPEN_URL))
    }

    @Test
    fun add_sshScheme_doesNotAddOpenUrl() {
        // SSH / file / git URLs are deliberately excluded from Open URL
        // — Termux users type these in shell sessions and an accidental
        // browser launch on "ssh://host" is a worse failure than missing
        // the menu item.
        val menu = newMenu()
        addSelectionMenuExtensions(menu,
            SelectionMenuConfig(context, "ssh://user@host"))
        assertNull(menu.findItem(SelectionMenuItemIds.OPEN_URL))
    }

    @Test
    fun add_calledTwice_dedupesItems() {
        // `onCreateActionMode` + `onPrepareActionMode` both call this
        // hook; if Termux rebuilds the menu between them our items must
        // not appear twice.
        val menu = newMenu()
        val cfg = SelectionMenuConfig(context, "https://example.com")
        addSelectionMenuExtensions(menu, cfg)
        addSelectionMenuExtensions(menu, cfg)
        assertEquals(1, countItemsWithId(menu, SelectionMenuItemIds.SHARE))
        assertEquals(1, countItemsWithId(menu, SelectionMenuItemIds.OPEN_URL))
        assertEquals(1, countItemsWithId(menu, SelectionMenuItemIds.SEARCH_WEB))
    }

    // --- handleSelectionMenuItemClick ------------------------------------

    @Test
    fun click_share_firesACTION_SEND_withSelectedText() {
        val cfg = SelectionMenuConfig(context, "share me")
        val handled = handleSelectionMenuItemClick(SelectionMenuItemIds.SHARE, cfg)

        assertTrue("Share click must be handled by us", handled)
        val started = nextStartedActivity()
        assertNotNull("Share must dispatch an Activity intent", started)
        // Share goes through Intent.createChooser, so the outer intent
        // is ACTION_CHOOSER wrapping the actual SEND. Unwrap it.
        assertEquals(Intent.ACTION_CHOOSER, started!!.action)
        val inner = started.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull("Chooser must wrap the original SEND intent", inner)
        assertEquals(Intent.ACTION_SEND, inner!!.action)
        assertEquals("text/plain", inner.type)
        assertEquals("share me", inner.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun click_openUrl_firesACTION_VIEW_withUrl() {
        val cfg = SelectionMenuConfig(context, "https://example.com/path?q=1")
        val handled = handleSelectionMenuItemClick(SelectionMenuItemIds.OPEN_URL, cfg)

        assertTrue(handled)
        val started = nextStartedActivity()
        assertNotNull(started)
        assertEquals(Intent.ACTION_VIEW, started!!.action)
        assertEquals("https://example.com/path?q=1", started.dataString)
    }

    @Test
    fun click_searchWeb_firesACTION_VIEW_withGoogleUrl() {
        val cfg = SelectionMenuConfig(context, "kawaii emoji")
        val handled = handleSelectionMenuItemClick(SelectionMenuItemIds.SEARCH_WEB, cfg)

        assertTrue(handled)
        val started = nextStartedActivity()
        assertNotNull(started)
        assertEquals(Intent.ACTION_VIEW, started!!.action)
        // Google search URL — we don't pin the host exactly so a future
        // region-default change doesn't break this test, but the q=
        // param with URL-encoded selection must be present.
        assertTrue("data URI must contain ?q=",
            started.dataString?.contains("?q=") == true)
        assertTrue("query must be URL-encoded",
            started.dataString?.contains("kawaii%20emoji") == true)
    }

    @Test
    fun click_unknownItem_returnsFalse() {
        val handled = handleSelectionMenuItemClick(
            /* not ours: */ 999,
            SelectionMenuConfig(context, "anything"),
        )
        assertFalse("Unknown id must fall through to the caller", handled)
        assertNull("Unknown id must NOT dispatch an intent", nextStartedActivity())
    }

    @Test
    fun click_openUrl_onNonUrlText_returnsFalse_noIntent() {
        // Safety net: if for any reason Open URL is registered but
        // the selection text is not a URL, dispatch must NOT fire.
        val cfg = SelectionMenuConfig(context, "not a url")
        val handled = handleSelectionMenuItemClick(SelectionMenuItemIds.OPEN_URL, cfg)
        assertFalse(handled)
        assertNull(nextStartedActivity())
    }

    // --- helpers ----------------------------------------------------------

    private fun newMenu(): Menu {
        // Robolectric does not expose a public Menu constructor; the
        // simplest portable seam is `com.android.internal.view.menu.MenuBuilder`.
        // It's platform-internal but the name has been stable since
        // API 19, and Robolectric ships its own implementation that
        // matches the production behaviour for findItem / add / size.
        val builderClass = Class.forName(
            "com.android.internal.view.menu.MenuBuilder",
        )
        val ctor = builderClass.getDeclaredConstructor(Context::class.java)
        ctor.isAccessible = true
        return ctor.newInstance(context) as Menu
    }

    private fun countExtensionItems(menu: Menu): Int {
        var n = 0
        if (menu.findItem(SelectionMenuItemIds.SHARE) != null) n++
        if (menu.findItem(SelectionMenuItemIds.OPEN_URL) != null) n++
        if (menu.findItem(SelectionMenuItemIds.SEARCH_WEB) != null) n++
        return n
    }

    private fun countItemsWithId(menu: Menu, itemId: Int): Int {
        // Menu.size() exists on the concrete implementation but not on
        // the Menu interface (it was added in API 30+). Reflectively
        // count occurrences for older API compatibility — pinned in
        // tests, not in production.
        val sizeMethod = menu.javaClass.getMethod("size")
        val size = sizeMethod.invoke(menu) as Int
        var n = 0
        for (i in 0 until size) {
            val getItem = menu.javaClass.getMethod("getItem", Int::class.javaPrimitiveType)
            val item = getItem.invoke(menu, i) as MenuItem
            if (item.itemId == itemId) n++
        }
        return n
    }
}