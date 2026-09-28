package com.example.notesai.auth

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * Where the refresh token lives.
 *
 * NOTE: the only implementation so far keeps tokens in the local SQLite database via
 * `AppMetadata`. That is fine for development but is *not* secure storage — on
 * Android/iOS this should be backed by the Keystore/Keychain, and on desktop by the
 * OS credential store. The interface exists so that swap is a one-line change.
 */
interface TokenStore {
    fun load(): AuthTokens?
    fun save(tokens: AuthTokens)
    fun clear()
}

/** Token store backed by the `AppMetadata` key/value table. */
class MetadataTokenStore(
    private val read: (String) -> String?,
    private val write: (String, String) -> Unit,
    private val remove: (String) -> Unit,
    private val key: String = TOKENS_KEY,
) : TokenStore {

    override fun load(): AuthTokens? =
        read(key)?.let { runCatching { authJson.decodeFromString<AuthTokens>(it) }.getOrNull() }

    override fun save(tokens: AuthTokens) {
        write(key, authJson.encodeToString(tokens))
    }

    override fun clear() {
        remove(key)
    }

    companion object {
        const val TOKENS_KEY = "oauth.tokens"
    }
}
