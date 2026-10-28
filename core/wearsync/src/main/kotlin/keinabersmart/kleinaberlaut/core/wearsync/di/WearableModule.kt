package keinabersmart.kleinaberlaut.core.wearsync.di

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.Wearable
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

/** Clients der Wearable Data Layer API, an den Application-Context gebunden. */
@Module
@InstallIn(SingletonComponent::class)
internal object WearableModule {

    @Provides
    fun provideDataClient(@ApplicationContext context: Context): DataClient = Wearable.getDataClient(context)

    @Provides
    fun provideMessageClient(@ApplicationContext context: Context): MessageClient = Wearable.getMessageClient(context)

    @Provides
    fun provideCapabilityClient(@ApplicationContext context: Context): CapabilityClient =
        Wearable.getCapabilityClient(context)

    @Provides
    fun provideNodeClient(@ApplicationContext context: Context): NodeClient = Wearable.getNodeClient(context)

    @Provides
    fun provideChannelClient(@ApplicationContext context: Context): ChannelClient = Wearable.getChannelClient(context)
}
