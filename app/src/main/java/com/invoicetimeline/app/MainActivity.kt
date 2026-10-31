package com.invoicetimeline.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.webkit.*
import android.widget.ImageView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class Store(ctx: Context) : SQLiteOpenHelper(ctx, "invoices.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE records(id INTEGER PRIMARY KEY AUTOINCREMENT,province TEXT NOT NULL,printer TEXT NOT NULL,qty INTEGER NOT NULL,created INTEGER NOT NULL,status TEXT NOT NULL)")
        db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT,record_id INTEGER NOT NULL,title TEXT NOT NULL,detail TEXT NOT NULL,at INTEGER NOT NULL,photo TEXT NOT NULL DEFAULT '')")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    fun record(p: String, printer: String, qty: Int, at: Long = System.currentTimeMillis(), status: String = "دریافت شد"): Long {
        val v = ContentValues().apply { put("province", p); put("printer", printer); put("qty", qty); put("created", at); put("status", status) }
        return writableDatabase.insertOrThrow("records", null, v)
    }
    fun event(id: Long, title: String, detail: String = "", photo: String = "", at: Long = System.currentTimeMillis(), update: Boolean = true): Long {
        require(readableDatabase.rawQuery("SELECT id FROM records WHERE id=?", arrayOf(id.toString())).use { it.moveToFirst() })
        val v = ContentValues().apply { put("record_id", id); put("title", title); put("detail", detail); put("photo", photo); put("at", at) }
        val eventId = writableDatabase.insertOrThrow("events", null, v)
        if (update) writableDatabase.execSQL("UPDATE records SET status=? WHERE id=?", arrayOf(title, id))
        return eventId
    }
    fun snapshot(): JSONObject {
        val records = JSONArray(); val events = JSONArray()
        readableDatabase.rawQuery("SELECT * FROM records ORDER BY created DESC,id DESC", null).use { c ->
            while (c.moveToNext()) records.put(JSONObject().apply {
                put("id", c.getLong(0)); put("province", c.getString(1)); put("printer", c.getString(2)); put("qty", c.getInt(3)); put("created", c.getLong(4)); put("status", c.getString(5))
            })
        }
        readableDatabase.rawQuery("SELECT * FROM events ORDER BY at DESC,id DESC", null).use { c ->
            while (c.moveToNext()) events.put(JSONObject().apply {
                put("id", c.getLong(0)); put("record_id", c.getLong(1)); put("title", c.getString(2)); put("detail", c.getString(3)); put("at", c.getLong(4)); put("photo", c.getString(5))
            })
        }
        return JSONObject().put("records", records).put("events", events)
    }
}

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var store: Store
    private val worker = Executors.newSingleThreadExecutor()
    private var pendingId = 0L
    private var pendingTitle = ""
    private var pendingDetail = ""
    private var pendingExport = ""
    private val allowed = setOf("تحویل به مالی", "نیازمند اصلاح", "رفع نقص شد", "پرداخت شد", "تصمیم مدیر فروش", "یادداشت", "عکس هنگام دریافت")
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        store = Store(this)
        pendingId = state?.getLong("pendingId") ?: 0L
        pendingTitle = state?.getString("pendingTitle") ?: ""
        pendingDetail = state?.getString("pendingDetail") ?: ""
        pendingExport = state?.getString("pendingExport") ?: ""
        window.statusBarColor = Color.rgb(248, 247, 244)
        window.navigationBarColor = Color.rgb(255, 255, 255)
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        web = WebView(this)
        web.setBackgroundColor(Color.rgb(248, 247, 244))
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = false
            allowFileAccess = false; allowContentAccess = false
            setSupportMultipleWindows(false); javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.url.toString() == "https://masir.local/") return WebResourceResponse("text/html", "UTF-8", assets.open("workspace.html"))
                return WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0)))
            }
        }
        web.addJavascriptInterface(Bridge(), "Workspace")
        setContentView(web)
        web.loadUrl("https://masir.local/")
    }
    override fun onSaveInstanceState(out: Bundle) {
        out.putLong("pendingId", pendingId); out.putString("pendingTitle", pendingTitle)
        out.putString("pendingDetail", pendingDetail); out.putString("pendingExport", pendingExport)
        super.onSaveInstanceState(out)
    }
    private fun call(js: String) = runOnUiThread { if (!isFinishing) web.evaluateJavascript(js, null) }
    private fun notifyUser(text: String) = call("window.nativeMessage(" + JSONObject.quote(text) + ")")
    private fun refresh() = call("window.nativeRefresh()")
    private fun runTask(task: () -> Unit) { worker.execute { try { task() } catch (e: Exception) { notifyUser(e.message ?: "عملیات انجام نشد") } } }
    private fun privatePhoto(path: String): File {
        val file = File(Uri.parse(path).path ?: "").canonicalFile
        require(file.path.startsWith(File(filesDir, "evidence").canonicalPath + File.separator))
        return file
    }
    inner class Bridge {
        @JavascriptInterface fun closeApp() = runOnUiThread { finish() }
        @JavascriptInterface fun data(): String = store.snapshot().toString()
        @JavascriptInterface fun create(province: String, printer: String, qty: String): String = try {
            val p = province.trim(); val pr = printer.trim(); val n = ascii(qty).toIntOrNull() ?: 0
            require(p.isNotEmpty() && pr.isNotEmpty() && p.length <= 120 && pr.length <= 180 && n in 1..1000000) { "استان، چاپخانه و تعداد معتبر را وارد کن" }
            val db = store.writableDatabase; db.beginTransaction()
            val id: Long
            try { id = store.record(p, pr, n); store.event(id, "دریافت شد", update = false); db.setTransactionSuccessful() } finally { db.endTransaction() }
            JSONObject().put("id", id).toString()
        } catch (e: Exception) { JSONObject().put("error", e.message ?: "ثبت انجام نشد").toString() }
        @JavascriptInterface fun event(id: Long, title: String, detail: String): Boolean = try {
            require(title in allowed && detail.length <= 4000)
            require(title != "پرداخت شد") { "فیش پرداخت را پیوست کن" }
            store.event(id, title, detail.trim(), update = title !in setOf("یادداشت", "تصمیم مدیر فروش", "عکس هنگام دریافت")); true
        } catch (e: Exception) { notifyUser(e.message ?: "ثبت انجام نشد"); false }
        @JavascriptInterface fun photo(id: Long, payment: Boolean, detail: String) = runOnUiThread {
            pendingId = id; pendingTitle = if (payment) "پرداخت شد" else "عکس هنگام دریافت"; pendingDetail = detail.take(4000)
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE) }, 101)
        }
        @JavascriptInterface fun thumb(eventId: Long): String = try {
            val path = store.readableDatabase.rawQuery("SELECT photo FROM events WHERE id=?", arrayOf(eventId.toString())).use { if (it.moveToFirst()) it.getString(0) else "" }
            val f = privatePhoto(path)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(f.path, bounds)
            var sample = 1; while (bounds.outWidth / sample > 700 || bounds.outHeight / sample > 700) sample *= 2
            val image = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return ""
            val out = ByteArrayOutputStream(); image.compress(Bitmap.CompressFormat.JPEG, 78, out); image.recycle()
            "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) { "" }
        @JavascriptInterface fun viewPhoto(eventId: Long) = runOnUiThread {
            try {
                val path = store.readableDatabase.rawQuery("SELECT photo FROM events WHERE id=?", arrayOf(eventId.toString())).use { require(it.moveToFirst()); it.getString(0) }
                val f = privatePhoto(path); val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(f.path, b)
                var sample = 1; while (b.outWidth / sample > 1800 || b.outHeight / sample > 1800) sample *= 2
                val bitmap = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("تصویر در دسترس نیست")
                val image = ImageView(this@MainActivity).apply { setImageBitmap(bitmap); adjustViewBounds = true; setPadding(18,18,18,18) }
                AlertDialog.Builder(this@MainActivity).setView(image).setPositiveButton("بستن", null).show()
            } catch (e: Exception) { notifyUser("تصویر در دسترس نیست") }
        }
        @JavascriptInterface fun export(kind: String) = runOnUiThread {
            pendingExport = if (kind == "backup") "backup" else "csv"
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = if (pendingExport == "backup") "application/zip" else "text/csv"; putExtra(Intent.EXTRA_TITLE, if (pendingExport == "backup") "Personal-Agent-backup.zip" else "Personal-Agent-report.csv") }, 102)
        }
        @JavascriptInterface fun importFile(kind: String) = runOnUiThread {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }, if (kind == "backup") 104 else 103)
        }
    }
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        val uri = data?.data ?: return
        if (result != RESULT_OK) return
        val id = pendingId; val title = pendingTitle; val detail = pendingDetail; val exportType = pendingExport
        runTask {
            when (code) {
                101 -> {
                    val dir = File(filesDir, "evidence").apply { mkdirs() }; val f = File(dir, UUID.randomUUID().toString() + ".img")
                    try {
                        contentResolver.openInputStream(uri)!!.use { input -> f.outputStream().use { out ->
                            val buffer = ByteArray(8192); var total = 0L
                            while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= 25L*1024*1024) { "حجم عکس باید کمتر از ۲۵ مگابایت باشد" }; out.write(buffer,0,n) }
                        } }
                        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(f.path,b); require(b.outWidth > 0) { "فایل انتخاب‌شده تصویر معتبر نیست" }
                        store.event(id, title, detail, Uri.fromFile(f).toString(), update = title == "پرداخت شد")
                        refresh(); notifyUser("پیوست و رویداد ذخیره شد")
                    } catch(e: Exception) { f.delete(); throw e }
                }
                102 -> {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        if (exportType == "backup") {
                            val snapshot = store.snapshot(); snapshot.put("format", "masir-backup-1")
                            ZipOutputStream(out).use { zip ->
                                zip.putNextEntry(ZipEntry("data.json")); zip.write(snapshot.toString().toByteArray(Charsets.UTF_8)); zip.closeEntry()
                                val events = snapshot.getJSONArray("events"); val seen = mutableSetOf<String>()
                                for (i in 0 until events.length()) { val path = events.getJSONObject(i).getString("photo"); if (path.isNotEmpty()) {
                                    val f = privatePhoto(path); require(f.exists()) { "یکی از پیوست‌ها پیدا نشد؛ بکاپ کامل نشد" }
                                    if (seen.add(f.name)) { zip.putNextEntry(ZipEntry("evidence/" + f.name)); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
                                } }
                            }
                        } else out.write(csv().toByteArray(Charsets.UTF_8))
                    } ?: error("ذخیره‌سازی ممکن نشد")
                    notifyUser(if (exportType == "backup") "بکاپ کامل، همراه عکس‌ها ذخیره شد" else "گزارش ذخیره شد")
                }
                103 -> { val text = contentResolver.openInputStream(uri)!!.use { input -> val bytes = input.readBytesLimited(4*1024*1024); String(bytes, Charsets.UTF_8) }; val n = importCsv(text); refresh(); notifyUser("$n پرونده از گزارش قبلی وارد شد؛ عکس‌ها در CSV نیستند") }
                104 -> { val n = restore(uri); refresh(); notifyUser("$n پرونده و پیوست‌هایشان از بکاپ وارد شد") }
            }
        }
    }
    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val out = ByteArrayOutputStream(); val b = ByteArray(8192)
        while (true) { val n = read(b); if (n < 0) break; require(out.size()+n <= limit) { "فایل بیش از حد بزرگ است" }; out.write(b,0,n) }; return out.toByteArray()
    }
    private fun ascii(s: String) = s.map { if (it in '۰'..'۹') '0' + (it-'۰') else if (it in '٠'..'٩') '0'+(it-'٠') else it }.joinToString("")
    private fun date(ms: Long): String {
        val c = android.icu.util.Calendar.getInstance(Locale.forLanguageTag("fa-IR-u-ca-persian")); c.timeInMillis = ms
        return "%04d/%02d/%02d %02d:%02d".format(Locale.US,c.get(1),c.get(2)+1,c.get(5),c.get(11),c.get(12))
    }
    private fun parseDate(s: String): Long {
        val n = Regex("\\d+").findAll(ascii(s)).map { it.value.toInt() }.toList(); require(n.size >= 3) { "تاریخ گزارش نامعتبر است" }
        val c = android.icu.util.Calendar.getInstance(Locale.forLanguageTag("fa-IR-u-ca-persian")); c.clear(); c.isLenient = false
        c.set(n[0],n[1]-1,n[2],n.getOrElse(3){0},n.getOrElse(4){0}); return c.timeInMillis
    }
    private fun csv(): String {
        val sb = StringBuilder("\uFEFFشناسه,استان,چاپخانه,تعداد,تاریخ دریافت,وضعیت,رویداد,تاریخ رویداد,توضیح\n")
        store.readableDatabase.rawQuery("SELECT r.id,r.province,r.printer,r.qty,r.created,r.status,e.title,e.at,e.detail FROM records r LEFT JOIN events e ON e.record_id=r.id ORDER BY r.id,e.at,e.id",null).use { c ->
            while(c.moveToNext()) { val cells = listOf(c.getLong(0).toString(),c.getString(1),c.getString(2),c.getInt(3).toString(),date(c.getLong(4)),c.getString(5),c.getString(6)?:"",if(c.isNull(7)) "" else date(c.getLong(7)),c.getString(8)?:"")
                sb.append(cells.joinToString(",") { value -> val safe = if(value.trimStart().firstOrNull() in listOf('=','+','-','@')) "'"+value else value; "\""+safe.replace("\"","\"\"")+"\"" }).append('\n')
            }
        }; return sb.toString()
    }
    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>(); val row = mutableListOf<String>(); val field = StringBuilder(); var quoted = false; var i=0
        val input = text.removePrefix("\uFEFF")
        while(i<input.length) { val ch=input[i]
            if(ch=='"') { if(quoted && i+1<input.length && input[i+1]=='"') { field.append('"'); i++ } else quoted=!quoted }
            else if(ch==',' && !quoted) { row.add(field.toString()); field.clear() }
            else if(ch=='\n' && !quoted) { row.add(field.toString().trimEnd('\r')); field.clear(); if(row.any { it.isNotEmpty() }) rows.add(row.toList()); row.clear() }
            else field.append(ch)
            i++
        }
        require(!quoted) { "ساختار CSV نامعتبر است" }; if(field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString().trimEnd('\r')); rows.add(row.toList()) }; return rows
    }
    private fun importCsv(text: String): Int {
        val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        val prefs = getSharedPreferences("imports", Context.MODE_PRIVATE); require(!prefs.getBoolean(hash,false)) { "این گزارش قبلاً وارد شده است" }
        val rows=parseCsv(text); require(rows.isNotEmpty() && rows[0].take(3)==listOf("شناسه","استان","چاپخانه")) { "گزارش CSV ردیاب فاکتور را انتخاب کن" }
        val groups=rows.drop(1).onEach { require(it.size==9) { "ستون‌های گزارش نامعتبر است" } }.groupBy { it[0] }; require(groups.isNotEmpty()) { "گزارش خالی است" }
        val db=store.writableDatabase; db.beginTransaction()
        try { for((_,group) in groups) { val r=group.first(); val qty=ascii(r[3]).toInt(); require(qty>0 && r[1].isNotBlank() && r[2].isNotBlank())
            val workflowTitles=setOf("دریافت شد","تحویل به مالی","نیازمند اصلاح","رفع نقص شد","پرداخت شد")
            val status=group.lastOrNull { it[6] in workflowTitles }?.get(6) ?: "دریافت شد"
            val id=store.record(r[1],r[2],qty,parseDate(r[4]),status)
            group.forEach { if(it[6].isNotBlank()) store.event(id,it[6],it[8],at=if(it[7].isBlank()) parseDate(r[4]) else parseDate(it[7]),update=false) }
        }; db.setTransactionSuccessful() } finally { db.endTransaction() }
        prefs.edit().putBoolean(hash,true).apply(); return groups.size
    }
    private fun restore(uri: Uri): Int {
        val stage=File(cacheDir,"restore-"+UUID.randomUUID()).apply { mkdirs() }; val copied=mutableListOf<File>()
        try {
            var total=0L
            ZipInputStream(contentResolver.openInputStream(uri)!!).use { zip -> while(true) {
                val entry=zip.nextEntry ?: break
                require(entry.name=="data.json" || Regex("evidence/[A-Za-z0-9.-]+\\.img").matches(entry.name)) { "بکاپ نامعتبر است" }
                require(!entry.isDirectory); val dest=File(stage,entry.name); require(!dest.exists()); dest.parentFile?.mkdirs()
                dest.outputStream().use { out -> val buffer=ByteArray(8192); while(true) { val n=zip.read(buffer); if(n<0) break; total+=n; require(total<=200L*1024*1024) { "بکاپ بیش از ۲۰۰ مگابایت است" }; out.write(buffer,0,n) } }; zip.closeEntry()
            } }
            val snapshot=JSONObject(File(stage,"data.json").readText()); require(snapshot.getString("format")=="masir-backup-1")
            val rs=snapshot.getJSONArray("records"); val es=snapshot.getJSONArray("events"); val mapping=mutableMapOf<Long,Long>(); val photoMap=mutableMapOf<String,String>()
            val db=store.writableDatabase; db.beginTransaction()
            try {
                for(i in 0 until rs.length()) { val r=rs.getJSONObject(i); require(r.getInt("qty")>0); mapping[r.getLong("id")]=store.record(r.getString("province"),r.getString("printer"),r.getInt("qty"),r.getLong("created"),r.getString("status")) }
                for(i in 0 until es.length()) { val e=es.getJSONObject(i); val original=e.getString("photo"); var photo=""
                    if(original.isNotBlank()) photo=photoMap.getOrPut(original) {
                        val name=File(Uri.parse(original).path?:"").name; require(Regex("[A-Za-z0-9.-]+\\.img").matches(name))
                        val src=File(stage,"evidence/"+name); require(src.isFile) { "پیوست بکاپ ناقص است" }; val dir=File(filesDir,"evidence").apply{mkdirs()}; val dest=File(dir,UUID.randomUUID().toString()+".img"); src.copyTo(dest); copied.add(dest); Uri.fromFile(dest).toString()
                    }
                    store.event(mapping[e.getLong("record_id")] ?: error("پروندهٔ رویداد پیدا نشد"),e.getString("title"),e.getString("detail"),photo,e.getLong("at"),false)
                }; db.setTransactionSuccessful()
            } catch(e: Exception) { copied.forEach{it.delete()}; throw e } finally { db.endTransaction() }
            return rs.length()
        } finally { stage.deleteRecursively() }
    }
    @Deprecated("Legacy back callback") override fun onBackPressed() { web.evaluateJavascript("window.goBack()",null) }
    override fun onDestroy() { web.removeJavascriptInterface("Workspace"); web.destroy(); worker.shutdown(); super.onDestroy() }
}
