import express from "express";
import { createClient } from "@supabase/supabase-js";
import bcrypt from "bcryptjs";
import jwt from "jsonwebtoken";
import rateLimit from "express-rate-limit";
import multer from "multer";
import crypto from "node:crypto";
import path from "node:path";

const app = express();
const port = Number(process.env.PORT || 3000);
const supabaseUrl = process.env.SUPABASE_URL;
const supabaseKey = process.env.SUPABASE_SERVICE_ROLE_KEY;
const jwtSecret = process.env.JWT_SECRET;
const bucket = process.env.SUPABASE_STORAGE_BUCKET || "manw-assets";

if (!supabaseUrl || !supabaseKey || !jwtSecret) {
  throw new Error("SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY, and JWT_SECRET must be configured");
}
const supabase = createClient(supabaseUrl, supabaseKey, {
  auth: { persistSession: false, autoRefreshToken: false }
});

app.disable("x-powered-by");
app.use((req, res, next) => {
  res.setHeader("X-Content-Type-Options", "nosniff");
  res.setHeader("Referrer-Policy", "no-referrer");
  res.setHeader("Cache-Control", "no-store");
  next();
});
app.use(express.json({ limit: "100kb" }));
app.use("/api/auth", rateLimit({ windowMs: 15 * 60 * 1000, limit: 10, standardHeaders: true, legacyHeaders: false }));

function auth(req, res, next) {
  const token = (req.headers.authorization || "").replace(/^Bearer\\s+/i, "");
  try {
    req.admin = jwt.verify(token, jwtSecret, { issuer: "manw-control" });
    next();
  } catch {
    return res.status(401).json({ error: "غير مصرح" });
  }
}

async function readSetting(key) {
  const { data, error } = await supabase.from("app_settings").select("value").eq("key", key).maybeSingle();
  if (error) throw error;
  return data?.value;
}

async function writeSetting(key, value) {
  const { error } = await supabase.from("app_settings").upsert(
    { key, value, updated_at: new Date().toISOString() },
    { onConflict: "key" }
  );
  if (error) throw error;
}

async function ensureAdmin() {
  const { data, error } = await supabase.from("admin_credentials").select("id").eq("id", 1).maybeSingle();
  if (error) throw error;
  if (!data) {
    const initialPassword = process.env.ADMIN_INITIAL_PASSWORD;
    if (!initialPassword) throw new Error("No admin exists. Set ADMIN_INITIAL_PASSWORD once for first startup.");
    const password_hash = await bcrypt.hash(initialPassword, 12);
    const { error: insertError } = await supabase.from("admin_credentials").insert({
      id: 1, password_hash, must_change: true
    });
    if (insertError && insertError.code !== "23505") throw insertError;
  }
}

app.get("/health", (req, res) => res.json({ ok: true }));

app.get("/api/settings", async (req, res) => {
  try {
    const { data, error } = await supabase.from("app_settings").select("key,value,updated_at");
    if (error) throw error;
    const settings = Object.fromEntries((data || []).map(row => [row.key, row.value]));
    const updatedAt = data?.reduce((latest, row) =>
      !latest || row.updated_at > latest ? row.updated_at : latest, null) || null;
    res.json({ settings, updatedAt });
  } catch (error) {
    console.error("settings_read_failed", error.message);
    res.status(500).json({ error: "تعذر تحميل الإعدادات" });
  }
});

app.post("/api/auth/login", async (req, res) => {
  try {
    const password = String(req.body?.password || "");
    const { data: admin, error } = await supabase.from("admin_credentials")
      .select("password_hash,must_change").eq("id", 1).maybeSingle();
    if (error) throw error;
    if (!admin || !(await bcrypt.compare(password, admin.password_hash))) {
      return res.status(401).json({ error: "كلمة المرور غير صحيحة" });
    }
    const token = jwt.sign({ sub: "admin" }, jwtSecret, { expiresIn: "2h", issuer: "manw-control" });
    res.json({ token, mustChangePassword: !!admin.must_change });
  } catch (error) {
    console.error("admin_login_failed", error.message);
    res.status(500).json({ error: "تعذر تسجيل الدخول" });
  }
});

app.post("/api/auth/change-password", auth, async (req, res) => {
  try {
    const current = String(req.body?.currentPassword || "");
    const next = String(req.body?.newPassword || "");
    const confirm = String(req.body?.confirmPassword || "");
    const { data: admin, error } = await supabase.from("admin_credentials")
      .select("password_hash").eq("id", 1).single();
    if (error) throw error;
    if (!(await bcrypt.compare(current, admin.password_hash))) {
      return res.status(400).json({ error: "كلمة المرور الحالية غير صحيحة" });
    }
    if (next.length < 12 || next.length > 128) {
      return res.status(400).json({ error: "كلمة المرور الجديدة يجب أن تكون 12 حرفًا على الأقل" });
    }
    if (next !== confirm) return res.status(400).json({ error: "تأكيد كلمة المرور غير مطابق" });
    const password_hash = await bcrypt.hash(next, 12);
    const { error: updateError } = await supabase.from("admin_credentials")
      .update({ password_hash, must_change: false }).eq("id", 1);
    if (updateError) throw updateError;
    res.json({ ok: true });
  } catch (error) {
    console.error("password_change_failed", error.message);
    res.status(500).json({ error: "تعذر تغيير كلمة المرور" });
  }
});

const upload = multer({
  storage: multer.memoryStorage(),
  limits: { fileSize: 5 * 1024 * 1024 },
  fileFilter: (req, file, cb) => cb(null, ["image/png", "image/jpeg", "image/webp"].includes(file.mimetype))
});

app.post("/api/admin/background", auth, upload.single("image"), async (req, res) => {
  try {
    if (!req.file) return res.status(400).json({ error: "ارفع صورة PNG أو JPG أو WEBP بحد أقصى 5MB" });
    const ext = ({ "image/png": ".png", "image/jpeg": ".jpg", "image/webp": ".webp" })[req.file.mimetype];
    const objectPath = `background/${crypto.randomUUID()}${ext}`;
    const { error } = await supabase.storage.from(bucket).upload(objectPath, req.file.buffer, {
      contentType: req.file.mimetype, upsert: false, cacheControl: "3600"
    });
    if (error) throw error;
    const { data } = supabase.storage.from(bucket).getPublicUrl(objectPath);
    const value = { url: data.publicUrl };
    await writeSetting("background", value);
    res.json(value);
  } catch (error) {
    console.error("background_upload_failed", error.message);
    res.status(500).json({ error: "تعذر رفع صورة الخلفية" });
  }
});

app.put("/api/admin/settings", auth, async (req, res) => {
  try {
    const allowed = new Set(["appTitle", "texts", "background"]);
    const entries = Object.entries(req.body?.settings || {});
    if (entries.length > 100) return res.status(400).json({ error: "عدد إعدادات كبير" });
    for (const [key, value] of entries) {
      if (!allowed.has(key)) continue;
      const raw = JSON.stringify(value);
      if (raw.length > 20000) return res.status(400).json({ error: "قيمة الإعداد كبيرة" });
      await writeSetting(key, value);
    }
    res.json({ ok: true });
  } catch (error) {
    console.error("settings_write_failed", error.message);
    res.status(400).json({ error: "تعذر حفظ الإعدادات" });
  }
});

await ensureAdmin();
app.listen(port, "0.0.0.0", () => console.log("MANW Control API listening"));
