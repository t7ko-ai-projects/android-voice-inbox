package t7helpers.voiceinbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShortcutTest {
    @Test
    fun listRoundTrips() {
        val list = listOf(
            Shortcut("a1", "Inbox", "content://x/Inbox.md", "Inbox.md", "ru"),
            Shortcut("b2", "Work “EN”", null, null, "en"),
        )
        assertEquals(list, Shortcut.listFromJson(Shortcut.listToJson(list)))
    }

    @Test
    fun missingLanguageIsRussian() {
        val s = Shortcut.listFromJson("""[{"id":"a","name":"Record"}]""").single()
        assertEquals(SpeechModel.RUSSIAN.language, s.language)
        assertNull(s.targetUri)
    }

    @Test
    fun captureKeepsItsShortcut() {
        val record = CaptureRecord("20261108-120000-000", 1L, CaptureStatus.READY, shortcut = "a1")
        assertEquals("a1", CaptureRecord.fromJson(record.toJson(), false).shortcut)
        val old = CaptureRecord("20261108-120000-000", 1L, CaptureStatus.READY)
        assertNull(CaptureRecord.fromJson(old.toJson(), false).shortcut)
    }
}
