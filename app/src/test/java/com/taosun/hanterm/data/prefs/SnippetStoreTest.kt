package com.taosun.hanterm.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric tests for [SnippetStore] — Sprint 3 / Module 16 / SNP-ST-01..06.
 *
 * Each test starts from a clean SharedPreferences so cases don't bleed into
 * each other. Robolectric gives us a real `applicationContext` whose
 * SharedPreferences writes actually round-trip through the file system,
 * which is what the production code uses — no shadow indirection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SnippetStoreTest {

    private lateinit var context: Context
    private lateinit var store: SnippetStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Belt-and-braces: wipe any leftover SharedPreferences from prior
        // tests so the empty-store assertions (SNP-ST-01) start from a
        // truly empty state.
        context.getSharedPreferences(SnippetStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        store = SnippetStore(context)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(SnippetStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    // ---- SNP-ST-01: getAll on never-written store returns empty list ----

    @Test
    fun snp_st_01_getAllReturnsEmptyListWhenNeverWritten() {
        assertEquals(
            "fresh store must return an empty list (SNP-ST-01)",
            emptyList<CommandSnippet>(),
            store.getAll(),
        )
    }

    // ---- SNP-ST-02: add appends to the end, preserves insertion order ----

    @Test
    fun snp_st_02_addAppendsAndPreservesInsertionOrder() {
        val a = CommandSnippet(label = "list", command = "ls -la")
        val b = CommandSnippet(label = "status", command = "systemctl status nginx")

        store.add(a)
        store.add(b)

        val all = store.getAll()
        assertEquals(2, all.size)
        assertEquals("first-added must be at index 0", a, all[0])
        assertEquals("second-added must be at index 1", b, all[1])
    }

    // ---- SNP-ST-03: update replaces in place, preserves position ----

    @Test
    fun snp_st_03_updatePreservesListPosition() {
        val a = CommandSnippet(label = "list", command = "ls")
        val b = CommandSnippet(label = "status", command = "systemctl status nginx")
        val c = CommandSnippet(label = "tail", command = "tail -f /var/log/syslog")

        store.add(a)
        store.add(b)
        store.add(c)

        // Update the middle entry — its position MUST NOT change.
        val updatedB = b.copy(label = "svc-status", command = "systemctl status sshd")
        store.update(updatedB)

        val all = store.getAll()
        assertEquals(
            "size must remain 3 after update (no add or delete side-effect)",
            3,
            all.size,
        )
        assertEquals(
            "updated entry must stay at index 1 — insertion order preserved",
            updatedB,
            all[1],
        )
        // The id MUST be unchanged on update (SNP-DM-02 invariant).
        assertEquals(
            "id must be stable across update",
            b.id,
            all[1].id,
        )
        // The unchanged entries must still be the same references.
        assertEquals(a, all[0])
        assertEquals(c, all[2])
    }

    @Test
    fun snp_st_03_updateUnknownId_appendsAsFallback() {
        // Defensive: if a caller passes a snippet whose id isn't in the
        // store, we append rather than silently losing data. The UI's edit
        // flow normally won't hit this branch, but a stale reference from
        // a previous session shouldn't drop the user's edit.
        val a = CommandSnippet(label = "a", command = "echo a")
        store.add(a)

        val ghost = CommandSnippet(label = "ghost", command = "echo ghost")
        assertNotEquals("test precondition: ghost id must differ from a's id", ghost.id, a.id)

        store.update(ghost)

        val all = store.getAll()
        assertEquals(2, all.size)
        assertEquals(a, all[0])
        assertEquals(ghost, all[1])
    }

    // ---- SNP-ST-04: delete removes by id, no-op on unknown id ----

    @Test
    fun snp_st_04_deleteRemovesById() {
        val a = CommandSnippet(label = "a", command = "echo a")
        val b = CommandSnippet(label = "b", command = "echo b")
        store.add(a)
        store.add(b)

        store.delete(a.id)

        val all = store.getAll()
        assertEquals(1, all.size)
        assertEquals(b, all.single())
    }

    @Test
    fun snp_st_04_deleteUnknownId_isNoOp() {
        val a = CommandSnippet(label = "a", command = "echo a")
        store.add(a)

        // Delete an id that was never added — must NOT throw.
        store.delete("00000000-0000-0000-0000-000000000000")

        val all = store.getAll()
        assertEquals("delete of unknown id must leave the store unchanged", 1, all.size)
        assertEquals(a, all.single())
    }

    // ---- SNP-ST-05: storage is a single SharedPreferences string field ----

    @Test
    fun snp_st_05_storageIsSingleSharedPreferencesField() {
        val a = CommandSnippet(label = "list", command = "ls -la")
        store.add(a)

        val prefs = context.getSharedPreferences(
            SnippetStore.PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        val raw = prefs.getString(SnippetStore.KEY_SNIPPETS, null)
        assertNotNull("snippet JSON must be persisted under KEY_SNIPPETS", raw)
        assertTrue(
            "persisted payload must look like a JSON array, was: $raw",
            raw!!.startsWith("["),
        )
        assertTrue(
            "persisted payload must end like a JSON array, was: $raw",
            raw.endsWith("]"),
        )
        // Confirm there's exactly one key under our prefs file — defends
        // against a future maintainer accidentally adding a second field
        // that splits the source-of-truth.
        assertEquals(
            "store must own exactly one SharedPreferences key",
            setOf(SnippetStore.KEY_SNIPPETS),
            prefs.all.keys,
        )
    }

    // ---- SNP-ST-06: corrupted JSON returns empty list, no throw ----

    @Test
    fun snp_st_06_corruptedJsonReturnsEmptyList() {
        // Write three flavors of corruption directly into SharedPreferences:
        //   1. truncated JSON array
        //   2. valid JSON but wrong shape (object instead of array)
        //   3. garbage text
        // All three must yield an empty list, not crash the UI.
        val prefs = context.getSharedPreferences(
            SnippetStore.PREFS_NAME,
            Context.MODE_PRIVATE,
        )

        prefs.edit().putString(SnippetStore.KEY_SNIPPETS, "[{\"id\":\"abc").commit()
        assertEquals(
            "truncated JSON must read as empty list",
            emptyList<CommandSnippet>(),
            store.getAll(),
        )

        prefs.edit().putString(SnippetStore.KEY_SNIPPETS, "{\"not\":\"an array\"}").commit()
        assertEquals(
            "JSON object (not array) must read as empty list",
            emptyList<CommandSnippet>(),
            store.getAll(),
        )

        prefs.edit().putString(SnippetStore.KEY_SNIPPETS, "not even json").commit()
        assertEquals(
            "non-JSON garbage must read as empty list",
            emptyList<CommandSnippet>(),
            store.getAll(),
        )
    }

    @Test
    fun snp_st_06_perEntryMalformedRowDoesNotPoisonList() {
        // A well-formed entry sandwiched between malformed ones must still
        // come back. Mirrors KHS-ST-06's row-level tolerance.
        val prefs = context.getSharedPreferences(
            SnippetStore.PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        // First object: missing 'command' field → skipped.
        // Second object: well-formed → kept.
        prefs.edit().putString(
            SnippetStore.KEY_SNIPPETS,
            "[{\"id\":\"bad\",\"label\":\"no-cmd\"}," +
                "{\"id\":\"good\",\"label\":\"ok\",\"command\":\"echo hi\",\"appendNewline\":true}]",
        ).commit()

        val all = store.getAll()
        assertEquals("only the well-formed row must survive", 1, all.size)
        assertEquals("good", all.single().id)
        assertEquals("echo hi", all.single().command)
    }

    // ---- recovery: a write after a corruption heals the store ----

    @Test
    fun snp_st_06_nextWriteHealsTheStore() {
        val prefs = context.getSharedPreferences(
            SnippetStore.PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        prefs.edit().putString(SnippetStore.KEY_SNIPPETS, "garbage").commit()
        assertEquals(emptyList<CommandSnippet>(), store.getAll())

        // Next add must overwrite the bad blob with valid JSON.
        val a = CommandSnippet(label = "list", command = "ls")
        store.add(a)

        val after = prefs.getString(SnippetStore.KEY_SNIPPETS, null)
        assertNotNull(after)
        assertFalse(
            "post-recovery payload must not contain the original garbage",
            after!!.contains("garbage"),
        )
        assertEquals(1, store.getAll().size)
    }
}