package net.ankio.ledger

import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import net.ankio.ledger.ocr.*
import net.ankio.ledger.capture.CaptureProcessor
import net.ankio.ledger.core.Source
import net.ankio.ledger.data.RecognitionTasks
import net.ankio.ledger.data.RecognitionDiagnostics
import net.ankio.ledger.ui.MainActivity
import net.ankio.ledger.ui.LedgerWebBridge
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RecognitionTaskTest {
    private val app get()=ApplicationProvider.getApplicationContext<LedgerApp>()

    @Test fun diagnosticExportKeepsStageButExcludesSecretsBillTextAndImages() {
        val name="diagnostic-${UUID.randomUUID()}.db"
        val tasks=RecognitionTasks(app,name)

        try {
            val id=tasks.create(Source.OCR,"private-origin")
            tasks.prepared(id,byteArrayOf(3,4,5))
            tasks.diagnose(id,"window","net.ankio.ledger → com.tencent.mm")
            tasks.diagnose(id,"stage","model_request")
            tasks.diagnose(id,"window_details","windows=2; selected=7; root=query_timeout; cache=disabled")
            tasks.diagnose(id,"icon_details","reason=too_small; image=900x1800; box=40,10,42,11")
            tasks.diagnose(id,"trigger","sensor_age_ms=20; dispatch_age_ms=100; queue_ms=80")
            tasks.diagnose(id,"timing","finished_age_ms=3100")
            tasks.update(id,"REVIEW","private bill detail",123)
            val report=RecognitionDiagnostics.encode(tasks)
            assertFalse(report.contains("private-origin"));assertFalse(report.contains("private bill detail"))
            assertFalse(report.contains("do-not-export-key"));assertFalse(report.contains("example.invalid"))
            val attempt=JSONObject(report).getJSONArray("attempts").getJSONObject(0)
            assertFalse(attempt.has("image"));assertFalse(attempt.has("rawText"));assertFalse(attempt.has("message"))
            assertEquals("model_request",attempt.getJSONObject("diagnostic").getString("stage"))
            assertTrue(attempt.getJSONObject("diagnostic").getString("window_details").contains("root=query_timeout"))
            assertTrue(attempt.getJSONObject("diagnostic").getString("icon_details").contains("reason=too_small"))
            assertTrue(attempt.getJSONObject("diagnostic").getString("trigger").contains("queue_ms=80"))
            assertEquals("finished_age_ms=3100",attempt.getJSONObject("diagnostic").getString("timing"))
            assertEquals("REVIEW",attempt.getString("status"))
        } finally { tasks.close();app.deleteDatabase(name);app.recognitionSession.invalidate() }
    }

    @Test fun taskDatabaseUpgradeDiscardsUnlinkedFailureAndImage() {
        val name="diagnostic-upgrade-${UUID.randomUUID()}.db"
        val old=android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(app.getDatabasePath(name),null)
        old.execSQL("CREATE TABLE tasks(id INTEGER PRIMARY KEY AUTOINCREMENT,source TEXT NOT NULL,origin TEXT NOT NULL,created_at INTEGER NOT NULL,status TEXT NOT NULL,message TEXT NOT NULL,record_id INTEGER,image BLOB)")
        old.execSQL("INSERT INTO tasks VALUES(1,'OCR','shake',1,'FAILED','old failure',NULL,X'030405')")
        old.version=1;old.close()
        val tasks=RecognitionTasks(app,name)
        try {
            assertNull(tasks.status(1));assertNull(tasks.image(1));assertEquals(0,tasks.snapshot().length())
        } finally { tasks.close();app.deleteDatabase(name) }
    }

    @Test fun requestHasProgressUntilFailureThenDiscardsItsScreenshot()=runBlocking {

        // Launch before the deliberately unresolved model request: ActivityScenario
        // waits for idle, whereas the progress animation intentionally schedules frames.
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
        val web=WebUiProbe(scenario);web.ready()
        val id=app.tasks.create(Source.OCR,"com.tencent.mm")
        val bitmap=Bitmap.createBitmap(80,160,Bitmap.Config.ARGB_8888)
        val entered=CompletableDeferred<Unit>();val finish=CompletableDeferred<Unit>()
        val before=app.store.all().map { it.uid }.toSet()
        val processor=CaptureProcessor(app,OcrClient(app,SyntheticRecognitionBackend { _,_,payload,_ ->
            entered.complete(Unit);finish.await();throw RecognitionException("合成网络失败，请重试")
        }))
        val job=async { runCatching { processor.process(bitmap,Source.OCR,"com.tencent.mm",id) { true } } }
        try {
            withTimeout(5_000){entered.await()}
            val reopened=RecognitionTasks(app)
            try { assertEquals("RECOGNIZING",reopened.status(id));assertNotNull(reopened.image(id)) } finally { reopened.close() }
                web.await("!!document.querySelector('[data-task=\"$id\"]')")
                web.run("document.querySelector('[data-task=\"$id\"]').click()")
                web.await("recordPanelOpen && document.querySelector('#import-form .primary').disabled")
                web.await("document.querySelector('#capture-preview').naturalWidth===80")
                finish.complete(Unit);job.await();app.changed()
                web.await("!document.querySelector('[data-task=\"$id\"]') && !recordPanelOpen")
                assertFalse(app.tasks.exists(id));assertNull(app.tasks.image(id))
                assertEquals(before,app.store.all().map { it.uid }.toSet())
                scenario.recreate();web.ready()
                assertEquals("false",web.value("!!document.querySelector('[data-task=\"$id\"]')"))
        } finally { finish.complete(Unit);job.cancelAndJoin();app.tasks.delete(id);bitmap.recycle();app.recognitionSession.invalidate();app.changed() }
        }
    }

    @Test fun nonBillAndInterruptedAttemptsAreDiscardedWithoutInventingMoney()=runBlocking {

        val id=app.tasks.create(Source.IMAGE,"image");val interrupted=app.tasks.create(Source.OCR,"shake")
        val image=Bitmap.createBitmap(10,10,Bitmap.Config.ARGB_8888)
        val before=app.store.all().map { it.uid }.toSet()
        try {
            CaptureProcessor(app,OcrClient(app,SyntheticRecognitionBackend { _,_,_,_ -> JSONObject().put("choices",JSONArray().put(JSONObject().put("message",JSONObject().put("content","{\"is_bill\":false,\"raw_text\":\"非账单\"}")))) }))
                .process(image,Source.IMAGE,"image",id) { true }
            app.tasks.recoverInterrupted()
            assertNull(app.tasks.status(id));assertNull(app.tasks.status(interrupted))
            assertEquals(before,app.store.all().map { it.uid }.toSet())
        } finally { app.tasks.delete(id);app.tasks.delete(interrupted);image.recycle();app.recognitionSession.invalidate() }
    }

    @Test fun failedAttemptCannotBeSavedFromAStaleEditor() {
        val id=app.tasks.create(Source.OCR,"shake");app.tasks.update(id,"FAILED","识别失败")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { activity ->
                val input=JSONObject().put("id",0).put("recognitionTaskId",id).put("merchant","补录-${UUID.randomUUID()}")
                    .put("category","其他").put("amount","12.34").put("direction","EXPENSE").put("occurredAt",System.currentTimeMillis()).put("note","")
                assertFalse(JSONObject(LedgerWebBridge(activity).save(input.toString())).getBoolean("ok"))
                assertFalse(app.tasks.exists(id))
            } }
        } finally { app.tasks.delete(id);app.changed() }
    }
}
