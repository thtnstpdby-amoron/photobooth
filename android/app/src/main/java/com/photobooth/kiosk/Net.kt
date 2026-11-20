package com.photobooth.kiosk

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Net {
    private lateinit var p: SharedPreferences
    lateinit var dir: File
    @Volatile var lan = ""
    @Volatile var via = "unknown"
    @Volatile var serverVer = 0
    @Volatile var syncWarning = ""
    @Volatile var probeLog = ""
    @Volatile var uploadingSessionId = ""
    @Volatile var cachingSessionId = ""
    private val sessionIoLock = Any()
    private val sessionName = Regex("[A-Za-z0-9_-]{8,80}")
    private val resultFile = Regex("(?:photo(?:[2-5])?|raw[1-9]|thumb)\\.jpg|gif\\.gif|live\\.mp4")
    private const val LOCAL_GALLERY_BUDGET = 4_000_000_000L // soft limit, including uploaded sessions
    private const val MIN_FREE_BYTES = 500_000_000L
    private const val MAX_HOST_FILE_BYTES = 120_000_000L
    @Volatile private var lanFails = 0
    @Volatile private var probing = false

    fun init(c: Context) { p = c.getSharedPreferences("pb", 0); dir = c.filesDir; File(dir, "img").mkdirs(); File(dir, "q").mkdirs(); File(dir, "host_cache").mkdirs() }
    fun pub() = p.getString("pub", "")!!
    fun token() = p.getString("tok", "")!!
    fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }
    fun rid() = hex(ByteArray(16).also { SecureRandom().nextBytes(it) }).lowercase()
    fun imgFile(n: String) = File(File(dir, "img"), n)
    fun camMode() = p.getString("cam", "auto")!!
    fun setCam(m: String) { p.edit().putString("cam", m).apply() }
    fun linkMode() = p.getString("mode", "auto")!!
    fun setLinkMode(m: String) { p.edit().putString("mode", m).apply(); if (m == "internet") lan = "" }
    private fun manualLan() = p.getString("manual", "")!!
    fun norm(a: String): String {
        var x = a.trim().trimEnd('/'); if (x == "") return x
        if (!x.startsWith("http")) x = "http://$x"
        if (Regex("^http://[0-9.]+$").matches(x)) x += ":5080"
        return x
    }
    private fun isLocalUrl(u: String) = Regex("^http://(192\\.168\\.|10\\.|172\\.(1[6-9]|2[0-9]|3[01])\\.|169\\.254\\.)").containsMatchIn(u)
    fun viaText() = when (via) { "local" -> "Local network (fast)"; "internet" -> "Internet"; "offline" -> "Offline (photos are saved and upload later)"; else -> "Checking connection..." }

    private fun raw(url: String, m: String, body: ByteArray?, ct: String?, auth: Boolean, to: Int): Pair<Int, ByteArray> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = m; c.connectTimeout = to; c.readTimeout = 120000
            if (auth) c.setRequestProperty("Authorization", "Bearer " + token())
            if (body != null) {
                c.doOutput = true; c.setRequestProperty("Content-Type", ct ?: "application/json"); c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val s = if (code >= 400) c.errorStream else c.inputStream
            return code to (s?.use { it.readBytes() } ?: ByteArray(0))
        } finally { c.disconnect() }
    }

    // Local network first (when it is verified and the mode allows it), the internet as the fallback.
    // A dead local link fails fast (1.5 s) and the same request is retried on the internet straight away.
    fun req(m: String, path: String, body: ByteArray? = null, ct: String? = null): Pair<Int, ByteArray> {
        val cands = when (linkMode()) { "internet" -> listOf(pub()); "local" -> listOf(lan); else -> listOf(lan, pub()) }.filter { it != "" }.distinct()
        for (b in cands) {
            try {
                val r = raw(b + path, m, body, ct, true, if (b == lan) 1500 else 8000)
                via = if (b == lan) "local" else "internet"; if (b == lan) lanFails = 0
                return r
            } catch (e: IOException) { if (b == lan && ++lanFails >= 2) { lan = ""; lanFails = 0 } }
        }
        via = "offline"
        throw IOException("offline")
    }
    private fun json(path: String): JSONObject { val (c, b) = req("GET", path); if (c != 200) throw IOException("server answered $c"); return JSONObject(String(b)) }

    // Pair with the public address (internet) or with a local address (same Wi-Fi, no internet needed).
    fun pair(addr0: String, code: String, timeout: Int = 10000, pubUrl: String = "") {
        val addr = norm(addr0)
        val body = JSONObject().put("code", code).put("name", android.os.Build.MODEL).toString().toByteArray()
        val (c, b) = raw("$addr/api/v1/pair", "POST", body, null, false, timeout)
        if (c != 200) throw IOException("pairing refused ($c): check the code, it is valid for 5 minutes")
        val j = JSONObject(String(b)); val local = isLocalUrl(addr)
        p.edit().putString("pub", if (local) pubUrl else addr).putString("tok", j.getString("token")).putString("dev", j.getString("deviceId"))
            .putString("srv", j.getString("serverId")).putLong("cur", 0).putString("evs", "{}").putString("dirty", "[]").putString("pimg", "[]").putString("manual", "").apply()
        if (local) { lan = addr; via = "local"; lanFails = 0; p.edit().putString("lanaddrs", JSONArray().put(addr).toString()).apply() }
    }

    // Pairing from the QR code shown by the Windows app: photobooth://pair?c=123456&a=192.168.1.20:5080&a=...&p=https%3A%2F%2Fphotos.example.com
    // c = one-time code, a = laptop addresses on the local network (tried first, short timeout), p = public address (internet fallback).
    fun isPairQr(text: String) = text.trim().startsWith("photobooth://pair")
    fun pairQr(text: String) {
        val u = android.net.Uri.parse(text.trim())
        if (u.scheme != "photobooth" || u.host != "pair") throw IOException("This QR code is not a Photobooth pairing code")
        val code = (u.getQueryParameter("c") ?: "").trim()
        if (code.length != 6) throw IOException("The pairing QR code is damaged, show a new one on the laptop")
        val locals = u.getQueryParameters("a").map { it.trim() }.filter { it != "" }
        val pub = (u.getQueryParameter("p") ?: "").trim().trimEnd('/')
        var last: Exception? = null
        for (a in locals) {
            try { pair(a, code, 4000, pub); return }
            catch (e: Exception) { last = e; if (e.message?.startsWith("pairing refused") == true) throw e }
        }
        if (pub != "") {
            try { pair(pub, code, 10000, ""); return }
            catch (e: Exception) { last = e }
        }
        throw IOException("Could not reach the laptop (" + (last?.message ?: "no address in the QR code") + "). Check that the tablet is on the same Wi-Fi, or that the laptop has a public address.")
    }

    // Looks for the laptop on this network: UDP broadcast, saved addresses, addresses the laptop reports, and a typed address.
    fun discover(): List<String> {
        val out = ArrayList<String>()
        try {
            DatagramSocket().use { s ->
                s.broadcast = true; s.soTimeout = 400
                val msg = "PHOTOBOOTH_DISCOVER".toByteArray(); val targets = HashSet<String>(); targets.add("255.255.255.255")
                for (ni in NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()) for (ia in ni.interfaceAddresses) { val bc = ia.broadcast; if (bc != null) targets.add(bc.hostAddress ?: "") }
                for (t in targets) if (t != "") try { s.send(DatagramPacket(msg, msg.size, InetAddress.getByName(t), 5081)) } catch (_: Exception) { }
                val end = System.currentTimeMillis() + 1200
                while (System.currentTimeMillis() < end) {
                    try {
                        val pk = DatagramPacket(ByteArray(512), 512); s.receive(pk)
                        val j = JSONObject(String(pk.data, 0, pk.length))
                        if (j.optString("app") == "photobooth") out.add("http://${pk.address.hostAddress}:${j.optInt("port", 5080)}")
                    } catch (_: SocketTimeoutException) { }
                }
            }
        } catch (_: Exception) { }
        return out.distinct()
    }

    // Finds the laptop on the local network and proves it is the real server (HMAC challenge; the token is never sent).
    fun probeLan(): Boolean {
        if (probing || token() == "") return lan != ""
        probing = true
        val log = StringBuilder()
        try {
            val cands = LinkedHashSet<String>()
            if (manualLan() != "") cands.add(manualLan())
            if (lan != "") cands.add(lan)
            var asked = false
            if (pub() != "" && linkMode() != "local") try {
                val (c, b) = raw(pub() + "/api/v1/lan", "GET", null, null, true, 2500)
                if (c == 200) {
                    val a = JSONObject(String(b)).getJSONArray("addresses"); for (i in 0 until a.length()) cands.add(a.getString(i))
                    p.edit().putString("lanaddrs", a.toString()).apply(); asked = true; log.append("The laptop reports ${a.length()} local address(es).\n")
                }
            } catch (_: Exception) { }
            if (!asked) { val a = JSONArray(p.getString("lanaddrs", "[]")!!); for (i in 0 until a.length()) cands.add(a.getString(i)); log.append("Could not ask the laptop over the internet; using saved addresses.\n") }
            for (d in discover()) if (cands.add(d)) log.append("Found by broadcast: $d\n")
            if (cands.isEmpty()) log.append("No address to try. Use the same Wi-Fi as the laptop, or type its address.\n")
            val key = hex(MessageDigest.getInstance("SHA-256").digest(token().toByteArray()))
            for (addr in cands) {
                val nonce = rid()
                try {
                    val (c, b) = raw("$addr/api/v1/lan/verify?device=${p.getString("dev", "")}&nonce=$nonce", "GET", null, null, false, 1200)
                    if (c != 200) { log.append("$addr: answered with code $c (this tablet is not paired with that laptop)\n"); continue }
                    val r = JSONObject(String(b))
                    if (r.getString("serverId") != p.getString("srv", "")) { log.append("$addr: a different Photobooth server\n"); continue }
                    val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key.toByteArray(), "HmacSHA256")) }
                    if (!hex(mac.doFinal(nonce.toByteArray())).equals(r.getString("proof"), true)) { log.append("$addr: failed the security check\n"); continue }
                    lan = addr; lanFails = 0; via = "local"; log.append("$addr: connected (verified)\n"); probeLog = log.toString(); return true
                } catch (e: Exception) { log.append("$addr: no answer (different Wi-Fi, laptop network profile set to Public, or the router blocks device-to-device traffic)\n") }
            }
            lan = ""; probeLog = log.toString(); return false
        } finally { probing = false }
    }
    fun tryManual(input: String): Boolean { p.edit().putString("manual", norm(input)).apply(); return probeLan() }

    // Quick liveness check of the current local link. Two misses in a row and we drop to the internet.
    fun checkLan() {
        val a = lan; if (a == "") return
        try { if (raw("$a/api/v1/health", "GET", null, null, false, 1000).first == 200) { lanFails = 0; return } } catch (_: Exception) { }
        if (++lanFails >= 2) { lan = ""; lanFails = 0; ping() }
    }
    fun ping() { try { req("GET", "/api/v1/status") } catch (_: Exception) { } }

    // Runs for the whole life of the app, whatever screen is showing (also the start screen).
    fun monitor() {
        var n = 0
        while (true) {
            try {
                if (token() != "") {
                    if (lan != "") checkLan() else if (n % 2 == 0 && linkMode() != "internet") probeLan()
                    if (n % 3 == 0) ping()
                }
            } catch (_: Exception) { }
            n++; Thread.sleep(3000)
        }
    }

    // ---- local event store (offline first). "dirty" = events changed here and not yet pushed; "pimg" = frame/theme images not yet uploaded.
    private val lock = Any()
    private fun evMap() = JSONObject(p.getString("evs", "{}")!!)
    private fun dirtySet(): MutableSet<String> { val a = JSONArray(p.getString("dirty", "[]")!!); val s = HashSet<String>(); for (i in 0 until a.length()) s.add(a.getString(i)); return s }
    private fun putState(o: JSONObject, d: Set<String>) { p.edit().putString("evs", o.toString()).putString("dirty", JSONArray(d.toList()).toString()).apply() }
    private fun pending() = JSONArray(p.getString("pimg", "[]")!!)
    private fun queueImg(t: String, eid: String, id: String, name: String) { val a = pending(); a.put(JSONObject().put("t", t).put("eid", eid).put("id", id).put("name", name)); p.edit().putString("pimg", a.toString()).apply() }
    fun now() = System.currentTimeMillis() + p.getLong("off", 0)
    fun dirtyCount() = synchronized(lock) { dirtySet().size + pending().length() }
    fun events(): List<JSONObject> = synchronized(lock) { val o = evMap(); o.keys().asSequence().map { o.getJSONObject(it) }.filter { !it.optBoolean("deleted") }.sortedBy { it.optString("name") }.toList() }
    fun event(id: String): JSONObject? = synchronized(lock) { evMap().optJSONObject(id) }
    fun saveEvent(e: JSONObject) { synchronized(lock) { e.put("updated", now()); val o = evMap(); o.put(e.getString("id"), e); val d = dirtySet(); d.add(e.getString("id")); putState(o, d) } }
    fun newEvent(): String {
        val id = rid()
        saveEvent(JSONObject().put("id", id).put("name", "New event").put("theme", JSONObject()).put("frames", JSONArray()).put("shots", 4).put("countdown", 3).put("retention", 10).put("copies", 1).put("maxFrames", 1).put("rev", 0).put("created", now()).put("deleted", false))
        return id
    }
    // A brand-new event that is NOT stored yet: the editor stores it only when Save is pressed.
    fun newEventDraft(): JSONObject = JSONObject().put("id", rid()).put("name", "New event").put("theme", JSONObject()).put("frames", JSONArray()).put("shots", 4).put("countdown", 3).put("retention", 10).put("copies", 1).put("maxFrames", 1).put("rev", 0).put("created", now()).put("deleted", false)
    fun queueImage(t: String, eid: String, id: String, name: String) { synchronized(lock) { queueImg(t, eid, id, name) } }
    fun deleteEvent(id: String) { synchronized(lock) { val e = evMap().optJSONObject(id); if (e != null) { e.put("deleted", true); saveEvent(e) } } }
    fun clearThemeImage(eid: String) { synchronized(lock) { val e = event(eid) ?: return; e.getJSONObject("theme").remove("image"); saveEvent(e) } }
    // Forget the host: the tablet must be paired again. Photos not uploaded yet stay in the queue.
    fun unpair() { val cam = camMode(); val mode = linkMode(); p.edit().clear().putString("cam", cam).putString("mode", mode).apply(); lan = ""; via = "unknown"; probeLog = "" }
    fun addFrame(eid: String, name: String, bytes: ByteArray) {
        synchronized(lock) {
            val e = event(eid) ?: return
            val id = rid(); imgFile("f_$id").writeBytes(bytes)
            e.getJSONArray("frames").put(JSONObject().put("id", id).put("name", name).put("slots", JSONArray()))
            queueImg("f", eid, id, name); saveEvent(e)
        }
    }
    fun setThemeImage(eid: String, bytes: ByteArray) {
        synchronized(lock) {
            val e = event(eid) ?: return
            val id = rid(); imgFile("t_$id").writeBytes(bytes)
            e.getJSONObject("theme").put("image", id); queueImg("t", eid, id, ""); saveEvent(e)
        }
    }

    // Two-way sync: push local changes, upload new images, then pull what changed on the server. Latest edit wins per event.
    fun sync() {
        try { serverVer = JSONObject(String(req("GET", "/api/v1/health").second)).optInt("version", 0) } catch (_: Exception) { }
        try { val s = json("/api/v1/settings").optString("publicUrl", ""); if (s != "" && s != pub()) p.edit().putString("pub", s).apply() } catch (_: Exception) { }
        syncWarning = ""
        pushDirty(); pushImages(); pull()
    }
    // Compare the effective source mapping, not just JSON serialization: an older
    // server can round-trip x/y/w/h/r while silently dropping slot.source.
    private fun repeatMapPreserved(local: JSONObject, remote: JSONObject): Boolean {
        val lframes = local.optJSONArray("frames") ?: return true
        val rframes = remote.optJSONArray("frames") ?: return false
        for (i in 0 until lframes.length()) {
            val lf = lframes.optJSONObject(i) ?: continue
            val ls = lf.optJSONArray("slots") ?: continue
            // Only protect custom/repeated mappings. Legacy all-unique layouts are unaffected.
            val hasRepeat = (0 until ls.length()).any { j ->
                val explicit = ls.optJSONObject(j)?.optInt("source", -1) ?: -1
                explicit >= 0 && explicit != j
            }
            if (!hasRepeat) continue
            val rf = (0 until rframes.length()).mapNotNull { rframes.optJSONObject(it) }
                .firstOrNull { it.optString("id") == lf.optString("id") } ?: return false
            val rs = rf.optJSONArray("slots") ?: return false
            if (rs.length() != ls.length()) return false
            for (j in 0 until ls.length()) {
                val localSource = ls.optJSONObject(j)?.optInt("source", -1)?.takeIf { it >= 0 } ?: j
                val remoteSource = rs.optJSONObject(j)?.optInt("source", -1)?.takeIf { it >= 0 } ?: j
                if (localSource != remoteSource) return false
            }
        }
        return true
    }

    private fun pushDirty() {
        val (arr, sent) = synchronized(lock) {
            val a = JSONArray(); val m = HashMap<String, Long>(); val o = evMap()
            for (id in dirtySet()) { val e = o.optJSONObject(id); if (e != null) { a.put(e); m[id] = e.optLong("updated") } }
            a to m
        }
        if (arr.length() == 0) return
        val (c, b) = req("POST", "/api/v1/sync/push", arr.toString().toByteArray())
        if (c != 200) throw IOException("push refused ($c)")
        val win = JSONArray(String(b))
        synchronized(lock) {
            val o = evMap(); val d = dirtySet()
            for (i in 0 until win.length()) {
                val w = win.getJSONObject(i); val id = w.getString("id"); val loc = o.optJSONObject(id)
                if (loc != null && loc.optLong("updated") != sent[id]) continue   // edited again while pushing: stays dirty
                if (loc != null && !w.optBoolean("deleted") && !repeatMapPreserved(loc, w)) {
                    syncWarning = "Server returned an event with missing/replaced photo-repeat mappings. Local layout preserved. Update/restart the backend and Windows admin."
                    continue // Keep the local event and pending sync instead of silently losing data.
                }
                if (w.optBoolean("deleted")) o.remove(id) else o.put(id, w)
                d.remove(id)
            }
            putState(o, d)
        }
    }
    private fun pushImages() {
        val a = synchronized(lock) { pending() }; val done = HashSet<String>(); var ok = true
        for (i in 0 until a.length()) {
            val q = a.getJSONObject(i); val id = q.getString("id"); val eid = q.getString("eid"); val frame = q.getString("t") == "f"
            val f = imgFile((if (frame) "f_" else "t_") + id)
            if (!f.exists()) { done.add(id); continue }
            if (!ok) continue
            val code = try {
                if (frame) post("/api/v1/events/$eid/frames", mapOf("name" to q.optString("name"), "id" to id), listOf(Triple("f", "frame.png", f.readBytes()))).first
                else post("/api/v1/events/$eid/theme-image", mapOf("id" to id), listOf(Triple("f", "theme.img", f.readBytes()))).first
            } catch (e: IOException) { 0 }
            if (code == 200 || code == 404) done.add(id) else ok = false   // 404: the event no longer exists on the server
        }
        synchronized(lock) { val cur = pending(); val n = JSONArray(); for (i in 0 until cur.length()) if (cur.getJSONObject(i).getString("id") !in done) n.put(cur.getJSONObject(i)); p.edit().putString("pimg", n.toString()).apply() }
    }
    private fun pull() {
        val j = json("/api/v1/sync/pull?since=${p.getLong("cur", 0)}")
        val st = j.optLong("serverTime", 0L)
        synchronized(lock) {
            val o = evMap(); val d = dirtySet(); val a = j.getJSONArray("events")
            for (i in 0 until a.length()) {
                val e = a.getJSONObject(i); val id = e.getString("id"); val loc = o.optJSONObject(id)
                if (loc != null && d.contains(id) && loc.optLong("updated") >= e.optLong("updated")) continue   // our newer edit wins, it will be pushed
                if (loc != null && !e.optBoolean("deleted") &&
                    (d.contains(id) || loc.optLong("updated") >= e.optLong("updated")) &&
                    !repeatMapPreserved(loc, e)) {
                    syncWarning = "Server pull dropped photo-repeat mappings. Tablet copy retained for retry."
                    d.add(id)
                    continue
                }
                if (e.optBoolean("deleted")) o.remove(id) else o.put(id, e)
                d.remove(id)
            }
            putState(o, d); p.edit().putLong("cur", j.getLong("cursor")).apply()
            if (st > 0) p.edit().putLong("off", st - System.currentTimeMillis()).apply()
        }
        for (e in events()) {
            val id = e.getString("id"); val fr = e.optJSONArray("frames")
            if (fr != null) for (i in 0 until fr.length()) { val f = fr.getJSONObject(i).getString("id"); fetch("/frames/$id/$f", "f_$f") }
            val t = e.optJSONObject("theme")?.optString("image") ?: ""
            if (t != "") fetch("/themes/$id/$t", "t_$t")
        }
    }
    private fun fetch(path: String, name: String) { val f = imgFile(name); if (f.exists()) return; val (c, b) = req("GET", path); if (c == 200) f.writeBytes(b) }

    // Returns the HTTP code and the names of the files the server confirmed it saved.
    fun post(path: String, fields: Map<String, String>, files: List<Triple<String, String, ByteArray>>): Pair<Int, List<String>> {
        val bd = "pb" + rid(); val o = ByteArrayOutputStream()
        fun w(s: String) = o.write(s.toByteArray())
        for ((k, v) in fields) w("--$bd\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n")
        for ((k, fn, b) in files) { w("--$bd\r\nContent-Disposition: form-data; name=\"$k\"; filename=\"$fn\"\r\nContent-Type: application/octet-stream\r\n\r\n"); o.write(b); w("\r\n") }
        w("--$bd--\r\n")
        val (code, body) = req("POST", path, o.toByteArray(), "multipart/form-data; boundary=$bd")
        val saved = try { val a = JSONObject(String(body)).getJSONArray("saved"); (0 until a.length()).map { a.getString(it) } } catch (_: Exception) { listOf<String>() }
        return code to saved
    }

    // ---- offline admin gallery --------------------------------------------------
    // q/<session-id> is now BOTH the retry queue and durable local photo archive.
    // The receipts in upload_state.json record which files reached the host.
    // Never delete images merely because their upload completed.
    private fun queueDir(id: String) = File(File(dir, "q"), id)
    private fun hostCacheDir(id: String) = File(File(dir, "host_cache"), id)
    private fun stateFile(d: File) = File(d, "upload_state.json")
    private fun receiptState(d: File): JSONObject = try { JSONObject(stateFile(d).readText()) } catch (_: Exception) { JSONObject() }
    private fun storeReceipt(d: File, state: JSONObject) {
        val target = stateFile(d)
        val atomic = android.util.AtomicFile(target)
        val out = atomic.startWrite()
        try { out.write(state.toString().toByteArray()); atomic.finishWrite(out) }
        catch (e: Exception) { atomic.failWrite(out); throw e }
    }
    private fun cachedMetadata(d: File): JSONObject? = try { JSONObject(File(d, "meta.json").readText()) } catch (_: Exception) { null }
    private fun localResultFiles(d: File): List<String> =
        d.listFiles()?.filter { it.isFile && resultFile.matches(it.name) }?.map { it.name }?.sorted() ?: emptyList()

    fun localSessionFile(id: String, name: String): File? {
        if (!sessionName.matches(id) || !resultFile.matches(name)) return null
        val local = File(queueDir(id), name)
        if (local.isFile) return local
        val cached = File(hostCacheDir(id), name)
        return if (cached.isFile) cached else null
    }

    fun hasLocalSession(id: String): Boolean = sessionName.matches(id) &&
        (cachedMetadata(queueDir(id)) != null || cachedMetadata(hostCacheDir(id)) != null)

    fun hasPendingUpload(id: String): Boolean = sessionName.matches(id) &&
        queueDir(id).isDirectory && !receiptState(queueDir(id)).optBoolean("complete")

    fun localSessionFiles(id: String): List<String> {
        if (!sessionName.matches(id)) return emptyList()
        return (localResultFiles(queueDir(id)) + localResultFiles(hostCacheDir(id))).distinct().sorted()
    }

    /** Download a host image/video on demand when only its session metadata is cached. */
    fun downloadSessionFile(id: String, name: String): File? {
        if (!sessionName.matches(id) || !resultFile.matches(name)) return null
        localSessionFile(id, name)?.let { return it }
        if (!enoughSpaceForCache()) return null
        val cache = hostCacheDir(id).apply { mkdirs() }
        val dest = File(cache, name)
        return if (fetchResultFile("/api/v1/sessions/$id/files/$name", dest)) dest else null
    }

    fun localSessionStatus(id: String): String {
        val queued = queueDir(id)
        if (queued.isDirectory) {
            val receipt = receiptState(queued)
            if (receipt.optBoolean("complete")) return if (localSessionFile(id, "photo.jpg") != null)
                "Uploaded · on tablet" else "Uploaded · photo needs caching"
            val saved = receipt.optJSONArray("saved") ?: JSONArray()
            val done = HashSet<String>()
            for (i in 0 until saved.length()) done.add(saved.getString(i))
            val present = localResultFiles(queued).map { it.substringBeforeLast('.') }
            val total = present.size.coerceAtLeast(1)
            val uploaded = present.count { it in done }
            val progress = "$uploaded/$total files"
            return when {
                uploadingSessionId == id -> "Uploading · $progress"
                via == "offline" -> "Waiting for host · $progress"
                else -> "Queued · $progress"
            }
        }
        val cache = hostCacheDir(id)
        if (cache.isDirectory) {
            val m = cachedMetadata(cache)
            val expected = m?.optJSONArray("files")
            val complete = expected != null && (0 until expected.length()).all { i -> File(cache, expected.getString(i)).isFile }
            return if (complete) "Stored on tablet" else "Partly cached on tablet"
        }
        return "On host"
    }

    fun localSessions(eventId: String): List<JSONObject> {
        val dirs = listOf(File(dir, "q"), File(dir, "host_cache"))
        val sessions = linkedMapOf<String, JSONObject>()
        for (parent in dirs) {
            for (d in parent.listFiles() ?: emptyArray()) {
                if (!d.isDirectory || !sessionName.matches(d.name) || d.name.startsWith(".")) continue
                val meta = cachedMetadata(d) ?: continue
                if (meta.optString("eventId") != eventId) continue
                if (sessions.containsKey(d.name)) continue
                val fileNames = localSessionFiles(d.name)
                val s = JSONObject(meta.toString())
                s.put("id", d.name)
                s.put("created", meta.optLong("created", d.lastModified()))
                s.put("files", JSONArray(fileNames))
                s.put("local", true)
                sessions[d.name] = s
            }
        }
        return sessions.values.sortedByDescending { it.optLong("created") }
    }

    /** Cancel pending upload (if any) and remove this tablet's local session copy. */
    fun removeLocalSession(id: String) {
        if (!sessionName.matches(id)) return
        synchronized(sessionIoLock) {
            queueDir(id).deleteRecursively()
            hostCacheDir(id).deleteRecursively()
        }
    }

    /** Store host-only sessions for later offline viewing. Called from a background thread. */
    fun cacheHostSessions(eventId: String, arr: JSONArray) {
        // The newest sessions are cached first. Pending local captures are never touched.
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val id = s.optString("id")
            if (!sessionName.matches(id)) continue
            val names = s.optJSONArray("files") ?: continue
            val missing = (0 until names.length()).map { names.getString(it) }
                .filter { resultFile.matches(it) && localSessionFile(id, it) == null }
            if (missing.isEmpty()) continue
            val d = hostCacheDir(id)
            d.mkdirs()
            val meta = JSONObject(s.toString()).put("eventId", eventId)
            File(d, "meta.json").writeText(meta.toString())
            cachingSessionId = id
            try {
                // Thumbnail first: cards become available offline quickly.
                val all = missing.sortedBy { when (it) { "thumb.jpg" -> 0; "photo.jpg" -> 1; else -> 2 } }
                for (name in all) {
                    if (localSessionFile(id, name) != null) continue
                    if (!enoughSpaceForCache()) return
                    if (!fetchResultFile("/api/v1/sessions/$id/files/$name", File(d, name))) break
                }
            } finally { cachingSessionId = "" }
        }
    }

    private fun enoughSpaceForCache(): Boolean =
        File(dir, "host_cache").usableSpace > MIN_FREE_BYTES && localGalleryBytes() < LOCAL_GALLERY_BUDGET

    // Stream directly to disk to avoid holding large MP4/GIF files in RAM.
    private fun fetchResultFile(path: String, destination: File): Boolean {
        val bases = when (linkMode()) {
            "internet" -> listOf(pub())
            "local" -> listOf(lan)
            else -> listOf(lan, pub())
        }.filter { it.isNotBlank() }.distinct()
        for (base in bases) {
            var conn: HttpURLConnection? = null
            val part = File(destination.parentFile, ".${destination.name}.${rid()}.part")
            try {
                conn = URL(base + path).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = if (base == lan) 1500 else 8000
                conn.readTimeout = 30000
                conn.setRequestProperty("Authorization", "Bearer " + token())
                if (conn.responseCode != 200 || conn.contentLengthLong > MAX_HOST_FILE_BYTES) return false
                var count = 0L
                conn.inputStream.use { input ->
                    part.outputStream().use { output ->
                        val buf = ByteArray(32768)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            count += n
                            if (count > MAX_HOST_FILE_BYTES || File(dir, "host_cache").usableSpace < MIN_FREE_BYTES)
                                throw IOException("Local gallery storage limit reached")
                            output.write(buf, 0, n)
                        }
                    }
                }
                if (!part.renameTo(destination)) throw IOException("Could not store downloaded session file")
                via = if (base == lan) "local" else "internet"
                return true
            } catch (_: IOException) {
                part.delete()
            } finally { conn?.disconnect() }
        }
        return false
    }

    private fun folderSize(f: File): Long = if (f.isFile) f.length()
        else f.listFiles()?.sumOf { folderSize(it) } ?: 0L
    private fun localGalleryBytes() = folderSize(File(dir, "q")) + folderSize(File(dir, "host_cache"))

    /** Local retention follows each event's host retention; never purge unsent photos. */
    fun cleanupLocalGallery() {
        synchronized(sessionIoLock) {
            val now = now()
            val complete = mutableListOf<File>()
            for (base in listOf(File(dir, "q"), File(dir, "host_cache"))) {
                for (d in base.listFiles() ?: emptyArray()) {
                    if (!d.isDirectory || !sessionName.matches(d.name)) continue
                    if (uploadingSessionId == d.name || cachingSessionId == d.name) continue
                    if (base.name == "q" && !receiptState(d).optBoolean("complete")) continue
                    val m = cachedMetadata(d) ?: continue
                    val event = event(m.optString("eventId"))
                    val days = (event?.optInt("retention", 10) ?: 10).coerceAtLeast(1)
                    val created = m.optLong("created", d.lastModified())
                    if (now - created > days * 86400000L) d.deleteRecursively()
                    else complete.add(d)
                }
            }
            // A storage budget is required on tablets with small internal storage.
            var total = localGalleryBytes()
            if (total > LOCAL_GALLERY_BUDGET || File(dir, "q").usableSpace < MIN_FREE_BYTES) {
                for (d in complete.sortedBy { cachedMetadata(it)?.optLong("created", it.lastModified()) ?: it.lastModified() }) {
                    if (total <= LOCAL_GALLERY_BUDGET && File(dir, "q").usableSpace >= MIN_FREE_BYTES) break
                    val size = folderSize(d)
                    if (d.deleteRecursively()) total -= size
                }
            }
        }
    }

    // Upload receipt makes queue files persistent instead of deleting them after upload.
    // Replaying a request after a crash is safe: host uploads replace the same file name.
    fun flush() {
        synchronized(sessionIoLock) {
            for (d in File(dir, "q").listFiles()?.filter { it.isDirectory && sessionName.matches(it.name) }?.sortedBy { it.lastModified() } ?: return) {
                val m = cachedMetadata(d) ?: continue
                val id = d.name
                val state = receiptState(d)
                if (state.optBoolean("complete")) continue
                uploadingSessionId = id
                try {
                    val ack = mutableSetOf<String>()
                    val saved = state.optJSONArray("saved") ?: JSONArray()
                    for (i in 0 until saved.length()) ack.add(saved.getString(i))
                    fun mark(name: String) {
                        if (ack.add(name)) {
                            state.put("saved", JSONArray(ack.sorted()))
                            storeReceipt(d, state)
                        }
                    }
                    val ph = File(d, "photo.jpg")
                    val th = File(d, "thumb.jpg")
                    // Legacy pending sessions had their already-uploaded files deleted.
                    if (!ph.exists() && !th.exists() && ack.isEmpty()) { mark("photo"); mark("thumb") }
                    if ("photo" !in ack || "thumb" !in ack) {
                        if (!ph.isFile || !th.isFile) return
                        val fields = mapOf("eventId" to m.getString("eventId"), "frameId" to m.getString("frameId"),
                            "rawCount" to m.optInt("rawCount", 0).toString(), "photoCount" to m.optInt("photoCount", 1).toString())
                        val (code, confirmed) = post("/api/v1/sessions/$id", fields,
                            listOf(Triple("photo", "photo.jpg", ph.readBytes()), Triple("thumb", "thumb.jpg", th.readBytes())))
                        if (code != 200 || "photo" !in confirmed || "thumb" !in confirmed) return
                        mark("photo"); mark("thumb")
                    }
                    val extra = localResultFiles(d).filter { it != "photo.jpg" && it != "thumb.jpg" }
                        .sortedBy { if (it.startsWith("photo")) 0 else if (it.startsWith("raw")) 1 else 2 }
                    for (name in extra) {
                        val field = name.substringBeforeLast('.')
                        if (field in ack) continue
                        val (code, confirmed) = post("/api/v1/sessions/$id",
                            mapOf("eventId" to m.getString("eventId")), listOf(Triple(field, name, File(d, name).readBytes())))
                        if (code != 200 || field !in confirmed) return
                        mark(field)
                    }
                    state.put("complete", true).put("completedAt", now())
                    storeReceipt(d, state)
                } finally { uploadingSessionId = "" }
            }
        }
    }
}
