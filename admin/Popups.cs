using System.Text;
using System.Text.Json.Nodes;

// Standard dialog window (system title bar), content in the same light style as the main window.
class Popup : Form
{
    public Popup(int w, int h)
    {
        FormBorderStyle = FormBorderStyle.FixedDialog; MaximizeBox = false; MinimizeBox = false; ShowInTaskbar = false; StartPosition = FormStartPosition.CenterParent;
        AutoScaleMode = AutoScaleMode.None; BackColor = Color.White; ForeColor = Theme.Text; KeyPreview = true; DoubleBuffered = true; Text = "Photobooth Admin"; ClientSize = new Size(w, h);
    }
    protected override void OnKeyDown(KeyEventArgs e) { if (e.KeyCode == Keys.Escape) { DialogResult = DialogResult.Cancel; Close(); } base.OnKeyDown(e); }
}

partial class AdminForm
{
    bool Confirm(string msg)
    {
        using var f = new Popup(U(420), U(190));
        Put(f, new Txt(msg, Sr(18), Theme.Maroon) { Wrap = true, V = StringAlignment.Near }, U(28), U(28), U(364), U(86));
        var yes = Put(f, new Pill("Yes", Sn(15), true), U(28), U(128), U(170), U(38)); yes.Click += (o, ea) => f.DialogResult = DialogResult.Yes;
        var no = Put(f, Ghost("No", Sn(15)), U(222), U(128), U(170), U(38)); no.Click += (o, ea) => f.DialogResult = DialogResult.No;
        return f.ShowDialog(this) == DialogResult.Yes;
    }
    void Info(string msg)
    {
        using var f = new Popup(U(440), U(200));
        Put(f, new Txt(msg, Sr(18), Theme.Maroon) { Wrap = true, V = StringAlignment.Near }, U(28), U(28), U(384), U(96));
        var ok = Put(f, new Pill("OK", Sn(15), true), U(135), U(140), U(170), U(38)); ok.Click += (o, ea) => f.DialogResult = DialogResult.OK;
        f.ShowDialog(this);
    }
    string Ask(string heading)
    {
        using var f = new Popup(U(480), U(210));
        Put(f, new Txt(heading, Sn(15), Theme.Maroon) { Wrap = true, V = StringAlignment.Near }, U(28), U(22), U(424), U(44));
        var fld = Put(f, new Field(Sr(18), Theme.Maroon, Color.White) { Stroke = Theme.LineStrong }, U(28), U(76), U(424), U(40)); fld.Tb.UseSystemPasswordChar = true;
        fld.Tb.KeyDown += (o, ea) => { if (ea.KeyCode == Keys.Enter) { ea.SuppressKeyPress = true; f.DialogResult = DialogResult.OK; } };
        var ok = Put(f, new Pill("OK", Sn(15), true), U(28), U(140), U(206), U(40)); ok.Click += (o, ea) => f.DialogResult = DialogResult.OK;
        var no = Put(f, Ghost("Cancel", Sn(15)), U(246), U(140), U(206), U(40)); no.Click += (o, ea) => f.DialogResult = DialogResult.Cancel;
        f.Shown += (o, ea) => fld.Tb.Focus();
        return f.ShowDialog(this) == DialogResult.OK ? fld.Tb.Text : null;
    }

    // What the tablet reads from the QR code: photobooth://pair?c=123456&a=192.168.1.20:5080&a=...&p=https%3A%2F%2Fphotos.example.com
    // c = one-time pairing code, a = addresses of this laptop on the local network (tried first), p = public address (internet).
    static string PairPayload(string code, JsonNode lanInfo)
    {
        var addrs = new List<string>();
        if (lanInfo?["addresses"] is JsonArray arr) foreach (var x in arr) addrs.Add(x.ToString());
        int Rank(string ad) => ad.Contains("//192.168.") ? 0 : ad.Contains("//10.") ? 1 : ad.Contains("//172.") ? 2 : 3;
        var good = addrs.Where(ad => !ad.Contains("//169.254.")).OrderBy(Rank).Take(4).ToList();
        var sb = new StringBuilder("photobooth://pair?c=" + code);
        foreach (var ad in good) sb.Append("&a=" + Uri.EscapeDataString(ad.Replace("http://", "")).Replace("%3A", ":"));
        var pub = lanInfo?["publicUrl"]?.ToString() ?? "";
        if (pub != "") sb.Append("&p=" + Uri.EscapeDataString(pub));
        return sb.ToString();
    }

    // "Pair new device" popup (design: Popup.pdf)
    void PairPopup()
    {
        using var f = new Popup(U(463), U(566));
        Put(f, new Txt("Pair new device", Sn(16), Theme.Maroon), U(26), U(18), U(300), U(28));
        var row = Put(f, new Card(Color.White, 6) { Stroke = Theme.LineStrong }, U(22), U(58), U(419), U(46));
        var codeTxt = Put(row, new Txt("------", Sn(20), Theme.Maroon), U(20), 0, U(160), U(44));
        var btnCode = Put(row, new Pill("New pairing code", Sn(14), true), U(419) - U(10) - U(150), U(7), U(150), U(30));
        var qr = Put(f, new QrView(), U(45), U(120), U(373), U(318));
        var hint = Put(f, new Txt("", Sn(12.5f), Theme.Maroon) { H = StringAlignment.Center }, U(22), U(446), U(419), U(24));
        var btnQr = Put(f, new Pill("New QR", Sn(18), true), U(22), U(480), U(419), U(50));
        string code = null; long expiry = 0, devCount = -1; bool busy = false, done = false;
        void UpdateHint()
        {
            if (done) return;
            int sec = (int)Math.Max(0, (expiry - Now()) / 1000);
            hint.Text = code == null ? "Getting a pairing code..." : $"Scan this on the tablet, or type the code. Valid for {sec / 60}:{sec % 60:00}";
        }
        async Task Renew(bool fresh)
        {
            if (busy) return; busy = true; qr.Loading = true;
            try
            {
                if (fresh || code == null || Now() > expiry)
                {
                    var r = await Api.J("/pair/code", "POST"); if (r == null) { hint.Text = "The server did not answer."; return; }
                    code = (string)r["code"]; expiry = Now() + 290000;
                }
                var ln = await Api.J("/lan"); if (ln != null) lan = ln;
                codeTxt.Text = code; qr.Data = PairPayload(code, lan); UpdateHint();
            }
            catch (Exception ex) { AdminLog.Write(ex); hint.Text = "Could not create the code: " + ex.Message; }
            finally { busy = false; qr.Loading = false; }
        }
        btnCode.Click += async (o, ea) => await Renew(true);
        btnQr.Click += async (o, ea) => await Renew(false);
        var tm = new System.Windows.Forms.Timer { Interval = 1000 }; int n = 0;
        tm.Tick += async (o, ea) =>
        {
            if (done || f.IsDisposed) return;
            if (code != null && Now() > expiry) { await Renew(true); return; }
            UpdateHint();
            if (++n % 2 == 0)
            {
                var a = await Api.J("/devices") as JsonArray; if (a == null) return;
                long cnt = a.Count(x => !(bool)x["revoked"]);
                if (devCount < 0) devCount = cnt;
                else if (cnt > devCount) { done = true; hint.Text = "Tablet paired!"; await LoadDevices(); await Task.Delay(1400); if (!f.IsDisposed) f.Close(); }
            }
        };
        f.Shown += async (o, ea) => { await Renew(true); tm.Start(); };
        f.FormClosed += (o, ea) => tm.Dispose();
        f.ShowDialog(this);
        if (pg == Pg.Devices) { _ = LoadDevices().ContinueWith(t => BeginInvoke(new Action(() => ShowPage(true)))); }
    }

    // QR code that opens the download page of one session
    async Task SessionQrPopup(string sid)
    {
        var ln = await Api.J("/lan"); if (ln != null) lan = ln;
        string pub = lan?["publicUrl"]?.ToString() ?? "", bas = pub;
        if (bas == "" && lan?["addresses"] is JsonArray ad && ad.Count > 0) bas = ad[0].ToString();
        if (bas == "") bas = "http://localhost:5080";
        string link = bas.TrimEnd('/') + "/d/" + sid;
        using var f = new Popup(U(440), U(590));
        Put(f, new Txt("Session QR code", Sn(16), Theme.Maroon), U(26), U(18), U(300), U(28));
        Put(f, new QrView { Data = link }, U(40), U(60), U(360), U(330));
        Put(f, new Txt(link, Sn(11.5f), Theme.Maroon) { H = StringAlignment.Center, Wrap = true, V = StringAlignment.Near }, U(26), U(402), U(388), U(40));
        if (pub == "") Put(f, new Txt("No public address is set (Server Config), so guests can only open this on the same Wi-Fi.", Sr(14), Theme.Maroon) { H = StringAlignment.Center, Wrap = true, V = StringAlignment.Near }, U(26), U(446), U(388), U(44));
        var po = Put(f, new Pill("Open download page", Sn(15), true, "link"), U(26), U(504), U(250), U(46));
        po.Click += (o, ea) => { try { System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo("http://localhost:5080/d/" + sid) { UseShellExecute = true }); } catch (Exception ex) { AdminLog.Write(ex); } };
        var pc = Put(f, Ghost("Close", Sn(15)), U(288), U(504), U(126), U(46)); pc.Click += (o, ea) => f.Close();
        f.ShowDialog(this);
    }

    // Slot editor: drag on the frame to draw photo slots
    async Task EditSlots(int fi)
    {
        if (ev == null) return;
        var fr = ev["frames"][fi]; string fid = fr["id"].ToString();
        byte[] bytes = null;
        if (pendingFrames.TryGetValue(fid, out var localFile)) bytes = File.ReadAllBytes(localFile);
        else await Busy("Loading the frame...", async () => { bytes = await Api.Bytes($"/frames/{curEid}/{fid}"); });
        if (bytes == null) { Info("The frame image could not be loaded."); return; }
        using var img = Decode(bytes, 1600);
        var wa = Screen.FromControl(this).WorkingArea; int pw = Math.Min(U(820), wa.Width - U(40)), ph = Math.Min(U(650), wa.Height - U(110));
        using var f = new Popup(pw, ph);
        Put(f, new Txt("Edit photo slots", Sn(16), Theme.Maroon), U(26), U(16), pw / 2, U(28));
        Put(f, new Txt("Drag on the frame to draw a photo slot. Slots are filled in the order drawn.", Sr(15), Theme.Maroon), U(26), U(46), pw - U(52), U(24));
        var cv = Put(f, new SlotCanvas { Img = img }, U(24), U(80), pw - U(48), ph - U(80) - U(72));
        if (fr["slots"] is JsonArray old) foreach (var sl in old) cv.Slots.Add(new SlotItem { X = D(sl["x"]), Y = D(sl["y"]), W = D(sl["w"]), H = D(sl["h"]), R = sl["r"] == null ? 0 : D(sl["r"]) });
        int by = ph - U(58), bw = (pw - U(48) - 2 * U(10)) / 3;
        var pu = Put(f, Ghost("Undo last", Sn(14)), U(24), by, bw, U(42)); pu.Click += (o, ea) => { if (cv.Slots.Count > 0) cv.Slots.RemoveAt(cv.Slots.Count - 1); cv.Invalidate(); };
        var pcl = Put(f, Ghost("Clear all", Sn(14)), U(24) + bw + U(10), by, bw, U(42)); pcl.Click += (o, ea) => { cv.Slots.Clear(); cv.Invalidate(); };
        var pd = Put(f, new Pill("Done", Sn(14), true), U(24) + 2 * (bw + U(10)), by, bw, U(42)); pd.Click += (o, ea) => f.DialogResult = DialogResult.OK;
        if (f.ShowDialog(this) != DialogResult.OK) return;
        var arr = new JsonArray();
        foreach (var sl in cv.Slots) arr.Add(new JsonObject { ["x"] = sl.X, ["y"] = sl.Y, ["w"] = sl.W, ["h"] = sl.H, ["r"] = sl.R });
        fr["slots"] = arr; ShowPage(true);   // kept in the draft; Save on the event page stores it
    }

    // Full-size photo with previous / next / save
    void ViewerPopup(string sid, List<string> files, int idx)
    {
        var wa = Screen.FromControl(this).WorkingArea; int pw = Math.Min(U(860), wa.Width - U(40)), ph = Math.Min(U(670), wa.Height - U(110));
        using var f = new Popup(pw, ph);
        var cap = Put(f, new Txt("", Sn(15), Theme.Maroon), U(26), U(14), pw / 2, U(28));
        var th = Put(f, new Thumb { Radius = 12, Contain = true }, U(20), U(52), pw - U(40), ph - U(52) - U(70));
        int cur = idx, token = 0; byte[] lastBytes = null;
        async Task LoadCur()
        {
            int my = ++token; var fn = files[cur]; cap.Text = $"{Nice(fn)}   ({cur + 1}/{files.Count})"; th.Img = null; th.Loading = true; lastBytes = null;
            var b = await Api.GetBytes($"/api/v1/sessions/{sid}/files/{fn}"); if (my != token || f.IsDisposed) return;
            var im = b == null ? null : await Task.Run(() => Decode(b, 1600)); if (my != token || f.IsDisposed) return;
            lastBytes = b; th.Loading = false; th.Img = im; th.Invalidate();
        }
        int by = ph - U(54), bw = U(150);
        var pp = Put(f, new Pill("Previous", Sn(14), true, "chevl"), U(20), by, bw, U(40)); pp.Click += async (o, ea) => { cur = (cur - 1 + files.Count) % files.Count; await LoadCur(); };
        var pn = Put(f, new Pill("Next", Sn(14), true, "chevr", true), U(20) + bw + U(10), by, bw, U(40)); pn.Click += async (o, ea) => { cur = (cur + 1) % files.Count; await LoadCur(); };
        var ps = Put(f, Ghost("Save image...", Sn(14), "clip"), pw - U(20) - U(190), by, U(190), U(40));
        ps.Click += (o, ea) =>
        {
            if (lastBytes == null) return; var fn = files[cur];
            using var d = new SaveFileDialog { FileName = fn, Filter = "Image|*" + Path.GetExtension(fn), AutoUpgradeEnabled = false, RestoreDirectory = true };
            if (d.ShowDialog(f) == DialogResult.OK) File.WriteAllBytes(d.FileName, lastBytes);
        };
        f.Shown += async (o, ea) => await LoadCur();
        f.ShowDialog(this);
    }
}
