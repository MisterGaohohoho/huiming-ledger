package net.ankio.ledger.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Content-addressed private screenshots. Bills and tasks hold references, never a second image copy. */
class ScreenshotStore internal constructor(context: Context, namespace: String = "screenshots") :
    SQLiteOpenHelper(context, "$namespace.db", null, 1) {
    private val directory = File(context.filesDir, namespace).apply { check(isDirectory || mkdirs()) }
    private val preferences = context.getSharedPreferences("${namespace}_policy", Context.MODE_PRIVATE)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE assets (hash TEXT PRIMARY KEY NOT NULL, bytes INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE refs (owner TEXT PRIMARY KEY NOT NULL, hash TEXT NOT NULL, created_at INTEGER NOT NULL, state TEXT NOT NULL)")
        db.execSQL("CREATE INDEX refs_hash ON refs(hash)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** The file is durable before its reference or the caller's legacy BLOB is changed. */
    @Synchronized fun put(owner: String, image: ByteArray, createdAt: Long, state: String): String {
        require(image.isNotEmpty() && image.size <= 5 * 1024 * 1024)
        val hash = MessageDigest.getInstance("SHA-256").digest(image).joinToString("") { "%02x".format(it) }
        val target = file(hash)
        if (!target.exists()) {
            val temporary = File.createTempFile("capture-", ".tmp", directory)
            try {
                FileOutputStream(temporary).use { stream -> stream.write(image); stream.fd.sync() }
                check(temporary.renameTo(target)) { "无法保存账单截图" }
            } finally { temporary.delete() }
        }
        val db = writableDatabase
        val previous = fingerprint(owner)
        db.beginTransaction()
        try {
            db.insertWithOnConflict("assets", null, ContentValues().apply {
                put("hash", hash); put("bytes", image.size)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            db.insertWithOnConflict("refs", null, ContentValues().apply {
                put("owner", owner); put("hash", hash); put("created_at", createdAt); put("state", state)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (previous != null && previous != hash) pruneUnreferenced(previous)
        return hash
    }

    @Synchronized fun image(owner: String): ByteArray? {
        val hash = readableDatabase.rawQuery("SELECT hash FROM refs WHERE owner=?", arrayOf(owner)).use {
            if (it.moveToFirst()) it.getString(0) else return null
        }
        val image = file(hash)
        return if (image.isFile) image.readBytes() else null
    }
    @Synchronized fun contains(owner: String): Boolean = readableDatabase.rawQuery(
        "SELECT hash FROM refs WHERE owner=?", arrayOf(owner)).use { it.moveToFirst() && file(it.getString(0)).isFile }

    /** One reference query and one filesystem check per distinct asset for a UI snapshot. */
    @Synchronized fun availableOwners(): Set<String> = readableDatabase.rawQuery("SELECT owner,hash FROM refs",null).use { cursor ->
        val files=mutableMapOf<String,Boolean>()
        buildSet { while(cursor.moveToNext()) {
            val hash=cursor.getString(1)
            if(files.getOrPut(hash) { file(hash).isFile })add(cursor.getString(0))
        } }
    }

    /** Resolve only requested evidence slots; large ledgers need not stat every old image. */
    @Synchronized fun availableOwners(owners:Set<String>):Set<String> {
        val exists=mutableMapOf<String,Boolean>()
        return buildSet {
            owners.toList().chunked(500).forEach { batch->
                val placeholders=List(batch.size) { "?" }.joinToString(",")
                readableDatabase.rawQuery("SELECT owner,hash FROM refs WHERE owner IN ($placeholders)",batch.toTypedArray()).use { c->
                    while(c.moveToNext())if(exists.getOrPut(c.getString(1)) { file(c.getString(1)).isFile })add(c.getString(0))
                }
            }
        }
    }

    fun fingerprint(owner:String):String?=readableDatabase.rawQuery("SELECT hash FROM refs WHERE owner=?",arrayOf(owner)).use {
        if(it.moveToFirst())it.getString(0) else null
    }

    fun matches(owner:String,image:ByteArray):Boolean = fingerprint(owner)==
        MessageDigest.getInstance("SHA-256").digest(image).joinToString("") { "%02x".format(it) }

    @Synchronized fun state(owner: String, state: String) {
        if (state in DISCARD) { release(owner); return }
        writableDatabase.update("refs", ContentValues().apply { put("state", state) }, "owner=?", arrayOf(owner))
    }
    @Synchronized fun release(owner: String) {
        val hash = fingerprint(owner) ?: return
        writableDatabase.delete("refs", "owner=?", arrayOf(owner))
        pruneUnreferenced(hash)
    }

    fun limitBytes(): Long = preferences.getLong("max_bytes", MAX_BYTES).takeIf { it in 50L*1024*1024..MAX_CONFIGURED_BYTES } ?: MAX_BYTES
    private data class Asset(val hash:String,val bytes:Long,val refs:MutableList<Ref> = mutableListOf())
    private data class Ref(val owner:String,val at:Long,val state:String)
    private data class CleanupPlan(val expired:List<String>,val hashes:List<String>,val bytes:Long,val signature:String)
    private data class PendingLimit(val token:String,val bytes:Long,val plan:CleanupPlan,val at:Long)
    private var pendingLimit:PendingLimit?=null

    private fun cleanupPlan(now:Long,maxBytes:Long):CleanupPlan {
        val assets=linkedMapOf<String,Asset>()
        readableDatabase.rawQuery("SELECT hash,bytes FROM assets ORDER BY hash",null).use { c ->
            while(c.moveToNext())assets[c.getString(0)]=Asset(c.getString(0),c.getLong(1))
        }
        readableDatabase.rawQuery("SELECT hash,owner,created_at,state FROM refs ORDER BY owner",null).use { c ->
            while(c.moveToNext())assets[c.getString(0)]?.refs?.add(Ref(c.getString(1),c.getLong(2),c.getString(3)))
        }
        val signature=MessageDigest.getInstance("SHA-256").digest(assets.values.toString().toByteArray()).joinToString("") { "%02x".format(it) }
        val expired=mutableListOf<String>();val hashes=mutableListOf<String>();var freed=0L
        val candidates=mutableListOf<Pair<Asset,Long>>()
        val days=retentionDays()
        for(asset in assets.values) {
            val kept=asset.refs.filter { ref ->
                val keepDays=if(ref.state=="FAILED")7 else days
                val drop=ref.state !in PROTECTED && ref.state!="HISTORY" &&
                    (ref.state in DISCARD || keepDays>0 && ref.at<now-keepDays*DAY)
                if(drop)expired+=ref.owner
                !drop
            }
            if(kept.isEmpty()) { hashes+=asset.hash;freed+=asset.bytes }
            else if(kept.none { it.state in PROTECTED })candidates+=asset to kept.minOf { it.at }
        }
        val total=assets.values.sumOf { it.bytes }
        for((asset,_) in candidates.sortedWith(compareBy<Pair<Asset,Long>> { it.second }.thenBy { it.first.hash })) {
            if(total-freed<=maxBytes)break
            hashes+=asset.hash;freed+=asset.bytes
        }
        return CleanupPlan(expired,hashes,freed,signature)
    }

    @Synchronized fun previewLimit(mebibytes:Int,now:Long=System.currentTimeMillis()):JSONObject {
        require(mebibytes in 50..5120) { "请输入 50～5120 MiB 的整数" }
        val bytes=mebibytes.toLong()*1024*1024
        val plan=cleanupPlan(now,bytes)
        val pending=PendingLimit(java.util.UUID.randomUUID().toString(),bytes,plan,now);pendingLimit=pending
        return stats().put("ok",true).put("token",pending.token).put("proposedBytes",bytes)
            .put("removeCount",plan.hashes.size).put("removeBytes",plan.bytes)
            .put("projectedBytes",stats().getLong("bytes")-plan.bytes)
    }

    @Synchronized fun applyLimit(token:String,now:Long=System.currentTimeMillis()):JSONObject {
        val pending=pendingLimit
        require(pending!=null && pending.token==token && now-pending.at in 0..300_000) { "确认已过期，请重新核对" }
        require(cleanupPlan(now,pending.bytes)==pending.plan) { "截图状态已变化，请重新核对清理范围" }
        check(preferences.edit().putLong("max_bytes",pending.bytes).commit()) { "无法保存截图上限" }
        val before=stats().getLong("bytes")
        val result=cleanup(now,pending.bytes)
        pendingLimit=null
        return result.put("freedBytes",(before-result.getLong("bytes")).coerceAtLeast(0))
    }

    fun retentionDays(): Int = preferences.getInt("retention_days", 30)
    @Synchronized fun setRetentionDays(days: Int): JSONObject {
        require(days in setOf(0, 7, 30, 90)) { "请选择有效的截图保留期限" }
        check(preferences.edit().putInt("retention_days", days).commit())
        return cleanup()
    }

    @Synchronized fun stats(): JSONObject {
        val assets = readableDatabase.rawQuery("SELECT COUNT(*),COALESCE(SUM(bytes),0) FROM assets", null).use {
            it.moveToFirst(); it.getLong(0) to it.getLong(1)
        }
        val protectedBytes = readableDatabase.rawQuery("SELECT COALESCE(SUM(bytes),0) FROM assets WHERE hash IN " +
            "(SELECT hash FROM refs WHERE state IN ('CAPTURING','QUEUED','RECOGNIZING','UNDO'))", null).use { it.moveToFirst(); it.getLong(0) }
        return JSONObject().put("count", assets.first).put("bytes", assets.second).put("protectedBytes", protectedBytes)
            .put("maxBytes", limitBytes()).put("retentionDays", retentionDays()).put("failedRetentionDays", 7)
    }

    /** Retention/capacity remove images only; callers' bill text, state and icon assets are untouched. */
    @Synchronized fun cleanup(now: Long = System.currentTimeMillis(), maxBytes: Long = limitBytes()): JSONObject {
        require(maxBytes >= 0)
        val plan=cleanupPlan(now,maxBytes)
        val db=writableDatabase
        db.beginTransaction()
        try {
            plan.expired.forEach { db.delete("refs","owner=?",arrayOf(it)) }
            plan.hashes.forEach { db.delete("refs","hash=?",arrayOf(it)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        pruneUnreferenced()
        val known = db.rawQuery("SELECT hash FROM assets", null).use { c -> buildSet { while(c.moveToNext())add(c.getString(0)) } }
        directory.listFiles()?.forEach { asset ->
            if (asset.name.endsWith(".tmp") || asset.name.endsWith(".jpg") && asset.name.removeSuffix(".jpg") !in known) asset.delete()
        }
        return stats()
    }

    private fun pruneUnreferenced(hash: String? = null) {
        val db = writableDatabase
        val unused = db.rawQuery("SELECT hash FROM assets WHERE " + (if (hash == null) "" else "hash=? AND ") +
            "NOT EXISTS(SELECT 1 FROM refs WHERE refs.hash=assets.hash)", hash?.let { arrayOf(it) })
            .use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
        unused.forEach { hash ->
            if (!file(hash).exists() || file(hash).delete()) db.delete("assets", "hash=?", arrayOf(hash))
        }
    }
    private fun file(hash: String) = File(directory, "$hash.jpg")

    companion object {
        const val MAX_BYTES = 500L * 1024 * 1024
        const val MAX_CONFIGURED_BYTES = 5120L * 1024 * 1024
        private const val DAY = 86_400_000L
        private val PROTECTED = setOf("CAPTURING", "QUEUED", "RECOGNIZING", "UNDO")
        private val DISCARD = setOf("NO_BILL", "DUPLICATE", "CANCELLED","SKIPPED")
        @Volatile private var shared: ScreenshotStore? = null
        fun get(context: Context): ScreenshotStore = shared ?: synchronized(this) {
            shared ?: ScreenshotStore(context.applicationContext).also { shared = it }
        }
    }
}
