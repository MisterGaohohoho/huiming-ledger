package net.ankio.ledger

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.core.*
import net.ankio.ledger.data.*
import net.ankio.ledger.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DataManagementUiTest {
    private val app=ApplicationProvider.getApplicationContext<LedgerApp>()
    @Test fun nativeImportHistoryDeletesAndUndoesItsOwnBillsAndStoragePreviewCanBeCancelled(){
        val marker=UUID.randomUUID().toString()
        val rows=(1..2).map { i -> LedgerRecord(capture=Capture(Source.MANUAL,"import:test","合成文件 $marker $i"),
            bill=ParsedBill(100,Direction.EXPENSE,"合成批次商户 $i","其他",System.currentTimeMillis()+i,"$marker-$i",true,"",true),state=RecordState.POSTED) }
        try {
            LedgerCsvImport.apply(app.store,LedgerCsvImport.Document("通用 CSV",rows,emptyList(),2),"合成测试-$marker.csv")
            val list=ImportBatches(app.store).list(0).getJSONArray("items")
            val batch=(0 until list.length()).map { list.getJSONObject(it) }.first { it.getString("fileName").contains(marker) }.getString("uid")
            app.changed()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web=WebUiProbe(scenario);web.ready();web.run("go('settings');document.querySelector('#backup').click();document.querySelector('#import-history').click()")
                web.await("document.querySelector('[data-import-batch=\"$batch\"]')")
                web.run("document.querySelector('[data-import-batch=\"$batch\"]').click();document.querySelector('#import-batch-delete').click()")
                web.await("document.querySelector('#sheet-title').textContent.includes('2 笔')")
                web.run("document.querySelector('#import-delete-confirm').click()")
                web.await("document.querySelector('#delete-undo button')")
                assertFalse(app.store.all().any { r -> rows.any { it.uid==r.uid } })
                web.run("document.querySelector('#delete-undo button').click()")
                web.await("!document.querySelector('#delete-undo')")
                assertEquals(2,app.store.all().count { r -> rows.any { it.uid==r.uid } })
                val limit=app.screenshots.limitBytes()
                web.run("document.querySelector('#screenshot-storage').click();document.querySelector('#screenshot-limit').value='1024';document.querySelector('#storage-save').click()")
                web.await("document.querySelector('#storage-confirm')")
                web.run("document.querySelector('#storage-cancel').click()")
                web.await("document.querySelector('#screenshot-limit')")
                assertEquals(limit,app.screenshots.limitBytes())
            }
        } finally {
            synchronized(app.store){app.store.all().filter { r -> rows.any { it.uid==r.uid } }.forEach { app.store.delete(it.id) }}
            app.changed()
        }
    }
}
