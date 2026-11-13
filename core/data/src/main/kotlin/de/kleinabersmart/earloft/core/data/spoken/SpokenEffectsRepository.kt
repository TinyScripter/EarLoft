package de.kleinabersmart.earloft.core.data.spoken

import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.dao.PodcastDao
import de.kleinabersmart.earloft.core.database.entity.BookProgressEntity
import de.kleinabersmart.earloft.core.database.entity.PodcastFeedEntity
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.EffectLayers
import de.kleinabersmart.earloft.core.model.EffectOverrides
import de.kleinabersmart.earloft.core.model.SpokenEffects
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tempo und Wiedergabe-Effekte von Büchern und Folgen (Konzept 12.4): Rangfolge Folge bzw. Buch > Abo (nur Podcasts) >
 * Vorgabe des Bereichs. Eigene Werte eines Buchs bzw. einer Folge stehen nur, solange sie von dem abweichen, was sie
 * erben; wer zurück auf den geerbten Wert stellt, folgt damit wieder Abo und Bereich.
 */
@Singleton
class SpokenEffectsRepository @Inject constructor(
    private val books: BookDao,
    private val podcasts: PodcastDao,
    private val settings: SpokenSettings,
) {
    private val mutex = Mutex()

    /** Die Ebenen des Buchs bzw. der Folge, neu bei jeder Änderung an einer davon. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun layers(bookId: Long): Flow<EffectLayers> = flow { emit(books.book(bookId)) }
        .flatMapLatest { book ->
            val feedId = book?.takeIf { it.kind == BookKind.EPISODE }?.serverId
            combine(
                books.observeProgress(bookId),
                if (feedId != null) podcasts.observeFeed(feedId) else flowOf(null),
                if (book?.kind == BookKind.EPISODE) settings.podcastEffects else settings.bookEffects,
            ) { progress, feed, area -> layersOf(progress, feed, area) }
        }
        .distinctUntilChanged()

    /** Was beim Abspielen gilt. */
    fun effects(bookId: Long): Flow<SpokenEffects> = layers(bookId).map { it.effects }.distinctUntilChanged()

    suspend fun effectsNow(bookId: Long): SpokenEffects = layers(bookId).first().effects

    /** Eigenes Tempo; gleich dem geerbten heißt: wieder dem Abo bzw. Bereich folgen. */
    suspend fun setSpeed(bookId: Long, speed: Float) = updateOwn(bookId) { it.copy(speed = speed) }

    suspend fun setSkipSilence(bookId: Long, enabled: Boolean) = updateOwn(bookId) { it.copy(skipSilence = enabled) }

    suspend fun setVolumeBoost(bookId: Long, db: Int) = updateOwn(bookId) { it.copy(volumeBoostDb = db) }

    /**
     * „Als Standard“ bzw. „Für diesen Podcast“: Was jetzt für [bookId] gilt, wird zur Vorgabe des Abos (Folgen mit Abo)
     * bzw. des Bereichs; die eigenen Werte entfallen damit. Andere Folgen und Bücher mit eigenen Werten behalten sie.
     */
    suspend fun rememberAsDefault(bookId: Long) = mutex.withLock {
        val book = books.book(bookId) ?: return@withLock
        val effects = layers(bookId).first().effects
        val feed = book.takeIf { it.kind == BookKind.EPISODE }?.let { podcasts.feed(it.serverId) }
        when {
            feed != null -> {
                // Was der Bereich ohnehin vorgibt, bleibt am Abo offen und folgt ihm weiter.
                val area = settings.podcastEffects.first()
                val own = EffectOverrides(effects.speed, effects.skipSilence, effects.volumeBoostDb).minus(area)
                podcasts.setEffects(feed.feedId, own.speed, own.skipSilence, own.volumeBoostDb)
            }
            book.kind == BookKind.EPISODE -> settings.setPodcastEffects(effects.copy(skipIntroMs = 0, skipOutroMs = 0))
            else -> settings.setBookEffects(effects.copy(skipIntroMs = 0, skipOutroMs = 0))
        }
        if (books.progress(bookId) != null) books.setEffects(bookId, null, null, null)
    }

    /** Vorgabe des Abos ändern; [change] bekommt die bisherigen Werte des Abos (`null` = Vorgabe des Bereichs). */
    suspend fun setFeedEffects(feedId: String, change: (EffectOverrides) -> EffectOverrides) = mutex.withLock {
        val feed = podcasts.feed(feedId) ?: return@withLock
        val next = change(EffectOverrides(feed.speed, feed.skipSilence, feed.volumeBoostDb))
        podcasts.setEffects(feedId, next.speed, next.skipSilence, next.volumeBoostDb)
    }

    suspend fun setSkipIntroOutro(feedId: String, introS: Int, outroS: Int) = podcasts.setSkipIntroOutro(feedId, introS, outroS)

    private suspend fun updateOwn(bookId: Long, change: (EffectOverrides) -> EffectOverrides) = mutex.withLock {
        val layers = layers(bookId).first()
        val own = change(layers.own).minus(layers.inherited)
        if (own == layers.own) return@withLock
        if (books.progress(bookId) != null) {
            books.setEffects(bookId, own.speed, own.skipSilence, own.volumeBoostDb)
        } else {
            // Noch kein Stand (der Server gewinnt dann beim nächsten Abgleich sofort, updatedAt = 0).
            val duration = books.book(bookId)?.durationMs ?: return@withLock
            books.upsertProgress(
                BookProgressEntity(
                    bookId,
                    positionMs = 0,
                    durationMs = duration,
                    speed = own.speed,
                    updatedAt = 0,
                    skipSilence = own.skipSilence,
                    volumeBoostDb = own.volumeBoostDb,
                ),
            )
        }
    }
}

internal fun layersOf(progress: BookProgressEntity?, feed: PodcastFeedEntity?, area: SpokenEffects) = EffectLayers(
    own = EffectOverrides(progress?.speed, progress?.skipSilence, progress?.volumeBoostDb),
    feed = feed?.let { EffectOverrides(it.speed, it.skipSilence, it.volumeBoostDb) },
    area = area,
    skipIntroS = feed?.skipIntroS ?: 0,
    skipOutroS = feed?.skipOutroS ?: 0,
)
