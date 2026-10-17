package io.github.aedev.flow.data.youtubeaccount

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Signs Google accounts in and out, switches between them, and hands the rest of the account module a
 * valid access token for whichever one is active. Tokens live in memory only; a refresh is made when
 * the cached one is about to lapse.
 */
@Singleton
class YouTubeAccountManager
    @Inject
    constructor(
        private val store: YouTubeAccountStore,
        private val api: YouTubeAccountApi,
    ) {
        val state: Flow<YouTubeAccountsState> = store.state

        private val tokenLock = Mutex()
        private val tokens = HashMap<String, OAuthTokens>()

        /**
         * One interactive sign-in. Emits the code to show, then waits for the person to approve it on
         * another device (or this one) and finishes with the new account, which becomes active.
         */
        fun signIn(): Flow<YouTubeSignInProgress> =
            flow {
                emit(YouTubeSignInProgress.Starting)
                val client =
                    runCatching { api.fetchTvOAuthClient() }
                        .onFailure { Log.w(TAG, "Could not load the TV sign-in client", it) }
                        .getOrNull()
                        ?: return@flow emit(YouTubeSignInProgress.Failed(YouTubeSignInProgress.Reason.NO_CLIENT))
                val code =
                    try {
                        api.requestDeviceCode(client, store.deviceId())
                    } catch (e: IOException) {
                        Log.w(TAG, "Device code request failed", e)
                        return@flow emit(YouTubeSignInProgress.Failed(YouTubeSignInProgress.Reason.NETWORK))
                    }
                emit(YouTubeSignInProgress.AwaitingApproval(code))

                var interval = code.intervalSeconds.coerceAtLeast(1)
                while (System.currentTimeMillis() < code.expiresAtMs) {
                    delay(interval * 1000L)
                    val poll =
                        try {
                            api.pollDeviceToken(client, code.deviceCode)
                        } catch (e: IOException) {
                            Log.w(TAG, "Token poll failed; retrying", e)
                            TokenPoll.Pending
                        }
                    when (poll) {
                        TokenPoll.Pending -> Unit
                        TokenPoll.SlowDown -> interval += SLOW_DOWN_STEP_S
                        TokenPoll.Denied -> return@flow emit(YouTubeSignInProgress.Failed(YouTubeSignInProgress.Reason.DENIED))
                        TokenPoll.Expired -> return@flow emit(YouTubeSignInProgress.Failed(YouTubeSignInProgress.Reason.EXPIRED))
                        is TokenPoll.Granted -> return@flow emit(finishSignIn(client, poll.tokens))
                    }
                }
                emit(YouTubeSignInProgress.Failed(YouTubeSignInProgress.Reason.EXPIRED))
            }

        private suspend fun finishSignIn(
            client: TvOAuthClient,
            granted: OAuthTokens,
        ): YouTubeSignInProgress {
            val refreshToken =
                granted.refreshToken
                    ?: return YouTubeSignInProgress.Failed(YouTubeSignInProgress.Reason.NO_ACCOUNT)
            val identities =
                try {
                    api.accounts(YouTubeAccountApi.Auth(granted.accessToken, pageId = null))
                } catch (e: IOException) {
                    Log.w(TAG, "accounts_list failed", e)
                    return YouTubeSignInProgress.Failed(YouTubeSignInProgress.Reason.NETWORK)
                }
            // The selected identity is the one the sign-in was approved as; brand channels come after it.
            val identity =
                identities.firstOrNull { it.isSelected } ?: identities.firstOrNull()
                    ?: return YouTubeSignInProgress.Failed(YouTubeSignInProgress.Reason.NO_ACCOUNT)
            val account =
                YouTubeAccount(
                    id = accountId(identity),
                    name = identity.name,
                    email = identity.email,
                    handle = identity.handle,
                    avatarUrl = identity.avatarUrl,
                    pageId = null,
                )
            store.put(account, YouTubeCredential(client.clientId, client.clientSecret, refreshToken))
            tokenLock.withLock { tokens[account.id] = granted }
            return YouTubeSignInProgress.Done(account)
        }

        suspend fun switchTo(id: String?) = store.setActive(id)

        suspend fun remove(id: String) {
            val credential = store.credential(id)
            tokenLock.withLock { tokens.remove(id) }
            store.remove(id)
            credential?.let { api.revoke(it.refreshToken) }
        }

        suspend fun setReportHistory(enabled: Boolean) = store.setReportHistory(enabled)

        /**
         * The active account and request auth for it, or null in guest mode, when the account needs a
         * new sign-in, or when no token can be had right now (offline).
         */
        suspend fun activeAuth(): Pair<YouTubeAccount, YouTubeAccountApi.Auth>? {
            val account = store.current().active ?: return null
            if (account.needsSignIn) return null
            val token = accessToken(account) ?: return null
            return account to YouTubeAccountApi.Auth(token, account.pageId)
        }

        /** Drops the cached token after YouTube refused it, so the next call refreshes. */
        suspend fun invalidate(accountId: String) {
            tokenLock.withLock { tokens.remove(accountId) }
        }

        /** Runs [block] with the active account's auth, refreshing and retrying once if the token was refused. */
        suspend fun <T> withActiveAuth(block: suspend (YouTubeAccount, YouTubeAccountApi.Auth) -> T): T? {
            val (account, auth) = activeAuth() ?: return null
            return try {
                block(account, auth)
            } catch (e: YouTubeUnauthorizedException) {
                invalidate(account.id)
                val (_, retry) = activeAuth() ?: return null
                block(account, retry)
            }
        }

        private suspend fun accessToken(account: YouTubeAccount): String? =
            tokenLock.withLock {
                tokens[account.id]
                    ?.takeIf { it.expiresAtMs - REFRESH_MARGIN_MS > System.currentTimeMillis() }
                    ?.let { return@withLock it.accessToken }
                val credential = store.credential(account.id) ?: return@withLock null
                try {
                    api.refreshAccessToken(credential.client, credential.refreshToken).also { tokens[account.id] = it }.accessToken
                } catch (e: YouTubeAuthRevokedException) {
                    Log.w(TAG, "Sign-in for ${account.id} was revoked")
                    store.markNeedsSignIn(account.id)
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    Log.w(TAG, "Token refresh failed", e)
                    null
                }
            }

        private fun accountId(identity: RemoteIdentity): String {
            val basis =
                identity.email
                    .ifBlank { identity.handle }
                    .ifBlank { identity.name }
                    .lowercase()
            val digest = MessageDigest.getInstance("SHA-256").digest(basis.toByteArray())
            return digest.take(ID_BYTES).joinToString("") { "%02x".format(it) }
        }

        private companion object {
            const val TAG = "YouTubeAccounts"
            const val SLOW_DOWN_STEP_S = 5
            const val REFRESH_MARGIN_MS = 60_000L
            const val ID_BYTES = 12
        }
    }
