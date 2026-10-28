package keinabersmart.kleinaberlaut.wear

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Die Uhr startet bewusst keinen `LibraryRefresher`, keine Sync-Queue und keine `AutoDownloads`
 * (Wear-Konzept 7.3): Was auf die Uhr kommt, bestimmt allein das Handy.
 */
@HiltAndroidApp
class WearApplication : Application()
