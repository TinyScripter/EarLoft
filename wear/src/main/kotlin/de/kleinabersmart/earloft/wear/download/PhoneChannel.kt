package de.kleinabersmart.earloft.wear.download

import com.google.android.gms.wearable.ChannelClient
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.kleinabersmart.earloft.core.wearsync.FileReceive
import de.kleinabersmart.earloft.core.wearsync.receiveFile
import de.kleinabersmart.earloft.core.wearsync.receiveFileResumable
import de.kleinabersmart.earloft.wear.sync.PhoneConnection
import java.io.File
import java.io.OutputStream
import javax.inject.Inject

/**
 * The file channel to the phone ([PhoneFileFetcher]): which phone is connected and the transfer itself (Wear concept 8.4).
 * Tests hand in files directly instead of over the Play services.
 */
internal interface PhoneChannel {
    suspend fun phoneNodeId(): String?

    /** [receiveFileResumable]. */
    suspend fun receiveResumable(
        nodeId: String,
        sourceUid: String,
        part: File,
        lengthFile: File,
        fits: (missingBytes: Long) -> Boolean,
        onProgress: suspend (bytesDone: Long, bytesTotal: Long) -> Unit,
    ): FileReceive

    /** [receiveFile]. */
    suspend fun receive(nodeId: String, sourceUid: String, output: OutputStream): FileReceive
}

internal class PlayServicesPhoneChannel @Inject constructor(
    private val channelClient: ChannelClient,
    private val phone: PhoneConnection,
) : PhoneChannel {
    override suspend fun phoneNodeId(): String? = phone.connectedPhoneNodeId()

    override suspend fun receiveResumable(
        nodeId: String,
        sourceUid: String,
        part: File,
        lengthFile: File,
        fits: (missingBytes: Long) -> Boolean,
        onProgress: suspend (bytesDone: Long, bytesTotal: Long) -> Unit,
    ): FileReceive = channelClient.receiveFileResumable(nodeId, sourceUid, part, lengthFile, fits, onProgress)

    override suspend fun receive(nodeId: String, sourceUid: String, output: OutputStream): FileReceive =
        channelClient.receiveFile(nodeId, sourceUid, output)
}

@Module
@InstallIn(SingletonComponent::class)
internal interface PhoneChannelModule {
    @Binds
    fun bindPhoneChannel(impl: PlayServicesPhoneChannel): PhoneChannel
}
