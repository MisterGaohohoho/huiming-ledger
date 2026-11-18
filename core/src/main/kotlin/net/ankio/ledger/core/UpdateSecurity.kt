package net.ankio.ledger.core

import java.net.URI
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** The signed bytes, not transport headers or a remote boolean, authorize an update. */
object UpdateSecurity {
    const val MAX_ENVELOPE = 65536
    const val MAX_APK = 256L * 1024 * 1024
    data class Release(val packageName:String,val versionName:String,val versionCode:Long,
        val abi:String,val minSdk:Int,val apkUrl:String,val sourceUrl:String,val bytes:Long,
        val sha256:String,val issuedAt:Long,val expiresAt:Long,val notes:String)

    fun verify(schema:Int,algorithm:String,keyId:String,expectedKeyId:String,payload:String,signature:String,key:ByteArray):ByteArray {
        require(schema==1 && algorithm=="SHA256withRSA" && keyId==expectedKeyId) { "更新签名格式或密钥不受信任" }
        require(payload.length in 1..MAX_ENVELOPE && signature.length in 1..1024)
        val bytes=Base64.getDecoder().decode(payload)
        require(bytes.size<=49152)
        val verifier=Signature.getInstance("SHA256withRSA")
        verifier.initVerify(KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(key)))
        verifier.update(bytes)
        require(verifier.verify(Base64.getDecoder().decode(signature))) { "更新信息签名校验失败" }
        return bytes
    }

    fun url(value:String,base:String):URI {
        val origin=URI(base);val uri=URI(value)
        require(origin.scheme=="https" && origin.host!=null && origin.port==-1 && origin.rawUserInfo==null)
        require(uri.scheme=="https" && uri.host==origin.host && uri.port==-1 && uri.rawUserInfo==null && uri.rawQuery==null && uri.rawFragment==null) { "更新地址不受信任" }
        require(uri.rawPath==uri.path && !uri.path.contains("..") && !uri.path.contains('\\')) { "更新路径无效" }
        return uri
    }

    fun validate(release:Release,base:String,packageName:String,sdk:Int,abis:List<String>,now:Long,highestSeen:Long) {
        require(release.packageName==packageName) { "更新包名不匹配" }
        require(release.versionCode in 1..Int.MAX_VALUE.toLong() && release.versionCode>=highestSeen) { "拒绝过期或降级的更新信息" }
        require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?").matches(release.versionName))
        require(release.minSdk in 30..sdk && release.abi in abis) { "此更新不支持当前设备" }
        require(release.issuedAt in 1..(now+300) && release.expiresAt>now && release.expiresAt>release.issuedAt && release.expiresAt-release.issuedAt<=31L*86400) { "更新信息已过期或设备时间不正确" }
        require(release.bytes in 1..MAX_APK && Regex("[a-f0-9]{64}").matches(release.sha256)) { "安装包校验信息无效" }
        require(release.notes.length<=12000)
        val path="/releases/v${release.versionName}/"
        require(url(release.apkUrl,base).path==path+"app.apk" && url(release.sourceUrl,base).path==path+"source.zip") { "更新版本与下载路径不一致" }
    }
}
