using System.Text.Json.Nodes;

// One builder per screen of the design. Each returns the height of its content (for the scroll area).
partial class AdminForm
{
    void ClearPage()
    {
        page.AutoScrollMinSize = new Size(0, 0);
        foreach (var f in new[] { fName, fPub, fTok, fLog }) f.Parent?.Controls.Remove(f);
        foreach (Control c in page.Controls.Cast<Control>().ToList()) { page.Controls.Remove(c); c.Dispose(); }
        srvDesc = null; unsaved = null;
    }
    void ShowPage(bool keep)
    {
        if (page == null || page.IsDisposed || building) return;
        building = true;
        try
        {
            int sy = keep ? -page.AutoScrollPosition.Y : 0;
            page.SuspendLayout(); ClearPage();
            int w = page.ClientSize.Width - 2 * U(10); if (w < U(300)) return;
            int h = Build(w);
            if (h > page.ClientSize.Height) { ClearPage(); w -= SystemInformation.VerticalScrollBarWidth; h = Build(w); }
            page.AutoScrollMinSize = new Size(0, h); page.ResumeLayout();
            page.AutoScrollPosition = new Point(0, sy);
        }
        catch (Exception ex) { AdminLog.Write(ex); }
        finally { building = false; }
    }
    int Build(int w)
    {
        switch (pg)
        {
            case Pg.Events: return BuildEvents(w);
            case Pg.EventInfo: return BuildEventInfo(w);
            case Pg.EventEdit: return BuildEventEdit(w);
            case Pg.Gallery: return BuildGallery(w);
            case Pg.GallerySessions: return BuildGallerySessions(w);
            case Pg.GallerySession: return BuildGallerySession(w);
            case Pg.Print: return BuildPrint(w);
            case Pg.Devices: return BuildDevices(w);
            default: return BuildServer(w);
        }
    }

    // ---- shared pieces
    Txt unsaved;
    void RefreshUnsaved() { if (unsaved != null && !unsaved.IsDisposed) unsaved.Visible = EvDirty(); }
    static Card Cd() => new Card(Color.White, 6) { Stroke = Theme.Line };
    Txt PageTitle(string text, int w, Func<Task> back = null)
    {
        var t = new Txt(text, Sn(21), Theme.Maroon);
        int tw = Theme.Measure(text, t.Font) + U(44);
        if (back != null) { t.Icon = "chevl"; AClick(t, back); t.Cursor = Cursors.Hand; }
        return Put(page, t, U(10), U(6), Math.Min(w / 2, tw), U(30));
    }
    static int Cols(int w) => w >= U(760) ? 4 : w >= U(540) ? 3 : 2;
    static int CardH(int cw) => U(10) + (int)((cw - U(20)) * 0.56f) + U(67);
    void AddFilter(int w, int reserve = 0)
    {
        var t = new Txt(filterNames[evFilter], Sn(15), Theme.Maroon) { Icon = "filter", Cursor = Cursors.Hand };
        t.FitWidth(); t.Height = U(26); t.Left = U(10) + w - reserve - t.Width; t.Top = U(8); page.Controls.Add(t);
        t.Click += (s, ea) =>
        {
            var m = new ContextMenuStrip { Font = Sn(13) };
            for (int i = 0; i < filterNames.Length; i++) { int k = i; m.Items.Add(filterNames[i], null, (o, ea2) => { evFilter = k; ShowPage(true); }); }
            m.Show(Cursor.Position);
        };
    }
    // Card used for events and gallery events: picture, title, date and optional edit/delete buttons.
    Card EventCard(int x, int y, int cw, JsonNode ej, bool gallery, bool buttons)
    {
        int pad = U(10), imgH = (int)((cw - 2 * pad) * 0.56f);
        var c = Put(page, Cd(), x, y, cw, CardH(cw));
        var th = Put(c, new Thumb { Radius = 4 }, pad, pad, cw - 2 * pad, imgH);
        string eid = ej["id"].ToString(), iid = ThemeImg(ej), nm = ej["name"].ToString();
        if (ThemeKind(ej) == "video") th.Badge = "play"; else if (iid != "") Img(th, "t:" + eid + ":" + iid, () => Api.Bytes($"/themes/{eid}/{iid}"));
        Lbl(c, gallery ? nm + " Galleries" : nm, SrB(18), Theme.Text, U(16), pad + imgH + U(9), cw - U(24), U(26));
        Lbl(c, LongDate(EvDate(ej)), Sr(14.5f), Theme.Sub, U(16), pad + imgH + U(35), cw - U(24) - (buttons ? U(72) : 0), U(22));
        if (buttons)
        {
            int d = U(28), by = pad + imgH + U(28);
            var ed = Put(c, new Circ("edit"), cw - U(16) - 2 * d - U(4), by, d, d); AClick(ed, () => Go(Pg.EventEdit, eid));
            var de = Put(c, new Circ("trash"), cw - U(16) - d, by, d, d); AClick(de, () => DeleteEvent(eid));
        }
        Func<Task> go = () => gallery ? Go(Pg.GallerySessions, eid) : Go(Pg.EventInfo, eid);
        Clickable(c, go);
        return c;
    }
    Card SessionCard(int x, int y, int cw, JsonNode sj, bool buttons, Pg back)
    {
        int pad = U(10), imgH = (int)((cw - 2 * pad) * 0.56f);
        string sid = sj["id"].ToString(); long created = L(sj["created"]);
        var c = Put(page, Cd(), x, y, cw, CardH(cw));
        var th = Put(c, new Thumb { Radius = 4 }, pad, pad, cw - 2 * pad, imgH);
        Img(th, "s:" + sid, () => Api.GetBytes($"/api/v1/sessions/{sid}/files/thumb.jpg"));
        int n = sessInfo.TryGetValue(sid, out var si) ? si.N : 0;
        Lbl(c, "Session " + (n > 0 ? n.ToString() : sid.Substring(0, 4)), SrB(18), Theme.Text, U(16), pad + imgH + U(9), cw - U(24), U(26));
        Lbl(c, buttons ? Stamp(created) : LongDate(created), Sr(14.5f), Theme.Sub, U(16), pad + imgH + U(35), cw - U(24) - (buttons ? U(72) : 0), U(22));
        Func<Task> open = () => { sessBack = back; return Go(Pg.GallerySession, null, sid); };
        if (buttons)
        {
            int d = U(28), by = pad + imgH + U(28);
            var ed = Put(c, new Circ("edit"), cw - U(16) - 2 * d - U(4), by, d, d); AClick(ed, open);
            var de = Put(c, new Circ("trash"), cw - U(16) - d, by, d, d); AClick(de, () => DeleteSession(sid, back));
        }
        Clickable(c, open);
        return c;
    }
    async Task DeleteEvent(string eid) { if (!Confirm("Delete this event?")) return; await Busy("Deleting the event...", async () => { await Api.J("/events/" + eid, "DELETE"); }); await Go(Pg.Events); }
    async Task DeleteSession(string sid, Pg back) { if (!Confirm("Delete this session and all its photos?")) return; await Busy("Deleting the session...", async () => { await Api.J("/sessions/" + sid, "DELETE"); }); await Go(back); }
    int SessionGrid(int w, int y0, JsonArray sess, bool buttons, Pg back)
    {
        int gap = U(13), cols = Cols(w), cw = (w - gap * (cols - 1)) / cols, ch = CardH(cw), i = 0;
        if (sess.Count == 0) { Lbl(page, "No photo sessions yet.", Sr(18), Theme.Maroon, U(14), y0 + U(6), w, U(28)); return y0 + U(50); }
        foreach (var sj in sess) { SessionCard(U(10) + (i % cols) * (cw + gap), y0 + (i / cols) * (ch + gap), cw, sj, buttons, back); i++; }
        return y0 + ((i + cols - 1) / cols) * (ch + gap) + U(8);
    }

    // ---- Events (design 1)
    int BuildEvents(int w)
    {
        PageTitle("Events", w);
        var nb = new Pill("New event", Sn(14), true, "plus"); nb.SetBounds(0, U(4), 10, U(30)); nb.Fit(U(16)); nb.Left = U(10) + w - nb.Width; page.Controls.Add(nb); AClick(nb, NewEvent);
        AddFilter(w, nb.Width + U(14));
        int gap = U(13), cols = Cols(w), cw = (w - gap * (cols - 1)) / cols, ch = CardH(cw), y0 = U(56), i = 0;
        foreach (var ej in EventList().ToList()) { EventCard(U(10) + (i % cols) * (cw + gap), y0 + (i / cols) * (ch + gap), cw, ej, false, true); i++; }
        var nc = Put(page, Cd(), U(10) + (i % cols) * (cw + gap), y0 + (i / cols) * (ch + gap), cw, ch);
        int d = U(46); Put(nc, new IconView("pluscircle", Theme.Text), (cw - d) / 2, ch / 2 - d + U(6), d, d);
        Lbl(nc, "New Event", SrB(18), Theme.Text, 0, ch / 2 + U(10), cw, U(26), StringAlignment.Center);
        Clickable(nc, NewEvent); i++;
        return y0 + ((i + cols - 1) / cols) * (ch + gap) + U(8);
    }
    async Task NewEvent()
    {
        var id = Guid.NewGuid().ToString("N");
        ev = new JsonObject { ["id"] = id, ["name"] = "New event", ["theme"] = new JsonObject(), ["frames"] = new JsonArray(), ["shots"] = 4, ["countdown"] = 3, ["retention"] = 10, ["copies"] = 1, ["updated"] = Now(), ["deleted"] = false };
        evBase = ev.ToJsonString(); draftId = id; draftNew = true; pendingTheme = null; pendingThemeId = null; pendingFrames.Clear();
        await Go(Pg.EventEdit, id);   // nothing is sent to the server until Save is pressed
    }
    // ---- Event information (design 2)
    int BuildEventInfo(int w)
    {
        var ej = FindEv(curEid);
        PageTitle("Events", w, () => Go(Pg.Events)); if (ej == null) return U(80);
        int x0 = U(10), y = U(56), pad = U(10), hh = U(124); string eid = curEid, iid = ThemeImg(ej);
        var hc = Put(page, Cd(), x0, y, w, hh);
        int tw = Math.Min(U(186), w / 4); var th = Put(hc, new Thumb { Radius = 4 }, pad, pad, tw, hh - 2 * pad - U(4));
        if (ThemeKind(ej) == "video") th.Badge = "play"; else if (iid != "") Img(th, "t:" + eid + ":" + iid, () => Api.Bytes($"/themes/{eid}/{iid}"));
        int tx = pad + tw + U(16);
        Lbl(hc, ej["name"].ToString(), SrB(20), Theme.Text, tx, U(12), w - tx - U(150), U(28));
        Lbl(hc, LongDate(EvDate(ej)), Sr(15), Theme.Sub, tx, U(42), w - tx - U(150), U(24));
        int d = U(28), by = hh - U(15) - d - U(4);
        var ed = Put(hc, new Circ("edit"), tx, by, d, d); AClick(ed, () => Go(Pg.EventEdit, eid));
        var de = Put(hc, new Circ("trash"), tx + d + U(6), by, d, d); AClick(de, () => DeleteEvent(eid));
        string[] ic = { "clock", "camera", "cloud" }, tx2 = { D(ej["countdown"]) + "s", D(ej["shots"]) + "x", D(ej["retention"]) + "d" };
        for (int i = 0; i < 3; i++)
        {
            int cx = w - U(14) - (3 - i) * U(40);
            Put(hc, new IconView(ic[i], Theme.Text), cx + U(10), U(14), U(18), U(18));
            Lbl(hc, tx2[i], Sr(15), Theme.Text, cx, U(36), U(38), U(22), StringAlignment.Center);
        }
        y += hh + U(11);
        var gb = Put(page, Cd(), x0, y, w, U(42)); Lbl(gb, "Event Gallery", SrB(19), Theme.Text, U(20), 0, w / 2, U(40));
        return SessionGrid(w, y + U(42) + U(16), GalSessions(eid), true, Pg.EventInfo);
    }

    // ---- Event edit (design 3)
    int BuildEventEdit(int w)
    {
        PageTitle("Events", w, () => Go(Pg.Events)); if (ev == null) return U(80);
        int x0 = U(10), y = U(56), gap = U(11), pad = U(10); string eid = curEid;
        var sv = new Pill("Save", Sn(14), true, "check"); sv.SetBounds(0, U(4), 10, U(30)); sv.Fit(U(20)); sv.Left = U(10) + w - sv.Width; page.Controls.Add(sv); AClick(sv, SaveEvent);
        unsaved = new Txt("Unsaved changes", Sr(15), Theme.Sub) { H = StringAlignment.Far }; unsaved.SetBounds(sv.Left - U(170), U(6), U(160), U(26)); page.Controls.Add(unsaved); unsaved.Visible = EvDirty();
        int lw = (int)(w * 0.407), rw = w - lw - gap, imgH = (int)((lw - 2 * pad) * 0.5625f), ch = Math.Max(U(262), U(44) + imgH + U(10) + U(24) + U(14));
        // section 1: start screen (picture or MP4 video)
        var lc = Put(page, Cd(), x0, y, lw, ch);
        Lbl(lc, "Start Screen :", SrB(19), Theme.Text, U(12), U(8), lw - U(20), U(28));
        var th = Put(lc, new Thumb { Radius = 4 }, pad, U(44), lw - 2 * pad, imgH); string iid = ThemeImg(ev);
        if (ThemeKind(ev) == "video") { th.Badge = "play"; Lbl(lc, "Video, plays in a loop", Sr(13), Theme.Sub, lw - U(180), U(14), U(168), U(24), StringAlignment.Far); }
        else if (iid != "")
        {
            if (pendingTheme != null && iid == pendingThemeId) { var local = pendingTheme; Img(th, "local:" + local, () => Task.FromResult(File.ReadAllBytes(local)), 800); }
            else Img(th, "t:" + eid + ":" + iid, () => Api.Bytes($"/themes/{eid}/{iid}"), 800);
        }
        int by = U(44) + imgH + U(10), bw = (lw - 2 * pad - U(4)) / 2;
        var pc = Put(lc, new Pill("Choose file", Sr(14), false, "clip"), pad, by, bw, U(24)); AClick(pc, ChooseStartImage);
        var pd = Put(lc, new Pill("Delete", Sr(14), false, "trash"), pad + bw + U(4), by, bw, U(24));
        SClick(pd, () => { ev["theme"].AsObject().Remove("image"); ev["theme"].AsObject().Remove("kind"); pendingTheme = null; pendingThemeId = null; ShowPage(true); });
        // section 2: the event name now sits at the top of the settings section
        var rc = Put(page, Cd(), x0 + lw + gap, y, rw, ch);
        Lbl(rc, "Event name :", SrB(19), Theme.Text, U(12), U(8), rw - U(24), U(28));
        Put(rc, fName, U(13), U(40), rw - U(26), U(30));
        void Step(int i, string label, string ico, string key, int lo, int hi)
        {
            var st = new Stepper(label, ico, (int)D(ev[key]), lo, hi, Sr(16.5f), Sn(14));
            st.Changed += v => { ev[key] = v; RefreshUnsaved(); };
            Put(rc, st, U(13), U(86) + i * U(42), rw - U(26), U(31));
        }
        Step(0, "Countdown :", "clock", "countdown", 0, 30); Step(1, "Shots per session :", "camera", "shots", 1, 9);
        Step(2, "Keep files for (days) :", "cloud", "retention", 1, 365); Step(3, "Max print copies :", "print", "copies", 1, 20);
        y += ch + gap;
        // section 3: frames
        var fb = Put(page, Cd(), x0, y, w, U(43)); Lbl(fb, "Add Frame", SrB(19), Theme.Text, U(12), 0, w / 2, U(41));
        var pf = Put(fb, new Pill("Choose file", Sr(14), false, "clip"), w - U(12) - U(104), U(8), U(104), U(26)); AClick(pf, ChooseFrame);
        y += U(43) + gap;
        var frames = ev["frames"].AsArray();
        bool anySingle = frames.Any(fj => (fj["slots"] as JsonArray)?.Count == 1);
        int cols = Cols(w), cw = (w - gap * (cols - 1)) / cols, fimg = (int)((cw - 2 * pad) * 0.6f), fh = pad + U(22) + U(8) + fimg + pad + (anySingle ? U(20) : 0), k = 0;
        for (int fi = 0; fi < frames.Count; fi++)
        {
            int idx = fi; string fid = frames[fi]["id"].ToString(); bool single = (frames[fi]["slots"] as JsonArray)?.Count == 1;
            var fc = Put(page, Cd(), x0 + (k % cols) * (cw + gap), y + (k / cols) * (fh + gap), cw, fh);
            int hw = (cw - 2 * pad - U(4)) / 2;
            var be = Put(fc, new Pill("Edit slots", Sr(13), false, "edit"), pad, pad, hw, U(22)); AClick(be, () => EditSlots(idx));
            var bd = Put(fc, new Pill("Delete", Sr(13), false, "trash"), pad + hw + U(4), pad, hw, U(22));
            SClick(bd, () => { ev["frames"].AsArray().RemoveAt(idx); pendingFrames.Remove(fid); ShowPage(true); });
            var ft = Put(fc, new Thumb { Radius = 4, Contain = true, Placeholder = Color.White }, pad, pad + U(30), cw - 2 * pad, fimg);
            if (pendingFrames.TryGetValue(fid, out var localFrame)) Img(ft, "local:" + localFrame, () => Task.FromResult(File.ReadAllBytes(localFrame)));
            else Img(ft, "f:" + eid + ":" + fid, () => Api.Bytes($"/frames/{eid}/{fid}"));
            if (single) Lbl(fc, "1 slot: slideshow GIF possible", Sr(12.5f), Theme.Sub, pad, pad + U(30) + fimg + U(2), cw - 2 * pad, U(18));
            k++;
        }
        return y + ((k + cols - 1) / cols) * (fh + gap) + U(10);
    }
    // Picking a file only stages it: it is uploaded when Save is pressed.
    Task ChooseStartImage()
    {
        var f = Pick("Image or MP4 video|*.png;*.jpg;*.jpeg;*.mp4|Images|*.png;*.jpg;*.jpeg|MP4 video|*.mp4"); if (f == null || ev == null) return Task.CompletedTask;
        bool vid = f.EndsWith(".mp4", StringComparison.OrdinalIgnoreCase);
        if (vid && new FileInfo(f).Length > 40L * 1024 * 1024) { Info("That video is larger than 40 MB. The tablets have to download it, so please use a shorter or smaller one."); return Task.CompletedTask; }
        var id = Guid.NewGuid().ToString("N"); pendingTheme = f; pendingThemeId = id;
        var th = ev["theme"].AsObject(); th["image"] = id; if (vid) th["kind"] = "video"; else th.Remove("kind");
        ShowPage(true); return Task.CompletedTask;
    }
    Task ChooseFrame()
    {
        var f = Pick("PNG|*.png"); if (f == null || ev == null) return Task.CompletedTask;
        var id = Guid.NewGuid().ToString("N"); pendingFrames[id] = f;
        ev["frames"].AsArray().Add(new JsonObject { ["id"] = id, ["name"] = Path.GetFileName(f), ["slots"] = new JsonArray() });
        ShowPage(true); return Task.CompletedTask;
    }
    // ---- Gallery (design 4), session list (design 5), session photos (design 6)
    int BuildGallery(int w)
    {
        PageTitle("Gallery", w); AddFilter(w);
        int gap = U(13), cols = Cols(w), cw = (w - gap * (cols - 1)) / cols, ch = CardH(cw), y0 = U(56), i = 0;
        foreach (var ej in EventList().ToList()) { EventCard(U(10) + (i % cols) * (cw + gap), y0 + (i / cols) * (ch + gap), cw, ej, true, false); i++; }
        if (i == 0) Lbl(page, "No events yet.", Sr(18), Theme.Maroon, U(14), y0 + U(6), w, U(28));
        return y0 + Math.Max(1, (i + cols - 1) / cols) * (ch + gap) + U(8);
    }
    int BuildGallerySessions(int w)
    {
        PageTitle("Session", w, () => Go(Pg.Gallery)); var ej = FindEv(curEid); if (ej == null) return U(80);
        int x0 = U(10), y = U(62), hh = U(98), pad = U(10); string eid = curEid, iid = ThemeImg(ej), nm = ej["name"].ToString();
        var hc = Put(page, Cd(), x0, y, w, hh);
        int tw = (int)((hh - 2 * pad) * 1.77f); var th = Put(hc, new Thumb { Radius = 4 }, pad, pad, tw, hh - 2 * pad);
        if (ThemeKind(ej) == "video") th.Badge = "play"; else if (iid != "") Img(th, "t:" + eid + ":" + iid, () => Api.Bytes($"/themes/{eid}/{iid}"));
        int tx = pad + tw + U(16);
        Lbl(hc, nm + " Galleries", SrB(19), Theme.Text, tx, U(22), w - tx - U(150), U(28)); Lbl(hc, LongDate(EvDate(ej)), Sr(14.5f), Theme.Sub, tx, U(50), w - tx - U(150), U(24));
        var px = Put(hc, new Pill("Export zip", Sr(14), false, "clip"), w - U(14) - U(104), (hh - U(26)) / 2, U(104), U(26)); AClick(px, () => ExportEvent(eid, nm));
        return SessionGrid(w, y + hh + U(13), GalSessions(eid), false, Pg.GallerySessions);
    }
    int BuildGallerySession(int w)
    {
        PageTitle("Gallery", w, () => Go(sessBack)); var sj = FindSession(curSid); if (sj == null) return U(80);
        int x0 = U(10), y = U(62), hh = U(67), pad = U(10); string sid = curSid; long created = L(sj["created"]);
        var hc = Put(page, Cd(), x0, y, w, hh);
        int n = sessInfo.TryGetValue(sid, out var si) ? si.N : 0;
        Lbl(hc, "Session " + (n > 0 ? n.ToString() : sid.Substring(0, 4)), SrB(19), Theme.Text, U(16), U(8), w / 2, U(28)); Lbl(hc, Stamp(created), Sr(14.5f), Theme.Sub, U(16), U(35), w / 2, U(22));
        var pd = Put(hc, new Pill("Delete", Sn(12), false, "trash"), 0, (hh - U(26)) / 2 - U(2), U(84), U(26)); pd.Left = w - U(14) - pd.Width; AClick(pd, () => DeleteSession(sid, sessBack));
        var pq = Put(hc, new Pill("QR code", Sn(12), false, "link"), 0, (hh - U(26)) / 2 - U(2), U(92), U(26)); pq.Left = pd.Left - U(8) - pq.Width; AClick(pq, () => SessionQrPopup(sid));
        var files = sj["files"].AsArray().Select(x => x.ToString()).ToList();
        var order = files.Where(f => f.StartsWith("photo")).OrderBy(f => f).Concat(files.Where(f => f.StartsWith("raw")).OrderBy(f => f)).Concat(files.Where(f => f.StartsWith("gif"))).ToList();
        int gap = U(13), cols = Cols(w), cw = (w - gap * (cols - 1)) / cols, p9 = U(9), imgH = (int)((cw - 2 * p9) * 0.56f), ch = imgH + 2 * p9, i = 0, y0 = y + hh + U(13);
        foreach (var f in order)
        {
            string fn = f; int idx = i;
            var c = Put(page, Cd(), x0 + (i % cols) * (cw + gap), y0 + (i / cols) * (ch + gap), cw, ch);
            var th = Put(c, new Thumb { Radius = 4 }, p9, p9, cw - 2 * p9, imgH);
            Img(th, "p:" + sid + ":" + fn, () => Api.GetBytes($"/api/v1/sessions/{sid}/files/{fn}"));
            Clickable(c, () => { ViewerPopup(sid, order, idx); return Task.CompletedTask; }); i++;
        }
        if (i == 0) Lbl(page, "This session has no photos yet.", Sr(18), Theme.Maroon, U(14), y0 + U(6), w, U(28));
        return y0 + Math.Max(1, (i + cols - 1) / cols) * (ch + gap) + U(8);
    }
    async Task ExportEvent(string eid, string name)
    {
        var safe = string.Concat(name.Select(ch => Path.GetInvalidFileNameChars().Contains(ch) ? '_' : ch));
        using var d = new SaveFileDialog { Filter = "Zip|*.zip", FileName = safe + ".zip", AutoUpgradeEnabled = false, RestoreDirectory = true };
        if (d.ShowDialog(this) != DialogResult.OK) return;
        bool good = false;
        await Busy("Exporting the event...", async () =>
        {
            var r = await Api.Req(HttpMethod.Get, "/api/v1/events/" + eid + "/export");
            if (!r.IsSuccessStatusCode) return;
            using (var s = await r.Content.ReadAsStreamAsync()) using (var fs = File.Create(d.FileName)) await s.CopyToAsync(fs);
            good = true;
        });
        Info(good ? "Saved " + d.FileName : "Export failed.");
    }

    // ---- Print queue (design 7)
    int BuildPrint(int w)
    {
        PageTitle("Print queue", w);
        var tg = new Toggle("Print automatically", Sr(16), false) { On = autoPrint }; tg.SetBounds(0, U(8), 10, U(24)); tg.Fit(); tg.Left = U(10) + w - tg.Width; page.Controls.Add(tg);
        tg.Changed += v => autoPrint = v;
        Lbl(page, "Printer :", Sr(16), Theme.Nav, U(10), U(50), U(72), U(30));
        string curPrinter = PrinterName;
        var ppick = new Pill(curPrinter == "" ? "No printer found" : curPrinter, Sn(13), false, "print"); ppick.SetBounds(U(84), U(50), 10, U(30)); ppick.Fit(U(14)); ppick.Width = Math.Min(ppick.Width + U(20), Math.Max(U(160), w - U(100))); page.Controls.Add(ppick);
        ppick.Click += (s, ea) =>
        {
            var menu = new ContextMenuStrip { Font = Sn(13) };
            foreach (var pname in Printers()) { var pn0 = pname; var item = new ToolStripMenuItem(pname) { Checked = pname == PrinterName }; item.Click += (o, ea2) => { AdminSettings.Printer = pn0; status.Set(3, pn0, 0); ShowPage(true); }; menu.Items.Add(item); }
            if (menu.Items.Count == 0) menu.Items.Add(new ToolStripMenuItem("No printers are installed") { Enabled = false });
            menu.Show(Cursor.Position);
        };
        int y = U(100), rh = U(73), gap = U(14), pad = U(10), i = 0;
        foreach (var pj in prints.ToList())
        {
            string id = pj["id"].ToString(), sid = pj["sessionId"].ToString(), st = pj["status"].ToString(); int copies = (int)D(pj["copies"]);
            var c = Put(page, Cd(), U(10), y + i * (rh + gap), w, rh);
            var th = Put(c, new Thumb { Radius = 4 }, pad, pad, (int)((rh - 2 * pad - U(4)) * 1.77f), rh - 2 * pad - U(4));
            Img(th, "s:" + sid, () => Api.GetBytes($"/api/v1/sessions/{sid}/files/thumb.jpg"));
            int tx = pad + th.Width + U(16);
            string nm = sessInfo.TryGetValue(sid, out var si) ? $"Session {si.N} from {si.Event}" : "Session " + sid.Substring(0, 4);
            Lbl(c, nm, SrB(18), Theme.Text, tx, U(12), w - tx - U(170), U(26));
            Lbl(c, DT(L(pj["created"])).ToString("d MMMM yyyy HH:mm", System.Globalization.CultureInfo.InvariantCulture) + "  -  " + copies + (copies == 1 ? " copy" : " copies"), Sr(14.5f), Theme.Sub, tx, U(38), w - tx - U(170), U(22));
            bool busy = printing.Contains(id);
            string label = busy ? "Printing..." : st == "queued" ? "Waiting..." : st == "done" ? "Print Success" : "Cancelled";
            var sp = new Pill(label, Sr(13), false, st == "done" ? "check" : st == "cancelled" ? "xcircle" : "print", true); if (st != "done" && st != "cancelled") sp.Outlined(); else { sp.Cursor = Cursors.Default; }
            sp.Height = U(21); sp.Fit(U(10)); sp.Left = w - U(12) - sp.Width; sp.Top = st == "done" || st == "cancelled" ? (rh - sp.Height) / 2 - U(2) : U(10); c.Controls.Add(sp);
            if (st == "queued" && !busy)
            {
                var pc = Put(c, new Pill("Cancel", Sr(13), false, "xcircle", true), 0, U(38), 10, U(21)); pc.Fit(U(10)); pc.Left = w - U(12) - pc.Width; AClick(pc, async () => { await Api.J($"/prints/{id}/cancelled", "POST"); await LoadPrints(); ShowPage(true); });
                var pp = Put(c, new Pill("Print now", Sr(13), false, "print", true), 0, U(38), 10, U(21)); pp.Fit(U(10)); pp.Left = pc.Left - U(6) - pp.Width; var job = pj; AClick(pp, () => PrintJob(job));
            }
            i++;
        }
        if (i == 0) Lbl(page, "The print queue is empty.", Sr(18), Theme.Maroon, U(14), y + U(6), w, U(28));
        return y + Math.Max(1, i) * (rh + gap) + U(8);
    }

    // ---- Devices (design 8)
    int BuildDevices(int w)
    {
        PageTitle("Devices", w);
        var nd = new Pill("New device", Sn(14), true, "plus"); nd.SetBounds(0, U(4), 10, U(30)); nd.Fit(U(16)); nd.Left = U(10) + w - nd.Width; page.Controls.Add(nd); SClick(nd, PairPopup);
        int y = U(60), rh = U(75), gap = U(14), i = 0;
        foreach (var dj in devs.Where(x => !(bool)x["revoked"]).ToList())
        {
            string id = dj["id"].ToString(), nm = dj["name"].ToString(); bool revoked = (bool)dj["revoked"]; string cn = Conn(dj); long seen = L(dj["seen"]);
            var c = Put(page, Cd(), U(10), y + i * (rh + gap), w, rh);
            Put(c, new IconView("tablet", Theme.Maroon), U(12), U(10), U(56), U(50));
            Lbl(c, nm, SrB(22), Theme.Text, U(80), U(8), (int)(w * 0.3), U(32));
            Lbl(c, "Paired at : " + DT(L(dj["created"])).ToString("d MMM yyyy", System.Globalization.CultureInfo.InvariantCulture), Sr(14.5f), Theme.Sub, U(80), U(42), (int)(w * 0.3), U(22));
            string conn = revoked ? "Unpaired" : cn == "Local network" ? "Online & local" : cn == "Internet" ? "Online (internet)" : cn == "offline" ? "Offline" : "Never connected";
            string last = seen == 0 ? "-" : (cn == "Local network" || cn == "Internet") ? "Now/Online" : DT(seen).ToString("d MMM yyyy HH:mm", System.Globalization.CultureInfo.InvariantCulture);
            string[] hd = { "Pairing status", "Connection", "Last seen" }, vl = { revoked ? "Revoked" : "Active", conn, last }; double[] fx = { 0.337, 0.549, 0.746 };
            for (int k = 0; k < 3; k++)
            {
                int cx = (int)(w * fx[k]), cwid = (int)(w * (k == 2 ? 0.13 : 0.19));
                Lbl(c, hd[k], SrB(18), Theme.Text, cx, U(14), cwid, U(26)); Lbl(c, vl[k], Sr(15), Theme.Sub, cx, U(40), cwid, U(24));
            }
            if (!revoked)
            {
                var pu = Put(c, new Pill("Unpair", Sr(13), false, "xcircle", true), 0, U(10), 10, U(21)); pu.Fit(U(10)); pu.Left = w - U(12) - pu.Width;
                AClick(pu, async () => { if (!Confirm("Unpair " + nm + "? It will have to be paired again.")) return; await Busy("Removing the tablet...", async () => { await Api.J("/devices/" + id, "DELETE"); await LoadDevices(); }); ShowPage(true); });
            }
            i++;
        }
        if (i == 0) Lbl(page, "No tablets are paired yet. Press New Device to show a pairing QR code.", Sr(18), Theme.Maroon, U(14), y + U(6), w, U(28));
        return y + Math.Max(1, i) * (rh + gap) + U(8);
    }

    // ---- Server config (design 9)
    string ServerDescription()
    {
        bool up = status.Values[0] == "Up"; string ts = tunnel?["state"]?.ToString() ?? "unknown";
        string a = up ? "Server is running" : "Server is not running", b = !tunnelOn ? "the tunnel is off" : ts == "online" ? "the tunnel is online" : "tunnel: " + ts;
        var addr = lan?["addresses"] is JsonArray arr && arr.Count > 0 ? "\nThis laptop on the local network: " + string.Join("   ", arr.Select(x => x.ToString())) + "\nKeep this laptop's network profile on Private (Windows) so tablets can reach it." : "";
        return a + ", " + b + "." + addr;
    }
    void UpdateServerLive()
    {
        if (pg != Pg.Server || building) return;
        if (srvDesc != null && !srvDesc.IsDisposed) { var d = ServerDescription(); if (srvDesc.Text != d) srvDesc.Text = d; }
        if (!fLog.IsDisposed && tunnel?["log"] is JsonArray lg)
        {
            var text = string.Join("\r\n", lg.Select(x => x.ToString()));
            if (fLog.Tb.Text != text) { fLog.Tb.Text = text; fLog.Tb.SelectionStart = fLog.Tb.TextLength; fLog.Tb.ScrollToCaret(); }
        }
    }
    int BuildServer(int w)
    {
        PageTitle("Server", w);
        int x0 = U(10), y = U(56), pad = U(12), c1h = U(214), fx = U(142);
        var c1 = Put(page, Cd(), x0, y, w, c1h);
        int bw = U(116);
        Lbl(c1, "Public URL", SrB(19), Theme.Text, pad, U(14), U(124), U(30)); Put(c1, fPub, fx, U(14), w - fx - bw - U(26), U(30));
        Lbl(c1, "Tunnel token", SrB(19), Theme.Text, pad, U(54), U(124), U(30)); Put(c1, fTok, fx, U(54), w - fx - bw - U(26), U(30));
        var pr = Put(c1, new Pill("Restart server", Sr(13), false, "refresh", true), w - pad - bw, U(18), bw, U(22)); AClick(pr, () => Busy("Restarting the server...", async () => { StopServer(); await Task.Delay(1500); StartServer(); await Task.Delay(2500); await Tick(); await LoadServer(); UpdateServerLive(); }));
        var ps = Put(c1, new Pill("Save", Sr(13), false, "check", true), w - pad - bw, U(58), bw, U(22)); AClick(ps, SaveServer);
        Lbl(c1, "Description :", SrB(19), Theme.Text, pad, U(98), w / 2, U(28));
        srvDesc = Put(c1, new Txt(ServerDescription(), Sr(15), Theme.Sub) { Wrap = true, V = StringAlignment.Near }, pad, U(126), w - 2 * pad, U(52));
        var tg = new Toggle("Run the Cloudflare tunnel", Sr(16), true) { On = tunnelOn }; tg.SetBounds(pad, U(182), 10, U(22)); tg.Fit(); c1.Controls.Add(tg); tg.Changed += v => tunnelOn = v;
        var pf = Put(c1, new Pill("Allow through firewall...", Sr(13), false, "server", true), 0, U(183), 10, U(22)); pf.Fit(U(12)); pf.Left = w - pad - pf.Width;
        AClick(pf, async () => { if (await FixWindowsSetup()) Info("Finished. Details: " + Path.Combine(Path.GetTempPath(), "photobooth-service-setup.log")); });
        y += c1h + U(14);
        int lh = Math.Max(U(220), page.ClientSize.Height - y - U(24));
        var c2 = Put(page, Cd(), x0, y, w, lh);
        Lbl(c2, "Log History :", Sr(16), Theme.Text, pad, U(8), w / 2, U(26));
        Put(c2, fLog, U(10), U(38), w - U(20), lh - U(54)); UpdateServerLogNow();
        return y + lh + U(12);
    }
    void UpdateServerLogNow()
    {
        if (tunnel?["log"] is JsonArray lg) { fLog.Tb.Text = string.Join("\r\n", lg.Select(x => x.ToString())); fLog.Tb.SelectionStart = fLog.Tb.TextLength; }
    }
    async Task SaveServer()
    {
        await Busy("Saving...", async () =>
        {
            await Api.J("/settings", "PUT", new { publicUrl = fPub.Text.Trim() });
            await Api.J("/tunnel", "PUT", new { token = fTok.Text.Trim(), enabled = tunnelOn });
            fTok.Text = ""; await LoadServer(); UpdateServerLive();
        });
        Info("Saved.");
    }
}
