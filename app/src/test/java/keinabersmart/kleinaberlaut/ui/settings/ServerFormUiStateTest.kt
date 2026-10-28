package keinabersmart.kleinaberlaut.ui.settings

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
        assertFalse(editing.copy(password = "neu").keepsStoredPassword)
    }

    @Test
    fun `editing with an unreadable password needs a new one`() {
        val editing = filled.copy(isEditing = true, hasStoredPassword = false)
        assertFalse(editing.canSubmit)
        assertTrue(editing.copy(password = "neu").canSubmit)
    }

    @Test
    fun `nothing can be submitted while loading`() {
        assertFalse(filled.copy(isEditing = true, hasStoredPassword = true, isLoading = true).canSubmit)
    }
}
