package com.example.notesai.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AccountScopeTest {

    @Test
    fun differentAccountsGetDifferentDatabases() {
        assertNotEquals(
            accountDatabaseName("alice@example.com"),
            accountDatabaseName("bob@example.com"),
        )
    }

    @Test
    fun theSameAccountAlwaysMapsToTheSameDatabase() {
        // Case and surrounding whitespace shouldn't produce a second, empty notebook.
        assertEquals(
            accountDatabaseName("alice@example.com"),
            accountDatabaseName("  Alice@Example.COM  "),
        )
    }

    @Test
    fun theNameIsSafeToPutOnDiskAndDoesNotLeakTheAddress() {
        val name = accountDatabaseName("weird+address.with/slashes@example.com")

        assertTrue(name.startsWith("notes-"), name)
        assertTrue(name.all { it.isLetterOrDigit() || it == '-' }, name)
        assertTrue(!name.contains("example"), "the address should not appear in the file name")
    }

    @Test
    fun aMissingAddressStillGetsItsOwnBucket() {
        assertEquals(accountDatabaseName(null), accountDatabaseName("   "))
    }
}
