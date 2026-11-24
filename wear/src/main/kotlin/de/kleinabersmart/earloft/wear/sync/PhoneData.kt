package de.kleinabersmart.earloft.wear.sync

import com.google.android.gms.wearable.DataClient
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.kleinabersmart.earloft.core.wearsync.assetPayloadsAt
import de.kleinabersmart.earloft.core.wearsync.hashesAt
import de.kleinabersmart.earloft.core.wearsync.payloadsAt
import de.kleinabersmart.earloft.core.wearsync.putPayload
import javax.inject.Inject

/**
 * The DataItems between phone and watch (Wear concept 6): what the phone published, by node, and what the watch reports
 * back. Tests hand in the phone's state directly instead of over the Play services.
 */
internal interface PhoneData {
    /** [payloadsAt]. */
    suspend fun payloads(path: String): Map<String, ByteArray>

    /** [hashesAt]. */
    suspend fun hashes(path: String): Map<String, String>

    /** [assetPayloadsAt]. */
    suspend fun assetPayloads(path: String): Map<String, ByteArray>

    /** [putPayload]; `false` if it could not be written. */
    suspend fun put(path: String, payload: ByteArray): Boolean
}

internal class PlayServicesPhoneData @Inject constructor(private val dataClient: DataClient) : PhoneData {
    override suspend fun payloads(path: String): Map<String, ByteArray> = dataClient.payloadsAt(path)

    override suspend fun hashes(path: String): Map<String, String> = dataClient.hashesAt(path)

    override suspend fun assetPayloads(path: String): Map<String, ByteArray> = dataClient.assetPayloadsAt(path)

    override suspend fun put(path: String, payload: ByteArray): Boolean = dataClient.putPayload(path, payload)
}

/**
 * What [PhoneLibrarySync] asks of the phone besides the DataItems: whether it is connected, credentials, and withdrawing the
 * request to open the app there ([PhoneReturn]).
 */
internal interface PhoneLink {
    /** Asked anew, not from the short cache of [PhoneConnection]: a sync is rare, and it decides whether the phone is back. */
    suspend fun connectedPhoneNodeId(): String?

    /** [CredentialsRequester.request]. */
    suspend fun requestCredentials(sourceIds: Collection<String>, phoneNodeId: String)

    /** [CredentialsRequester.requestMissing]. */
    suspend fun requestMissingCredentials(sourceIds: Collection<String>, phoneNodeId: String)

    /** [PhoneReturn.withdraw]. */
    suspend fun withdrawReturnRequest()
}

internal class PlayServicesPhoneLink @Inject constructor(
    private val connection: PhoneConnection,
    private val credentials: CredentialsRequester,
    private val phoneReturn: PhoneReturn,
) : PhoneLink {
    override suspend fun connectedPhoneNodeId(): String? = connection.connectedPhoneNodeId(fresh = true)

    override suspend fun requestCredentials(sourceIds: Collection<String>, phoneNodeId: String) =
        credentials.request(sourceIds, phoneNodeId)

    override suspend fun requestMissingCredentials(sourceIds: Collection<String>, phoneNodeId: String) =
        credentials.requestMissing(sourceIds, phoneNodeId)

    override suspend fun withdrawReturnRequest() = phoneReturn.withdraw()
}

@Module
@InstallIn(SingletonComponent::class)
internal interface PhoneDataModule {
    @Binds
    fun bindPhoneData(impl: PlayServicesPhoneData): PhoneData

    @Binds
    fun bindPhoneLink(impl: PlayServicesPhoneLink): PhoneLink
}
