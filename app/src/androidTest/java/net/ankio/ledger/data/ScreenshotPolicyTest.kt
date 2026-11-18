package net.ankio.ledger.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ScreenshotPolicyTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val namespace="policy-${UUID.randomUUID()}"
    private var images=ScreenshotStore(context,namespace)
    private val now=System.currentTimeMillis()
    @After fun cleanup(){images.close();context.deleteDatabase("$namespace.db");File(context.filesDir,namespace).deleteRecursively();context.getSharedPreferences("${namespace}_policy",Context.MODE_PRIVATE).edit().clear().commit()}

    @Test fun lowerLimitPreviewsRealBytesProtectsInFlightAndPersistsAfterRestart(){
        repeat(11) { index -> val bytes=ByteArray(5*1024*1024);bytes[0]=index.toByte();images.put("image$index",bytes,now-100_000+index,"HISTORY") }
        assertEquals(55L*1024*1024,images.stats().getLong("bytes"))
        val first=images.previewLimit(50,now)
        assertEquals(1,first.getInt("removeCount"));assertEquals(5L*1024*1024,first.getLong("removeBytes"))
        assertNotNull(images.image("image0")) // Preview never deletes.
        images.state("image0","QUEUED")
        try{images.applyLimit(first.getString("token"),now+1);fail("Changed protection must invalidate preview")}catch(_:IllegalArgumentException){}
        assertEquals(ScreenshotStore.MAX_BYTES,images.limitBytes())
        val second=images.previewLimit(50,now+2);val result=images.applyLimit(second.getString("token"),now+3)
        assertEquals(5L*1024*1024,result.getLong("freedBytes"));assertNotNull(images.image("image0"));assertNull(images.image("image1"))
        images.close();images=ScreenshotStore(context,namespace)
        assertEquals(50L*1024*1024,images.limitBytes());assertEquals(50L*1024*1024,images.cleanup(now+4).getLong("bytes"))
        try{images.applyLimit(second.getString("token"),now+4);fail("Token cannot replay")}catch(_:IllegalArgumentException){}
    }

    @Test fun invalidLimitsNeverChangeSettingsAndSharedUndoImageRemainsProtected(){
        for(value in listOf(-1,0,49,5121,Int.MAX_VALUE))try{images.previewLimit(value);fail("Invalid capacity")}catch(_:IllegalArgumentException){}
        assertEquals(ScreenshotStore.MAX_BYTES,images.limitBytes())
        images.put("history",byteArrayOf(1,2),now-100_000,"HISTORY")
        images.put("undo",byteArrayOf(1,2),now,"UNDO")
        images.cleanup(now,0);assertNotNull(images.image("history"))
        images.release("undo");images.cleanup(now,0);assertNull(images.image("history"))
        val p=images.previewLimit(5120,now);images.applyLimit(p.getString("token"),now+1)
        assertEquals(5L*1024*1024*1024,images.limitBytes())
    }
}
