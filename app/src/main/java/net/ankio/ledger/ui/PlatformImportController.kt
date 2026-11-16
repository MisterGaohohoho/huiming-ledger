package net.ankio.ledger.ui

import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.*
import net.ankio.ledger.data.PlatformStatements
import net.ankio.ledger.core.Money
import net.ankio.ledger.ledger
import org.json.JSONObject

internal class PlatformImportController(private val activity:MainActivity) {
    fun open(platform:String) {
        require(platform in setOf("wechat","alipay"))
        activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type="*/*";addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("text/csv","application/csv","text/comma-separated-values","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","application/octet-stream"))
        },if(platform=="wechat")WECHAT else ALIPAY)
    }

    fun read(uri:Uri,platform:String) { activity.uiScope.launch {
        activity.dispatch("fileOperation",JSONObject.quote("正在读取账单"))
        try {
            val document=withContext(Dispatchers.IO) {
                (activity.contentResolver.openInputStream(uri)?:error("无法读取文件")).use { PlatformStatements.read(it,platform) }
            }
            activity.dispatch("fileOperation","null")
            if(activity.isFinishing || activity.isDestroyed)return@launch
            val sheet=MintDialog(activity,"${if(platform=="wechat")"微信" else "支付宝"}账单导入","文件在本机解析")
            sheet.content.addView(sheet.text("可处理 ${document.entries.size} 笔",20,MintDialog.INK,true))
            sheet.content.addView(sheet.text("跳过 ${document.skipped} 行 · 需核对 ${document.errors.size} 行",14,MintDialog.MUTED))
            document.entries.take(4).forEach { entry -> sheet.content.addView(sheet.text("${entry.bill.merchant.take(24)} · ¥${Money.format(entry.bill.amountCents!!)}${if(entry.bill.refund!=null)"（退款后）" else ""}",14,MintDialog.INK)) }
            if(document.errors.isNotEmpty()) {
                sheet.label("以下行不会导入")
                document.errors.take(6).forEach { sheet.content.addView(sheet.text(it,12,MintDialog.MUTED)) }
            }
            sheet.content.addView(sheet.text("与已有账本核对订单号，重复付款跳过，退款更新原账单。疑似重复或无法关联的记录进入待确认；新账单按该渠道的默认账户联动余额。请核对导入结果。",13,MintDialog.MUTED))
            if(document.entries.isNotEmpty())sheet.action("确认导入",true) {
                sheet.dismiss()
                activity.uiScope.launch {
                    activity.dispatch("fileOperation",JSONObject.quote("正在导入账单"))
                    try {
                        val result=withContext(Dispatchers.IO){ensureActive();PlatformStatements.apply(activity.ledger.store,document)}
                        activity.ledger.changed()
                        activity.message("新增 ${result.saved}，更新 ${result.updated}，重复 ${result.duplicates}，待确认 ${result.review}")
                    } catch(cancelled:CancellationException){throw cancelled}
                    catch(error:Exception){activity.message(error.message?.take(80)?:"导入失败，账本未修改")}
                    finally{activity.dispatch("fileOperation","null")}
                }
            }
            sheet.action("取消"){sheet.dismiss()};sheet.show()
        } catch(cancelled:CancellationException){throw cancelled}
        catch(error:Exception){activity.message(error.message?.take(100)?:"无法读取账单文件")}
        finally{activity.dispatch("fileOperation","null")}
    } }
    companion object { const val WECHAT=17;const val ALIPAY=18 }
}
