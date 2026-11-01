package com.sanchari.bus.data.local

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sanchari.bus.data.manager.SearchManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DatabaseUpdateTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext

    private fun isolated(test: (Context, File) -> Unit) {
        val root = File(base.cacheDir, "database-update-test-${UUID.randomUUID()}").apply { mkdirs() }
        val preferences = "database-update-test-${root.name}"
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File = File(root, name)
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences(preferences, mode)
        }
        try {
            TimetableDatabaseHelper.resetInstance()
            SearchManager.invalidateStopSuggestionsCache()
            test(context, root)
        } finally {
            TimetableDatabaseHelper.resetInstance()
            SearchManager.invalidateStopSuggestionsCache()
            base.getSharedPreferences(preferences, 0).edit().clear().commit()
            root.deleteRecursively()
        }
    }

    private fun snapshot(file: File, version: Int, community: Boolean = false, name: String = "Original") {
        SQLiteDatabase.openOrCreateDatabase(file, null).use {
            it.execSQL(DatabaseConstants.VersionTable.CREATE_TABLE)
            it.execSQL("INSERT INTO DbVersion VALUES (?)", arrayOf(version))
            if (community) {
                it.execSQL(DatabaseConstants.BusRatingTable.CREATE_TABLE)
                it.execSQL(DatabaseConstants.UserCommentTable.CREATE_TABLE)
            } else {
                it.execSQL(DatabaseConstants.BusServiceTable.CREATE_TABLE)
                it.execSQL(DatabaseConstants.StopTable.CREATE_TABLE)
                it.execSQL(DatabaseConstants.RouteStopTable.CREATE_TABLE)
                it.execSQL("INSERT INTO Stop (locationName, latitude, longitude) VALUES (?, 10, 76)", arrayOf(name))
            }
            it.version = 1
        }
    }

    @Test
    fun verifiedSnapshotsRefreshVersionAndAutocompleteWithoutTouchingPrivateFiles() = isolated { context, root ->
        snapshot(context.getDatabasePath(DatabaseConstants.TIMETABLE_DATABASE_NAME), 1)
        snapshot(context.getDatabasePath(DatabaseConstants.COMMUNITY_DATABASE_NAME), 1, community = true)
        LocalVersionManager.syncVersionsFromDbFiles(context)
        val user = File(root, DatabaseConstants.USER_DATABASE_NAME).apply { writeText("private user fixture") }
        val originalUser = user.readBytes()
        assertEquals(listOf("Original"), SearchManager.getStopSuggestions(context))
        runBlocking {
            assertTrue(DatabaseManager.downloadAndReplaceDatabase(context, DatabaseConstants.TIMETABLE_DATABASE_NAME, 2, "fixture") { _, target ->
                target.delete()
                snapshot(target, 2, name = "Updated")
                true
            })
            assertTrue(DatabaseManager.downloadAndReplaceDatabase(context, DatabaseConstants.COMMUNITY_DATABASE_NAME, 3, "fixture") { _, target ->
                target.delete()
                snapshot(target, 3, community = true)
                true
            })
        }
        assertEquals(2, LocalVersionManager.getTimetableDbVersion(context))
        assertEquals(3, LocalVersionManager.getCommunityDbVersion(context))
        assertEquals(listOf("Updated"), SearchManager.getStopSuggestions(context))
        assertArrayEquals(originalUser, user.readBytes())
        assertTrue(root.listFiles().orEmpty().none { ".download" in it.name })
    }

    @Test
    fun corruptMissingSchemaWrongVersionAndFailedDownloadsPreserveOriginal() = isolated { context, root ->
        val installed = context.getDatabasePath(DatabaseConstants.TIMETABLE_DATABASE_NAME)
        snapshot(installed, 1)
        LocalVersionManager.syncVersionsFromDbFiles(context)
        val original = installed.readBytes()
        for (failure in listOf("corrupt", "table", "column", "version", "download")) {
            runBlocking {
                assertFalse(failure, DatabaseManager.downloadAndReplaceDatabase(context, installed.name, 2, "fixture") { _, target ->
                    target.delete()
                    when (failure) {
                        "corrupt" -> target.writeText("Not a SQLite file")
                        "download" -> return@downloadAndReplaceDatabase false
                        else -> {
                            snapshot(target, if (failure == "version") 9 else 2)
                            SQLiteDatabase.openDatabase(target.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use {
                                if (failure == "table") it.execSQL("DROP TABLE RouteStop")
                                if (failure == "column") {
                                    it.execSQL("DROP TABLE RouteStop")
                                    it.execSQL("CREATE TABLE RouteStop(routeStopId INTEGER)")
                                }
                            }
                        }
                    }
                    true
                })
            }
            assertArrayEquals(failure, original, installed.readBytes())
            assertEquals(1, LocalVersionManager.getTimetableDbVersion(context))
            assertTrue(root.listFiles().orEmpty().none { ".download" in it.name })
        }
    }

    @Test
    fun cancellationPreservesSnapshotAndCleansStaging() = isolated { context, root ->
        val installed = context.getDatabasePath(DatabaseConstants.TIMETABLE_DATABASE_NAME)
        snapshot(installed, 1)
        val original = installed.readBytes()
        try {
            runBlocking {
                DatabaseManager.downloadAndReplaceDatabase(context, installed.name, 2, "fixture") { _, target ->
                    target.writeText("interrupted download")
                    throw CancellationException("fixture cancellation")
                }
            }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertArrayEquals(original, installed.readBytes())
            assertTrue(root.listFiles().orEmpty().none { ".download" in it.name })
        }
    }

    @Test
    fun failedAtomicRenameKeepsDestinationAndCleansStaging() = isolated { context, root ->
        val destination = context.getDatabasePath(DatabaseConstants.TIMETABLE_DATABASE_NAME).apply { mkdir() }
        val marker = File(destination, "original-marker").apply { writeText("unchanged") }
        runBlocking {
            assertFalse(DatabaseManager.downloadAndReplaceDatabase(context, destination.name, 2, "fixture") { _, target ->
                target.delete()
                snapshot(target, 2)
                true
            })
        }
        assertEquals("unchanged", marker.readText())
        assertTrue(root.listFiles().orEmpty().none { ".download" in it.name })
    }
}
