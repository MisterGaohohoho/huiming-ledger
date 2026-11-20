package net.ankio.ledger.update

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import net.ankio.ledger.BuildConfig
import net.ankio.ledger.core.UpdateSecurity
import net.ankio.ledger.ui.MainActivity
import net.ankio.ledger.ui.MintDialog
import java.io.File

class UpdateController(private val activity:MainActivity) {
    private val client=UpdateClient(activity)
    private var sheet:MintDialog?=null
    private var job:Job?=null
    private var release:UpdateSecurity.Release?=null
    private var downloaded:File?=null
    fun close(){client.cancel();job?.cancel();job=null;sheet?.dismiss();sheet=null}
    fun show(){
        if(sheet?.dialog?.isShowing==true)return
        page("版本更新").apply {
            content.addView(text("当前版本 ${BuildConfig.VERSION_NAME}\n\n点击检查时连接更新服务，仅获取版本信息和安装包。账单、账户和截图不会上传。服务提供方可能处理 IP、访问时间等网络信息。",14,MintDialog.MUTED))
            action("检查更新",true){check()};show()
        }
    }
    private fun page(title:String):MintDialog {
        sheet?.dialog?.setOnDismissListener(null);sheet?.dismiss()
        return MintDialog(activity,title,showCloseButton=false).also { current -> sheet=current
            current.dialog.setCanceledOnTouchOutside(true)
            current.dialog.setOnDismissListener { if(sheet===current){sheet=null;client.cancel();job?.cancel();job=null} }
        }
    }
    private fun check(){
        val current=page("检查更新");current.content.addView(current.text("正在检查…"));current.show()
        job=activity.uiScope.launch {
            try{
                val value=withContext(Dispatchers.IO){client.check()};release=value
                if(value.versionCode<=BuildConfig.VERSION_CODE){page("版本更新").apply {content.addView(text("当前已是最新版本 ${BuildConfig.VERSION_NAME}"));action("重新检查"){check()};show()}}
                else available(value)
            }catch(_:CancellationException){}catch(_:Exception){failure("无法完成检查。请确认网络和设备时间后重试；这不表示当前已经是最新版。")}
        }
    }
    private fun available(value:UpdateSecurity.Release){
        page("发现新版本 ${value.versionName}").apply {
            content.addView(text("安装包 ${String.format(java.util.Locale.ROOT,"%.1f",value.bytes/1048576.0)} MB\n\n${value.notes}",14,MintDialog.INK))
            content.addView(text("覆盖安装保留本机账本，仍建议定期导出备份。安装需由系统确认。",13,MintDialog.MUTED))
            action("下载更新",true){download(value)};show()
        }
    }
    private fun download(value:UpdateSecurity.Release){
        val current=page("下载更新");val status=current.text("准备下载…");current.content.addView(status)
        current.action("取消下载"){client.cancel();job?.cancel();available(value)};current.show()
        job=activity.uiScope.launch {
            try{
                var last=0L
                val file=withContext(Dispatchers.IO){client.download(value){received,total ->
                    val now=android.os.SystemClock.elapsedRealtime()
                    if(now-last>=200 || received==total){last=now;activity.runOnUiThread { if(sheet===current)status.text="已下载 ${received*100/total}%\n${String.format(java.util.Locale.ROOT,"%.1f / %.1f MB",received/1048576.0,total/1048576.0)}" }}
                }}
                downloaded=file;ready(value)
            }catch(_:CancellationException){}catch(_:Exception){failure("下载或安全校验未完成，安装包不会安装。请重试。")}
        }
    }
    private fun ready(value:UpdateSecurity.Release){
        page("更新已就绪").apply {
            content.addView(text("${value.versionName} 已通过完整性及签名检查。\n\n如果系统要求允许安装，请仅为有数开启安装权限，再返回此处点击安装。"))
            action("安装更新",true){install(value)};show()
        }
    }
    private fun install(value:UpdateSecurity.Release){
        if(!activity.packageManager.canRequestPackageInstalls()){
            try{activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${activity.packageName}")))}catch(_:Exception){failure("请在系统设置中允许有数安装应用，然后重试。")}
            return
        }
        val file=downloaded?:return
        val current=page("安装前校验");current.content.addView(current.text("正在重新校验安装包…"));current.show()
        job=activity.uiScope.launch {
            try{
                withContext(Dispatchers.IO){client.verifyForInstall(file,value)}
                val uri=FileProvider.getUriForFile(activity,"${activity.packageName}.updates",file)
                ready(value)
                activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }catch(_:CancellationException){}catch(_:Exception){failure("无法安全安装此更新，请重新下载或检查系统安装权限。")}
        }
    }
    private fun failure(message:String){if(activity.isDestroyed||activity.isFinishing)return;page("更新未完成").apply {content.addView(text(message));action("重新检查",true){check()};show()}}
}
