package com.apexplow.hanterm.terminal.selection

import android.app.Application
import android.content.Context
import android.content.Intent
import android.view.Menu
import android.view.MenuItem
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Integration tests for the extension items appended to Termux's
 * floating text-selection toolbar (Share / Search web).
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
 *
 * **2026-08-01 — Open URL removed.** The user asked for URL opening to
 * live in the URL long-press (now single-tap) flow's
 * `LinkDialog`/`LinkIntentLauncher`, not buried in the selection
 * toolbar's overflow. These tests pin the remaining two items.
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
    fun add_plainText_addsShareAndSearchWeb() {
        val menu = newMenu()
        addSelectionMenuExtensions(menu, SelectionMenuConfig(context, "hello world"))
        assertNotNull("Share must be added for any non-empty selection",
            menu.findItem(SelectionMenuItemIds.SHARE))
        assertNotNull("Search web must be added for any non-empty selection",
            menu.findItem(SelectionMenuItemIds.SEARCH_WEB))
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