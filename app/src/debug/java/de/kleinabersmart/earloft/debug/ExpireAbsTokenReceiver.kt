package de.kleinabersmart.earloft.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import de.kleinabersmart.earloft.core.data.spoken.AudiobookshelfServers
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsAuthApi
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsServerRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Debug builds only: makes the Audiobookshelf access tokens from an OpenID sign-in invalid in memory, so the server
 * rejects the next request as if the token had expired. Checks the renewal (review H3) without waiting an hour:
 * `adb shell am broadcast -a de.kleinabersmart.earloft.debug.EXPIRE_ABS_TOKEN -p de.kleinabersmart.earloft.debug`.
 * Only the shell may send it (permission `DUMP`); the stored tokens stay untouched.
 */
@AndroidEntryPoint
class ExpireAbsTokenReceiver : BroadcastReceiver() {

    @Inject lateinit var servers: AudiobookshelfServers

    @Inject lateinit var registry: AbsServerRegistry

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                for (server in servers.servers.first()) {
                    // Loads the server into the registry if nothing used it yet.
                    val current = servers.server(server.serverId) ?: continue
                    if (AbsAuthApi.expiresAt(current.token) == null) {
                        Log.i(TAG, "${server.name}: no OpenID access token, left as it is")
                        continue
                    }
                    registry.register(current.copy(token = current.token + "expired"))
                    Log.i(TAG, "${server.name}: access token made invalid")
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "ExpireAbsToken"
    }
}
