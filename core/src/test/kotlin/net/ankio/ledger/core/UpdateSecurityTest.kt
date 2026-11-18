package net.ankio.ledger.core

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class UpdateSecurityTest {
    private val base="https://updates.example.com"
    private val now=1800000000L
    private fun release()=UpdateSecurity.Release("net.ankio.ledger","0.5.119-youshu.1",125,"arm64-v8a",30,
        "$base/releases/v0.5.119-youshu.1/app.apk","$base/releases/v0.5.119-youshu.1/source.zip",21630100,
        "a".repeat(64),now-60,now+86400,"修复与改进")
    private fun validate(value:UpdateSecurity.Release,highest:Long=124)=UpdateSecurity.validate(value,base,"net.ankio.ledger",35,listOf("arm64-v8a"),now,highest)
    private fun rejected(block:()->Unit){try{block();fail("Unsafe update accepted")}catch(_:IllegalArgumentException){}}

    @Test fun verifiesExactSignedBytesAndRejectsTampering(){
        val keys=KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val bytes="{\"versionCode\":125}".toByteArray()
        val signer=Signature.getInstance("SHA256withRSA").apply { initSign(keys.private);update(bytes) }
        val signed=Base64.getEncoder().encodeToString(signer.sign());val payload=Base64.getEncoder().encodeToString(bytes)
        assertArrayEquals(bytes,UpdateSecurity.verify(1,"SHA256withRSA","key","key",payload,signed,keys.public.encoded))
        rejected { UpdateSecurity.verify(1,"SHA256withRSA","key","key",Base64.getEncoder().encodeToString("changed".toByteArray()),signed,keys.public.encoded) }
        rejected { UpdateSecurity.verify(1,"none","key","key",payload,signed,keys.public.encoded) }
        rejected { UpdateSecurity.verify(1,"SHA256withRSA","attacker","key",payload,signed,keys.public.encoded) }
    }
    @Test fun rejectsExpiredFutureDatedAndReplayedMetadata(){
        validate(release())
        rejected { validate(release().copy(expiresAt=now)) }
        rejected { validate(release().copy(issuedAt=now+301)) }
        rejected { validate(release().copy(expiresAt=now+40*86400)) }
        rejected { validate(release(),126) }
    }
    @Test fun bindsVersionPackageDeviceAndArtifact(){
        rejected { validate(release().copy(packageName="other.app")) }
        rejected { validate(release().copy(minSdk=36)) }
        rejected { validate(release().copy(abi="x86_64")) }
        rejected { validate(release().copy(versionName="0.5.1")) }
        rejected { validate(release().copy(bytes=UpdateSecurity.MAX_APK+1)) }
        rejected { validate(release().copy(sha256="malformed")) }
    }
    @Test fun rejectsRemoteHostsCredentialsHttpQueriesFragmentsAndEncodedPaths(){
        for(url in listOf("http://updates.example.com/releases/v0.5.119-youshu.1/app.apk",
            "https://updates.example.com.evil.test/app.apk","https://user@updates.example.com/app.apk",
            "$base:443/app.apk",release().apkUrl+"?token=anything",release().apkUrl+"#fragment",
            "$base/releases/v0.5.119-youshu.1/%61pp.apk","$base/releases/../app.apk")) {
            rejected { validate(release().copy(apkUrl=url)) }
        }
    }
}
