package net.ankio.ledger.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import net.ankio.ledger.BuildConfig
import net.ankio.ledger.R
import net.ankio.ledger.core.UpdateSecurity
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection

/** Only this native client accesses the update host. It never reads ledger data. */
class UpdateClient(private val context:Context) {
    private val connection=AtomicReference<HttpsURLConnection?>()
    private val preferences=context.getSharedPreferences("verified_updates",Context.MODE_PRIVATE)
    private val base=BuildConfig.UPDATE_BASE_URL.trimEnd('/')
    fun cancel() { connection.getAndSet(null)?.disconnect() }
    private fun open(url:String):HttpsURLConnection {
        UpdateSecurity.url(url,base)
        require(!base.endsWith(".invalid")) { "此构建尚未配置更新服务" }
        val current=URL(url).openConnection() as HttpsURLConnection
        current.instanceFollowRedirects=false
        current.connectTimeout=15000;current.readTimeout=20000
        current.useCaches=false
        current.setRequestProperty("Accept-Encoding","identity")
        current.setRequestProperty("User-Agent","Youshu-Updater")
        connection.set(current)
        return current
    }
    suspend fun check():UpdateSecurity.Release {
        currentCoroutineContext().ensureActive()
        val request=open("$base/latest.json")
        val bytes=try {
            val status=request.responseCode
            require(status==200) { if(status==503)"更新清单暂未就绪，请稍后重试" else "更新服务暂不可用（$status）" }
            require(request.contentLengthLong<=UpdateSecurity.MAX_ENVELOPE)
            request.inputStream.use { stream ->
                val buffer=java.io.ByteArrayOutputStream();val chunk=ByteArray(4096)
                while(true){currentCoroutineContext().ensureActive();val count=stream.read(chunk);if(count<0)break
                    require(buffer.size()+count<=UpdateSecurity.MAX_ENVELOPE);buffer.write(chunk,0,count)}
                buffer.toByteArray()
            }
        }finally{connection.compareAndSet(request,null);request.disconnect()}
        val envelope=JSONObject(String(bytes,Charsets.UTF_8))
        val publicKey=context.resources.openRawResource(R.raw.update_public_key).use { it.readBytes() }
        val signed=UpdateSecurity.verify(envelope.getInt("schemaVersion"),envelope.getString("algorithm"),envelope.getString("keyId"),
            "youshu-2026-11",envelope.getString("payload"),envelope.getString("signature"),publicKey)
        val json=JSONObject(String(signed,Charsets.UTF_8))
        val release=UpdateSecurity.Release(json.getString("packageName"),json.getString("versionName"),json.getLong("versionCode"),
            json.getString("abi"),json.getInt("minSdk"),base+json.getString("apkPath"),base+json.getString("sourcePath"),json.getLong("bytes"),
            json.getString("sha256"),json.getLong("issuedAt"),json.getLong("expiresAt"),json.getString("notes"))
        UpdateSecurity.validate(release,base,context.packageName,Build.VERSION.SDK_INT,Build.SUPPORTED_ABIS.toList(),System.currentTimeMillis()/1000,
            preferences.getLong("highest_version",0))
        preferences.edit().putLong("highest_version",maxOf(release.versionCode,preferences.getLong("highest_version",0))).apply()
        return release
    }
    suspend fun download(release:UpdateSecurity.Release,progress:(Long,Long)->Unit):File {
        require(release.versionCode>BuildConfig.VERSION_CODE) { "拒绝安装旧版本" }
        val directory=File(context.cacheDir,"updates").apply { mkdirs() }
        directory.listFiles()?.filter { it.lastModified()<System.currentTimeMillis()-86400000 }?.forEach { it.delete() }
        val partial=File.createTempFile("download-",".part",directory)
        val target=File(directory,partial.nameWithoutExtension+".apk")
        val request=open(release.apkUrl)
        try {
            require(request.responseCode==200) { "安装包下载失败，请重试" }
            val declared=request.contentLengthLong
            require(declared<0 || declared==release.bytes) { "安装包长度不匹配" }
            var received=0L;val hash=MessageDigest.getInstance("SHA-256")
            request.inputStream.use { input -> partial.outputStream().use { output ->
                val bytes=ByteArray(64*1024)
                while(true){currentCoroutineContext().ensureActive();val count=input.read(bytes);if(count<0)break
                    received+=count;require(received<=release.bytes) { "安装包超出预期大小" }
                    output.write(bytes,0,count);hash.update(bytes,0,count);progress(received,release.bytes)}
                output.fd.sync()
            } }
            require(received==release.bytes && hex(hash.digest())==release.sha256) { "安装包不完整或已被修改，请重新下载" }
            verifyArchive(partial,release)
            require(partial.renameTo(target)) { "无法保存已校验安装包" }
            return target
        }finally{connection.compareAndSet(request,null);request.disconnect();partial.delete()}
    }
    suspend fun verifyForInstall(file:File,release:UpdateSecurity.Release) {
        UpdateSecurity.validate(release,base,context.packageName,Build.VERSION.SDK_INT,Build.SUPPORTED_ABIS.toList(),System.currentTimeMillis()/1000,
            preferences.getLong("highest_version",0))
        require(release.versionCode>BuildConfig.VERSION_CODE && file.length()==release.bytes) { "安装包版本或长度无效" }
        val hash=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val bytes=ByteArray(65536);while(true){currentCoroutineContext().ensureActive();val count=input.read(bytes);if(count<0)break;hash.update(bytes,0,count)} }
        require(hex(hash.digest())==release.sha256) { "安装包校验失败，请重新下载" }
        verifyArchive(file,release)
    }
    private fun verifyArchive(file:File,release:UpdateSecurity.Release) {
        val manager=context.packageManager
        val archive=manager.getPackageArchiveInfo(file.absolutePath,PackageManager.GET_SIGNING_CERTIFICATES)
            ?:throw IllegalArgumentException("无法读取安装包")
        val installed=manager.getPackageInfo(context.packageName,PackageManager.GET_SIGNING_CERTIFICATES)
        require(archive.packageName==context.packageName && archive.longVersionCode==release.versionCode && archive.versionName==release.versionName) { "安装包版本或包名不匹配" }
        fun signers(info:android.content.pm.PackageInfo)=info.signingInfo?.apkContentsSigners?.map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }?.toSet().orEmpty()
        require(signers(installed).isNotEmpty() && signers(installed)==signers(archive)) { "安装包签名与当前应用不一致" }
    }
    private fun hex(bytes:ByteArray)=bytes.joinToString("") { "%02x".format(it) }
}
