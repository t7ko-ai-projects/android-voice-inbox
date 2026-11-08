package t7helpers.voiceinbox.ui

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import t7helpers.voiceinbox.R
import t7helpers.voiceinbox.Shortcuts
import t7helpers.voiceinbox.SpeechModel

/**
 * A home-screen button for one shortcut, for launchers that do not let the app pin
 * shortcuts. [RecordWidgetSetup] asks which shortcut when it is placed; the label follows
 * the shortcut's name.
 */
class RecordWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        Shortcuts.init(context)
        for (id in ids) update(context, manager, id)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        prefs(context).edit { ids.forEach { remove(it.toString()) } }
    }

    companion object {
        private fun prefs(context: Context) = context.getSharedPreferences("widgets", Context.MODE_PRIVATE)

        fun assign(context: Context, widgetId: Int, shortcutId: String) {
            prefs(context).edit(commit = true) { putString(widgetId.toString(), shortcutId) }
            update(context, AppWidgetManager.getInstance(context), widgetId)
        }

        /** Called whenever the shortcuts change. */
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            for (id in manager.getAppWidgetIds(ComponentName(context, RecordWidget::class.java))) update(context, manager, id)
        }

        private fun update(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val shortcutId = prefs(context).getString(widgetId.toString(), null) ?: return
            val shortcut = Shortcuts.get(shortcutId)
            val views = RemoteViews(context.packageName, R.layout.record_widget)
            views.setTextViewText(R.id.widget_label, shortcut?.name ?: "Deleted")
            // A deleted shortcut's widget opens the app, which says so.
            val intent = MainActivity.shortcutIntent(context, shortcutId)
            val pending = PendingIntent.getActivity(
                context,
                widgetId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_root, pending)
            manager.updateAppWidget(widgetId, views)
        }
    }
}

/** Shown when a Record widget is placed (and, on Android 12+, to change it later): pick its shortcut. */
class RecordWidgetSetup : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Backing out leaves no widget.
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        Shortcuts.init(this)
        enableEdgeToEdge()
        setContent {
            val shortcuts by Shortcuts.list.collectAsState()
            VoiceInboxTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Record widget", style = MaterialTheme.typography.headlineSmall)
                        Text("Which shortcut should it start?")
                        for (shortcut in shortcuts) {
                            Card(Modifier.fillMaxWidth().clickable {
                                RecordWidget.assign(this@RecordWidgetSetup, widgetId, shortcut.id)
                                setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                                finish()
                            }) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(shortcut.name, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "${shortcut.targetName ?: "No file chosen"} · ${SpeechModel.info(shortcut.language).languageName}",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                        Text(
                            "Add and edit shortcuts in the app: Settings, Shortcuts.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
