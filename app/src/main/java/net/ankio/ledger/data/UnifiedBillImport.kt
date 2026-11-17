package net.ankio.ledger.data

import net.ankio.ledger.core.*
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.UUID

/** Local schema detection. Input contents are data, never executable formulas or instructions. */
object UnifiedBillImport {
    sealed interface Document
    data class Platform(val document:PlatformStatements.Document):Document
    data class General(val document:LedgerCsvImport.Document):Document
    data class Jiezhang(val rows:List<Item>,val errors:List<String>):Document
    data class Item(val record:LedgerRecord,val asset:String)
    private val header=setOf("子分类","父分类","类型","资产","备注","金额","创建时间","更新时间")

    fun read(input:InputStream):Document {
        val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true){val count=input.read(buffer);if(count<0)break;require(out.size()+count<=8*1024*1024){"文件超过 8 MB，请分批导入"};out.write(buffer,0,count)}
        val bytes=out.toByteArray()
        require(bytes.size<=8*1024*1024){"文件超过 8 MB，请分批导入"}
        val rows=PlatformStatements.readRows(bytes.inputStream())
        val first=rows.firstOrNull()?.map { it.trim().removePrefix("\uFEFF") }.orEmpty()
        if(first.size==header.size && first.toSet()==header)return jiezhang(rows)
        val official=rows.firstOrNull { row->"交易时间" in row.map(String::trim) }.orEmpty().map(String::trim)
        if("交易分类" in official || "收/付款方式" in official)return Platform(PlatformStatements.fromRows(rows,"alipay"))
        if("当前状态" in official || "商品" in official)return Platform(PlatformStatements.fromRows(rows,"wechat"))
        return General(LedgerCsvImport.read(bytes.inputStream()))
    }

    internal fun jiezhang(rows:List<List<String>>):Jiezhang {
        require(rows.size<=50_001){"最多导入 50000 笔，请分批导入"}
        val columns=rows.first().map(String::trim)
        require(columns.size==header.size && columns.toSet()==header){"洁账表头不完整"}
        val items=mutableListOf<Item>();val errors=mutableListOf<String>();val occurrences=mutableMapOf<String,Int>()
        val format=DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)
        for((index,row) in rows.drop(1).withIndex())try {
            if(row.all(String::isBlank))continue
            require(row.size==columns.size){"列数与表头不一致"}
            val fields=columns.zip(row.map(String::trim)).toMap()
            fun field(name:String)=fields.getValue(name)
            val direction=when(field("类型")){"收入"->Direction.INCOME;"支出"->Direction.EXPENSE;else->error("暂不支持此类型，请核对转账或退款")}
            val amount=java.math.BigDecimal(field("金额")).movePointRight(2).longValueExact()
            require(amount in 1..Money.MAX_CENTS){"金额必须为正数且最多两位小数"}
            fun time(value:String)=LocalDateTime.parse(value,format).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val at=time(field("创建时间"));val updated=time(field("更新时间"))
            require(at>0 && at<4133980800000L && updated>0){"时间无效"}
            val category=listOf(field("父分类"),field("子分类")).filter(String::isNotBlank).joinToString("/").ifBlank{"其他"}
            val asset=field("资产").ifBlank{"未指定账户"};val note=field("备注")
            require(category.length<=40 && asset.length<=40 && note.length<=500){"分类、资产或备注过长"}
            val signature=columns.sorted().joinToString(""){key->field(key).let { "${key.length}:$key:${it.length}:$it" }}
            val ordinal=occurrences.getOrDefault(signature,0);occurrences[signature]=ordinal+1
            val uid=UUID.nameUUIDFromBytes(("jiezhang\u0000$signature\u0000$ordinal").toByteArray()).toString()
            val origin=when(asset){"微信钱包","微信","微信零钱","零钱"->PaymentApps.WECHAT;"支付宝","支付宝余额"->PaymentApps.ALIPAY;else->"import:jiezhang"}
            val capture=Capture(Source.MANUAL,origin,"洁账导入：$uid\n原资产：$asset\n更新时间：${field("更新时间")}",updated)
            items+=Item(LedgerRecord(uid=uid,capture=capture,bill=ParsedBill(amount,direction,
                field("子分类").ifBlank{"洁账导入"},category,at,null,true,"",true),state=RecordState.POSTED,note=note),asset)
        }catch(error:Exception){errors+="第 ${index+2} 行：${error.message?:"字段无效"}"}
        return Jiezhang(items,errors)
    }

    data class Selection(val fresh:List<Item>,val duplicates:Int,val possible:Set<String>)
    fun preview(store:LedgerStore,document:Jiezhang):Selection=synchronized(store) {
        val existing=store.all()
        // Deleted rows still own their import identity, so re-import cannot resurrect them.
        val uids=mutableSetOf<String>()
        store.readableDatabase.rawQuery("SELECT uid FROM records",null).use{c->while(c.moveToNext())uids+=c.getString(0)}
        val fresh=document.rows.filter { uids.add(it.record.uid) }
        val candidates=existing.filter{it.state==RecordState.POSTED}.groupBy { it.bill.amountCents to it.bill.direction }
        val possible=fresh.filter { item -> candidates[item.record.bill.amountCents to item.record.bill.direction].orEmpty().any { old ->
            kotlin.math.abs(old.bill.occurredAt-item.record.bill.occurredAt)<=120_000 &&
                (paymentProvider(old.capture)==paymentProvider(item.record.capture) || paymentProvider(item.record.capture)==null)
        }}.mapTo(mutableSetOf()){it.record.uid}
        return Selection(fresh,document.rows.size-fresh.size,possible)
    }

    fun apply(store:LedgerStore,document:Jiezhang,accountMapping:Map<String,String?>):Pair<Int,Int> = synchronized(store) {
        val db=store.writableDatabase;db.beginTransaction()
        try {
            val selection=preview(store,document)
            val resolved=accountMapping.mapValues { (asset,uid)->
                if(uid=="@new")store.accounts().firstOrNull{LedgerAccount.nameKey(it.name)==LedgerAccount.nameKey(asset)}?.uid
                    ?:store.saveAccount(LedgerAccount(name=asset,amountCents=0,kind=when(asset){"微信钱包","微信","微信零钱"->"wechat";"支付宝","支付宝余额"->"alipay";else->"custom"})).uid
                else uid
            }
            val records=selection.fresh.map { item ->
                val uid=resolved[item.asset]
                require(uid==null || store.accounts().any{it.uid==uid}){"关联账户已不存在，请重新导入"}
                item.record.copy(accountUid=uid,state=if(item.record.uid in selection.possible)RecordState.REVIEW else RecordState.POSTED,
                    reason=if(item.record.uid in selection.possible)"与已有账单金额、时间相近，请核对是否重复后再确认" else "")
            }
            store.merge(records,categories=records.map { CustomCategory(it.bill.category,it.bill.direction!!) }.distinct())
            db.setTransactionSuccessful()
            (records.size-selection.possible.size) to selection.possible.size
        }finally{db.endTransaction()}
    }
}
