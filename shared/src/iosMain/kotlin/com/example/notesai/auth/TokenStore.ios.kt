@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.example.notesai.auth

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * iOS token storage: a single generic-password item in the system Keychain, accessible
 * after first unlock so background sync can read it without the device being opened.
 *
 * NOTE: compiled but not yet exercised on a device — verify a sign-in survives an app
 * restart, and that sign-out actually removes the item.
 */
class KeychainTokenStore(
    private val service: String = "com.example.notesai.oauth",
    private val account: String = "default",
) : TokenStore {

    override fun load(): AuthTokens? {
        val query = newQuery()
        CFDictionaryAddValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionaryAddValue(query, kSecMatchLimit, kSecMatchLimitOne)

        val text = memScoped {
            val result = alloc<COpaquePointerVar>()
            val status = SecItemCopyMatching(query, result.ptr)
            CFRelease(query)
            if (status != errSecSuccess) return@memScoped null

            val data: CFDataRef? = result.value?.reinterpret()
            val length = CFDataGetLength(data).toInt()
            val bytesPtr = CFDataGetBytePtr(data)
            val bytes = bytesPtr?.reinterpret<ByteVar>()?.readBytes(length)
            CFRelease(result.value)
            bytes?.decodeToString()
        }

        return text?.let { runCatching { authJson.decodeFromString<AuthTokens>(it) }.getOrNull() }
    }

    override fun save(tokens: AuthTokens) {
        clear()

        val payload = authJson.encodeToString(tokens).encodeToByteArray()
        val query = newQuery()
        payload.usePinned { pinned ->
            val data = CFDataCreate(
                kCFAllocatorDefault,
                pinned.addressOf(0).reinterpret<UByteVar>(),
                payload.size.toLong(),
            )
            CFDictionaryAddValue(query, kSecValueData, data)
            CFDictionaryAddValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlock)
            SecItemAdd(query, null)
            CFRelease(data)
        }
        CFRelease(query)
    }

    override fun clear() {
        val query = newQuery()
        SecItemDelete(query)
        CFRelease(query)
    }

    private fun newQuery(): CFMutableDictionaryRef? {
        val query = CFDictionaryCreateMutable(
            kCFAllocatorDefault,
            5,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        )
        CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(query, kSecAttrService, cfString(service))
        CFDictionaryAddValue(query, kSecAttrAccount, cfString(account))
        return query
    }
}

private fun cfString(value: String): CFStringRef? =
    CFStringCreateWithCString(kCFAllocatorDefault, value, kCFStringEncodingUTF8)
