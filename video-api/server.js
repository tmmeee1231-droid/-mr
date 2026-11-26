import http from "node:http";
import { URL } from "node:url";

const PORT = Number(process.env.PORT || 8080);
const API_KEY = process.env.SAVERAPI_KEY;
const HOSTS = new Set(["tiktok.com","www.tiktok.com","m.tiktok.com","vm.tiktok.com","vt.tiktok.com"]);

function send(res, status, data) {
  res.writeHead(status, {"content-type":"application/json; charset=utf-8","cache-control":"no-store","x-content-type-options":"nosniff"});
  res.end(JSON.stringify(data));
}

http.createServer(async (req, res) => {
  const current = new URL(req.url || "/", `http://127.0.0.1:${PORT}`);
  if (req.method === "GET" && current.pathname === "/health") return send(res, 200, {ok:true, configured:Boolean(API_KEY)});
  if (req.method !== "GET" || current.pathname !== "/resolve") return send(res, 404, {error:"Not found"});
  let target;
  try { target = new URL(current.searchParams.get("url") || ""); } catch { return send(res,400,{error:"رابط الفيديو غير صحيح."}); }
  if (!["http:","https:"].includes(target.protocol) || !HOSTS.has(target.hostname.toLowerCase())) return send(res,400,{error:"حاليًا الخدمة مخصصة لروابط TikTok العامة فقط."});
  if (!API_KEY) return send(res,503,{error:"الخدمة لم تُفعّل بعد. أضف SAVERAPI_KEY في متغيرات Railway."});
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 280000);
  try {
    const endpoint = new URL("https://saverapi.net/api/all-in-one-downloader-api");
    endpoint.searchParams.set("url", target.toString());
    const upstream = await fetch(endpoint, {headers:{"x-api-key":API_KEY,"accept":"application/json"},signal:controller.signal});
    const body = await upstream.json().catch(()=>({}));
    if (!upstream.ok) return send(res, upstream.status===401?502:upstream.status, {error:body.message || "تعذر استخراج الفيديو من TikTok."});
    if (body.error || typeof body.download_url!=="string") return send(res,422,{error:body.message || "لم يتم العثور على فيديو عام في الرابط."});
    let media;
    try { media = new URL(body.download_url); } catch { return send(res,502,{error:"رابط الفيديو المستخرج غير صالح."}); }
    if (!["http:","https:"].includes(media.protocol)) return send(res,502,{error:"رابط الفيديو المستخرج غير آمن."});
    return send(res,200,{download_url:media.toString(),title:typeof body.caption==="string"?body.caption.slice(0,120):"MANW_video",thumbnail:typeof body.thumb==="string"?body.thumb:null,type:body.type||"video"});
  } catch (error) {
    return send(res,error?.name==="AbortError"?504:502,{error:error?.name==="AbortError"?"انتهت مهلة استخراج الفيديو، جرّب مرة أخرى.":"تعذر الاتصال بخدمة استخراج الفيديو."});
  } finally { clearTimeout(timer); }
}).listen(PORT,"0.0.0.0",()=>console.log(`MANW video resolver listening on ${PORT}`));
