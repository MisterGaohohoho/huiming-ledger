package net.ankio.ledger.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.core.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import java.io.ByteArrayInputStream

@RunWith(AndroidJUnit4::class)
class OfflineBillsTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val name="offline-${UUID.randomUUID()}.db"
    private val store=LedgerStore(context,name)
    @After fun close(){store.close();context.deleteDatabase(name)}
    private val header=listOf("交易时间","交易类型","交易对方","商品","收/支","金额(元)","支付方式","当前状态","交易单号","商户单号","备注")
    private fun wx(amount:String="100.00",status:String="支付成功",order:String="SYNTHETIC-ORDER")=PlatformStatements.fromRows(listOf(header,
        listOf("2026-11-10 12:00:00","餐饮","测试商户","午餐","支出",amount,"零钱",status,order,"MERCHANT-001","")),"wechat")
    private fun query(channel:String="",mode:String="month",period:String="2026-11")=JSONObject().put("channel",channel).put("mode",mode).put("period",period)
    private fun time(value:String)=LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test fun fileImportIsIdempotentAndUpdatesTheSameRefundAndAccount() {
        val account=store.saveAccount(LedgerAccount(name="测试微信",amountCents=100_000))
        store.setChannelDefaultAccount("wechat",account.uid)
        assertEquals(1,PlatformStatements.apply(store,wx()).saved)
        val original=store.all().single()
        assertEquals(90_000L,store.accounts().single { it.uid==account.uid }.amountCents)
        assertEquals(1,PlatformStatements.apply(store,wx()).duplicates)
        assertEquals(1,PlatformStatements.apply(store,wx(status="已退款(￥30.00)")).updated)
        assertEquals(original.id,store.all().single().id)
        assertEquals(7_000L,store.get(original.id)!!.bill.amountCents)
        assertEquals(93_000L,store.accounts().single { it.uid==account.uid }.amountCents)
        assertEquals(7_000L,OfflineAnalytics(store).summary(query("wechat")).getJSONObject("totals").getLong("expense"))
        PlatformStatements.apply(store,wx()) // Old exports must never undo a newer refund.
        assertEquals(7_000L,store.get(original.id)!!.bill.amountCents)
    }

    @Test fun importRecognizesAnExistingAutomaticallyCapturedPayment() {
        val entry=wx().entries.single()
        val saved=(store.ingest(entry.capture.copy(source=Source.OCR),entry.bill) as IngestResult.Saved).record
        assertEquals(1,PlatformStatements.apply(store,wx()).duplicates)
        assertEquals(saved.id,store.all().single().id)
        assertEquals(1,PlatformStatements.apply(store,wx(status="已退款")).updated)
        assertEquals(0L,OfflineAnalytics(store).summary(query()).getJSONObject("totals").getLong("expense"))
    }

    @Test fun filtersKeepChannelsPeriodsAndPendingRecordsSeparate() {
        PlatformStatements.apply(store,wx())
        fun add(origin:String,amount:Long,at:String,state:RecordState=RecordState.POSTED)=LedgerRecord(
            capture=Capture(Source.MANUAL,origin,""),bill=ParsedBill(amount,Direction.INCOME,"测试收入","工资",time(at),null,true,"",true),state=state)
        store.merge(listOf(add(PaymentApps.ALIPAY,50_000,"2026-11-12T08:00:00"),add(PaymentApps.ALIPAY,90_000,"2026-10-12T08:00:00"),
            add(PaymentApps.WECHAT,5_000,"2026-11-13T08:00:00",RecordState.REVIEW)))
        val analytics=OfflineAnalytics(store)
        assertEquals(50_000L,analytics.summary(query()).getJSONObject("totals").getLong("income"))
        assertEquals(0L,analytics.summary(query("wechat")).getJSONObject("totals").getLong("income"))
        assertEquals(2,analytics.details(query("wechat").put("management",true)).getJSONArray("records").length())
        assertEquals(1,analytics.details(query("alipay").put("search","测试")).getJSONArray("records").length())
        val year=analytics.summary(query("alipay","year","2026"))
        assertEquals(140_000L,year.getJSONObject("totals").getLong("income"));assertEquals(12,year.getJSONArray("trend").length())
        assertEquals(29,analytics.summary(query("","month","2024-02")).getJSONArray("trend").length())
        store.delete(store.all().first { it.bill.direction==Direction.EXPENSE }.id)
        assertEquals(0L,analytics.summary(query()).getJSONObject("totals").getLong("expense"))
    }

    @Test fun alipayRefundsAreMergedAndUnlinkedRefundsAreReported() {
        val header=listOf("交易时间","交易分类","交易对方","商品说明","收/支","金额","收/付款方式","交易状态","交易订单号","商家订单号")
        val doc=PlatformStatements.fromRows(listOf(header,
            listOf("2026-11-10 12:00:00","购物","测试商户","商品","支出","100.00","余额","交易成功","PAY-0001","MERCHANT-001"),
            listOf("2026-11-11 12:00:00","退款","测试商户","退款","不计收支","30.00","余额","退款成功","REFUND-001","MERCHANT-001"),
            listOf("2026-11-11 12:00:00","退款","另一商户","退款","收入","10.00","余额","退款成功","REFUND-002","MERCHANT-002")),"alipay")
        assertEquals(1,doc.entries.size);assertEquals(7_000L,doc.entries.single().bill.amountCents);assertEquals(1,doc.errors.size)
        PlatformStatements.apply(store,doc)
        assertEquals(7_000L,OfflineAnalytics(store).summary(query("alipay")).getJSONObject("totals").getLong("expense"))
    }

    @Test fun csvEncodingQuotedMerchantAndWrongChannelAreHandled() {
        val csv=header.joinToString(",")+"\r\n2026-11-10 12:00:00,餐饮,\"商户,分店\",早餐,支出,12.34,零钱,支付成功,SYNTHETIC-ID,MERCHANT-ID,备注\r\n"
        for(charset in listOf(Charsets.UTF_8,java.nio.charset.Charset.forName("GB18030"))) {
            val doc=PlatformStatements.read(ByteArrayInputStream(csv.toByteArray(charset)),"wechat")
            assertEquals("商户,分店",doc.entries.single().bill.merchant);assertEquals(1234L,doc.entries.single().bill.amountCents)
        }
        try{PlatformStatements.read(ByteArrayInputStream(csv.toByteArray()),"alipay");fail("Wrong channel accepted")}
        catch(_:IllegalArgumentException){}
    }
}
