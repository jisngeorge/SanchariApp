package com.sanchari.bus.data.manager

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sanchari.bus.data.local.DatabaseConstants
import com.sanchari.bus.ui.helper.ScheduleEditorLogic
import com.sanchari.bus.data.model.EditableStop
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class EditorLookupTest {
    private lateinit var db: SQLiteDatabase

    @Before
    fun createDatabase() {
        db = SQLiteDatabase.create(null)
        db.execSQL(DatabaseConstants.BusServiceTable.CREATE_TABLE)
        db.execSQL(DatabaseConstants.StopTable.CREATE_TABLE)
        db.execSQL(DatabaseConstants.RouteStopTable.CREATE_TABLE)
    }

    @After
    fun closeDatabase() = db.close()

    @Test
    fun analysisQueryCoordinatesExcludeDetoursAndUnverifiableSequences() {
        listOf(10.0, 11.0, 10.05, 10.1).forEachIndexed { index, latitude ->
            db.execSQL("INSERT INTO Stop VALUES (?, ?, ?, 76.0)", arrayOf(index + 1, "Stop ${index + 1}", latitude))
        }
        for ((id, type, stops) in listOf(
            Triple("detour", "Express", listOf(1, 2, 4)),
            Triple("direct", "Ordinary", listOf(1, 3, 4))
        )) {
            db.execSQL("INSERT INTO BusService VALUES (?, ?, ?, 1, 0)", arrayOf(id, id, type))
            stops.forEachIndexed { order, stopId ->
                db.execSQL("INSERT INTO RouteStop(serviceId,stopId,scheduledTime,stopOrder) VALUES (?, ?, '', ?)",
                    arrayOf(id, stopId, order + 1))
            }
        }
        val editor = listOf(EditableStop("Stop 1", "", 1, 1), EditableStop("Stop 4", "", 2, 4))
        val routes = SearchManager.getAnalysisRoutes(db, listOf(1, 4))
        val result = MissingStopAnalysis.analyse(editor, routes, "Express", null)
        assertEquals(3, result.single().alternatives.single().stops.single().id)
        db.execSQL("UPDATE Stop SET latitude = 'unknown' WHERE stopId = 3")
        val invalid = SearchManager.getAnalysisRoutes(db, listOf(1, 4))
        assertNull(invalid.single { it.serviceId == "direct" }.stops[1].latitude)
        assertTrue(MissingStopAnalysis.analyse(editor, invalid, "Express", null).isEmpty())
    }

    @Test
    fun stopLookupRetainsDuplicateNameIdsAndDisambiguatesLabels() {
        listOf("A", "A", "O'Brien", "A*").forEachIndexed { index, name ->
            db.execSQL("INSERT INTO Stop VALUES (?, ?, 0, 0)", arrayOf(index + 1, name))
        }
        val options = SearchManager.getEditorStopOptions(db)
        assertEquals(setOf(1, 2, 3, 4), options.map { it.id }.toSet())
        assertEquals(2, options.filter { it.name == "A" }.map { it.label }.distinct().size)
        assertEquals("O'Brien", options.single { it.id == 3 }.name)
        assertTrue(db.isOpen)
    }

    @Test
    fun typeLookupSkipsBlanksAndUsesExistingCaseInsensitiveKeys() {
        listOf("Express", " express ", "LOCAL", "", " ").forEachIndexed { index, type ->
            db.execSQL("INSERT INTO BusService VALUES (?, ?, ?, 1, 0)", arrayOf("$index", "Test", type))
        }
        val types = SearchManager.getEditorBusTypes(db)
        assertEquals(setOf("EXPRESS", "LOCAL"), types.map(RouteResultsManager::busTypeKey).toSet())
        assertEquals(2, types.size)
    }

    @Test
    fun unavailableTimetableDoesNotCreateAnEmptyDatabaseDuringFirstRun() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val path = File(context.cacheDir, "editor-missing-${UUID.randomUUID()}.db")
        val missingDatabaseContext = object : ContextWrapper(context) {
            override fun getDatabasePath(name: String): File = path
        }
        try {
            SearchManager.getEditorStopOptions(missingDatabaseContext)
            fail("Missing timetable must be reported explicitly")
        } catch (expected: SQLiteException) {
            assertFalse(path.exists())
        } finally {
            if (path.exists()) path.delete()
        }
    }

    @Test
    fun existingFixedRoutePairsSupplyDirectionalHaltAwareEstimateSamples() {
        listOf("A", "X", "B").forEachIndexed { index, name ->
            db.execSQL("INSERT INTO Stop VALUES (?, ?, 0, 0)", arrayOf(index + 1, name))
        }

        db.execSQL("INSERT INTO BusService VALUES ('test', 'Test', 'EXPRESS', 0, 0)")
        val rows = listOf(1 to "08:00", 1 to "08:10", 2 to "08:20", 3 to "08:40", 3 to "08:50", 1 to "09:30")
        rows.forEachIndexed { index, (stopId, time) ->
            db.execSQL(
                "INSERT INTO RouteStop VALUES (?, 'test', ?, ?, ?)",
                arrayOf(index + 1, stopId, time, index + 1)
            )
        }
        val forward = SearchManager.findRouteResults(db, "A", "B")
        val backward = SearchManager.findRouteResults(db, "B", "A")
        assertEquals(30, ScheduleEditorLogic.timetableEstimate(forward.map { it.service }, "EXPRESS")?.minutes)
        assertEquals(40, ScheduleEditorLogic.timetableEstimate(backward.map { it.service }, "")?.minutes)
        assertEquals(2, forward.single().fromRouteStopId)
        assertEquals(4, forward.single().toRouteStopId)
    }

    @Test
    fun analysisQueryReturnsOrderedRoutesAndBatchesDoNotDuplicateServices() {
        for (id in 1..405) {
            db.execSQL("INSERT INTO Stop VALUES (?, ?, ?, 76.0)", arrayOf(id, "Stop $id", 10.0 + id * 0.01))
        }
        db.execSQL("INSERT INTO BusService VALUES ('test', 'Test', 'EXPRESS', 0, 0)")
        listOf(3, 2, 1).forEachIndexed { index, id ->
            db.execSQL("INSERT INTO RouteStop VALUES (?, 'test', ?, 'bad-time', ?)",
                arrayOf(index + 1, id, index + 1))
        }
        val result = SearchManager.getAnalysisRoutes(db, (1..405).toList())
        assertEquals(1, result.size)
        assertEquals("EXPRESS", result.single().type)
        assertEquals(listOf(3, 2, 1), result.single().stops.map { it.id })
        assertEquals(10.03, requireNotNull(result.single().stops.first().latitude), 1e-9)
        assertEquals(76.0, requireNotNull(result.single().stops.first().longitude), 1e-9)
        assertTrue(SearchManager.getAnalysisRoutes(db, emptyList()).isEmpty())
        assertTrue(SearchManager.getAnalysisRoutes(db, listOf(999)).isEmpty())
        assertTrue(db.isOpen)
    }
}
