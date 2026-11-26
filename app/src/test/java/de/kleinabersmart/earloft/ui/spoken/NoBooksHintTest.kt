package de.kleinabersmart.earloft.ui.spoken

import de.kleinabersmart.earloft.ui.LoadState
import org.junit.Assert.assertEquals
import org.junit.Test

/** The hint "No books yet" on the books start page only appears for an empty library (review4 T41). */
class NoBooksHintTest {

    private val none = LoadState.Ready(emptyList<Int>())
    private val some = LoadState.Ready(listOf(1))
    private val noBooks = LoadState.Ready(false)
    private val books = LoadState.Ready(true)

    @Test
    fun `an empty library shows the hint`() {
        assertEquals(true, showsNoBooksHint(none, none, noBooks, hasServer = true))
    }

    @Test
    fun `a library whose books are all heard or hidden shows no hint`() {
        // Both shelves are empty, but syncing would change nothing.
        assertEquals(false, showsNoBooksHint(none, none, books, hasServer = true))
    }

    @Test
    fun `nothing is said while something loads or fails, or without a server`() {
        assertEquals(false, showsNoBooksHint(LoadState.Loading, none, noBooks, hasServer = true))
        assertEquals(false, showsNoBooksHint(none, none, LoadState.Failed, hasServer = true))
        assertEquals(false, showsNoBooksHint(none, none, noBooks, hasServer = false))
        assertEquals(false, showsNoBooksHint(none, none, noBooks, hasServer = null))
    }

    @Test
    fun `a shelf with books shows no hint`() {
        assertEquals(false, showsNoBooksHint(some, none, noBooks, hasServer = true))
        assertEquals(false, showsNoBooksHint(none, some, noBooks, hasServer = true))
    }
}
