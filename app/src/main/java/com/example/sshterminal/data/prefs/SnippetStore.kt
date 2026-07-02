package com.example.sshterminal.data.prefs

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/**
 * A user-saved command to send into the active SSH session.
 *
 * Sprint 3 / Module 16 / SNP-DM-01..02:
 *   - [id] is assigned once at construction and is stable for the lifetime
 *     of the snippet — edits to `label` / `command` / `appendNewline` MUST
 *     NOT change it. [SnippetStore.update] relies on this to find the
 *     existing entry to replace in place (SNP-ST-03 — list order is
 *     preserved across edits).
 *   - [appendNewline] defaults to `true` so a freshly-added snippet behaves
 *     like a "tap to run" shortcut rather than a partial-keystroke stub.
 */
data class CommandSnippet(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val command: String,
    val appendNewline: Boolean = true,
)

/**
 * Persistent store for [CommandSnippet]s.
 *
 * ## Storage backend
 *
 * [SharedPreferences] with a single `String` field holding a JSON array.
 * Why SharedPreferences and not AtomicFile (which [com.example.sshterminal.ssh.security.KnownHostsStore]
 * uses): snippets are a tiny list (typically 5–20 entries), the read/write
 * happens entirely on the UI thread or a single test thread, and the
 * AtomicFile machinery buys us crash-safety against process kill mid-write
 * that we don't actually need for a v1 best-effort list. Per CLAUDE.md's
 * "no libraries not listed in implementation_plan.md", JSON serialization
 * uses Android's built-in [org.json] classes — no Gson / Moshi /
 * kotlinx.serialization.
 *
 * ## Corruption tolerance
 *
 * Per SNP-ST-06 (mirroring `KnownHostsStore`'s KHS-ST-06 posture): any
 * parse error → [getAll] returns an empty list. A corrupted store is
 * equivalent to "no data", NOT a crash. The next [add] / [update] call
 * overwrites the bad blob with valid JSON, so the user self-heals on
 * the next save.
 *
 * ## Threading
 *
 * All mutating methods take a `synchronized(this)` lock on the store
 * instance so concurrent `add` / `update` / `delete` calls from multiple
 * coroutines can't tear the read-modify-write cycle (SharedPreferences
 * itself is thread-safe for individual ops, but not for the compound
 * "read list, mutate, write list" sequence this class needs).
 */
class SnippetStore(context: Context) {

    private val appContext: Context = context.applicationContext

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------

    /**
     * Returns every persisted snippet in insertion order. Returns an empty
     * list if the store has never been written OR if the persisted JSON is
     * unparseable (SNP-ST-06).
     */
    fun getAll(): List<CommandSnippet> {
        val raw = prefs.getString(KEY_SNIPPETS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    parseSnippet(obj)?.let { add(it) }
                }
            }
        } catch (e: JSONException) {
            // Malformed JSON at the top level (truncated file, manual edit,
            // older format). Treat as "no data" rather than crashing the UI.
            // The next mutating call will overwrite the bad blob.
            emptyList()
        }
    }

    /**
     * Appends [snippet] to the end of the list. The [CommandSnippet.id] is
     * the one returned by the caller's constructor; this method does NOT
     * regenerate it (SNP-DM-02 — the id is generated once at creation and
     * never changes).
     */
    fun add(snippet: CommandSnippet) = mutate { it + snippet }

    /**
     * Replaces the entry with the same [CommandSnippet.id] in place.
     * If no entry with that id exists, [snippet] is appended as if [add]
     * were called (defensive — the UI's edit flow normally wouldn't reach
     * this branch, but a stale reference shouldn't crash or silently lose
     * data). SNP-ST-03: position of the original entry is preserved.
     */
    fun update(snippet: CommandSnippet) = mutate { current ->
        val idx = current.indexOfFirst { it.id == snippet.id }
        if (idx >= 0) {
            current.toMutableList().apply { this[idx] = snippet }
        } else {
            current + snippet
        }
    }

    /**
     * Removes the entry with the given [id]. No-op if the id isn't in the
     * store (SNP-ST-04).
     */
    fun delete(id: String) = mutate { current ->
        current.filterNot { it.id == id }
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    /**
     * Runs [transform] under a lock against the current list, persists the
     * result, and returns the new list. The lock prevents concurrent
     * add/update/delete from racing on the read-modify-write cycle.
     */
    private fun mutate(transform: (List<CommandSnippet>) -> List<CommandSnippet>): List<CommandSnippet> {
        return synchronized(this) {
            val current = getAll()
            val updated = transform(current)
            prefs.edit().putString(KEY_SNIPPETS, serialize(updated)).apply()
            updated
        }
    }

    /**
     * Encodes [snippets] to a JSON array string. Order is preserved
     * because [JSONArray] is ordered and we add in the order received.
     */
    private fun serialize(snippets: List<CommandSnippet>): String {
        val arr = JSONArray()
        snippets.forEach { snippet ->
            val obj = JSONObject().apply {
                put(KEY_FIELD_ID, snippet.id)
                put(KEY_FIELD_LABEL, snippet.label)
                put(KEY_FIELD_COMMAND, snippet.command)
                put(KEY_FIELD_APPEND_NEWLINE, snippet.appendNewline)
            }
            arr.put(obj)
        }
        return arr.toString()
    }

    /**
     * Parses a single snippet object. Returns `null` on any per-field
     * error — a malformed individual entry doesn't poison the rest of the
     * list (SNP-ST-06's row-level analogue).
     */
    private fun parseSnippet(obj: JSONObject): CommandSnippet? = try {
        CommandSnippet(
            id = obj.getString(KEY_FIELD_ID),
            label = obj.getString(KEY_FIELD_LABEL),
            command = obj.getString(KEY_FIELD_COMMAND),
            appendNewline = obj.optBoolean(KEY_FIELD_APPEND_NEWLINE, true),
        )
    } catch (e: JSONException) {
        // Missing required field or wrong type — skip this entry.
        null
    }

    companion object {
        const val PREFS_NAME = "ssh_term_snippets"
        const val KEY_SNIPPETS = "snippets_v1"

        // Field names — keep in sync with [serialize] and [parseSnippet].
        private const val KEY_FIELD_ID = "id"
        private const val KEY_FIELD_LABEL = "label"
        private const val KEY_FIELD_COMMAND = "command"
        private const val KEY_FIELD_APPEND_NEWLINE = "appendNewline"
    }
}