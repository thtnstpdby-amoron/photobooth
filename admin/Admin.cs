using System.Diagnostics;
using System.Drawing.Printing;
using System.Globalization;
using System.Text;
using System.Text.Json.Nodes;

// Main window: standard Windows frame, sidebar on the left, white content area that shows one page at a time.
partial class AdminForm : Form
{
    enum Pg { Events, EventInfo, EventEdit, Gallery, GallerySessions, GallerySession, Print, Devices, Server }
    Pg pg = Pg.Events, sessBack = Pg.GallerySessions;
    string curEid = "", curSid = "";
    int evFilter;
    static readonly string[] filterNames = { "ALL Events", "Today", "This week", "This month" };

    readonly Panel sidebar, content; readonly Thumb logo;
    readonly NavItem[] nav = new NavItem[5]; readonly StatusBox status; Panel page;
    readonly Field fName, fPub, fTok, fLog;
    readonly NotifyIcon tray = new() { Icon = SystemIcons.Application, Text = "Photobooth Admin", Visible = true };
    readonly System.Windows.Forms.Timer relayTm = new() { Interval = 160 }, tickTm = new() { Interval = 3000 };
    JsonArray evs = new(), gal = new(), prints = new(), devs = new(); JsonNode ev, tunnel, lan;
    readonly Dictionary<string, (string Event, int N, long Created)> sessInfo = new();
    readonly HashSet<string> printing = new();
    bool exiting, autoPrint, tunnelOn, loadingEdit, building; int down; string lastPrints = "", lastDevs = "";
    Txt srvDesc;
    // every installed printer (list cached for a few seconds); the admin picks one on the Print page
    static List<string> printerList; static DateTime printerAt;
    static List<string> Printers()
    {
        if (printerList == null || (DateTime.Now - printerAt).TotalSeconds > 20) { try { printerList = PrinterSettings.InstalledPrinters.Cast<string>().ToList(); } catch { printerList = new List<string>(); } printerAt = DateTime.Now; }
        return printerList;
    }
    static string PrinterName { get { var s = AdminSettings.Printer; if (s != "" && Printers().Contains(s)) return s; try { return new PrinterSettings().PrinterName; } catch { return ""; } } }
    static string ThemeKind(JsonNode ej) { try { return ej["theme"]?["kind"]?.ToString() ?? ""; } catch { return ""; } }
    BusyOverlay overlay; int busyActive;
    // event editor draft: edited here, stored on the server only when Save is pressed
    string draftId = "", evBase = "", pendingTheme, pendingThemeId; bool draftNew; readonly Dictionary<string, string> pendingFrames = new();

    // ---- small helpers (all sizes are design units, converted with U)
    static int U(float v) => Theme.U(v);
    static Font Sr(float px) => Theme.SerifPx(px);
    static Font SrB(float px) => Theme.SerifBoldPx(px);
    static Font Sn(float px) => Theme.SansPx(px);
    static T Put<T>(Control p, T c, int x, int y, int w, int h) where T : Control { c.SetBounds(x, y, w, h); p.Controls.Add(c); return c; }
    static Txt Lbl(Control p, string s, Font f, Color c, int x, int y, int w, int h, StringAlignment ha = StringAlignment.Near) => Put(p, new Txt(s, f, c) { H = ha }, x, y, w, h);
    static double D(JsonNode n) => double.Parse(n.ToJsonString(), CultureInfo.InvariantCulture);
    static long L(JsonNode n) => n == null ? 0 : (long)D(n);
    static long Now() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
    static DateTime DT(long ms) => DateTimeOffset.FromUnixTimeMilliseconds(ms).LocalDateTime;
    static string Stamp(long ms) => DT(ms).ToString("dd/MM/yyyy HH:mm", CultureInfo.InvariantCulture);
    static string LongDate(long ms) => DT(ms).ToString("d MMMM yyyy", CultureInfo.InvariantCulture);
    static long EvDate(JsonNode ej) { var c = L(ej["created"]); return c > 0 ? c : L(ej["updated"]); }
    static string ThemeImg(JsonNode ej) { try { return ej["theme"]?["image"]?.ToString() ?? ""; } catch { return ""; } }
    static string Nice(string f) => f.StartsWith("photo") ? "Framed photo " + (f.Length > 9 ? f.Substring(5, 1) : "1") : f.StartsWith("raw") ? "Raw photo " + f.Substring(3, 1) : f.StartsWith("gif") ? "GIF" : f.StartsWith("live") ? "Live photo" : f;
    static string LogPath => Path.Combine(Path.GetTempPath(), "photobooth-admin.log");

    void AClick(Control c, Func<Task> a) { c.Click += async (s, ea) => { try { await a(); } catch (Exception ex) { AdminLog.Write(ex); Info("Something went wrong: " + ex.Message); } }; }
    void SClick(Control c, Action a) { c.Click += (s, ea) => { try { a(); } catch (Exception ex) { AdminLog.Write(ex); Info("Something went wrong: " + ex.Message); } }; }
    // whole card is clickable (children too), except the small round / pill buttons inside it
    void Clickable(Control c, Func<Task> a)
    {
        c.Cursor = Cursors.Hand; AClick(c, a);
        foreach (Control k in c.Controls) if (!(k is Circ) && !(k is Pill)) Clickable(k, a);
    }
    static Pill Ghost(string text, Font f, string icon = null) => new Pill(text, f, false, icon);

    public AdminForm()
    {
        Text = "Photobooth Admin"; AutoScaleMode = AutoScaleMode.None; BackColor = Theme.White; ForeColor = Theme.Text;
        StartPosition = FormStartPosition.CenterScreen; DoubleBuffered = true;
        ClientSize = new Size(U(1200), U(638)); MinimumSize = new Size(U(900), U(540));
        content = new Panel { Dock = DockStyle.Fill, BackColor = Theme.White, Padding = new Padding(U(8), U(20), U(8), U(8)) };
        sidebar = new Panel { Dock = DockStyle.Left, Width = U(212), BackColor = Theme.Side };
        Controls.Add(content); Controls.Add(sidebar);
        sidebar.Paint += (s, ea) => { using var pen = new Pen(Theme.Line); ea.Graphics.DrawLine(pen, sidebar.Width - 1, 0, sidebar.Width - 1, sidebar.Height); };
        logo = new Thumb { Radius = 0, Contain = true, Placeholder = Color.Empty, Img = Theme.Logo }; sidebar.Controls.Add(logo);

        string[] navText = { "Events", "Gallery", "Print", "Devices", "Server Config" }, navIco = { "calendar", "image", "print", "tablet", "server" };
        Pg[] navPg = { Pg.Events, Pg.Gallery, Pg.Print, Pg.Devices, Pg.Server };
        for (int i = 0; i < 5; i++)
        {
            var target = navPg[i]; nav[i] = new NavItem(navText[i], navIco[i], Sn(15.5f)); sidebar.Controls.Add(nav[i]); AClick(nav[i], () => Go(target));
        }
        status = new StatusBox(); sidebar.Controls.Add(status);
        page = new Panel { BackColor = Theme.White, AutoScroll = true, Dock = DockStyle.Fill }; content.Controls.Add(page);
        overlay = new BusyOverlay { Dock = DockStyle.Fill }; content.Controls.Add(overlay);

        fName = new Field(Sr(16), Theme.Text, Color.White); fName.Tb.TextChanged += (s, ea) => { if (!loadingEdit && ev != null && pg == Pg.EventEdit) { ev["name"] = fName.Text; RefreshUnsaved(); } };
        fPub = new Field(Sr(16), Theme.Text, Color.White); fPub.Tb.PlaceholderText = "https://photos.example.com";
        fTok = new Field(Sr(16), Theme.Text, Color.White); fTok.Tb.PlaceholderText = "Token here... (leave empty to keep the saved token)"; fTok.Tb.UseSystemPasswordChar = true;
        fLog = new Field(Sn(12), Theme.Text, Color.White, true);

        sidebar.SizeChanged += (s, ea) => LayoutAll();
        content.SizeChanged += (s, ea) => { relayTm.Stop(); relayTm.Start(); };
        relayTm.Tick += (s, ea) => { relayTm.Stop(); ShowPage(true); };
        tickTm.Tick += async (s, ea) => await Tick(); tickTm.Start();
        Application.AddMessageFilter(new WheelFilter(this));
        LayoutAll(); UpdateNav();

        tray.ContextMenuStrip = new ContextMenuStrip();
        tray.ContextMenuStrip.Items.Add("Open", null, (s, ea) => ShowMe()); tray.ContextMenuStrip.Items.Add("Exit (server keeps running)", null, (s, ea) => Quit()); tray.ContextMenuStrip.Items.Add("Stop server and exit", null, (s, ea) => { StopServer(); Quit(); });
        tray.DoubleClick += (s, ea) => ShowMe();
        FormClosing += (s, ea) => { if (!exiting && ea.CloseReason == CloseReason.UserClosing) { ea.Cancel = true; Hide(); } };
        Shown += async (s, ea) => await Boot();
    }

    void LayoutAll()
    {
        int W = sidebar.ClientSize.Width, H = sidebar.ClientSize.Height; if (H < 100) return;
        logo.SetBounds(U(14), U(24), W - U(28), U(40));
        int nx = U(8), nw = W - U(16), nh = U(32);
        for (int i = 0; i < 3; i++) nav[i].SetBounds(nx, U(84) + i * U(36), nw, nh);
        status.SetBounds(U(10), H - U(14) - U(108), W - U(20), U(108));
        nav[3].SetBounds(nx, status.Top - U(80), nw, nh); nav[4].SetBounds(nx, status.Top - U(42), nw, nh);
    }
    void UpdateNav()
    {
        int sel = pg switch { Pg.Events or Pg.EventInfo or Pg.EventEdit => 0, Pg.Gallery or Pg.GallerySessions or Pg.GallerySession => 1, Pg.Print => 2, Pg.Devices => 3, _ => 4 };
        for (int i = 0; i < 5; i++) { nav[i].Selected = i == sel; nav[i].Invalidate(); }
    }

    // the mouse wheel scrolls the page under the pointer (custom controls cannot take the focus)
    class WheelFilter : IMessageFilter
    {
        readonly AdminForm f; public WheelFilter(AdminForm form) { f = form; }
        public bool PreFilterMessage(ref Message m)
        {
            if (m.Msg != 0x020A || f.IsDisposed || Form.ActiveForm != f || f.page == null || !f.page.Visible) return false;
            var pos = Cursor.Position; var rect = f.page.RectangleToScreen(f.page.ClientRectangle); if (!rect.Contains(pos)) return false;
            Control c = f.page; Control k;
            while ((k = c.GetChildAtPoint(c.PointToClient(pos))) != null) c = k;
            if (c is TextBox tb && tb.Multiline) return false;
            int delta = (short)((m.WParam.ToInt64() >> 16) & 0xFFFF);
            int cur = -f.page.AutoScrollPosition.Y, max = Math.Max(0, f.page.DisplayRectangle.Height - f.page.ClientSize.Height);
            f.page.AutoScrollPosition = new Point(0, Math.Max(0, Math.Min(max, cur - delta / 120 * U(70))));
            return true;
        }
    }

    // ---- server process (normal hidden background program started by this app, no Windows service)
    static string ServerExe => Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "..", "Photobooth.exe"));
    static void StartServer()
    {
        try
        {
            if (!File.Exists(ServerExe) || Process.GetProcessesByName("Photobooth").Length > 0) return;
            Process.Start(new ProcessStartInfo(ServerExe) { WorkingDirectory = Path.GetDirectoryName(ServerExe), UseShellExecute = false, CreateNoWindow = true, WindowStyle = ProcessWindowStyle.Hidden });
        }
        catch (Exception ex) { AdminLog.Write(ex); }
    }
    static void StopServer() { try { foreach (var p in Process.GetProcessesByName("Photobooth")) p.Kill(true); } catch (Exception ex) { AdminLog.Write(ex); } }
    // Optional, on demand: removes an old Photobooth Windows service and opens the firewall (asks for administrator permission).
    async Task<bool> FixWindowsSetup()
    {
        var cmd = Path.Combine(Path.GetTempPath(), "photobooth-service-setup.cmd"); var log = Path.Combine(Path.GetTempPath(), "photobooth-service-setup.log");
        var lines = new[]
        {
            "sc stop PhotoboothServer", "sc delete PhotoboothServer",
            "netsh advfirewall firewall delete rule name=\"Photobooth\"", "netsh advfirewall firewall delete rule name=\"Photobooth discovery\"",
            "netsh advfirewall firewall add rule name=\"Photobooth\" dir=in action=allow protocol=TCP localport=5080 profile=private",
            "netsh advfirewall firewall add rule name=\"Photobooth discovery\" dir=in action=allow protocol=UDP localport=5081 profile=private",
        };
        File.WriteAllText(cmd, "@echo off\r\n" + string.Join("\r\n", lines.Select(l => $"echo {l} >> \"{log}\"\r\n{l} >> \"{log}\" 2>&1")) + "\r\n");
        try
        {
            using var p = Process.Start(new ProcessStartInfo(cmd) { Verb = "runas", UseShellExecute = true, WindowStyle = ProcessWindowStyle.Hidden });
            if (p != null) await Task.Run(() => p.WaitForExit(60000));
        }
        catch (Exception ex) { Info("Cancelled or failed: " + ex.Message); return false; }
        return true;
    }
    void Quit() { exiting = true; tray.Visible = false; Application.Exit(); }
    void ShowMe() { Show(); WindowState = FormWindowState.Normal; Activate(); }
    string Pick(string filter) { using var d = new OpenFileDialog { Filter = filter, AutoUpgradeEnabled = false, RestoreDirectory = true, InitialDirectory = Environment.GetFolderPath(Environment.SpecialFolder.Desktop) }; return d.ShowDialog(this) == DialogResult.OK ? d.FileName : null; }

    async Task Boot()
    {
        overlay.ShowMsg("Starting the Photobooth server...");
        string st = null;
        try { st = await Api.H.GetStringAsync("/api/v1/admin/state"); } catch { }
        if (st == null)
        {
            StartServer();
            for (var i = 0; i < 40 && st == null; i++) { await Task.Delay(1000); try { st = await Api.H.GetStringAsync("/api/v1/admin/state"); } catch { } }
            if (st == null) { Info("The Photobooth server did not start. Try starting Photobooth.exe in the install folder by hand to see its error."); Quit(); return; }
        }
        if (!(bool)JsonNode.Parse(st)["setup"])
        {
            overlay.HideNow(); string p; do { p = Ask("First run: choose an admin password (8+ characters)"); if (p == null) { Quit(); return; } } while (p.Length < 8);
            await Api.H.PostAsync("/api/v1/admin/setup", Api.C(new { password = p })); Api.Save(p); await Api.Login(p);
        }
        else
        {
            var pw = Api.Saved();
            overlay.ShowMsg("Signing in...");
            while (pw == "" || await Api.Login(pw) != 200) { overlay.HideNow(); pw = Ask("Admin password"); if (pw == null) { Quit(); return; } }
            Api.Save(pw);
        }
        overlay.ShowMsg("Loading...");
        var cfg = await Api.J("/settings"); if (cfg != null) fPub.Text = cfg["publicUrl"]?.ToString() ?? "";
        await LoadServer(); await Tick(); overlay.HideNow(); await Go(Pg.Events);
        if (Environment.GetCommandLineArgs().Contains("--minimized")) Hide();
    }

    // ---- data
    async Task LoadServer()
    {
        var tn = await Api.J("/tunnel"); if (tn != null) { tunnel = tn; tunnelOn = (bool)tn["enabled"]; }
        var ln = await Api.J("/lan"); if (ln != null) lan = ln;
    }
    async Task LoadEvents() { var a = await Api.J("/events") as JsonArray; if (a != null) evs = a; }
    async Task LoadGallery()
    {
        var a = await Api.J("/gallery") as JsonArray; if (a == null) return; gal = a; sessInfo.Clear();
        foreach (var ej in gal)
        {
            var sess = ej["sessions"].AsArray(); int n = sess.Count, i = 0;
            foreach (var sj in sess) { sessInfo[sj["id"].ToString()] = (ej["name"].ToString(), n - i, L(sj["created"])); i++; }
        }
    }
    JsonNode FindEv(string id) => evs.FirstOrDefault(x => x != null && x["id"].ToString() == id);
    JsonArray GalSessions(string eid) { var g = gal.FirstOrDefault(x => x != null && x["id"].ToString() == eid); return g == null ? new JsonArray() : g["sessions"].AsArray(); }
    JsonNode FindSession(string sid)
    {
        foreach (var ej in gal) foreach (var sj in ej["sessions"].AsArray()) if (sj["id"].ToString() == sid) return sj;
        return null;
    }
    IEnumerable<JsonNode> EventList()
    {
        long age(JsonNode x) => Now() - EvDate(x);
        return evs.Where(x => x != null).Where(x => evFilter switch { 1 => age(x) < 86400000L, 2 => age(x) < 7 * 86400000L, 3 => age(x) < 30 * 86400000L, _ => true }).OrderByDescending(x => EvDate(x));
    }
    async Task LoadPrints() { var a = await Api.J("/prints") as JsonArray; if (a != null) prints = a; }
    async Task LoadDevices()
    {
        var a = await Api.J("/devices") as JsonArray; if (a == null) return; devs = a;
        var act = devs.Where(x => !(bool)x["revoked"]).ToList(); var loc = act.Count(x => Conn(x) == "Local network");
        status.Set(2, $"{loc} Connected", 0);
    }
    static string Conn(JsonNode d)
    {
        if ((bool)d["revoked"]) return "revoked"; var seen = L(d["seen"]); if (seen == 0) return "never connected";
        if (Now() - seen > 45000) return "offline";
        return d["link"]?.ToString() == "local" ? "Local network" : "Internet";
    }

    async Task Tick()
    {
        try
        {
            var up = await Api.Bytes("/api/v1/health") != null; status.Set(0, up ? "Up" : "Down", up ? 1 : 2);
            if (up) down = 0; else if (++down >= 2) { down = 0; StartServer(); }
            var t = await Api.J("/tunnel");
            if (t != null)
            {
                tunnel = t; tunnelOn = (bool)t["enabled"]; var s = t["state"].ToString();
                if (!tunnelOn) status.Set(1, "Off", 0); else if (s == "online") status.Set(1, "Online", 1); else if (s.StartsWith("error")) status.Set(1, "Error", 2); else status.Set(1, s.Length > 0 ? char.ToUpper(s[0]) + s.Substring(1) : s, 0);
                UpdateServerLive();
            }
            status.Set(3, PrinterName == "" ? "No printer" : PrinterName, 0);
            await LoadPrints(); await LoadDevices();
            var ps = prints.ToJsonString() + string.Join(",", printing); if (ps != lastPrints) { lastPrints = ps; if (pg == Pg.Print && !building) ShowPage(true); }
            var ds = string.Join("|", devs.Select(x => x["id"] + "," + Conn(x) + "," + x["name"])); if (ds != lastDevs) { lastDevs = ds; if (pg == Pg.Devices && !building) ShowPage(true); }
            if (autoPrint) foreach (var pj in prints.ToList()) if (pj["status"].ToString() == "queued") await PrintJob(pj);
        }
        catch (Exception ex) { AdminLog.Write(ex); }
    }
    async Task PrintJob(JsonNode j)
    {
        var id = j["id"].ToString(); if (j["status"].ToString() != "queued" || !printing.Add(id)) return;
        try
        {
            var b = await Api.Bytes($"/f/{j["sessionId"]}/{(j["file"]?.ToString() ?? "photo.jpg")}"); if (b == null) throw new Exception("photo is missing");
            using var img = Image.FromStream(new MemoryStream(b));
            var pd = new PrintDocument(); pd.PrinterSettings.PrinterName = PrinterName; if (!pd.PrinterSettings.IsValid) throw new Exception("printer \"" + PrinterName + "\" is not available"); pd.PrinterSettings.Copies = (short)D(j["copies"]); pd.DefaultPageSettings.Landscape = img.Width > img.Height;
            pd.PrintPage += (s, ea) => { var a = ea.PageBounds; var sc = Math.Min((float)a.Width / img.Width, (float)a.Height / img.Height); var w = img.Width * sc; var h = img.Height * sc; ea.Graphics.DrawImage(img, a.X + (a.Width - w) / 2, a.Y + (a.Height - h) / 2, w, h); };
            pd.Print(); await Api.J($"/prints/{id}/done", "POST");
        }
        catch (Exception ex) { status.Set(3, "Print failed: " + ex.Message, 2); }
        finally { printing.Remove(id); await LoadPrints(); if (pg == Pg.Print && !building) ShowPage(true); }
    }

    // ---- navigation
    // Shows the loading screen when work takes longer than a moment (so quick loads do not flash).
    async Task Busy(string msg, Func<Task> work)
    {
        busyActive++;
        try
        {
            var wt = work(); var delay = Task.Delay(200);
            if (await Task.WhenAny(wt, delay) != wt) overlay.ShowMsg(msg);
            await wt;
        }
        finally { if (--busyActive == 0) overlay.HideNow(); }
    }
    async Task Go(Pg p, string eid = null, string sid = null)
    {
        if (pg == Pg.EventEdit && !(p == Pg.EventEdit && (eid == null || eid == curEid)))
        {
            if (EvDirty() && !Confirm("Leave without saving? Your changes will be lost.")) return;
            DiscardDraft();
        }
        await Busy("Loading...", () => GoCore(p, eid, sid));
    }
    async Task GoCore(Pg p, string eid, string sid)
    {
        if (eid != null) curEid = eid; if (sid != null) curSid = sid;
        pg = p;
        if (p is Pg.Events or Pg.EventInfo or Pg.EventEdit) await LoadEvents();
        if (p is Pg.EventInfo or Pg.Gallery or Pg.GallerySessions or Pg.GallerySession or Pg.Print) await LoadGallery();
        if (p == Pg.EventEdit) { if (draftId != curEid) OpenDraft(curEid); loadingEdit = true; fName.Text = ev?["name"]?.ToString() ?? ""; loadingEdit = false; }
        if (p == Pg.Print) await LoadPrints();
        if (p == Pg.Devices) await LoadDevices();
        if (p == Pg.Server) await LoadServer();
        UpdateNav(); ShowPage(false);
    }
    // ---- event editor: the event is edited as a copy. Nothing reaches the server until Save is pressed.
    bool EvDirty() => pg == Pg.EventEdit && ev != null && ev.ToJsonString() != evBase;
    void OpenDraft(string id)
    {
        pendingTheme = null; pendingThemeId = null; pendingFrames.Clear(); draftNew = false;
        var src = FindEv(id); if (src == null) { ev = null; draftId = ""; evBase = ""; return; }
        ev = JsonNode.Parse(src.ToJsonString()); evBase = ev.ToJsonString(); draftId = id;
    }
    void DiscardDraft() { ev = null; draftId = ""; evBase = ""; draftNew = false; pendingTheme = null; pendingThemeId = null; pendingFrames.Clear(); }
    async Task SaveEvent()
    {
        if (ev == null) return;
        var nm = fName.Text.Trim(); if (nm == "") nm = "Event"; ev["name"] = nm; ev["updated"] = Now();
        string eid = ev["id"].ToString(); bool ok = true;
        await Busy("Saving the event...", async () =>
        {
            if (await Api.J("/events/" + eid, "PUT", ev) == null) { ok = false; Info("Saving failed. Is the server running?"); return; }
            foreach (var kv in pendingFrames.ToList())
            {
                if (!ev["frames"].AsArray().Any(fj => fj["id"].ToString() == kv.Key)) { pendingFrames.Remove(kv.Key); continue; }
                if (await Api.Upload($"/events/{eid}/frames", kv.Value, new() { ["name"] = Path.GetFileName(kv.Value), ["id"] = kv.Key })) pendingFrames.Remove(kv.Key); else ok = false;
            }
            if (pendingTheme != null)
            {
                if (ThemeImg(ev) != pendingThemeId) pendingTheme = null;
                else if (await Api.Upload($"/events/{eid}/theme-image", pendingTheme, new() { ["id"] = pendingThemeId })) pendingTheme = null; else ok = false;
            }
            if (!ok) { Info("The event was saved, but a file could not be uploaded. Press Save again to retry."); return; }
            await LoadEvents(); OpenDraft(eid); loadingEdit = true; fName.Text = ev?["name"]?.ToString() ?? ""; loadingEdit = false;
        });
        ShowPage(true);
    }

    // images: fetched once, shared, decoded off the UI thread, cropped by the Thumb control
    readonly Dictionary<string, Image> imgCache = new(); readonly Dictionary<string, Task<Image>> imgFlight = new();
    void Img(Thumb t, string key, Func<Task<byte[]>> fetch, int max = 480) { if (!imgCache.ContainsKey(key)) t.Loading = true; _ = ImgAsync(t, key, fetch, max); }
    async Task ImgAsync(Thumb t, string key, Func<Task<byte[]>> fetch, int max)
    {
        try
        {
            if (!imgCache.TryGetValue(key, out var im))
            {
                if (!imgFlight.TryGetValue(key, out var task)) { task = FetchImg(key, fetch, max); imgFlight[key] = task; }
                im = await task; if (im == null) imgFlight.Remove(key);
            }
            if (!t.IsDisposed) { t.Loading = false; if (im != null) { t.Img = im; t.Invalidate(); } }
        }
        catch (Exception ex) { AdminLog.Write(ex); }
    }
    async Task<Image> FetchImg(string key, Func<Task<byte[]>> fetch, int max)
    {
        try { var b = await fetch(); if (b == null) return null; var bmp = await Task.Run(() => Decode(b, max)); if (bmp != null) imgCache[key] = bmp; return bmp; }
        finally { imgFlight.Remove(key); }
    }
    static Image Decode(byte[] b, int max)
    {
        try
        {
            using var ms = new MemoryStream(b); using var src = Image.FromStream(ms);
            float sc = Math.Min(1f, (float)max / Math.Max(src.Width, src.Height)); int w = Math.Max(1, (int)(src.Width * sc)), h = Math.Max(1, (int)(src.Height * sc));
            var bmp = new Bitmap(w, h);
            using (var g = Graphics.FromImage(bmp)) { g.InterpolationMode = System.Drawing.Drawing2D.InterpolationMode.HighQualityBicubic; g.DrawImage(src, 0, 0, w, h); }
            return bmp;
        }
        catch { return null; }
    }
}
