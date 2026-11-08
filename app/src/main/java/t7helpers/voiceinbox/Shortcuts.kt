package t7helpers.voiceinbox

import android.content.Context
import androidx.core.net.toUri
import androidx.core.content.edit
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import t7helpers.voiceinbox.ui.MainActivity
import t7helpers.voiceinbox.ui.RecordWidget
import java.util.UUID

/**
 * A way to start recording: its own name (shown on the home screen), Markdown file and
 * language. [targetUri] is null until a file is chosen.
 */
data class Shortcut(
    val id: String,
    val name: String,
    val targetUri: String? = null,
    val targetName: String? = null,
    val language: String = SpeechModel.RUSSIAN.language,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .putOpt("targetUri", targetUri)
        .putOpt("targetName", targetName)
        .put("language", language)

    companion object {
        fun fromJson(o: JSONObject) = Shortcut(
            id = o.getString("id"),
            name = o.getString("name"),
            targetUri = if (o.has("targetUri")) o.getString("targetUri") else null,
            targetName = if (o.has("targetName")) o.getString("targetName") else null,
            language = o.optString("language", SpeechModel.RUSSIAN.language),
        )

        fun listToJson(list: List<Shortcut>): String = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

        fun listFromJson(json: String): List<Shortcut> {
            val array = JSONArray(json)
            return (0 until array.length()).map { fromJson(array.getJSONObject(it)) }
        }

        fun newId(): String = UUID.randomUUID().toString().substring(0, 8)
    }
}

/**
 * The user's shortcuts, kept in SharedPreferences, and the one the app's own screen
 * records with ([selected]). Every change is mirrored to the launcher: the long-press
 * menu of the app icon, shortcuts pinned to the home screen, and Record widgets.
 */
object Shortcuts {
    private const val LIST = "shortcuts"
    private const val SELECTED = "selectedShortcut"

    // The single target and language of versions before 1.1.0, moved into the first shortcut.
    private const val OLD_TARGET_URI = "targetUri"
    private const val OLD_TARGET_NAME = "targetName"
    private const val OLD_LANGUAGE = "language"

    /** Launcher shortcut ids: this plus [Shortcut.id]. */
    private const val PIN_PREFIX = "s-"

    private val _list = MutableStateFlow<List<Shortcut>>(emptyList())
    val list: StateFlow<List<Shortcut>> = _list
    private val _selected = MutableStateFlow("")
    val selectedId: StateFlow<String> = _selected

    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Loads the list; the first start of 1.1.0 turns the old single target into the shortcut "Record". */
    @Synchronized
    fun init(context: Context) {
        if (_list.value.isNotEmpty()) return
        val prefs = prefs(context)
        val stored = prefs.getString(LIST, null)?.let { runCatching { Shortcut.listFromJson(it) }.getOrNull() }
        val list = if (stored.isNullOrEmpty()) {
            listOf(
                Shortcut(
                    Shortcut.newId(),
                    "Record",
                    prefs.getString(OLD_TARGET_URI, null),
                    prefs.getString(OLD_TARGET_NAME, null),
                    prefs.getString(OLD_LANGUAGE, null) ?: SpeechModel.RUSSIAN.language,
                ),
            )
        } else {
            stored
        }
        val selected = prefs.getString(SELECTED, null)?.takeIf { id -> list.any { it.id == id } } ?: list.first().id
        _list.value = list
        _selected.value = selected
        if (stored.isNullOrEmpty()) store(context)
        publish(context)
    }

    fun get(id: String?): Shortcut? = _list.value.find { it.id == id }

    val selected: Shortcut get() = get(_selected.value) ?: _list.value.first()

    fun select(context: Context, id: String) {
        if (get(id) == null || _selected.value == id) return
        _selected.value = id
        prefs(context).edit(commit = true) { putString(SELECTED, id) }
    }

    /** A new shortcut with the selected one's file and language; returns it. */
    @Synchronized
    fun add(context: Context): Shortcut {
        val base = selected
        val names = _list.value.map { it.name }.toSet()
        val name = generateSequence(2) { it + 1 }.map { "Record $it" }.first { it !in names }
        val shortcut = Shortcut(Shortcut.newId(), name, base.targetUri, base.targetName, base.language)
        _list.value = _list.value + shortcut
        store(context)
        publish(context)
        return shortcut
    }

    @Synchronized
    fun update(context: Context, shortcut: Shortcut) {
        val old = get(shortcut.id) ?: return
        if (old == shortcut) return
        _list.value = _list.value.map { if (it.id == shortcut.id) shortcut else it }
        store(context)
        publish(context)
        if (old.targetUri != shortcut.targetUri) old.targetUri?.let { TargetFile.releaseIfUnused(context, it.toUri()) }
    }

    /** The last shortcut cannot be deleted. Returns false then. */
    @Synchronized
    fun delete(context: Context, id: String): Boolean {
        val old = get(id) ?: return false
        if (_list.value.size <= 1) return false
        _list.value = _list.value.filter { it.id != id }
        if (_selected.value == id) select(context, _list.value.first().id)
        store(context)
        publish(context)
        old.targetUri?.let { TargetFile.releaseIfUnused(context, it.toUri()) }
        return true
    }

    /** Asks the launcher to put [shortcut] on the home screen. False if it does not support that. */
    fun pin(context: Context, shortcut: Shortcut): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false
        return ShortcutManagerCompat.requestPinShortcut(context, info(context, shortcut), null)
    }

    private fun store(context: Context) = prefs(context).edit(commit = true) {
        putString(LIST, Shortcut.listToJson(_list.value))
        putString(SELECTED, _selected.value)
    }

    private fun info(context: Context, shortcut: Shortcut) = ShortcutInfoCompat.Builder(context, PIN_PREFIX + shortcut.id)
        .setShortLabel(shortcut.name)
        .setLongLabel(shortcut.name)
        .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_record))
        .setIntent(MainActivity.shortcutIntent(context, shortcut.id))
        .build()

    /**
     * The long-press menu shows the first shortcuts that fit; pinned shortcuts and widgets
     * get the new names; pinned shortcuts of deleted ones are disabled.
     */
    private fun publish(context: Context) {
        runCatching {
            val list = _list.value
            val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceAtLeast(1)
            ShortcutManagerCompat.setDynamicShortcuts(context, list.take(max).map { info(context, it) })
            // Pinned ones that are not in the long-press menu.
            ShortcutManagerCompat.updateShortcuts(context, list.drop(max).map { info(context, it) })
            val ids = list.map { PIN_PREFIX + it.id }.toSet()
            val gone = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
                .map { it.id }
                .filter { it !in ids }
            if (gone.isNotEmpty()) ShortcutManagerCompat.disableShortcuts(context, gone, "This shortcut was deleted in Voice Inbox.")
        }
        RecordWidget.updateAll(context)
    }
}
