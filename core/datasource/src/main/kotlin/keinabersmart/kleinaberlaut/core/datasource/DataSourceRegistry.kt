package keinabersmart.kleinaberlaut.core.datasource

import keinabersmart.kleinaberlaut.core.datasource.local.LocalDataSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Zugriff auf alle Musikquellen per `sourceId`.
 * Phase 1 kennt nur die lokale Quelle; Server-Quellen kommen in Phase 2 dynamisch hinzu.
 */
@Singleton
class DataSourceRegistry @Inject constructor(
    private val localDataSource: LocalDataSource,
) {
    fun all(): List<MusicDataSource> = listOf(localDataSource)

    operator fun get(sourceId: String): MusicDataSource? = all().firstOrNull { it.sourceId == sourceId }
}
