package de.kleinabersmart.earloft.ui.settings

import androidx.lifecycle.SavedStateHandle
import de.kleinabersmart.earloft.core.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerFormSavedFieldsTest {

    private val typed = ServerFormUiState(
        type = SourceType.JELLYFIN,
        name = "Home",
        url = "http://music.example.org",
        username = "kal",
        password = "secret",
        allowHttp = true,
    )

    @Test
    fun `a fresh form has nothing to restore`() {
        assertNull(SavedFields.from(SavedStateHandle()))
    }

    @Test
    fun `the typed fields come back, the password does not`() {
        val handle = SavedStateHandle()
        SavedFields.of(typed).writeTo(handle)

        val restored = SavedFields.from(handle)!!.applyTo(ServerFormUiState().copy(initialFields = ServerFormUiState().fields()))

        assertEquals(SourceType.JELLYFIN, restored.type)
        assertEquals("Home", restored.name)
        assertEquals("http://music.example.org", restored.url)
        assertEquals("kal", restored.username)
        assertTrue(restored.allowHttp)
        assertEquals("", restored.password)
        assertFalse(handle.keys().any { handle.get<Any>(it) == "secret" })
    }

    @Test
    fun `restored fields still count as typed, so leaving asks`() {
        val empty = ServerFormUiState().let { it.copy(initialFields = it.fields()) }

        assertTrue(SavedFields.of(typed).applyTo(empty).isDirty)
    }

    @Test
    fun `an edited server keeps its type`() {
        val editing = ServerFormUiState(isEditing = true, type = SourceType.SUBSONIC)

        assertEquals(SourceType.SUBSONIC, SavedFields.of(typed).applyTo(editing).type)
    }
}
