package de.kleinabersmart.earloft.core.data.setup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SetupStateTest {

    @get:Rule val folder = TemporaryFolder()

    private fun TestScope.setupState(
        awaitRestore: suspend () -> Unit = {},
        hasContent: suspend () -> Boolean = { false },
    ): SetupState {
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { folder.newFile("setup.preferences_pb") }
        return SetupState(dataStore, awaitRestore, hasContent)
    }

    @Test
    fun `a fresh install without content shows the assistant`() = runTest {
        assertTrue(setupState().pending.first())
    }

    @Test
    fun `existing content counts as already set up`() = runTest {
        assertFalse(setupState(hasContent = { true }).pending.first())
    }

    @Test
    fun `finishing hides it for good`() = runTest {
        val state = setupState()
        assertTrue(state.pending.first())

        state.finish()

        assertFalse(state.pending.first())
    }

    @Test
    fun `the decision stays once made, even when content appears`() = runTest {
        var content = false
        val state = setupState(hasContent = { content })
        assertTrue(state.pending.first())

        content = true

        assertTrue(state.pending.first())
    }

    @Test
    fun `waits for the restore before looking at the content`() = runTest(UnconfinedTestDispatcher()) {
        val restored = CompletableDeferred<Unit>()
        var content = false
        val state = setupState(awaitRestore = { restored.await() }, hasContent = { content })
        val pending = async { state.pending.first() }
        runCurrent()
        assertFalse(pending.isCompleted)

        // Das Zurückspielen bringt einen Server mit.
        content = true
        restored.complete(Unit)

        assertEquals(false, pending.await())
    }
}
