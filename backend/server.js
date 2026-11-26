import express from "express";
import Database from "better-sqlite3";
import bcrypt from "bcryptjs";
import jwt from "jsonwebtoken";
import rateLimit from "express-rate-limit";
import multer from "multer";
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";

const app=express();
const port=process.env.PORT||3000;
const dataDir=process.env.DATA_DIR||"/data";
fs.mkdirSync(dataDir,{recursive:true});
fs.mkdirSync(path.join(dataDir,"uploads"),{recursive:true});
const db=new Database(path.join(dataDir,"manw.sqlite"));
db.pragma("journal_mode = WAL");
db.exec(`
CREATE TABLE IF NOT EXISTS admins(id INTEGER PRIMARY KEY CHECK(id=1), password_hash TEXT NOT NULL, must_change INTEGER NOT NULL DEFAULT 1);
CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY,value TEXT NOT NULL);
`);
const getSetting=db.prepare("SELECT value FROM settings WHERE key=?");
const putSetting=db.prepare("INSERT INTO settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value");
const secret=process.env.JWT_SECRET;
if(!secret) throw new Error("JWT_SECRET must be configured");
if(!db.prepare("SELECT id FROM admins WHERE id=1").get()){
  if(!process.env.ADMIN_INITIAL_PASSWORD) throw new Error("Set ADMIN_INITIAL_PASSWORD for first boot");
  db.prepare("INSERT INTO admins(id,password_hash,must_change) VALUES(1,?,1)").run(bcrypt.hashSync(process.env.ADMIN_INITIAL_PASSWORD,12));
}
app.disable("x-powered-by");
app.use((req,res,next)=>{res.setHeader("X-Content-Type-Options","nosniff");res.setHeader("Referrer-Policy","no-referrer");res.setHeader("Cache-Control","no-store");next();});
app.use(express.json({limit:"100kb"}));
app.use("/api/auth",rateLimit({windowMs:15*60*1000,limit:10,standardHeaders:true,legacyHeaders:false}));
function auth(req,res,next){
  const token=(req.headers.authorization||"").replace(/^Bearer\s+/i,"");
  try{req.admin=jwt.verify(token,secret);next();}catch{return res.status(401).json({error:"غير مصرح"});}
}
app.get("/health",(req,res)=>res.json({ok:true}));
app.get("/api/settings",(req,res)=>{
  const rows=db.prepare("SELECT key,value FROM settings").all();
  const settings=Object.fromEntries(rows.map(x=>[x.key,JSON.parse(x.value)]));
  res.json({settings,updatedAt:settings.updatedAt||null});
});
app.post("/api/auth/login",(req,res)=>{
  const password=String(req.body?.password||"");
  const admin=db.prepare("SELECT password_hash,must_change FROM admins WHERE id=1").get();
  if(!bcrypt.compareSync(password,admin.password_hash)) return res.status(401).json({error:"كلمة المرور غير صحيحة"});
  const token=jwt.sign({sub:"admin"},secret,{expiresIn:"2h",issuer:"manw-control"});
  res.json({token,mustChangePassword:!!admin.must_change});
});
app.post("/api/auth/change-password",auth,(req,res)=>{
  const current=String(req.body?.currentPassword||"");
  const next=String(req.body?.newPassword||"");
  const confirm=String(req.body?.confirmPassword||"");
  const admin=db.prepare("SELECT password_hash FROM admins WHERE id=1").get();
  if(!bcrypt.compareSync(current,admin.password_hash)) return res.status(400).json({error:"كلمة المرور الحالية غير صحيحة"});
  if(next.length<12||next.length>128) return res.status(400).json({error:"كلمة المرور الجديدة يجب أن تكون 12 حرفًا على الأقل"});
  if(next!==confirm) return res.status(400).json({error:"تأكيد كلمة المرور غير مطابق"});
  db.prepare("UPDATE admins SET password_hash=?,must_change=0 WHERE id=1").run(bcrypt.hashSync(next,12));
  res.json({ok:true});
});
const upload=multer({storage:multer.diskStorage({destination:(req,file,cb)=>cb(null,path.join(dataDir,"uploads")),filename:(req,file,cb)=>cb(null,crypto.randomUUID()+path.extname(file.originalname).toLowerCase())}),limits:{fileSize:5*1024*1024},fileFilter:(req,file,cb)=>cb(null,["image/png","image/jpeg","image/webp"].includes(file.mimetype))});
app.post("/api/admin/background",auth,upload.single("image"),(req,res)=>{
  if(!req.file)return res.status(400).json({error:"ارفع صورة PNG أو JPG أو WEBP بحد أقصى 5MB"});
  const value={url:"/uploads/"+req.file.filename};
  putSetting.run("background",JSON.stringify(value));
  res.json(value);
});
app.use("/uploads",express.static(path.join(dataDir,"uploads"),{immutable:true,maxAge:"30d"}));
app.put("/api/admin/settings",auth,(req,res)=>{
  const allowed=new Set(["appTitle","texts","background"]);
  const entries=Object.entries(req.body?.settings||{});
  if(entries.length>100)return res.status(400).json({error:"عدد إعدادات كبير"});
  const tx=db.transaction(()=>{for(const [key,value] of entries){if(!allowed.has(key))continue;const raw=JSON.stringify(value);if(raw.length>20000)throw new Error("قيمة الإعداد كبيرة");putSetting.run(key,raw);}putSetting.run("updatedAt",JSON.stringify(new Date().toISOString()));});
  try{tx();res.json({ok:true});}catch(e){res.status(400).json({error:e.message});}
});
app.listen(port,"0.0.0.0",()=>console.log("MANW Control API listening"));
