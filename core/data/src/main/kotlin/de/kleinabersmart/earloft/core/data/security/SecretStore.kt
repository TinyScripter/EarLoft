package de.kleinabersmart.earloft.core.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Verschlüsselt abgelegte Geheimnisse (Server-Passwörter). */
interface SecretStore {
    /** `null`, wenn nichts gespeichert ist oder es sich nicht mehr entschlüsseln lässt. */
    suspend fun get(key: String): String?

    suspend fun put(key: String, value: String)

    suspend fun remove(key: String)
}

private val Context.secretsDataStore: DataStore<Preferences> by preferencesDataStore(name = "secrets")

/**
 * AES-GCM mit einem Schlüssel im Android Keystore; der Geheimtext liegt im DataStore `secrets`
 * (Konzept 3, Sicherheit). Der Schlüssel ist an dieses Gerät gebunden – die Datei ist deshalb vom
 * Backup ausgeschlossen.
 */
@Singleton
class KeystoreSecretStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : SecretStore {

    override suspend fun get(key: String): String? {
        val stored = context.secretsDataStore.data.first()[stringPreferencesKey(key)] ?: return null
        return try {
            decrypt(stored)
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "Geheimnis $key lässt sich nicht entschlüsseln", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Geheimnis $key ist beschädigt", e)
            null
        }
    }

    override suspend fun put(key: String, value: String) {
        val encrypted = encrypt(value)
        context.secretsDataStore.edit { it[stringPreferencesKey(key)] = encrypted }
    }

    override suspend fun remove(key: String) {
        context.secretsDataStore.edit { it.remove(stringPreferencesKey(key)) }
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val payload = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = payload.copyOfRange(0, IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        }
        return String(cipher.doFinal(payload, IV_BYTES, payload.size - IV_BYTES), Charsets.UTF_8)
    }

    @Synchronized
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_BITS)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val TAG = "SecretStore"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "earloft_secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal interface SecretStoreModule {
    @Binds
    fun bindSecretStore(store: KeystoreSecretStore): SecretStore
}
