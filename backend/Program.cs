using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using Microsoft.Extensions.FileProviders;
using QRCoder;
using System.Diagnostics;
using Microsoft.AspNetCore.DataProtection;
using Microsoft.AspNetCore.Http.Features;
using System.IO.Compression;
using System.Net.NetworkInformation;
using System.Net.Sockets;

var b = WebApplication.CreateBuilder(args);
var data = Path.GetFullPath(b.Configuration["DataDir"] ?? (OperatingSystem.IsWindows() ? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "Photobooth") : "data"));
b.Host.UseWindowsService();
Directory.CreateDirectory(data);
b.WebHost.UseUrls(b.Configuration["Urls"] ?? "http://0.0.0.0:5080");
b.WebHost.ConfigureKestrel(k => k.Limits.MaxRequestBodySize = 300_000_000);
b.Services.ConfigureHttpJsonOptions(o => o.SerializerOptions.IncludeFields = true);
var app = b.Build();

var jo = new JsonSerializerOptions { IncludeFields = true };
var dbf = Path.Combine(data, "db.json");
var db = File.Exists(dbf) ? JsonSerializer.Deserialize<Store>(File.ReadAllText(dbf), jo) : new Store();
void Save() { lock (db) File.WriteAllText(dbf, JsonSerializer.Serialize(db, jo)); }
db.devices.RemoveAll(zz => zz.revoked);   // devices unpaired by older versions
long Now() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
string H(string s) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(s)));
string Tok() => Convert.ToHexString(RandomNumberGenerator.GetBytes(24));
bool Id(string s) => Regex.IsMatch(s ?? "", "^[0-9a-fA-F]{32}$");
var admins = new HashSet<string>(); var codes = new Dictionary<string, long>();
string PH(string p, string salt) => Convert.ToHexString(Rfc2898DeriveBytes.Pbkdf2(p, Encoding.UTF8.GetBytes(salt), 200000, HashAlgorithmName.SHA256, 32));
if (db.adminHash == "" && b.Configuration["AdminPassword"] is string ap0 && ap0.Length >= 8) { db.adminSalt = Tok(); db.adminHash = PH(ap0, db.adminSalt); Save(); }
long lastRev = db.events.Count == 0 ? 0 : db.events.Max(e => e.rev);
long NextRev() { lock (db) { lastRev = Math.Max(lastRev + 1, Now()); return lastRev; } }
var fails = new Dictionary<string, (int n, long t)>(); int pairFails = 0;
string Ip(HttpContext c) => c.Request.Headers["CF-Connecting-IP"].FirstOrDefault() ?? c.Connection.RemoteIpAddress?.ToString() ?? "?";
var port = 5080; try { port = new Uri((b.Configuration["Urls"] ?? "http://0.0.0.0:5080").Split(';')[0].Replace("0.0.0.0", "localhost").Replace("+", "localhost").Replace("*", "localhost")).Port; } catch { }
if (db.serverId == "") { db.serverId = Guid.NewGuid().ToString("N"); Save(); }
string[] Lan() => NetworkInterface.GetAllNetworkInterfaces().Where(n => n.OperationalStatus == OperationalStatus.Up && n.NetworkInterfaceType != NetworkInterfaceType.Loopback).SelectMany(n => n.GetIPProperties().UnicastAddresses).Where(a => a.Address.AddressFamily == AddressFamily.InterNetwork).Select(a => $"http://{a.Address}:{port}").ToArray();
string[] Files(string id) { var d = Path.Combine(data, "s", id); return Directory.Exists(d) ? Directory.GetFiles(d).Select(q => Path.GetFileName(q)).OrderBy(q => q).ToArray() : new string[0]; }
bool Locked(string ip) => fails.TryGetValue(ip, out var f) && f.n >= 8 && Now() - f.t < 300000;
void Fail(string ip) { fails[ip] = fails.TryGetValue(ip, out var f) && Now() - f.t < 300000 ? (f.n + 1, Now()) : (1, Now()); }

// ---- Cloudflare tunnel manager: runs cloudflared as a child process and restarts it if it exits.
var prot = DataProtectionProvider.Create(new DirectoryInfo(Path.Combine(data, "keys")), o => { if (OperatingSystem.IsWindows()) o.ProtectKeysWithDpapi(true); }).CreateProtector("tunnel");
string tstate = "stopped"; var tlog = new Queue<string>(); Process tproc = null; string tkey = null;
string CfPath() { var n = OperatingSystem.IsWindows() ? "cloudflared.exe" : "cloudflared"; var l = Path.Combine(AppContext.BaseDirectory, n); return File.Exists(l) ? l : (b.Configuration["CloudflaredPath"] ?? n); }
void TStop() { try { if (tproc != null && !tproc.HasExited) tproc.Kill(true); } catch { } tproc = null; tstate = "stopped"; }
_ = Task.Run(async () =>
{
    while (true)
    {
        try
        {
            var want = db.tunnelEnabled && db.tunnelToken != "";
            if (!want) { if (tproc != null) TStop(); }
            else if (tproc == null || tproc.HasExited || tkey != db.tunnelToken)
            {
                TStop(); tkey = db.tunnelToken; tstate = "starting";
                var psi = new ProcessStartInfo(CfPath()) { RedirectStandardOutput = true, RedirectStandardError = true, UseShellExecute = false, CreateNoWindow = true };
                foreach (var a in new[] { "tunnel", "--no-autoupdate", "run", "--token", prot.Unprotect(db.tunnelToken) }) psi.ArgumentList.Add(a);
                tproc = Process.Start(psi);
                DataReceivedEventHandler h = (sn, ev) =>
                {
                    if (ev.Data == null) return;
                    lock (tlog) { tlog.Enqueue(ev.Data); while (tlog.Count > 30) tlog.Dequeue(); }
                    if (ev.Data.Contains("Registered tunnel connection")) tstate = "online";
                };
                tproc.OutputDataReceived += h; tproc.ErrorDataReceived += h; tproc.BeginOutputReadLine(); tproc.BeginErrorReadLine();
            }
        }
        catch (Exception ex) { tstate = "error: " + ex.Message; await Task.Delay(10000); }
        await Task.Delay(3000);
    }
});
// Values written by the installer wizard (Cloudflare token, hostname, admin password). Consumed once, then deleted.
var inst = Path.Combine(data, "install.json");
if (File.Exists(inst))
{
    try
    {
        using var doc = JsonDocument.Parse(File.ReadAllText(inst)); var r = doc.RootElement;
        string G(string k) => r.TryGetProperty(k, out var v) ? v.GetString() ?? "" : "";
        if (G("tunnelToken") != "") { db.tunnelToken = prot.Protect(G("tunnelToken")); db.tunnelEnabled = true; }
        var hn = G("publicHostname").Trim(); if (hn != "") db.publicUrl = (hn.StartsWith("http") ? hn : "https://" + hn).TrimEnd('/');
        if (G("adminPassword").Length >= 8) { db.adminSalt = Tok(); db.adminHash = PH(G("adminPassword"), db.adminSalt); }
        Save();
    }
    catch { }
    try { File.Delete(inst); } catch { }
}

// null = allowed. admin=true -> admin only, otherwise admin or paired device.
IResult A(HttpContext c, bool admin = false)
{
    var h = c.Request.Headers.Authorization.ToString();
    var t = h.StartsWith("Bearer ") ? h[7..] : "";
    if (t != "" && admins.Contains(t) && !c.Request.Headers.ContainsKey("CF-Connecting-IP")) return null;
    if (!admin && t != "")
    {
        var dv = db.devices.Find(d => !d.revoked && d.hash == H(t));
        if (dv != null) { dv.seen = Now(); dv.link = c.Request.Headers.ContainsKey("CF-Connecting-IP") ? "internet" : "local"; return null; }
    }
    return Results.Unauthorized();
}
// Latest edit wins (per event).
Ev Up(Ev e)
{
    e.updated = Math.Min(e.updated, Now() + 300000);
    var o = db.events.Find(x => x.id == e.id);
    if (e.created == 0) e.created = (o != null && o.created > 0) ? o.created : Now();
    if (o != null && o.updated > e.updated) return o;
    if (o != null) db.events.Remove(o);
    e.rev = NextRev(); db.events.Add(e); Save(); return e;
}
void Clean()
{
    foreach (var s in db.sessions.Where(v => !v.deleted).ToList())
    {
        var days = db.events.Find(v => v.id == s.eventId)?.retention ?? 10;
        if (Now() - s.created > days * 86400000L)
        {
            s.deleted = true;
            var d = Path.Combine(data, "s", s.id);
            if (Directory.Exists(d)) Directory.Delete(d, true);
        }
    }
    Save();
}
_ = Task.Run(async () => { while (true) { try { Clean(); } catch { } await Task.Delay(TimeSpan.FromHours(1)); } });

// Requests that came through the Cloudflare tunnel may only reach the public download routes.
app.Use(async (c, n) =>
{
    var p = c.Request.Path.Value ?? "";
    var via = c.Request.Headers.ContainsKey("CF-Connecting-IP");
    var open = p.StartsWith("/d/") || p.StartsWith("/f/") || p.StartsWith("/frames/") || p.StartsWith("/themes/")
        || (p.StartsWith("/api/v1/") && !(p.StartsWith("/api/v1/admin") || p.StartsWith("/api/v1/qr") || p.StartsWith("/api/v1/devices") || p.StartsWith("/api/v1/tunnel") || p.StartsWith("/api/v1/pair/code") || p.StartsWith("/api/v1/printers") || p.StartsWith("/api/v1/print-run")));
    if (via && !open) { c.Response.StatusCode = 404; return; }
    await n();
});

// ---- auth / pairing
app.MapGet("/api/v1/health", () => Results.Ok(new { ok = true, app = "photobooth", version = 6, serverId = db.serverId }));
app.MapGet("/api/v1/admin/state", () => Results.Ok(new { setup = db.adminHash != "" }));
app.MapPost("/api/v1/admin/setup", (HttpContext c, JsonElement j) =>
{
    var p = j.GetProperty("password").GetString() ?? "";
    if (db.adminHash != "" || p.Length < 8 || c.Request.Headers.ContainsKey("CF-Connecting-IP")) return Results.BadRequest(new { error = "setup unavailable, or password shorter than 8 characters" });
    db.adminSalt = Tok(); db.adminHash = PH(p, db.adminSalt); Save(); return Results.Ok();
});
app.MapPost("/api/v1/admin/login", (HttpContext c, JsonElement j) =>
{
    var ip = Ip(c);
    if (Locked(ip)) return Results.StatusCode(429);
    if (db.adminHash == "" || PH(j.GetProperty("password").GetString() ?? "", db.adminSalt) != db.adminHash) { Fail(ip); return Results.Unauthorized(); }
    var t = Tok(); admins.Add(t); return Results.Ok(new { token = t });
});
app.MapPost("/api/v1/pair/code", (HttpContext c) =>
{
    var x = A(c, true); if (x != null) return x;
    var k = RandomNumberGenerator.GetInt32(100000, 1000000).ToString();
    codes[k] = Now() + 300000; pairFails = 0; return Results.Ok(new { code = k });
});
app.MapPost("/api/v1/pair", (HttpContext c, JsonElement j) =>
{
    var ip = Ip(c);
    if (Locked(ip)) return Results.StatusCode(429);
    var k = j.GetProperty("code").GetString() ?? "";
    if (!codes.Remove(k, out var exp) || exp < Now()) { Fail(ip); if (++pairFails >= 30) { codes.Clear(); pairFails = 0; } return Results.Unauthorized(); }
    var t = Tok();
    var did = Guid.NewGuid().ToString("N");
    db.devices.Add(new Dev { id = did, name = j.GetProperty("name").GetString(), hash = H(t), created = Now() });
    Save(); return Results.Ok(new { token = t, deviceId = did, serverId = db.serverId });
});
app.MapGet("/api/v1/devices", (HttpContext c) => A(c, true) ?? Results.Ok(db.devices.Select(d => new { d.id, d.name, d.created, d.revoked, d.seen, d.link })));
app.MapDelete("/api/v1/devices/{id}", (HttpContext c, string id) =>
{
    var x = A(c, true); if (x != null) return x;
    db.devices.RemoveAll(v => v.id == id);   // unpaired = gone from the list (its token stops working)
    Save(); return Results.Ok();
});
app.MapGet("/api/v1/status", (HttpContext c) => A(c) ?? Results.Ok(new { ok = true, now = Now() }));
app.MapGet("/api/v1/settings", (HttpContext c) => A(c) ?? Results.Ok(new { db.publicUrl }));
app.MapPut("/api/v1/settings", (HttpContext c, JsonElement j) =>
{
    var x = A(c, true); if (x != null) return x;
    db.publicUrl = (j.GetProperty("publicUrl").GetString() ?? "").TrimEnd('/'); Save(); return Results.Ok();
});

// ---- events + sync
app.MapGet("/api/v1/events", (HttpContext c) => A(c) ?? Results.Ok(db.events.Where(e => !e.deleted)));
app.MapPut("/api/v1/events/{id}", (HttpContext c, string id, Ev e) =>
{
    var x = A(c); if (x != null) return x;
    if (!Id(id)) return Results.BadRequest();
    e.id = id; return Results.Ok(Up(e));
});
app.MapDelete("/api/v1/events/{id}", (HttpContext c, string id) =>
{
    var x = A(c); if (x != null) return x;
    var e = db.events.Find(v => v.id == id);
    if (e != null) { e.deleted = true; e.updated = Now(); e.rev = NextRev(); Save(); }
    return Results.Ok();
});
app.MapGet("/api/v1/sync/pull", (HttpContext c, long since = 0) => A(c) ?? Results.Ok(new { cursor = db.events.Count == 0 ? since : Math.Max(since, db.events.Max(e => e.rev)), events = db.events.Where(e => e.rev > since).ToList(), serverTime = Now() }));
app.MapPost("/api/v1/sync/push", (HttpContext c, List<Ev> l) => A(c) ?? Results.Ok(l.Where(e => Id(e.id)).Select(Up).ToList()));

// ---- frames
app.MapPost("/api/v1/events/{id}/frames", async (HttpContext c, string id) =>
{
    var x = A(c); if (x != null) return x;
    var e = db.events.Find(v => v.id == id); if (e == null) return Results.NotFound();
    var form = await c.Request.ReadFormAsync();
    var file = form.Files.FirstOrDefault(); if (file == null) return Results.BadRequest();
    var fid = Id(form["id"].ToString()) ? form["id"].ToString() : Guid.NewGuid().ToString("N");
    var dir = Path.Combine(data, "frames", id); Directory.CreateDirectory(dir);
    using (var s = File.Create(Path.Combine(dir, fid + ".png"))) await file.CopyToAsync(s);
    if (!e.frames.Any(fr => fr.id == fid)) { e.frames.Add(new Frame { id = fid, name = form["name"].ToString() }); e.updated = Now(); }
    e.rev = NextRev(); Save(); return Results.Ok(e);
});
app.MapGet("/frames/{eid}/{fid}", (HttpContext c, string eid, string fid) =>
{
    if (c.Request.Headers.ContainsKey("CF-Connecting-IP") && A(c) != null) return Results.Unauthorized();
    var p = Path.Combine(data, "frames", eid, fid + ".png");
    return Id(eid) && Id(fid) && File.Exists(p) ? Results.File(p, "image/png") : Results.NotFound();
});

app.MapPost("/api/v1/events/{id}/theme-image", async (HttpContext c, string id) =>
{
    var x = A(c); if (x != null) return x;
    var e = db.events.Find(v => v.id == id); if (e == null) return Results.NotFound();
    var lim = c.Features.Get<Microsoft.AspNetCore.Http.Features.IHttpMaxRequestBodySizeFeature>(); if (lim != null && !lim.IsReadOnly) lim.MaxRequestBodySize = 120L * 1024 * 1024;   // start-screen videos
    var form = await c.Request.ReadFormAsync(); var file = form.Files.FirstOrDefault(); if (file == null) return Results.BadRequest();
    var iid = Id(form["id"].ToString()) ? form["id"].ToString() : Guid.NewGuid().ToString("N");
    var dir = Path.Combine(data, "themes", id); Directory.CreateDirectory(dir);
    var tp = Path.Combine(dir, iid);
    using (var s = File.Create(tp)) await file.CopyToAsync(s);
    if (IsMp4(tp)) e.theme["kind"] = "video"; else e.theme.Remove("kind");   // theme.kind = "video": the start screen plays it in a loop
    e.theme["image"] = iid; e.updated = Now(); e.rev = NextRev(); Save(); return Results.Ok(e);
});
app.MapGet("/themes/{eid}/{iid}", (HttpContext c, string eid, string iid) =>
{
    if (c.Request.Headers.ContainsKey("CF-Connecting-IP") && A(c) != null) return Results.Unauthorized();
    var p = Path.Combine(data, "themes", eid, iid);
    if (!Id(eid) || !Id(iid) || !File.Exists(p)) return Results.NotFound();
    int m; using (var fs = File.OpenRead(p)) m = fs.ReadByte();
    if (IsMp4(p)) return Results.File(p, "video/mp4", enableRangeProcessing: true);
    return Results.File(p, m == 0x89 ? "image/png" : "image/jpeg");
});

// ---- sessions (guest results), gallery, print queue
app.MapPost("/api/v1/sessions/{id}", async (HttpContext c, string id) =>
{
    var x = A(c); if (x != null) return x;
    if (!Id(id)) return Results.BadRequest();
    var form = await c.Request.ReadFormAsync();
    var dir = Path.Combine(data, "s", id); Directory.CreateDirectory(dir);
    var saved = new List<string>();
    foreach (var file in form.Files)
    {
        var n = file.Name;
        if (!Regex.IsMatch(n, "^(photo[2-5]?|thumb|gif|live|raw[1-9])$")) continue;
        using var s = File.Create(Path.Combine(dir, n + (n == "gif" ? ".gif" : n == "live" ? ".mp4" : ".jpg")));
        await file.CopyToAsync(s); saved.Add(n);
    }
    var ss = db.sessions.Find(v => v.id == id);
    if (ss == null) { ss = new Sess { id = id, eventId = form["eventId"].ToString(), frameId = form["frameId"].ToString(), created = Now() }; db.sessions.Add(ss); }
    ss.ready = File.Exists(Path.Combine(dir, "photo.jpg")); ss.files = Files(id).ToList();
    if (int.TryParse(form["rawCount"].ToString(), out var rc)) ss.rawCount = rc;
    if (int.TryParse(form["photoCount"].ToString(), out var pcnt)) ss.photoCount = pcnt;
    Save(); return Results.Ok(new { saved });
});
app.MapGet("/api/v1/events/{id}/sessions", (HttpContext c, string id) => A(c) ?? Results.Ok(db.sessions.Where(s => s.eventId == id && !s.deleted).OrderByDescending(s => s.created).Select(s => new { s.id, s.eventId, s.frameId, s.created, s.ready, files = Files(s.id) }).ToList()));
app.MapDelete("/api/v1/sessions/{id}", (HttpContext c, string id) =>
{
    var x = A(c); if (x != null) return x;
    if (Id(id))
    {
        var s = db.sessions.Find(v => v.id == id); if (s != null) { s.deleted = true; Save(); }
        var d = Path.Combine(data, "s", id); if (Directory.Exists(d)) Directory.Delete(d, true);
    }
    return Results.Ok();
});
// Everything grouped: events -> sessions -> files (framed photos, raw shots, gif, live photo).
app.MapGet("/api/v1/gallery", (HttpContext c) => A(c) ?? Results.Ok(db.events.Where(e => !e.deleted).Select(e => new
{
    e.id, e.name,
    sessions = db.sessions.Where(s => s.eventId == e.id && !s.deleted).OrderByDescending(s => s.created).Select(s => new { s.id, s.created, s.frameId, files = Files(s.id) }).ToList()
}).ToList()));
app.MapGet("/api/v1/sessions/{id}/files/{name}", (HttpContext c, string id, string name) =>
{
    var x = A(c); if (x != null) return x;
    if (!Id(id) || !Regex.IsMatch(name, "^(photo[2-5]?|thumb|gif|live|raw[1-9])\\.(jpg|gif|mp4)$")) return Results.NotFound();
    var p = Path.Combine(data, "s", id, name);
    return File.Exists(p) ? Results.File(p, null, name, enableRangeProcessing: true) : Results.NotFound();
});
app.MapGet("/api/v1/events/{id}/export", async (HttpContext c, string id) =>
{
    if (A(c) != null) { c.Response.StatusCode = 401; return; }
    c.Features.Get<IHttpBodyControlFeature>().AllowSynchronousIO = true;
    c.Response.ContentType = "application/zip"; c.Response.Headers.ContentDisposition = $"attachment; filename=event-{id}.zip";
    using var z = new ZipArchive(c.Response.Body, ZipArchiveMode.Create, true);
    foreach (var s in db.sessions.Where(v => v.eventId == id && !v.deleted).ToList())
        foreach (var f in Files(s.id)) z.CreateEntryFromFile(Path.Combine(data, "s", s.id, f), s.id + "/" + f);
    await Task.CompletedTask;
});
// Printing: the guest chooses framed photos and a number of copies for each. The event's "copies" setting is the maximum
// number of printed copies for ONE photo session (all framed photos together).
int PrintedSoFar(string sid) => db.prints.Where(p => p.sessionId == sid && p.status != "cancelled").Sum(p => p.copies);
app.MapGet("/api/v1/sessions/{id}/print-info", (HttpContext c, string id) =>
{
    var x = A(c); if (x != null) return x;
    var s = db.sessions.Find(v => v.id == id && !v.deleted); if (s == null) return Results.NotFound();
    var ev = db.events.Find(v => v.id == s.eventId);
    return Results.Ok(new { max = Math.Max(1, ev?.copies ?? 1), used = PrintedSoFar(id) });
});
app.MapPost("/api/v1/sessions/{id}/print", (HttpContext c, string id, JsonElement j) =>
{
    var x = A(c); if (x != null) return x;
    var s = db.sessions.Find(v => v.id == id && !v.deleted); if (s == null) return Results.NotFound();
    var ev = db.events.Find(v => v.id == s.eventId);
    var max = Math.Max(1, ev?.copies ?? 1);
    // body: {"items":[{"file":"photo.jpg","copies":2},...]}  or the old form {"file":"photo.jpg","copies":1}
    var items = new List<(string file, int copies)>();
    if (j.TryGetProperty("items", out var arr) && arr.ValueKind == JsonValueKind.Array)
    {
        foreach (var el in arr.EnumerateArray())
            items.Add((el.TryGetProperty("file", out var fn) ? fn.GetString() ?? "photo.jpg" : "photo.jpg", el.TryGetProperty("copies", out var cn) ? cn.GetInt32() : 1));
    }
    else items.Add((j.TryGetProperty("file", out var f1) ? f1.GetString() ?? "photo.jpg" : "photo.jpg", j.TryGetProperty("copies", out var c1) ? c1.GetInt32() : 1));
    items = items.Where(q => q.copies > 0).ToList();
    if (items.Count == 0) return Results.BadRequest(new { error = "nothing to print" });
    foreach (var q in items)
        if (!Regex.IsMatch(q.file, "^photo[2-5]?\\.jpg$") || !File.Exists(Path.Combine(data, "s", id, q.file))) return Results.BadRequest(new { error = "unknown photo " + q.file });
    var total = items.Sum(q => q.copies); var left = max - PrintedSoFar(id);
    if (total > left) return Results.Json(new { error = "limit", remaining = Math.Max(0, left), max }, statusCode: 409);
    foreach (var q in items) db.prints.Add(new Prt { id = Guid.NewGuid().ToString("N"), sessionId = id, eventId = s.eventId, file = q.file, copies = q.copies, status = "queued", created = Now() });
    Save(); return Results.Ok(new { queued = total, remaining = left - total });
});
app.MapGet("/api/v1/prints", (HttpContext c) => A(c) ?? Results.Ok(db.prints.OrderByDescending(p => p.created).Take(100)));
app.MapPost("/api/v1/prints/{id}/{st}", (HttpContext c, string id, string st) =>
{
    var x = A(c); if (x != null) return x;
    var p = db.prints.Find(v => v.id == id);
    if (p != null && (st == "done" || st == "cancelled")) { p.status = st; Save(); }
    return Results.Ok();
});
// ---- web admin (Linux / any browser): http://localhost:5080/admin  (not reachable through the tunnel)
app.MapGet("/admin", () =>
{
    var f = Path.Combine(AppContext.BaseDirectory, "webadmin", "index.html");
    return File.Exists(f) ? Results.File(f, "text/html; charset=utf-8") : Results.NotFound("webadmin/index.html is missing");
});
app.MapGet("/admin/", () => Results.Redirect("/admin"));

// ---- printing through CUPS (Linux): list the printers and print a queued job with lp
async Task<(int Code, string Out, string Err)> Run(string exe, string[] argv, int ms)
{
    try
    {
        var psi = new ProcessStartInfo(exe) { RedirectStandardOutput = true, RedirectStandardError = true, UseShellExecute = false };
        foreach (var arg in argv) psi.ArgumentList.Add(arg);
        using var pr0 = Process.Start(psi);
        var so = pr0.StandardOutput.ReadToEndAsync(); var se = pr0.StandardError.ReadToEndAsync();
        if (!pr0.WaitForExit(ms)) { try { pr0.Kill(true); } catch { } return (-1, "", "timeout"); }
        return (pr0.ExitCode, await so, await se);
    }
    catch (Exception ex) { return (-2, "", ex.Message); }
}
app.MapGet("/api/v1/printers", async (HttpContext c) =>
{
    var x = A(c, true); if (x != null) return x;
    var list = new List<string>(); var def = "";
    if (!OperatingSystem.IsWindows())
    {
        var o = await Run("lpstat", new[] { "-e" }, 5000);
        list = o.Out.Split('\n', StringSplitOptions.RemoveEmptyEntries).Select(v => v.Trim()).Where(v => v != "").ToList();
        var dd = await Run("lpstat", new[] { "-d" }, 5000); var mm = Regex.Match(dd.Out, @"destination:\s*(\S+)"); if (mm.Success) def = mm.Groups[1].Value;
    }
    return Results.Ok(new { printers = list, @default = def, supported = !OperatingSystem.IsWindows() });
});
var printingNow = new HashSet<string>();
app.MapPost("/api/v1/print-run/{id}", async (HttpContext c, string id, JsonElement j) =>
{
    var x = A(c, true); if (x != null) return x;
    if (OperatingSystem.IsWindows()) return Results.BadRequest(new { error = "the Windows admin app prints by itself" });
    var job = db.prints.Find(v => v.id == id); if (job == null || job.status != "queued") return Results.NotFound();
    var printer = j.TryGetProperty("printer", out var pe) ? pe.GetString() ?? "" : "";
    if (printer != "" && !Regex.IsMatch(printer, "^[A-Za-z0-9_.:@-]+$")) return Results.BadRequest(new { error = "bad printer name" });
    var file = Path.Combine(data, "s", job.sessionId, job.file);
    if (!Regex.IsMatch(job.file, "^photo[2-5]?\\.jpg$") || !File.Exists(file)) return Results.NotFound();
    lock (printingNow) if (!printingNow.Add(id)) return Results.Conflict();
    try
    {
        var lpArgs = new List<string> { "-n", Math.Max(1, job.copies).ToString(), "-o", "fit-to-page" };
        if (printer != "") { lpArgs.Add("-d"); lpArgs.Add(printer); }
        lpArgs.Add(file);
        var r = await Run("lp", lpArgs.ToArray(), 20000);
        if (r.Code != 0) return Results.Json(new { error = ((r.Err + " " + r.Out).Trim() == "" ? "lp failed" : (r.Err + " " + r.Out).Trim()) }, statusCode: 500);
        job.status = "done"; Save(); return Results.Ok();
    }
    finally { lock (printingNow) printingNow.Remove(id); }
});
app.MapGet("/api/v1/qr", (string text) =>
{
    using var g = new QRCodeGenerator();
    var d = g.CreateQrCode(text, QRCodeGenerator.ECCLevel.Q);
    return Results.Content(new SvgQRCode(d).GetGraphic(6), "image/svg+xml");
});

// ---- public download page + files (the only routes reachable through the tunnel)
app.MapGet("/d/{id}", (string id) =>
{
    if (!Id(id)) return Results.NotFound();
    var s = db.sessions.Find(v => v.id == id); if (s != null && s.deleted) return Results.NotFound();
    var fs = Files(id);
    var framed = fs.Where(x => Regex.IsMatch(x, "^photo[2-5]?\\.jpg$")).ToList();
    var raws = fs.Where(x => Regex.IsMatch(x, "^raw[1-9]\\.jpg$")).ToList();
    var pending = s == null || (Now() - s.created < 600000 && (framed.Count < s.photoCount || raws.Count < s.rawCount));
    var meta = pending ? "<meta http-equiv=refresh content=4>" : "";
    var sb = new StringBuilder();
    if (framed.Count == 0) sb.Append("<p>Preparing your photos…</p>");
    else
    {
        sb.Append("<h2>Your photos</h2>");
        foreach (var f in framed) sb.Append($"<img src='/f/{id}/{f}'><a class=b href='/f/{id}/{f}?dl=1'>Download</a>");
        if (fs.Contains("gif.gif")) sb.Append($"<h2>GIF</h2><img src='/f/{id}/gif.gif'><a class=b href='/f/{id}/gif.gif?dl=1'>Download GIF</a>");
        if (fs.Contains("live.mp4")) sb.Append($"<h2>Live photo</h2><video src='/f/{id}/live.mp4' controls loop muted playsinline></video><a class=b href='/f/{id}/live.mp4?dl=1'>Download live photo</a>");
        if (raws.Count > 0)
        {
            sb.Append("<h2>Original photos</h2><div class=g>");
            foreach (var f in raws) sb.Append($"<div><img loading=lazy src='/f/{id}/{f}'><a class=s href='/f/{id}/{f}?dl=1'>Download</a></div>");
            sb.Append("</div>");
        }
        if (framed.Count + raws.Count > 1) sb.Append($"<a class=b href='/f/{id}/all.zip'>Download everything (zip)</a>");
        if (pending) sb.Append("<p>More files are still arriving…</p>");
    }
    return Results.Content($"<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>{meta}<title>Your photos</title><style>body{{font-family:sans-serif;text-align:center;padding:16px;background:#111;color:#fff}}img,video{{max-width:100%;border-radius:8px;margin:12px 0}}h2{{font-size:18px;margin:20px 0 0}}.b{{display:block;background:#fff;color:#000;padding:14px;border-radius:8px;text-decoration:none;font-weight:600;margin:8px 0}}.g{{display:grid;grid-template-columns:1fr 1fr;gap:8px}}.s{{display:block;background:#fff;color:#000;padding:8px;border-radius:6px;text-decoration:none;font-size:14px}}</style>{sb}", "text/html");
});
app.MapGet("/f/{id}/{name}", (HttpContext c, string id, string name) =>
{
    if (!Id(id) || !Regex.IsMatch(name, "^(photo[2-5]?\\.jpg|raw[1-9]\\.jpg|thumb\\.jpg|gif\\.gif|live\\.mp4|all\\.zip)$")) return Results.NotFound();
    var s = db.sessions.Find(v => v.id == id); if (s != null && s.deleted) return Results.NotFound();
    if (name == "all.zip")
    {
        using var ms = new MemoryStream();
        using (var z = new ZipArchive(ms, ZipArchiveMode.Create, true))
            foreach (var x in Files(id).Where(x => !x.StartsWith("thumb"))) z.CreateEntryFromFile(Path.Combine(data, "s", id, x), x);
        return Results.File(ms.ToArray(), "application/zip", "photobooth-" + id.Substring(0, 6) + ".zip");
    }
    var p = Path.Combine(data, "s", id, name); if (!File.Exists(p)) return Results.NotFound();
    return Results.File(p, name.EndsWith("gif") ? "image/gif" : name.EndsWith("mp4") ? "video/mp4" : "image/jpeg", c.Request.Query.ContainsKey("dl") ? "photobooth-" + name : null, enableRangeProcessing: true);
});

app.MapGet("/api/v1/lan", (HttpContext c) => A(c) ?? Results.Ok(new { serverId = db.serverId, port, addresses = Lan(), publicUrl = db.publicUrl }));
app.MapGet("/api/v1/lan/verify", (string device, string nonce) =>
{
    var d = db.devices.Find(v => v.id == device && !v.revoked);
    if (d == null || (nonce ?? "").Length < 8) return Results.NotFound();
    using var hm = new HMACSHA256(Encoding.UTF8.GetBytes(d.hash));
    return Results.Ok(new { serverId = db.serverId, proof = Convert.ToHexString(hm.ComputeHash(Encoding.UTF8.GetBytes(nonce))) });
});
app.MapGet("/api/v1/tunnel", (HttpContext c) => A(c, true) ?? Results.Ok(new { enabled = db.tunnelEnabled, hasToken = db.tunnelToken != "", state = tstate, log = tlog.ToArray() }));
app.MapPut("/api/v1/tunnel", (HttpContext c, JsonElement j) =>
{
    var x = A(c, true); if (x != null) return x;
    if (j.TryGetProperty("token", out var tk) && (tk.GetString() ?? "") != "") db.tunnelToken = prot.Protect(tk.GetString());
    if (j.TryGetProperty("enabled", out var en)) db.tunnelEnabled = en.GetBoolean();
    Save(); return Results.Ok();
});
// LAN discovery: a tablet broadcasts PHOTOBOOTH_DISCOVER to UDP 5081 and gets this server's id and port back.
_ = Task.Run(async () =>
{
    while (true)
    {
        try
        {
            using var u = new UdpClient(5081);
            while (true)
            {
                var rcv = await u.ReceiveAsync();
                if (Encoding.UTF8.GetString(rcv.Buffer).Trim() != "PHOTOBOOTH_DISCOVER") continue;
                var reply = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(new { app = "photobooth", serverId = db.serverId, port }));
                await u.SendAsync(reply, reply.Length, rcv.RemoteEndPoint);
            }
        }
        catch { await Task.Delay(5000); }
    }
});
app.Lifetime.ApplicationStopping.Register(TStop);
static bool IsMp4(string path) { try { using var fs = File.OpenRead(path); var h = new byte[12]; return fs.Read(h, 0, 12) == 12 && h[4] == (byte)'f' && h[5] == (byte)'t' && h[6] == (byte)'y' && h[7] == (byte)'p'; } catch { return false; } }
app.Run();

class Slot { public double x, y, w, h, r; public int source = -1; }
class Frame { public string id, name; public List<Slot> slots = new(); }
class Ev { public string id, name = ""; public Dictionary<string, string> theme = new(); public List<Frame> frames = new(); public int shots = 4, countdown = 3, retention = 10, copies = 1, maxFrames = 1; public long updated, rev, created; public bool deleted; }
class Sess { public string id, eventId, frameId; public long created; public bool ready, deleted; public int rawCount, photoCount; public List<string> files = new(); }
class Prt { public string id, sessionId, eventId, status, file = "photo.jpg"; public int copies; public long created; }
class Dev { public string id, name, hash; public long created, seen; public bool revoked; public string link = ""; }
class Store { public List<Ev> events = new(); public List<Sess> sessions = new(); public List<Prt> prints = new(); public List<Dev> devices = new(); public string serverId = "", publicUrl = "", adminHash = "", adminSalt = "", tunnelToken = ""; public bool tunnelEnabled; }
