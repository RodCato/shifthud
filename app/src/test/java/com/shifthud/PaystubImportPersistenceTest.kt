package com.shifthud

import android.app.Application
import androidx.room.Room
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import com.shifthud.data.local.ShiftDatabase
import com.shifthud.data.repository.PayrollRepository
import com.shifthud.domain.paystub.*
import com.shifthud.domain.payroll.*
import com.shifthud.ui.payroll.PayrollDraft
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class PaystubImportPersistenceTest {
    @Test fun reviewingAndCancellingWritesNothing()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),ShiftDatabase::class.java).build()
        try {
            val repository=PayrollRepository(db);val e=extractPaystub(PAYSTUB_FIXTURE);val review=PayrollDraft.from(e.draft(e.start!!,e.end!!))
            val restored=PayrollDraft.restore(review.serialize()).model()
            assertNull(restored.netCents);assertEquals("35.39".toBigDecimal(),restored.reportedHours);assertFalse(restored.deductionsComplete)
            assertTrue(repository.paychecks.first().isEmpty());assertTrue(db.shifts().observeSessions().first().isEmpty())
        }finally{db.close()}
    }
    @Test fun correctedImportDraftSurvivesSavedStateWithoutReapplyingOcr() {
        val e=extractPaystub(CURRENT_PAYSTUB)
        val edited=PayrollDraft.from(e.draft(e.start!!,e.end!!)).copy(hours="35.40",gross="567.00",notes="Corrected by user")
        val restored=PayrollDraft.restore(edited.serialize())
        assertEquals(edited,restored)
        assertEquals("35.40".toBigDecimal(),restored.model().reportedHours)
        assertEquals(56700L,restored.model().grossCents)
        assertEquals(56624L,e.gross)
    }
    @Test fun confirmedFactsPersistAndDuplicateCannotOverwrite()=runBlocking {
        val context=RuntimeEnvironment.getApplication();val name="paystub-persistence.db";context.deleteDatabase(name)
        val e=extractPaystub(PAYSTUB_FIXTURE)
        // Model the user's explicit acceptance of hours, deductions, dates, and calculated net.
        val confirmed=e.draft(e.start!!,e.end!!).copy(reportedHours=e.regular,netCents=e.calculatedNet,deductionsComplete=true,
            deposits=e.deposits.map{it.copy(date=e.payDate)},notes="Net accepted as a calculated suggestion, not explicitly reported.")
        try {repeat(2){pass->
            val db=Room.databaseBuilder(context,ShiftDatabase::class.java,name).build()
            try {
                val repo=PayrollRepository(db)
                if(pass==0)repo.save(confirmed)
                val saved=repo.paychecks.first().single();assertEquals(49610L,saved.netCents);assertEquals(3,saved.lines.size);assertEquals(7014L,saved.lines.sumOf{it.cents!!});assertEquals(confirmed.notes,saved.notes)
                assertTrue(runCatching{repo.save(confirmed.copy(grossCents=1))}.isFailure);assertEquals(saved,repo.paychecks.first().single())
                assertTrue(db.shifts().observeSessions().first().isEmpty());assertTrue(db.shifts().scheduleSnapshot().isEmpty());assertEquals(6,db.openHelper.readableDatabase.version)
            }finally{db.close()}
        }}finally{context.deleteDatabase(name)}
    }
    @Test fun photoPickerRequestsOnlyImagesWithoutBroadStoragePermissions(){
        val context=RuntimeEnvironment.getApplication()
        val intent=PickVisualMedia().createIntent(context,PickVisualMediaRequest(PickVisualMedia.ImageOnly))
        assertEquals("image/*",intent.type)
        assertTrue(intent.action in listOf("android.provider.action.PICK_IMAGES","android.intent.action.OPEN_DOCUMENT","androidx.activity.result.contract.action.PICK_IMAGES"))
    }
    @Test fun applicationManifestRemovesNetworkPermissionAndDoesNotRequestGalleryAccess(){
        val manifest=listOf(File("src/main/AndroidManifest.xml"),File("app/src/main/AndroidManifest.xml")).first{it.exists()}.readText()
        assertTrue(manifest.contains("android.permission.INTERNET\" tools:node=\"remove"))
        listOf("READ_EXTERNAL_STORAGE","WRITE_EXTERNAL_STORAGE","READ_MEDIA_IMAGES","READ_MEDIA_VIDEO").forEach{assertFalse(manifest.contains(it))}
    }
}
