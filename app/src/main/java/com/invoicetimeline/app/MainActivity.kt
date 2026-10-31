
package com.invoicetimeline.app

import android.app.*
import android.os.*
import android.content.*
import android.database.sqlite.*
import android.graphics.Color
import android.net.Uri
import android.view.*
import android.widget.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import org.json.JSONObject

class Store(ctx: Context): SQLiteOpenHelper(ctx,"invoices.db",null,1) {
 override fun onCreate(db:SQLiteDatabase) {
  db.execSQL("CREATE TABLE records(id INTEGER PRIMARY KEY AUTOINCREMENT,province TEXT NOT NULL,printer TEXT NOT NULL,qty INTEGER NOT NULL,created INTEGER NOT NULL,status TEXT NOT NULL)")
  db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT,record_id INTEGER NOT NULL,title TEXT NOT NULL,detail TEXT NOT NULL,at INTEGER NOT NULL,photo TEXT NOT NULL DEFAULT '')")
 }
 override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int) {}
 fun add(province:String,printer:String,qty:Int):Long {
  val db=writableDatabase; val v=android.content.ContentValues()
  v.put("province",province);v.put("printer",printer);v.put("qty",qty);v.put("created",System.currentTimeMillis());v.put("status","دریافت شد")
  val id=db.insertOrThrow("records",null,v); event(id,"دریافت شد","", ""); return id
 }
 fun event(id:Long,title:String,detail:String,photo:String) {
  val v=android.content.ContentValues();v.put("record_id",id);v.put("title",title);v.put("detail",detail);v.put("at",System.currentTimeMillis());v.put("photo",photo)
  writableDatabase.insertOrThrow("events",null,v)
  writableDatabase.execSQL("UPDATE records SET status=? WHERE id=?",arrayOf(title,id))
 }
}
class MainActivity:Activity() {
 private lateinit var store:Store
 private lateinit var root:LinearLayout
 private var currentId:Long=0
 private var photoTitle="مدرک"
 private val bg=Color.rgb(18,18,20);private val orange=Color.rgb(255,144,35)
 private val dateFormat=SimpleDateFormat("yyyy/MM/dd HH:mm",Locale.US)
 private fun date(ms:Long):String {
  val c=android.icu.util.Calendar.getInstance(Locale.forLanguageTag("fa-IR-u-ca-persian"))
  c.timeInMillis=ms
  return "%04d/%02d/%02d  %02d:%02d".format(Locale.US,c.get(android.icu.util.Calendar.YEAR),c.get(android.icu.util.Calendar.MONTH)+1,c.get(android.icu.util.Calendar.DAY_OF_MONTH),c.get(android.icu.util.Calendar.HOUR_OF_DAY),c.get(android.icu.util.Calendar.MINUTE))
 }
 override fun onCreate(savedInstanceState:Bundle?) {super.onCreate(savedInstanceState);store=Store(this);home()}
 private fun screen(title:String) {
  root=LinearLayout(this);root.orientation=1;root.setPadding(22,25,22,12);root.setBackgroundColor(bg)
  val scroll=ScrollView(this);scroll.isFillViewport=true;scroll.addView(root);setContentView(scroll)
  label(title,25,orange)
 }
 private fun label(s:String,size:Int=16,color:Int=Color.WHITE) {
  val t=TextView(this);t.text=s;t.textSize=size.toFloat();t.setTextColor(color);t.setPadding(0,10,0,10);t.textDirection=View.TEXT_DIRECTION_RTL;root.addView(t)
 }
 private fun button(s:String,action:()->Unit) {
  val b=Button(this);b.text=s;b.setTextColor(Color.BLACK);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(orange));b.setOnClickListener{action()};root.addView(b)
 }
 private fun field(hint:String):EditText {
  val e=EditText(this);e.hint=hint;e.setSingleLine(true);e.setTextColor(Color.WHITE);e.setHintTextColor(Color.LTGRAY);e.textDirection=View.TEXT_DIRECTION_RTL;root.addView(e);return e
 }
 private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_SHORT).show()
 private fun home(query:String="") {
  screen("🎃 ردیاب فاکتورها")
  button("＋ ثبت تحویل جدید"){newRecord()}
  button("خروجی CSV برای اکسل"){exportCsv()}
  val search=field("جست‌وجوی استان یا چاپخانه")
  if(query.isNotEmpty())search.setText(query)
  button("جست‌وجو"){home(search.text.toString().trim())}
  val c=store.readableDatabase.rawQuery("SELECT id,province,printer,qty,created,status FROM records WHERE province LIKE ? OR printer LIKE ? ORDER BY created DESC",arrayOf("%$query%","%$query%"))
  var count=0
  while(c.moveToNext()) {
   count++;val id=c.getLong(0)
   button("${c.getString(1)} | ${c.getString(2)} | ${c.getInt(3)} فاکتور\n${date(c.getLong(4))} — ${c.getString(5)}"){detail(id)}
  }
  c.close();label("$count نوبت تحویل",14,Color.LTGRAY)
 }
 private fun newRecord(){
  screen("ثبت دریافت")
  val province=field("استان");val printer=field("چاپخانه");val qty=field("تعداد فاکتور")
  qty.inputType=2
  button("ثبت با زمان فعلی"){
   val p=province.text.toString().trim();val pr=printer.text.toString().trim();val n=qty.text.toString().toIntOrNull()
   if(p.isEmpty()||pr.isEmpty()||n==null||n<1){toast("استان، چاپخانه و تعداد معتبر لازم است");return@button}
   detail(store.add(p,pr,n))
  }
  button("بازگشت"){home()}
 }
 private fun detail(id:Long){
  currentId=id;screen("تایم‌لاین پرونده #$id")
  val r=store.readableDatabase.rawQuery("SELECT province,printer,qty FROM records WHERE id=?",arrayOf("$id"))
  if(!r.moveToFirst()){r.close();home();return}
  label("${r.getString(0)} | ${r.getString(1)} | ${r.getInt(2)} فاکتور");r.close()
  button("📷 افزودن عکس فاکتور یا پوشه"){choosePhoto("عکس هنگام دریافت")}
  button("تحویل به مالی"){store.event(id,"تحویل به مالی","","");detail(id)}
  button("ثبت نقص مدرک"){defect(id)}
  button("رفع نقص / ادامه رسیدگی"){store.event(id,"رفع نقص شد","","");detail(id)}
  button("📎 فیش پرداخت و ثبت پرداخت"){choosePhoto("پرداخت شد")}
  button("ثبت تصمیم مدیر فروش"){decision(id)}
  label("سوابق",20,orange)
  val c=store.readableDatabase.rawQuery("SELECT title,detail,at,photo FROM events WHERE record_id=? ORDER BY at ASC,id ASC",arrayOf("$id"))
  while(c.moveToNext()){
   val photo=c.getString(3)
   label("● ${c.getString(0)}  —  ${date(c.getLong(2))}\n${c.getString(1)}",16)
   if(photo.isNotBlank()) {
    val u=Uri.parse(photo)
    button("مشاهده پیوست"){try {val img=ImageView(this);img.setImageURI(u);AlertDialog.Builder(this).setView(img).setPositiveButton("بستن",null).show()}catch(e:Exception){toast("فایل تصویر در دسترس نیست")}}
   }
  };c.close()
  button("بازگشت به فهرست"){home()}
 }
 private fun defect(id:Long){
  val choices=arrayOf("نقص فرم سفارش چاپ و نصب","فقدان تأییدیه نصب","مغایرت اعداد فاکتور","سایر")
  AlertDialog.Builder(this).setTitle("علت توقف رسیدگی").setItems(choices){_,which->
   if(which==3){inputDialog("شرح نقص"){store.event(id,"نیازمند اصلاح",it,"");detail(id)}}
   else{store.event(id,"نیازمند اصلاح",choices[which],"");detail(id)}
  }.show()
 }
 private fun decision(id:Long){
  val choices=arrayOf("عودت اصل فاکتور به استان","نگهداری اصل نزد واحد","کنارگذاری / ابطال با دستور مدیر فروش")
  AlertDialog.Builder(this).setTitle("تصمیم مدیر فروش").setItems(choices){_,which->store.event(id,"تصمیم مدیر فروش",choices[which],"");detail(id)}.show()
 }
 private fun inputDialog(title:String,done:(String)->Unit){
  val e=EditText(this)
  AlertDialog.Builder(this).setTitle(title).setView(e).setPositiveButton("ثبت"){_,_->done(e.text.toString())}.setNegativeButton("انصراف",null).show()
 }
 private fun choosePhoto(title:String){
  photoTitle=title
  val i=Intent(Intent.ACTION_OPEN_DOCUMENT).apply {type="image/*";addCategory(Intent.CATEGORY_OPENABLE);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)}
  startActivityForResult(i,101)
 }
 override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
  super.onActivityResult(requestCode,resultCode,data)
  if(requestCode==102&&resultCode==RESULT_OK&&data?.data!=null){
   try { contentResolver.openOutputStream(data.data!!)?.use { out-> pendingCsv?.inputStream()?.use{it.copyTo(out)} };toast("گزارش ذخیره شد") }catch(e:Exception){toast("خطا در ذخیره گزارش")};return
  }
  if(requestCode==101&&resultCode==RESULT_OK&&data?.data!=null){
   val uri=data.data!!
   try {
    // Copy to app-private storage so original can be moved/deleted without losing evidence.
    val dir=File(filesDir,"evidence");dir.mkdirs()
    val dst=File(dir,"${System.currentTimeMillis()}_${UUID.randomUUID()}.jpg")
    contentResolver.openInputStream(uri)?.use{input->dst.outputStream().use{input.copyTo(it)}} ?: throw Exception("no input")
    store.event(currentId,photoTitle,"",Uri.fromFile(dst).toString())
    detail(currentId)
   }catch(e:Exception){toast("ذخیره عکس انجام نشد")}
  }
 }
 private fun exportCsv(){
  val sb=StringBuilder("\uFEFF")
  sb.append("شناسه,استان,چاپخانه,تعداد,تاریخ دریافت,وضعیت,رویداد,تاریخ رویداد,توضیح\n")
  val c=store.readableDatabase.rawQuery("""SELECT r.id,r.province,r.printer,r.qty,r.created,r.status,e.title,e.at,e.detail FROM records r LEFT JOIN events e ON e.record_id=r.id ORDER BY r.id,e.at""",null)
  fun esc(s:String)="\""+s.replace("\"","\"\"").replace("\n"," ")+"\""
  while(c.moveToNext()){
   val cells=listOf(c.getLong(0).toString(),c.getString(1),c.getString(2),c.getInt(3).toString(),date(c.getLong(4)),c.getString(5),c.getString(6)?:"",if(c.isNull(7))"" else date(c.getLong(7)),c.getString(8)?:"")
   sb.append(cells.joinToString(","){esc(it)}).append("\n")
  };c.close()
  val f=File(cacheDir,"invoice_report.csv");f.writeText(sb.toString(),Charsets.UTF_8)
  val i=Intent(Intent.ACTION_CREATE_DOCUMENT).apply {addCategory(Intent.CATEGORY_OPENABLE);type="text/csv";putExtra(Intent.EXTRA_TITLE,"invoice_report.csv")}
  pendingCsv=f
  startActivityForResult(i,102)
 }
 private var pendingCsv:File?=null

}
