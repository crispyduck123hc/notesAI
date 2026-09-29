package com.example.notesai.auth

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * Desktop token storage.
 *
 * On macOS this delegates to the login Keychain via the `security` CLI, which is the
 * system credential store and needs no JNI dependency. Every other OS falls back to a
 * 0600 file under the user's home directory — better than plaintext SQLite, but not a
 * real credential store, so this should be replaced with DPAPI/libsecret later.
 */
fun desktopTokenStore(): TokenStore =
    if (isMacOs()) MacKeychainTokenStore() else FileTokenStore(defaultTokenFile())

private fun isMacOs(): Boolean =
    System.getProperty("os.name").orEmpty().lowercase().contains("mac")

private class MacKeychainTokenStore(
    private val service: String = "com.example.notesai.oauth",
    private val account: String = "default",
) : TokenStore {

    override fun load(): AuthTokens? =
        security("find-generic-password", "-s", service, "-a", account, "-w")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { authJson.decodeFromString<AuthTokens>(it) }.getOrNull() }

    override fun save(tokens: AuthTokens) {
        // -U updates the item in place if it already exists.
        security("add-generic-password", "-s", service, "-a", account, "-w", authJson.encodeToString(tokens), "-U")
    }

    override fun clear() {
        security("delete-generic-password", "-s", service, "-a", account)
    }

    private fun security(vararg args: String): String? = runCatching {
        val process = ProcessBuilder(listOf("/usr/bin/security") + args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        output.takeIf { process.exitValue() == 0 }
    }.getOrNull()
}

private class FileTokenStore(private val file: File) : TokenStore {

    override fun load(): AuthTokens? = runCatching {
        file.takeIf { it.exists() }?.readText()?.let { authJson.decodeFromString<AuthTokens>(it) }
    }.getOrNull()

    override fun save(tokens: AuthTokens) {
        file.parentFile?.mkdirs()
        file.writeText(authJson.encodeToString(tokens))
        // Owner-only, best effort (no-op on filesystems without POSIX permissions).
        runCatching {
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
        }
    }

    override fun clear() {
        file.delete()
    }
}

private fun defaultTokenFile(): File =
    File(System.getProperty("user.home"), ".notesai/tokens.json")
