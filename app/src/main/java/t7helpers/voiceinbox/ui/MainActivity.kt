package t7helpers.voiceinbox.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import kotlinx.coroutines.delay
import t7helpers.voiceinbox.Capture
import t7helpers.voiceinbox.CaptureState
import t7helpers.voiceinbox.Shortcut
import t7helpers.voiceinbox.Shortcuts
import t7helpers.voiceinbox.SpeechModel
import t7helpers.voiceinbox.TargetFile

private enum class Screen { CAPTURE, HISTORY, SETTINGS, SHORTCUTS, SHORTCUT }

/** The Markdown file as the screens show it. */
data class Target(val name: String?, val accessible: Boolean, val problem: String? = null)

class MainActivity : ComponentActivity() {
    private var screen by mutableStateOf(Screen.CAPTURE)

    /** The shortcut open in the editor ([Screen.SHORTCUT]). */
    private var editing by mutableStateOf<String?>(null)

    /** Why the last file picked for a shortcut cannot be used, by [Shortcut.id]. */
    private var targetProblems by mutableStateOf(mapOf<String, String>())

    /** Bumped on every resume, so file access is checked again. */
    private var resumes by mutableIntStateOf(0)

    /** The shortcut whose file is being picked. */
    private var pickingFor: String? = null

    /** Set by a shortcut or widget; recording starts once the activity is in front. */
    private var recordOnResume: Capture.Start? = null

    /** The recording being started, perhaps after the permission prompt. */
    private var pendingStart: Capture.Start? = null

    private val targetPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val shortcut = Shortcuts.get(pickingFor)
        if (uri != null && shortcut != null) {
            when (val choice = TargetFile.choose(this, uri)) {
                is TargetFile.Choice.Picked -> {
                    targetProblems = targetProblems - shortcut.id
                    Shortcuts.update(this, shortcut.copy(targetUri = choice.uri.toString(), targetName = choice.name))
                }
                is TargetFile.Choice.Refused -> targetProblems = targetProblems + (shortcut.id to choice.message)
            }
        }
    }

    private val microphoneRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val how = pendingStart ?: Capture.Start(Shortcuts.selected.id, false)
        if (granted) Capture.start(this, how) else Capture.refuse("Voice Inbox needs the microphone. Allow it in App info.", openAppInfo = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Shortcuts.init(this)
        Capture.init(this)
        SpeechModel.refresh(this)
        SpeechModel.preload(this, Shortcuts.selected.language)
        screen = savedInstanceState?.getString(KEY_SCREEN)?.let { name -> Screen.entries.find { it.name == name } }
            ?: Screen.CAPTURE
        editing = savedInstanceState?.getString(KEY_EDITING)
        pickingFor = savedInstanceState?.getString(KEY_PICKING)
        // Not again after a rotation, which recreates the activity with the same intent.
        if (savedInstanceState == null) handle(intent)

        setContent {
            val state by Capture.state.collectAsState()
            val shortcuts by Shortcuts.list.collectAsState()
            val selectedId by Shortcuts.selectedId.collectAsState()
            val selected = shortcuts.find { it.id == selectedId } ?: shortcuts.first()
            LaunchedEffect(state) {
                val saved = state as? CaptureState.Saved
                if (saved != null && saved.closeApp) {
                    delay(CLOSE_DELAY_MS)
                    if (Capture.state.value == saved) {
                        Capture.close()
                        moveTaskToBack(true)
                    }
                }
            }
            VoiceInboxTheme {
                BackHandler(enabled = screen != Screen.CAPTURE) {
                    screen = when (screen) {
                        Screen.SHORTCUT -> Screen.SHORTCUTS
                        Screen.SHORTCUTS -> Screen.SETTINGS
                        else -> Screen.CAPTURE
                    }
                }
                when (screen) {
                    Screen.CAPTURE -> CaptureScreen(
                        shortcuts,
                        selected,
                        target(selected),
                        onSelectShortcut = {
                            Shortcuts.select(this, it)
                            SpeechModel.preload(this, Shortcuts.selected.language)
                        },
                        onLanguage = { setLanguage(selected, it) },
                        onRecord = { record(Capture.Start(Shortcuts.selected.id, fromIcon = false)) },
                        onPickTarget = { pickTarget(selected.id) },
                        onOpenAppInfo = ::openAppInfo,
                        onOpenHistory = { screen = Screen.HISTORY },
                        onOpenSettings = { screen = Screen.SETTINGS },
                    )
                    Screen.HISTORY -> HistoryScreen(onOpen = ::openCapture, onBack = { screen = Screen.CAPTURE })
                    Screen.SETTINGS -> SettingsScreen(
                        shortcuts,
                        onOpenShortcuts = { screen = Screen.SHORTCUTS },
                        onBack = { screen = Screen.CAPTURE },
                    )
                    Screen.SHORTCUTS -> ShortcutsScreen(
                        shortcuts,
                        targetOf = { target(it) },
                        onOpen = {
                            editing = it
                            screen = Screen.SHORTCUT
                        },
                        onAdd = {
                            editing = Shortcuts.add(this).id
                            screen = Screen.SHORTCUT
                        },
                        onBack = { screen = Screen.SETTINGS },
                    )
                    Screen.SHORTCUT -> {
                        val shortcut = shortcuts.find { it.id == editing }
                        if (shortcut == null) {
                            LaunchedEffect(Unit) { screen = Screen.SHORTCUTS }
                        } else {
                            ShortcutScreen(
                                shortcut,
                                target(shortcut),
                                canDelete = shortcuts.size > 1,
                                onChange = {
                                    Shortcuts.update(this, it)
                                    SpeechModel.preload(this, it.language)
                                },
                                onPickTarget = { pickTarget(shortcut.id) },
                                onPin = { pin(shortcut) },
                                onDelete = {
                                    Shortcuts.delete(this, shortcut.id)
                                    screen = Screen.SHORTCUTS
                                },
                                onBack = { screen = Screen.SHORTCUTS },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        resumes++
        recordOnResume?.let {
            recordOnResume = null
            record(it)
        }
    }

    override fun onStop() {
        super.onStop()
        // No background recording: a recording ends when the app leaves the screen. Its
        // audio and text are kept, and so is edited text.
        if (!isChangingConfigurations) Capture.onHidden()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SCREEN, screen.name)
        outState.putString(KEY_EDITING, editing)
        outState.putString(KEY_PICKING, pickingFor)
    }

    private fun handle(intent: Intent) {
        if (intent.action != ACTION_RECORD) return
        screen = Screen.CAPTURE
        val id = intent.getStringExtra(EXTRA_SHORTCUT)
        if (id != null && Shortcuts.get(id) == null) {
            Capture.refuse("This shortcut was deleted. Remove it from the home screen.", openAppInfo = false)
            return
        }
        recordOnResume = Capture.Start(id ?: Shortcuts.selected.id, fromIcon = true)
    }

    /** The shortcut's file as the screens show it; the access grant is read again on every resume. */
    @Composable
    private fun target(shortcut: Shortcut): Target {
        val problem = targetProblems[shortcut.id]
        val uri = shortcut.targetUri ?: return Target(null, false, problem)
        val accessible = remember(uri, resumes) { TargetFile.hasAccess(this, uri.toUri()) }
        return Target(shortcut.targetName, accessible, problem)
    }

    private fun setLanguage(shortcut: Shortcut, language: String) {
        Shortcuts.update(this, shortcut.copy(language = language))
        SpeechModel.preload(this, language)
    }

    private fun record(how: Capture.Start) {
        pendingStart = how
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            Capture.start(this, how)
        } else {
            microphoneRequest.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun pickTarget(shortcutId: String) {
        pickingFor = shortcutId
        // Markdown files often have no MIME type of their own, so any file can be picked.
        targetPicker.launch(arrayOf("*/*"))
    }

    /** Asks the launcher to pin [shortcut]; the widget is the way where it cannot. */
    private fun pin(shortcut: Shortcut) {
        if (!Shortcuts.pin(this, shortcut)) {
            Toast.makeText(this, "This launcher cannot add shortcuts. Add the Voice Inbox widget instead.", Toast.LENGTH_LONG).show()
        }
    }

    private fun openCapture(id: String) {
        if (Capture.open(id)) {
            screen = Screen.CAPTURE
        } else {
            Toast.makeText(this, "Finish the current capture first.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAppInfo() {
        startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    companion object {
        private const val KEY_SCREEN = "screen"
        private const val KEY_EDITING = "editing"
        private const val KEY_PICKING = "picking"

        /** How long "Saved" shows before the app goes back to the home screen. */
        private const val CLOSE_DELAY_MS = 1500L
        private const val ACTION_RECORD = "t7helpers.voiceinbox.action.RECORD"

        const val EXTRA_SHORTCUT = "t7helpers.voiceinbox.extra.SHORTCUT"

        /** Opens the capture screen and starts recording with [shortcutId], also when the app is open already. */
        fun recordIntent(context: Context, shortcutId: String?): Intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_RECORD)
            .apply { if (shortcutId != null) putExtra(EXTRA_SHORTCUT, shortcutId) }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        /** For launcher shortcuts and widgets: goes through [RecordActivity], see there. */
        fun shortcutIntent(context: Context, shortcutId: String): Intent = Intent(context, RecordActivity::class.java)
            .setAction(ACTION_RECORD)
            .putExtra(EXTRA_SHORTCUT, shortcutId)
    }
}
