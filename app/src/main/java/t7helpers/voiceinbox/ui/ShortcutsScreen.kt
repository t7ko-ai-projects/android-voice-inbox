package t7helpers.voiceinbox.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import t7helpers.voiceinbox.Shortcut
import t7helpers.voiceinbox.SpeechModel

/** The list of shortcuts; a tap opens one for editing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShortcutsScreen(
    shortcuts: List<Shortcut>,
    targetOf: @Composable (Shortcut) -> Target,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Shortcuts") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = onAdd) { Icon(Icons.Filled.Add, contentDescription = "New shortcut") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            for (shortcut in shortcuts) {
                val target = targetOf(shortcut)
                Card(Modifier.fillMaxWidth().clickable { onOpen(shortcut.id) }) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(shortcut.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${target.name ?: "No file chosen"} · ${SpeechModel.info(shortcut.language).languageName}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (target.name != null && !target.accessible) {
                            Text("The app lost access to this file.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Button(onClick = onAdd) { Text("New shortcut") }
            Text(
                "Put a shortcut on the home screen with \"Add to home screen\" in it, or with the Voice " +
                    "Inbox widget, which asks for the shortcut when you place it. A long press on the app " +
                    "icon lists the first few shortcuts too. Names and files changed here apply to them all.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** One shortcut: name, file and language, saved as they change. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShortcutScreen(
    shortcut: Shortcut,
    target: Target,
    canDelete: Boolean,
    onChange: (Shortcut) -> Unit,
    onPickTarget: () -> Unit,
    onPin: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    // The field may be empty while typing; the stored name never is.
    var name by remember(shortcut.id) { mutableStateOf(shortcut.name) }
    var confirmDelete by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(shortcut.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    if (it.isNotBlank()) onChange(shortcut.copy(name = it.trim()))
                },
                label = { Text("Name on the home screen") },
                singleLine = true,
                isError = name.isBlank(),
                supportingText = { if (name.isBlank()) Text("A name is needed; the old one stays until then.") },
                modifier = Modifier.fillMaxWidth(),
            )
            TargetCard(target, onPickTarget)
            Section("Language") {
                LanguageChips(shortcut.language) { onChange(shortcut.copy(language = it)) }
            }
            Section("Home screen") {
                OutlinedButton(onClick = onPin) { Text("Add to home screen") }
                Text(
                    "If nothing appears, the launcher may need the app's permission for home screen " +
                        "shortcuts (on Xiaomi phones: App info, Other permissions). The Voice Inbox widget " +
                        "works without it.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (canDelete) {
                Row { TextButton(onClick = { confirmDelete = true }) { Text("Delete shortcut", color = MaterialTheme.colorScheme.error) } }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete “${shortcut.name}”?") },
            text = {
                Text(
                    "Its home screen shortcuts stop working; widgets show it as deleted. Captures made " +
                        "with it stay in History, and unsaved ones are saved to the file of the shortcut " +
                        "chosen in the app.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
