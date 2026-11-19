package de.kleinabersmart.earloft.ui.components

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.data.mix.MixRepository
import de.kleinabersmart.earloft.core.data.mix.MixSeed
import de.kleinabersmart.earloft.testing.FakePlaybackConnection
import de.kleinabersmart.earloft.testing.MainDispatcherRule
import de.kleinabersmart.earloft.testing.awaitReal
import de.kleinabersmart.earloft.ui.snackbar.Snackbars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject

/** Starting a mix: without matching songs, a hint appears instead of playback (review F27). */
@HiltAndroidTest
@Config(application = HiltTestApplication::class)
@RunWith(RobolectricTestRunner::class)
class MixViewModelTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val main = MainDispatcherRule()

    @Inject lateinit var mixRepository: MixRepository
    @Inject lateinit var playback: FakePlaybackConnection
    @Inject lateinit var snackbars: Snackbars

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val viewModel by lazy { MixViewModel(context, mixRepository, playback, snackbars) }

    @Before
    fun setUp() {
        hilt.inject()
    }

    @Test
    fun `a mix without songs says so and starts nothing`() = runTest {
        val shown = CopyOnWriteArrayList<String>()
        backgroundScope.launch(Dispatchers.Unconfined) { snackbars.messages.collect { shown += it.text } }

        viewModel.start(MixSeed.Album(albumId = 999), afterCurrent = false)

        // The test scheduler skips the wait for Room, so the "Mix wird zusammengestellt" hint comes first.
        awaitReal { while (context.getString(R.string.mix_empty) !in shown) delay(10) }
        assertEquals(context.getString(R.string.mix_empty), shown.last())
        assertTrue(playback.commands.value.isEmpty())
    }
}
