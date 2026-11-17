package net.ankio.ledger.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import net.ankio.ledger.LedgerApp
import net.ankio.ledger.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class InvalidRecognitionCleanupTest {
    private val app=ApplicationProvider.getApplicationContext<LedgerApp>()
    @Test fun failuresLeaveNeitherTasksNorPrivateImagesButPreserveSharedValidEvidence(){
        val namespace="invalid-images-${UUID.randomUUID()}";val images=ScreenshotStore(app,namespace)
        val name="invalid-tasks-${UUID.randomUUID()}.db";var tasks=RecognitionTasks(app,name,images)
        val shared=byteArrayOf(1,2,3);images.put("valid-bill",shared,System.currentTimeMillis(),"HISTORY")
        try{
            for(status in listOf("NO_BILL","FAILED")){
                val id=tasks.create(Source.OCR,"shake:com.tencent.mm");tasks.queued(id,shared)
                assertTrue(tasks.update(id,status,"未识别到金额，请重试"))
                assertFalse(tasks.exists(id));assertNull(tasks.image(id));assertEquals(0,tasks.snapshot().length())
                assertArrayEquals(shared,images.image("valid-bill"))
            }
            val review=tasks.create(Source.OCR,"payment_auto:com.tencent.mm");tasks.queued(review,byteArrayOf(4,5))
            tasks.update(review,"REVIEW","疑似重复，请核对",999)
            val legacy=tasks.create(Source.OCR,"shake:com.tencent.mm");tasks.queued(legacy,byteArrayOf(8,9))
            tasks.writableDatabase.execSQL("UPDATE tasks SET status='FAILED' WHERE id=?",arrayOf(legacy))
            tasks.close();tasks=RecognitionTasks(app,name,images)
            assertFalse(tasks.exists(legacy));assertNull(tasks.image(legacy))
            assertEquals("REVIEW",tasks.status(review));assertNotNull(tasks.image(review));assertEquals(1,tasks.snapshot().length())
            tasks.delete(review)
        }finally{tasks.close();app.deleteDatabase(name);images.release("valid-bill");assertEquals(0,images.stats().getInt("count"));images.close();app.deleteDatabase("$namespace.db")}
    }
    @Test fun missingZeroAndNegativeAmountsCannotCreateReviewBills()=runBlocking {
        val before=app.store.all().map { it.uid }.toSet()
        for(amount in listOf(null,0L,-1L)){
            val result=app.coordinator.acceptRecognition(Capture(Source.OCR,"com.tencent.mm","合成无效截图"),
                ParsedBill(amount,Direction.EXPENSE,"无金额","其他",System.currentTimeMillis(),null,false,"待确认",true),byteArrayOf(1,2)) { true }
            assertEquals(IngestResult.Ignored,result)
        }
        assertEquals(before,app.store.all().map { it.uid }.toSet())
    }
}
