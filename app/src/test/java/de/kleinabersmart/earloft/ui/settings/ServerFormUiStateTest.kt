package de.kleinabersmart.earloft.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerFormUiStateTest {

    private val filled = ServerFormUiState(url = "https://musik.example.de", username = "kal")

    @Test
    fun `new server needs a password`() {
        assertFalse(filled.canSubmit)
        assertTrue(filled.copy(password = "x").canSubmit)
    }

    @Test
    fun `editing keeps the stored password when the field stays empty`() {
        val editing = filled.copy(isEditing = true, hasStoredPassword = true)
        assertTrue(editing.keepsStoredPassword)
        assertTrue(editing.canSubmit)
        assertFalse(editing.copy(password = "new").keepsStoredPassword)
    }

    @Test
    fun `editing with an unreadable password needs a new one`() {
        val editing = filled.copy(isEditing = true, hasStoredPassword = false)
        assertFalse(editing.canSubmit)
        assertTrue(editing.copy(password = "new").canSubmit)
    }

    @Test
    fun `nothing can be submitted while loading`() {
        assertFalse(filled.copy(isEditing = true, hasStoredPassword = true, isLoading = true).canSubmit)
    }

    @Test
    fun `typed changes count against the state the form opened with`() {
        val opened = filled.copy(initialFields = filled.fields())
        assertFalse(opened.isDirty)
        assertTrue(opened.copy(username = "other").isDirty)
        assertTrue(opened.copy(allowHttp = true).isDirty)
        // Typed back to the start: nothing to ask about.
        assertFalse(opened.copy(username = "other").copy(username = "kal").isDirty)
    }

    @Test
    fun `nothing to ask about while loading or once saved`() {
        assertFalse(filled.copy(isLoading = true).isDirty)
        assertFalse(filled.copy(initialFields = ServerFormUiState().fields(), done = true).isDirty)
    }
}
