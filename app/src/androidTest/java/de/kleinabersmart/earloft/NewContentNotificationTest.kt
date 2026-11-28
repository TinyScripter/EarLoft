package de.kleinabersmart.earloft

import android.app.Notification
import android.app.NotificationManager
import androidx.core.app.NotificationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import de.kleinabersmart.earloft.core.data.refresh.NewContent
import de.kleinabersmart.earloft.core.data.refresh.NewEpisodes
import de.kleinabersmart.earloft.core.model.SpokenMode
import de.kleinabersmart.earloft.debug.NewContentTestEntryPoint
import de.kleinabersmart.earloft.notification.AppNewContentNotifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * review5 P19: a second "new episodes" notification keeps the unread first one; once that was dismissed, the next one starts
 * fresh. Needs notifications allowed for the app; otherwise skipped. Uses invented podcasts, the ids belong to no episode.
 */
@RunWith(AndroidJUnit4::class)
class NewContentNotificationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val notifier = EntryPointAccessors.fromApplication(context, NewContentTestEntryPoint::class.java).newContentTestNotifier()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val id = AppNewContentNotifier.notificationId(SpokenMode.PODCASTS)

    @Before
    fun setUp() {
        assumeTrue("Notifications not allowed", NotificationManagerCompat.from(context).areNotificationsEnabled())
        manager.cancel(id)
    }

    @After
    fun tearDown() {
        manager.cancel(id)
    }

    @Test
    fun secondNotificationKeepsTheUnreadFirst() = runBlocking {
        notifier.show(NewContent.Podcasts(listOf(NewEpisodes("test-a", "Test Podcast A", listOf(-101)))))
        assertEquals(3, shown { it.title == "Test Podcast A" }.actions?.size ?: 0)

        notifier.show(NewContent.Podcasts(listOf(NewEpisodes("test-b", "Test Podcast B", listOf(-102)))))

        val merged = shown { it.title != "Test Podcast A" }
        val text = merged.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
        assertTrue(text, "Test Podcast A (1)" in text && "Test Podcast B (1)" in text)
        // Two episodes: no buttons for one of them.
        assertEquals(0, merged.actions?.size ?: 0)
    }

    @Test
    fun dismissedNotificationIsNotTakenOver() = runBlocking {
        notifier.show(NewContent.Podcasts(listOf(NewEpisodes("test-a", "Test Podcast A", listOf(-101)))))
        shown { it.title == "Test Podcast A" }
        manager.cancel(id)
        awaitGone()

        notifier.show(NewContent.Podcasts(listOf(NewEpisodes("test-b", "Test Podcast B", listOf(-102)))))

        val alone = shown { it.title != "Test Podcast A" }
        assertEquals("Test Podcast B", alone.title)
        assertFalse("Test Podcast A" in alone.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
    }

    /** `notify` reaches the system asynchronously: waits up to 5 s until the notification matching [matches] is posted. */
    private suspend fun shown(matches: (Notification) -> Boolean): Notification {
        repeat(50) {
            manager.activeNotifications.firstOrNull { it.id == id }?.notification?.takeIf(matches)?.let { return it }
            delay(100)
        }
        error("Notification not posted")
    }

    private suspend fun awaitGone() {
        repeat(50) {
            if (manager.activeNotifications.none { it.id == id }) return
            delay(100)
        }
        error("Notification not removed")
    }

    private val Notification.title: String get() = extras.getCharSequence(Notification.EXTRA_TITLE).toString()
}
