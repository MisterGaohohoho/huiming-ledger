package net.ankio.ledger.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/** Read the same posted ledger as the home screen, without copying bills or OCR text. */
class OfflineAnalytics(private val store: LedgerStore) {
    companion object {
        const val CHANNEL_SQL = """CASE
            WHEN lower(r.origin) LIKE '%com.tencent.mm%' OR r.origin IN ('微信','微信支付') THEN 'wechat'
            WHEN lower(r.origin) LIKE '%com.eg.android.alipaygphone%' OR r.origin='支付宝' THEN 'alipay'
            WHEN r.source IN ('IMAGE','OCR') AND instr(r.raw_text,'微信')>0 AND instr(r.raw_text,'支付宝')=0 THEN 'wechat'
            WHEN r.source IN ('IMAGE','OCR') AND instr(r.raw_text,'支付宝')>0 AND instr(r.raw_text,'微信')=0 THEN 'alipay'
            ELSE 'other' END"""
    }

    private data class Scope(val start: Long, val end: Long, val previous: Long, val year: Boolean,
                             val channel: String, val reimbursement: String)

    private fun scope(input: JSONObject): Scope {
        val year = when (input.optString("mode", "month")) {
            "month" -> false; "year" -> true; else -> error("统计周期无效")
        }
        val period = input.getString("period")
        require(Regex(if (year) "20[0-9]{2}|2100" else "(?:20[0-9]{2}|2100)-(?:0[1-9]|1[0-2])").matches(period)) { "日期范围无效" }
        val start = LocalDate.parse(if (year) "$period-01-01" else "$period-01")
        val end = if (year) start.plusYears(1) else start.plusMonths(1)
        val previous = if (year) start.minusYears(1) else start.minusMonths(1)
        val channel = input.optString("channel")
        require(channel in setOf("", "wechat", "alipay", "other")) { "支付渠道无效" }
        val reimbursement = input.optString("reimbursement")
        require(reimbursement in setOf("", "required", "notRequired")) { "报销筛选无效" }
        fun millis(date: LocalDate) = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return Scope(millis(start), millis(end), millis(previous), year, channel, reimbursement)
    }

    private val amount get() = ReimbursementStore.statisticalAmountSql()
    private fun where(s: Scope, management: Boolean = false): String = (if(management) "r.state IN ('POSTED','REVIEW')" else "r.state='POSTED'") + """
        AND NOT EXISTS(SELECT 1 FROM record_deletions d WHERE d.record_id=r.id)
        AND r.occurred_at>=? AND r.occurred_at<?""" +
        (if(management) "" else " AND (r.direction!='INCOME' OR $amount>0)") +
        (if (s.channel.isEmpty()) "" else " AND ($CHANNEL_SQL)=?") +
        when (s.reimbursement) { "required" -> " AND r.requires_reimbursement=1"; "notRequired" -> " AND r.requires_reimbursement=0"; else -> "" }
    private fun args(s: Scope, start: Long = s.start, end: Long = s.end) =
        (listOf(start.toString(), end.toString()) + if (s.channel.isEmpty()) emptyList() else listOf(s.channel)).toTypedArray()

    fun summary(input: JSONObject): JSONObject {
        val s = scope(input)
        fun totals(start: Long, end: Long): JSONObject {
            val out = JSONObject().put("income", 0).put("expense", 0).put("incomeCount", 0).put("expenseCount", 0)
            store.readableDatabase.rawQuery("SELECT r.direction,COALESCE(SUM($amount),0),COUNT(*) FROM records r WHERE ${where(s)} GROUP BY r.direction", args(s,start,end)).use { c ->
                while (c.moveToNext()) {
                    val key = if (c.getString(0) == "INCOME") "income" else "expense"
                    out.put(key,c.getLong(1)).put(key+"Count",c.getInt(2))
                }
            }
            return out.put("balance",out.getLong("income")-out.getLong("expense"))
        }
        val categories = JSONArray()
        store.readableDatabase.rawQuery("SELECT r.direction,r.category,SUM($amount),COUNT(*) FROM records r WHERE ${where(s)} GROUP BY r.direction,r.category ORDER BY SUM($amount) DESC,r.category",args(s)).use { c ->
            while(c.moveToNext()) categories.put(JSONObject().put("direction",c.getString(0)).put("name",c.getString(1)).put("amount",c.getLong(2)).put("count",c.getInt(3)))
        }
        val buckets = mutableMapOf<Int,JSONObject>()
        val date = if(s.year) "%m" else "%d"
        store.readableDatabase.rawQuery("SELECT strftime('$date',r.occurred_at/1000,'unixepoch','localtime'),r.direction,SUM($amount) FROM records r WHERE ${where(s)} GROUP BY 1,2 ORDER BY 1",args(s)).use { c ->
            while(c.moveToNext()) {
                val index=c.getString(0).toInt()
                buckets.getOrPut(index){JSONObject().put("index",index).put("income",0).put("expense",0)}
                    .put(if(c.getString(1)=="INCOME")"income" else "expense",c.getLong(2))
            }
        }
        val count=if(s.year)12 else java.time.Instant.ofEpochMilli(s.start).atZone(ZoneId.systemDefault()).toLocalDate().lengthOfMonth()
        val trend=JSONArray()
        for(index in 1..count) trend.put(buckets[index]?:JSONObject().put("index",index).put("income",0).put("expense",0))
        val top=details(JSONObject(input.toString()).put("direction","EXPENSE").put("sort","amount").put("limit",10)).getJSONArray("records")
        return JSONObject().put("ok",true).put("totals",totals(s.start,s.end)).put("previous",totals(s.previous,s.start))
            .put("categories",categories).put("trend",trend).put("top",top).put("period",input.getString("period"))
    }

    fun details(input: JSONObject): JSONObject {
        val s=scope(input); val management=input.optBoolean("management",false)
        val selectedAmount=if(management)"COALESCE(r.amount,0)" else amount
        val parameters=args(s).toMutableList(); val condition=StringBuilder(where(s,management))
        val direction=input.optString("direction")
        require(direction in setOf("","INCOME","EXPENSE")) { "收支类型无效" }
        if(direction.isNotEmpty()) { condition.append(" AND r.direction=?");parameters+=direction }
        val category=input.optString("category");require(category.length<=1024)
        if(category.isNotEmpty()){condition.append(" AND r.category=?");parameters+=category}
        val search=input.optString("search").trim();require(search.length<=200)
        if(search.isNotEmpty()) {
            condition.append(" AND (r.merchant LIKE ? ESCAPE '\\' OR r.note LIKE ? ESCAPE '\\' OR r.transaction_id LIKE ? ESCAPE '\\')")
            val term="%"+search.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%"
            repeat(3){parameters+=term}
        }
        val offset=input.optInt("offset",0);val limit=input.optInt("limit",50)
        require(offset in 0..1_000_000 && limit in 1..200)
        val sort=input.optString("sort","time");require(sort in setOf("time","amount"))
        val order=if(sort=="amount")"$selectedAmount DESC,r.occurred_at DESC,r.id DESC" else "r.occurred_at DESC,r.id DESC"
        val rows=JSONArray()
        store.readableDatabase.rawQuery("SELECT r.id,r.merchant,r.category,r.occurred_at,$selectedAmount,r.direction,($CHANNEL_SQL),r.note,r.transaction_id,r.state FROM records r WHERE $condition ORDER BY $order LIMIT ${limit+1} OFFSET $offset",parameters.toTypedArray()).use { c ->
            while(c.moveToNext())rows.put(JSONObject().put("id",c.getLong(0)).put("merchant",c.getString(1)).put("category",c.getString(2))
                .put("occurredAt",c.getLong(3)).put("amountCents",c.getLong(4)).put("direction",c.getString(5)).put("channel",c.getString(6))
                .put("note",c.getString(7)).put("transactionId",if(c.isNull(8))JSONObject.NULL else c.getString(8)).put("state",c.getString(9)))
        }
        val more=rows.length()>limit;if(more)rows.remove(rows.length()-1)
        return JSONObject().put("ok",true).put("records",rows).put("hasMore",more)
    }
}
