package net.ankio.ledger.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LedgerBatchDeletionTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val name="batch-${UUID.randomUUID()}.db"
    private var store=LedgerStore(context,name)
    private val now=System.currentTimeMillis()
    @After fun cleanup(){store.purgeExpiredDeletes(Long.MAX_VALUE);store.all().forEach { store.delete(it.id) };store.close();context.deleteDatabase(name)}
    private fun record(index:Int,provider:String=PaymentApps.WECHAT,direction:Direction=Direction.EXPENSE)=
        (store.ingest(Capture(Source.OCR,provider,"合成批量账单 $index",now+index*300_000L),
            ParsedBill(100,direction,"合成商户 $index","其他",now+index*300_000L,"BATCH-$index",true,"",true)) as IngestResult.Saved).record
    private fun request(all:Boolean=true,ids:List<Long> = emptyList(),excluded:List<Long> = emptyList(),channel:String="wechat",direction:String="EXPENSE")=
        JSONObject().put("period","all").put("search","").put("direction",direction).put("channel",channel)
            .put("all",all).put("ids",JSONArray(ids)).put("excluded",JSONArray(excluded)).toString()
    private fun rejected(block:()->Unit){try{block();fail("Expected atomic rejection")}catch(_:IllegalArgumentException){}catch(_:IllegalStateException){}}

    @Test fun filteredSelectionIncludesUnloadedPagesAndUndoRestoresEveryBalanceAfterRestart(){
        val account=store.saveAccount(LedgerAccount(name="合成账户",amountCents=100_000))
        val rows=(1..251).map { record(it) }
        val alipay=record(900,PaymentApps.ALIPAY);val income=record(901,direction=Direction.INCOME)
        assertEquals(200,LedgerReadView(store).records("all",channel="wechat",direction="EXPENSE").getJSONArray("records").length())
        val manager=LedgerBatchDeletion(store);val preview=manager.preview(request(excluded=listOf(rows.first().id)),now)
        assertEquals(250,preview.getInt("count"));assertEquals(25_000L,preview.getLong("expense"))
        val balance=store.getAccount(account.id)!!.amountCents
        val deleted=manager.commit(preview.getString("token"),now)
        assertEquals(3,store.all().size);assertNotNull(store.get(alipay.id));assertNotNull(store.get(income.id))
        assertEquals(balance+25_000,store.getAccount(account.id)!!.amountCents)
        assertEquals(100L,LedgerReadView(store).summary("all",channel="wechat").getJSONObject("totals").getLong("expense"))
        assertFalse(store.undoDelete(rows.last().id,now+1))
        store.close();store=LedgerStore(context,name)
        assertEquals(250,store.pendingDeletion(now+1)!!.count)
        assertEquals(deleted.batchId,store.pendingDeletion(now+1)!!.batchId)
        assertTrue(store.undoDeleteBatch(deleted.batchId!!,now+2))
        assertEquals(253,store.all().size);assertEquals(balance,store.getAccount(account.id)!!.amountCents)
        assertFalse(store.undoDeleteBatch(deleted.batchId,now+3))
    }

    @Test fun changingBillOrFilterMembershipRequiresANewConfirmation(){
        val first=record(1);val manager=LedgerBatchDeletion(store)
        val before=manager.preview(request(),now);record(2)
        rejected { manager.commit(before.getString("token"),now) };assertEquals(2,store.all().size)
        val next=manager.preview(request(all=false,ids=listOf(first.id)),now)
        store.saveEdited(first.copy(bill=first.bill.copy(amountCents=200)))
        rejected { manager.commit(next.getString("token"),now) };assertEquals(2,store.all().size)
    }

    @Test fun offsettingIncomeAndExpenseAtTheBalanceLimitCanBeDeletedAndRestored(){
        val account=store.saveAccount(LedgerAccount(name="合成边界账户",amountCents=Money.MAX_CENTS))
        val expense=record(1);val income=record(2,direction=Direction.INCOME)
        assertEquals(Money.MAX_CENTS,store.getAccount(account.id)!!.amountCents)
        val deletion=store.softDeleteBatch(listOf(expense.id,income.id),now)
        assertEquals(Money.MAX_CENTS,store.getAccount(account.id)!!.amountCents)
        assertTrue(store.undoDeleteBatch(deletion.batchId!!,now+1))
        assertEquals(Money.MAX_CENTS,store.getAccount(account.id)!!.amountCents)
    }

    @Test fun databaseFailureRollsBackTheEntireBatchAndUndo(){
        val account=store.saveAccount(LedgerAccount(name="合成账户",amountCents=1000))
        val rows=listOf(record(1),record(2));val db=store.writableDatabase
        fun trap(){db.execSQL("CREATE TRIGGER reject_batch BEFORE UPDATE OF account_effect ON records WHEN NEW.id=${rows.last().id} BEGIN SELECT RAISE(ABORT,'synthetic failure'); END")}
        trap()
        try{store.softDeleteBatch(rows.map { it.id },now);fail("Expected rollback")}catch(_:android.database.sqlite.SQLiteException){}
        assertEquals(2,store.all().size);assertEquals(800L,store.getAccount(account.id)!!.amountCents);assertNull(store.pendingDeletion(now))
        db.execSQL("DROP TRIGGER reject_batch")
        val deleted=store.softDeleteBatch(rows.map { it.id },now);trap()
        try{store.undoDeleteBatch(deleted.batchId!!,now+1);fail("Expected undo rollback")}catch(_:android.database.sqlite.SQLiteException){}
        assertTrue(store.all().isEmpty());assertEquals(1000L,store.getAccount(account.id)!!.amountCents)
        assertEquals(2,store.pendingDeletion(now+1)!!.count)
        db.execSQL("DROP TRIGGER reject_batch")
        assertTrue(store.undoDeleteBatch(deleted.batchId!!,now+2))
    }

    @Test fun version14MigrationPreservesSingleDeleteAndBatchExpiry(){
        val first=record(1);val second=record(2);store.softDelete(first.id,now)
        val db=store.writableDatabase
        db.execSQL("ALTER TABLE record_deletions RENAME TO newer_deletions")
        db.execSQL("CREATE TABLE record_deletions(record_id INTEGER PRIMARY KEY NOT NULL,expires_at INTEGER NOT NULL)")
        db.execSQL("INSERT INTO record_deletions SELECT record_id,expires_at FROM newer_deletions")
        db.execSQL("DROP TABLE newer_deletions");db.version=14;store.close();store=LedgerStore(context,name)
        assertTrue(store.undoDelete(first.id,now+1))
        val batch=store.softDeleteBatch(listOf(first.id,second.id),now)
        assertFalse(store.undoDeleteBatch(batch.batchId!!,now+10_000))
        assertEquals(2,store.purgeExpiredDeletes(now+10_000).size);assertTrue(store.all().isEmpty())
    }
}
