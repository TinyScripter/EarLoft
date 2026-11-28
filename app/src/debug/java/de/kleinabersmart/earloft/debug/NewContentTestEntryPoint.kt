package de.kleinabersmart.earloft.debug

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.kleinabersmart.earloft.core.data.refresh.NewContentNotifier

/** Debug builds only: the app's notifier for the instrumented test of the "new" notifications (`NewContentNotificationTest`). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface NewContentTestEntryPoint {
    fun newContentTestNotifier(): NewContentNotifier
}
