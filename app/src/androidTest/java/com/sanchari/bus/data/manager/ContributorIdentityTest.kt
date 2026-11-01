package com.sanchari.bus.data.manager

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sanchari.bus.data.local.UserDatabaseHelper
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ContributorIdentityTest {
    private lateinit var context: ContextWrapper
    private lateinit var database: File

    @Before
    fun isolateDatabase() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        database = File(base.cacheDir, "contributor-${UUID.randomUUID()}.db")
        context = object : ContextWrapper(base) {
            override fun getDatabasePath(name: String) = database
        }
    }

    @After
    fun removeDatabase() {
        android.database.sqlite.SQLiteDatabase.deleteDatabase(database)
    }

    @Test
    fun emptyProfileAutomaticallyPersistsOneNonemptyIdentity() {
        val first = UserDataManager.getUser(context)
        assertTrue(first.uuid.isNotBlank())
        assertEquals("", first.name)
        assertEquals("", first.email)
        assertEquals("", first.phone)
        assertEquals("", first.place)
        assertEquals(first, UserDataManager.getUser(context))
        UserDatabaseHelper(context).use { helper ->
            helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM User", null).use {
                assertTrue(it.moveToFirst())
                assertEquals(1, it.getInt(0))
            }
        }
    }

    @Test
    fun existingIdentitySurvivesProfileEditsAndClearingPersonalFields() {
        val first = UserDataManager.getUser(context)
        val edited = first.copy(name = "Test", email = "test@example.invalid", phone = "1234567890", place = "Test place")
        assertTrue(UserDataManager.saveUser(context, edited))
        assertEquals(edited, UserDataManager.getUser(context))
        assertTrue(UserDataManager.saveUser(context, first))
        assertEquals(first, UserDataManager.getUser(context))
    }

    @Test
    fun profileWritesCannotReplaceAnExistingIdentity() {
        val first = UserDataManager.getUser(context)
        assertFalse(UserDataManager.saveUser(context, first.copy(uuid = "different-id")))
        assertEquals(first, UserDataManager.getUser(context))
    }

    @Test
    fun legacyBlankIdentityIsRepairedOnceWithoutLosingTheProfile() {
        UserDatabaseHelper(context).use {
            it.writableDatabase.execSQL("INSERT INTO User (uuid, name, email) VALUES ('', 'Existing', 'old@example.invalid')")
        }
        val first = UserDataManager.getUser(context)
        assertTrue(first.uuid.isNotBlank())
        assertEquals("Existing", first.name)
        assertEquals("old@example.invalid", first.email)
        assertEquals(first, UserDataManager.getUser(context))
    }

    @Test
    fun simultaneousFirstReadsReturnTheSamePersistedIdentity() {
        UserDatabaseHelper(context).use { it.writableDatabase }
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures = (1..8).map { executor.submit<String> { UserDataManager.getUser(context).uuid } }
            val ids = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, ids.toSet().size)
        } finally {
            executor.shutdownNow()
        }
    }
}
