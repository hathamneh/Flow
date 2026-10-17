package io.github.aedev.flow.data.youtubeaccount

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.KeystoreSecretBox
import io.github.aedev.flow.data.local.safePreferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.youTubeAccountDataStore by safePreferencesDataStore(name = "youtube_accounts")

/**
 * The signed-in Google accounts, which one is active, and the sync options. Each account's OAuth
 * client and refresh token are sealed with the Keystore before they are written, and are kept apart
 * from the account list so nothing that reads the list ever holds a token.
 */
@Singleton
class YouTubeAccountStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val json = Json { ignoreUnknownKeys = true }
        private val accountsSerializer = ListSerializer(YouTubeAccount.serializer())
        private val accountsKey = stringPreferencesKey("accounts")
        private val activeKey = stringPreferencesKey("active_account")
        private val reportHistoryKey = booleanPreferencesKey("report_history")
        private val deviceIdKey = stringPreferencesKey("device_id")

        private fun credentialKey(id: String) = stringPreferencesKey("credential_$id")

        private fun lastPullKey(id: String) = longPreferencesKey("history_pulled_$id")

        private fun lastLibraryPullKey(id: String) = longPreferencesKey("library_pulled_$id")

        val state: Flow<YouTubeAccountsState> =
            context.youTubeAccountDataStore.data
                .map { preferences ->
                    val accounts = preferences.accounts()
                    YouTubeAccountsState(
                        accounts = accounts,
                        activeId = preferences[activeKey]?.takeIf { id -> accounts.any { it.id == id } },
                        reportHistory = preferences[reportHistoryKey] ?: true,
                    )
                }.distinctUntilChanged()
                .flowOn(Dispatchers.IO)

        suspend fun current(): YouTubeAccountsState = state.first()

        /** Adds [account], or replaces it with its new sign-in if it is already here, and makes it active. */
        suspend fun put(
            account: YouTubeAccount,
            credential: YouTubeCredential,
        ) = edit { preferences ->
            val others = preferences.accounts().filterNot { it.id == account.id }
            preferences.writeAccounts(others + account.copy(needsSignIn = false))
            preferences[credentialKey(account.id)] = KeystoreSecretBox.seal(json.encodeToString(YouTubeCredential.serializer(), credential))
            preferences[activeKey] = account.id
        }

        suspend fun credential(id: String): YouTubeCredential? =
            withContext(Dispatchers.IO) {
                val sealed = context.youTubeAccountDataStore.data.first()[credentialKey(id)] ?: return@withContext null
                KeystoreSecretBox
                    .open(sealed)
                    .takeIf { it.isNotEmpty() }
                    ?.let { runCatching { json.decodeFromString(YouTubeCredential.serializer(), it) }.getOrNull() }
            }

        /** Null switches to guest mode: Flow keeps working, nothing is sent to or read from Google. */
        suspend fun setActive(id: String?) =
            edit { preferences ->
                if (id == null) preferences.remove(activeKey) else preferences[activeKey] = id
            }

        suspend fun remove(id: String) =
            edit { preferences ->
                preferences.writeAccounts(preferences.accounts().filterNot { it.id == id })
                preferences.remove(credentialKey(id))
                preferences.remove(lastPullKey(id))
                preferences.remove(lastLibraryPullKey(id))
                if (preferences[activeKey] == id) preferences.remove(activeKey)
            }

        suspend fun markNeedsSignIn(id: String) =
            edit { preferences ->
                preferences.writeAccounts(preferences.accounts().map { if (it.id == id) it.copy(needsSignIn = true) else it })
            }

        suspend fun setReportHistory(enabled: Boolean) = edit { it[reportHistoryKey] = enabled }

        suspend fun lastHistoryPull(id: String): Long = context.youTubeAccountDataStore.data.first()[lastPullKey(id)] ?: 0L

        suspend fun setLastHistoryPull(
            id: String,
            at: Long,
        ) = edit { it[lastPullKey(id)] = at }

        suspend fun lastLibraryPull(id: String): Long = context.youTubeAccountDataStore.data.first()[lastLibraryPullKey(id)] ?: 0L

        suspend fun setLastLibraryPull(
            id: String,
            at: Long,
        ) = edit { it[lastLibraryPullKey(id)] = at }

        /** A random id for this install, sent with sign-in requests the way the TV app sends its own. */
        suspend fun deviceId(): String {
            context.youTubeAccountDataStore.data
                .first()[deviceIdKey]
                ?.let { return it }
            val created = UUID.randomUUID().toString()
            edit { preferences -> if (preferences[deviceIdKey] == null) preferences[deviceIdKey] = created }
            return context.youTubeAccountDataStore.data.first()[deviceIdKey] ?: created
        }

        private suspend fun edit(block: (MutablePreferences) -> Unit) {
            withContext(Dispatchers.IO) { context.youTubeAccountDataStore.edit(block) }
        }

        private fun Preferences.accounts(): List<YouTubeAccount> =
            this[accountsKey]
                ?.let { runCatching { json.decodeFromString(accountsSerializer, it) }.getOrNull() }
                .orEmpty()

        private fun MutablePreferences.writeAccounts(accounts: List<YouTubeAccount>) {
            this[accountsKey] = json.encodeToString(accountsSerializer, accounts)
        }
    }

/**
 * What it takes to mint access tokens for one account. The OAuth client is kept with the refresh token
 * because a refresh token only works with the client that issued it, even after the TV app moves on.
 */
@Serializable
data class YouTubeCredential(
    val clientId: String,
    val clientSecret: String,
    val refreshToken: String,
) {
    val client: TvOAuthClient
        get() = TvOAuthClient(clientId, clientSecret)
}
