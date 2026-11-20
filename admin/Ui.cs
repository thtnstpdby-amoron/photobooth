using System.Drawing.Drawing2D;

// Outline icons drawn with GDI+ on a 24 x 24 grid (same look as the Material outline icons used in the Android app).
static class Ico
{
    static void RR(Graphics g, Pen p, float x, float y, float w, float h, float r) { using var path = Theme.RoundRect(new RectangleF(x, y, w, h), r); g.DrawPath(p, path); }
    public static void Draw(Graphics g, string name, RectangleF r, Color c)
    {
        var st = g.Save();
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.TranslateTransform(r.X, r.Y); g.ScaleTransform(r.Width / 24f, r.Height / 24f);
        using var pen = new Pen(c, 2f) { StartCap = LineCap.Round, EndCap = LineCap.Round, LineJoin = LineJoin.Round };
        using var br = new SolidBrush(c);
        switch (name)
        {
            case "calendar": RR(g, pen, 3, 5, 18, 16, 3); g.DrawLine(pen, 3, 10, 21, 10); g.DrawLine(pen, 8, 3, 8, 7); g.DrawLine(pen, 16, 3, 16, 7); break;
            case "image":
                RR(g, pen, 3, 3, 18, 18, 3); g.FillEllipse(br, 7.2f, 7.2f, 3.2f, 3.2f);
                g.DrawLines(pen, new[] { new PointF(4, 18), new PointF(9, 13), new PointF(13, 17), new PointF(16, 14), new PointF(20, 18) }); break;
            case "print": RR(g, pen, 3, 8, 18, 9, 2.5f); g.DrawRectangle(pen, 6.5f, 3, 11, 5); g.DrawRectangle(pen, 7, 14, 10, 7); g.FillEllipse(br, 16f, 10.5f, 1.8f, 1.8f); break;
            case "tablet": RR(g, pen, 2, 5, 20, 14, 3); g.FillEllipse(br, 17f, 11f, 1.8f, 1.8f); break;
            case "server": RR(g, pen, 3, 3, 18, 7, 2.5f); RR(g, pen, 3, 14, 18, 7, 2.5f); g.FillEllipse(br, 6.2f, 5.7f, 1.7f, 1.7f); g.FillEllipse(br, 6.2f, 16.7f, 1.7f, 1.7f); break;
            case "edit":
                RR(g, pen, 3, 5, 15, 16, 3);
                using (var th = new Pen(c, 3.4f) { StartCap = LineCap.Round, EndCap = LineCap.Round }) g.DrawLine(th, 11, 13, 20, 4); break;
            case "trash":
                g.DrawLine(pen, 4, 7, 20, 7); g.DrawLines(pen, new[] { new PointF(9, 7), new PointF(9, 4), new PointF(15, 4), new PointF(15, 7) });
                RR(g, pen, 6, 7, 12, 14, 2.5f); g.DrawLine(pen, 10, 11, 10, 17); g.DrawLine(pen, 14, 11, 14, 17); break;
            case "clock": g.DrawEllipse(pen, 3, 3, 18, 18); g.DrawLine(pen, 12, 7, 12, 12); g.DrawLine(pen, 12, 12, 15.5f, 14); break;
            case "camera": RR(g, pen, 2, 7, 20, 14, 3); g.DrawEllipse(pen, 8.5f, 10.5f, 7, 7); g.DrawLines(pen, new[] { new PointF(8, 7), new PointF(9.5f, 4), new PointF(14.5f, 4), new PointF(16, 7) }); break;
            case "cloud":
                using (var p = new GraphicsPath())
                {
                    p.AddBezier(6, 19, 2.2f, 19, 1.5f, 14.5f, 5, 13.5f); p.AddBezier(5, 13.5f, 5, 9, 10, 7, 12.5f, 10); p.AddBezier(12.5f, 10, 14, 6.5f, 20, 7.5f, 19.5f, 12.5f);
                    p.AddBezier(19.5f, 12.5f, 23, 12.8f, 22.5f, 19, 18, 19); p.CloseFigure(); g.DrawPath(pen, p);
                }
                break;
            case "filter": using (var t = new Pen(c, 2.6f) { StartCap = LineCap.Round, EndCap = LineCap.Round }) { g.DrawLine(t, 3, 6, 21, 6); g.DrawLine(t, 6.5f, 12, 17.5f, 12); g.DrawLine(t, 10, 18, 14, 18); } break;
            case "clip":
                using (var p = new GraphicsPath())
                {
                    p.AddArc(6, 2, 12, 12, 180, 180); p.AddLine(18, 8, 18, 16); p.AddArc(6, 12, 12, 12, 0, 180); p.AddLine(6, 18, 6, 9);
                    g.DrawPath(pen, p); g.DrawLine(pen, 10, 9, 10, 17);
                }
                break;
            case "link": RR(g, pen, 2, 8, 10, 8, 4); RR(g, pen, 12, 8, 10, 8, 4); g.DrawLine(pen, 9, 12, 15, 12); break;
            case "check": g.DrawLines(pen, new[] { new PointF(5, 12.5f), new PointF(10, 17.5f), new PointF(19, 7) }); break;
            case "xcircle": g.DrawEllipse(pen, 3, 3, 18, 18); g.DrawLine(pen, 9, 9, 15, 15); g.DrawLine(pen, 15, 9, 9, 15); break;
            case "close": g.DrawLine(pen, 6, 6, 18, 18); g.DrawLine(pen, 18, 6, 6, 18); break;
            case "play": g.DrawPolygon(pen, new[] { new PointF(8, 5), new PointF(8, 19), new PointF(19, 12) }); break;
            case "plus": g.DrawLine(pen, 12, 5, 12, 19); g.DrawLine(pen, 5, 12, 19, 12); break;
            case "pluscircle": g.DrawEllipse(pen, 2, 2, 20, 20); g.DrawLine(pen, 12, 7, 12, 17); g.DrawLine(pen, 7, 12, 17, 12); break;
            case "chevl": g.DrawLines(pen, new[] { new PointF(15, 5), new PointF(8, 12), new PointF(15, 19) }); break;
            case "chevr": g.DrawLines(pen, new[] { new PointF(9, 5), new PointF(16, 12), new PointF(9, 19) }); break;
            case "refresh": g.DrawArc(pen, 4, 4, 16, 16, -50, 280); g.DrawLines(pen, new[] { new PointF(4.5f, 3.5f), new PointF(6.6f, 6.3f), new PointF(10.5f, 6.8f) }); break;
            case "qr":
                RR(g, pen, 3, 3, 7, 7, 1.5f); RR(g, pen, 14, 3, 7, 7, 1.5f); RR(g, pen, 3, 14, 7, 7, 1.5f);
                g.FillRectangle(br, 14.5f, 14.5f, 3, 3); g.FillRectangle(br, 18.5f, 18.5f, 2.5f, 2.5f); g.FillRectangle(br, 18.5f, 14, 2.5f, 2.5f); g.FillRectangle(br, 14, 18.5f, 2.5f, 2.5f); break;
        }
        g.Restore(st);
    }
}

// One shared timer that animates every visible spinner.
static class Anim
{
    static readonly HashSet<Control> set = new();
    static readonly System.Windows.Forms.Timer tm = new() { Interval = 40 };
    public static float Angle;
    static Anim() { tm.Tick += (s, e) => { Angle = (Angle + 16) % 360; foreach (var c in set.ToList()) { if (c.IsDisposed) set.Remove(c); else c.Invalidate(); } if (set.Count == 0) tm.Stop(); }; }
    public static void Add(Control c) { set.Add(c); if (!tm.Enabled) tm.Start(); }
    public static void Remove(Control c) { set.Remove(c); }
    public static void Spinner(Graphics g, RectangleF r, Color arc, Color track, float w)
    {
        var rr = new RectangleF(r.X + w / 2, r.Y + w / 2, r.Width - w, r.Height - w);
        using var tp = new Pen(track, w); using var ap = new Pen(arc, w) { StartCap = LineCap.Round, EndCap = LineCap.Round };
        g.DrawEllipse(tp, rr); g.DrawArc(ap, rr, Angle, 110);
    }
}

// Loading screen over the content area: spinner and a message.
class BusyOverlay : Gx
{
    string msg = "";
    public BusyOverlay() { BackColor = Theme.White; Visible = false; }
    public void ShowMsg(string m) { msg = m; Visible = true; BringToFront(); Anim.Add(this); Invalidate(); }
    public void HideNow() { Anim.Remove(this); Visible = false; }
    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics; g.Clear(Theme.White); Theme.Smooth(g);
        float s = Theme.UF(52); var r = new RectangleF((Width - s) / 2f, Height / 2f - s, s, s);
        Anim.Spinner(g, r, Theme.Maroon, Theme.Line, Theme.UF(5));
        Theme.Text_(g, msg, Theme.SansPx(17), Theme.Nav, new RectangleF(0, r.Bottom + Theme.UF(14), Width, Theme.UF(30)), StringAlignment.Center);
    }
}

// Base of every owner-drawn control: paints itself (anti-aliased) over the colour of its parent.
class Gx : Control
{
    public Gx()
    {
        SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
        SetStyle(ControlStyles.Selectable, false);
    }
    protected Color Back => Parent != null ? Parent.BackColor : Theme.White;
    protected override void OnTextChanged(EventArgs e) { base.OnTextChanged(e); Invalidate(); }
    protected override void OnFontChanged(EventArgs e) { base.OnFontChanged(e); Invalidate(); }
    protected override void OnForeColorChanged(EventArgs e) { base.OnForeColorChanged(e); Invalidate(); }
    protected override void OnEnabledChanged(EventArgs e) { base.OnEnabledChanged(e); Invalidate(); }
    protected override void OnBackColorChanged(EventArgs e) { base.OnBackColorChanged(e); Invalidate(); }
    protected override void OnParentChanged(EventArgs e) { base.OnParentChanged(e); Invalidate(); }
}

// Slightly rounded box with a soft outline. Children are placed on top of it.
class Card : Gx
{
    public float Radius; public Color Stroke = Color.Empty; public float StrokeW = 1f;
    public Card(Color fill, float radius = 6) { BackColor = fill; Radius = radius; }
    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics; g.Clear(Back); Theme.Smooth(g);
        var r = new RectangleF(0.5f, 0.5f, Width - 1.5f, Height - 1.5f);
        using var p = Theme.RoundRect(r, Theme.UF(Radius));
        using (var b = new SolidBrush(BackColor)) g.FillPath(b, p);
        if (!Stroke.IsEmpty) { using var pen = new Pen(Stroke, Math.Max(1f, Theme.UF(StrokeW))); g.DrawPath(pen, p); }
    }
}

// Static text drawn with GDI+. Optional icon on the left.
class Txt : Gx
{
    public StringAlignment H = StringAlignment.Near, V = StringAlignment.Center; public bool Wrap; public string Icon;
    public Txt(string text, Font f, Color c) { Text = text; Font = f; ForeColor = c; }
    public int FitWidth() { int w = Theme.Measure(Text, Font) + Theme.U(6); if (Icon != null) w += (int)(Font.Size * 1.5f); Width = w; return w; }
    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics; g.Clear(Back); Theme.Smooth(g); float x = 0;
        if (Icon != null) { float s = Font.Size * 1.1f; Ico.Draw(g, Icon, new RectangleF(0, (Height - s) / 2f, s, s), ForeColor); x = s + Font.Size * 0.4f; }
        Theme.Text_(g, Text, Font, ForeColor, new RectangleF(x, 0, Math.Max(1, Width - x), Height), H, V, Wrap);
    }
}

// Picture with slightly rounded corners (cropped to fill, or shown whole when Contain is set).
class Thumb : Gx
{
    public Image Img; public float Radius = 4; public bool Contain; public Color Placeholder = Theme.Tint; public string Badge; bool loading;
    // Loading = a small spinner is shown until the picture arrives
    public bool Loading { get => loading; set { loading = value; if (value) Anim.Add(this); else Anim.Remove(this); Invalidate(); } }
    protected override void Dispose(bool disposing) { if (disposing) Anim.Remove(this); base.Dispose(disposing); }
    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics; g.Clear(Back); Theme.Smooth(g);
        var box = new RectangleF(0, 0, Width, Height);
        using var path = Theme.RoundRect(box, Theme.UF(Radius));
        if (Img == null)
        {
            if (!Placeholder.IsEmpty) using (var b = new SolidBrush(Placeholder)) g.FillPath(b, path);
            float s = Math.Max(Theme.UF(14), Math.Min(Math.Min(Width, Height) * 0.28f, Theme.UF(36)));
            if (loading) Anim.Spinner(g, new RectangleF((Width - s) / 2f, (Height - s) / 2f, s, s), Theme.Maroon, Color.FromArgb(70, 255, 255, 255), Math.Max(2f, s * 0.12f));
            else if (Badge != null) Ico.Draw(g, Badge, new RectangleF((Width - s) / 2f, (Height - s) / 2f, s, s), Color.FromArgb(230, 128, 0, 0));
            return;
        }
        g.SetClip(path); g.InterpolationMode = InterpolationMode.HighQualityBicubic;
        if (!Placeholder.IsEmpty) using (var b = new SolidBrush(Placeholder)) g.FillPath(b, path);
        float sw = Img.Width, sh = Img.Height;
        if (Contain)
        {
            float sc = Math.Min(Width / sw, Height / sh); float w = sw * sc, h = sh * sc;
            g.DrawImage(Img, new RectangleF((Width - w) / 2f, (Height - h) / 2f, w, h));
        }
        else
        {
            float sc = Math.Max(Width / sw, Height / sh); float cw = Width / sc, ch = Height / sc;
            g.DrawImage(Img, new RectangleF(0, 0, Width, Height), new RectangleF((sw - cw) / 2f, (sh - ch) / 2f, cw, ch), GraphicsUnit.Pixel);
        }
        g.ResetClip();
    }
}

// Button: filled maroon (dark = primary) or white with a soft outline (secondary), optional icon.
class Pill : Gx
{
    public string Icon; public bool IconRight, Hot; public Color Fill, FillHot, Fore, Stroke = Color.Empty;
    public Pill(string text, Font f, bool dark, string icon = null, bool iconRight = false)
    {
        Text = text; Font = f; Icon = icon; IconRight = iconRight; Cursor = Cursors.Hand;
        if (dark) { Fill = Theme.Maroon; FillHot = Theme.MaroonHot; Fore = Color.White; Stroke = Theme.Maroon; }
        else { Fill = Color.White; FillHot = Theme.Hover; Fore = Theme.Maroon; Stroke = Theme.LineStrong; }
    }
    public Pill Outlined() { Fill = Color.Empty; FillHot = Color.Empty; Fore = Theme.Sub; Stroke = Theme.Line; Cursor = Cursors.Default; return this; }
    float IconSize => Font.Size * 1.05f;
    public int Fit(int padX) { int w = Theme.Measure(Text, Font) + padX * 2 + (Icon != null ? (int)(IconSize + Font.Size * 0.45f) : 0); Width = w; return w; }
    protected override void OnMouseEnter(EventArgs e) { base.OnMouseEnter(e); Hot = true; Invalidate(); }
    protected override void OnMouseLeave(EventArgs e) { base.OnMouseLeave(e); Hot = false; Invalidate(); }
    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics; g.Clear(Back); Theme.Smooth(g);
        var rect = new RectangleF(0.5f, 0.5f, Width - 1.5f, Height - 1.5f);
        int alpha = Enabled ? 255 : 120;
        using (var p = Theme.RoundRect(rect, Theme.UF(6)))
        {
            var fill = Hot && Enabled ? FillHot : Fill;
            if (!fill.IsEmpty) using (var b = new SolidBrush(Color.FromArgb(alpha, fill))) g.FillPath(b, p);
            if (!Stroke.IsEmpty) using (var pen = new Pen(Color.FromArgb(alpha, Stroke), Math.Max(1f, Theme.UF(1f)))) g.DrawPath(pen, p);
        }
        var fore = Color.FromArgb(alpha, Fore);
        float isz = Icon != null ? IconSize : 0, gap = Icon != null ? Font.Size * 0.45f : 0, tw = Theme.Measure(Text, Font);
        float x0 = (Width - (isz + gap + tw)) / 2f;
        if (Icon != null && !IconRight) { Ico.Draw(g, Icon, new RectangleF(x0, (Height - isz) / 2f, isz, isz), fore); x0 += isz + gap; }
        Theme.Text_(g, Text, Font, fore, new RectangleF(x0, 0, tw + Theme.U(8), Height));
        if (Icon != null && IconRight) Ico.Draw(g, Icon, new RectangleF(x0 + tw + gap, (Height - isz) / 2f, isz, isz), fore);
    }
}

// Small square icon button.
class Circ : Gx
{
    public string Icon; public bool Hot; public bool Dark;
    public Circ(string icon, bool dark = false) { Icon = icon; Dark = dark; Cursor = Cursors.Hand; }
    protected override void OnMouseEnter(EventArgs e) { base.OnMouseEnter(e); Hot = true; Invalidate(); }
    protected override void OnMouseLeave(EventArgs e) { base.OnMouseLeave(e); Hot = false; Invalidate(); }
    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics; g.Clear(Back); Theme.Smooth(g);
        var r = new RectangleF(0.5f, 0.5f, Width - 1.5f, Height - 1.5f);
        using (var p = Theme.RoundRect(r, Theme.UF(6)))
        {
            using (var b = new SolidBrush(Dark ? (Hot ? Theme.MaroonHot : Theme.Maroon) : (Hot ? Theme.Hover : Color.White))) g.FillPath(b, p);
            using (var pen = new Pen(Dark ? Theme.Maroon : Theme.LineStrong, Math.Max(1f, Theme.UF(1f)))) g.DrawPath(pen, p);
        }
        float s = Math.Min(Width, Height) * 0.54f;
        Ico.Draw(g, Icon, new RectangleF((Width - s) / 2f, (Height - s) / 2f, s, s), Dark ? Color.White : Theme.Maroon);
    }
}

// Switch with a text label.
class Toggle : Gx
{
    public bool On; public string Label; public event Action<bool> Changed;
    public Toggle(string label, Font f, bool unused = false) { Label = label; Font = f; Cursor = Cursors.Hand; }
    public int Fit() { Width = (int)(Height * 1.8f) + Theme.U(10) + Theme.Measure(Label, Font) + Theme.U(6); return Width; }
    protected override void OnClick(EventArgs e) { base.OnClick(e); On = !On; Changed?.Invoke(On); Invalidate(); }
    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics; g.Clear(Back); Theme.Smooth(g);
        float tw = Height * 1.8f; var track = new RectangleF(0.5f, 0.5f, tw - 1.5f, Height - 1.5f);
        using (var p = Theme.RoundRect(track, track.Height / 2f))
        {
            if (On) { using var b = new SolidBrush(Theme.Maroon); g.FillPath(b, p); } else { using var pen = new Pen(Theme.LineStrong, Math.Max(1f, Theme.UF(1.2f))); g.DrawPath(pen, p); }
        }
        float k = Height - Theme.UF(8); float kx = On ? tw - k - Theme.UF(4) : Theme.UF(4);
        using (var b = new SolidBrush(On ? Color.White : Theme.LineStrong)) g.FillEllipse(b, kx, (Height - k) / 2f, k, k);
        Theme.Text_(g, Label, Font, Theme.Text, new RectangleF(tw + Theme.U(10), 0, Math.Max(1, Width - tw - Theme.U(10)), Height));
    }
}

// Outlined row "icon + label ..... [ < value > ]".
class Stepper : Gx
{
    public string Label, Icon; public int Value, Min, Max; public event Action<int> Changed;
    readonly Font valFont; Rectangle left, right, chip; readonly System.Windows.Forms.Timer rep = new() { Interval = 380 }; int dir;
    public Stepper(string label, string icon, int v, int min, int max, Font labelFont, Font valueFont)
    {
        Label = label; Icon = icon; Value = v; Min = min; Max = max; Font = labelFont; valFont = valueFont;
        rep.Tick += (s, e) => { rep.Interval = 70; Step(dir); };
    }
    void Calc()
    {
        int ch = Height - Theme.U(8), tw = Theme.Measure(Value.ToString(), valFont); int cw = Math.Max(Theme.U(76), tw + ch * 2 + Theme.U(6));
        chip = new Rectangle(Width - Theme.U(5) - cw, Theme.U(4), cw, ch); left = new Rectangle(chip.X, chip.Y, ch, ch); right = new Rectangle(chip.Right - ch, chip.Y, ch, ch);
    }
    void Step(int d) { int nv = Math.Max(Min, Math.Min(Max, Value + d)); if (nv != Value) { Value = nv; Changed?.Invoke(nv); Invalidate(); } }
    protected override void OnMouseDown(MouseEventArgs e)
    {
        base.OnMouseDown(e); Calc(); dir = left.Contains(e.Location) ? -1 : right.Contains(e.Location) ? 1 : 0;
        if (dir != 0) { Step(dir); rep.Interval = 380; rep.Start(); }
    }
    protected override void OnMouseUp(MouseEventArgs e) { base.OnMouseUp(e); rep.Stop(); }
    protected override void OnMouseMove(MouseEventArgs e) { base.OnMouseMove(e); Calc(); Cursor = left.Contains(e.Location) || right.Contains(e.Location) ? Cursors.Hand : Cursors.Default; }
    protected override void Dispose(bool disposing) { if (disposing) rep.Dispose(); base.Dispose(disposing); }
    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics; g.Clear(Back); Theme.Smooth(g); Calc();
        var r = new RectangleF(0.5f, 0.5f, Width - 1.5f, Height - 1.5f);
        using (var p = Theme.RoundRect(r, Theme.UF(6))) { using (var b = new SolidBrush(Theme.Side)) g.FillPath(b, p); using var pen = new Pen(Theme.Line, Math.Max(1f, Theme.UF(1f))); g.DrawPath(pen, p); }
        float isz = Height * 0.5f, x0 = Height * 0.42f;
        Ico.Draw(g, Icon, new RectangleF(x0, (Height - isz) / 2f, isz, isz), Theme.Maroon);
        Theme.Text_(g, Label, Font, Theme.Text, new RectangleF(x0 + isz + Theme.U(8), 0, Math.Max(1, chip.X - x0 - isz - Theme.U(12)), Height));
        using (var p = Theme.RoundRect(chip, Theme.UF(5))) { using (var b = new SolidBrush(Color.White)) g.FillPath(b, p); using var pen = new Pen(Theme.LineStrong, Math.Max(1f, Theme.UF(1f))); g.DrawPath(pen, p); }
        float a = chip.Height * 0.5f;
        Ico.Draw(g, "chevl", new RectangleF(left.X + (left.Width - a) / 2f, left.Y + (left.Height - a) / 2f, a, a), Value <= Min ? Theme.LineStrong : Theme.Maroon);
        Ico.Draw(g, "chevr", new RectangleF(right.X + (right.Width - a) / 2f, right.Y + (right.Height - a) / 2f, a, a), Value >= Max ? Theme.LineStrong : Theme.Maroon);
        Theme.Text_(g, Value.ToString(), valFont, Theme.Text, new RectangleF(left.Right, chip.Y, right.X - left.Right, chip.Height), StringAlignment.Center);
    }
}

// Text input: a standard TextBox on a white box with a soft outline.
class Field : Gx
{
    public readonly TextBox Tb = new() { BorderStyle = BorderStyle.None };
    public Color Fill, Stroke = Theme.LineStrong; public bool Multi;
    public Field(Font f, Color fore, Color fill, bool multi = false)
    {
        Fill = fill; Multi = multi; BackColor = fill; Tb.Font = f; Tb.ForeColor = fore; Tb.BackColor = fill;
        if (multi) { Tb.Multiline = true; Tb.ReadOnly = true; Tb.ScrollBars = ScrollBars.Vertical; }
        Controls.Add(Tb); Cursor = Cursors.IBeam;
    }
    public override string Text { get => Tb.Text; set => Tb.Text = value ?? ""; }
    protected override void OnResize(EventArgs e) { base.OnResize(e); Place(); }
    public void Place()
    {
        int pad = Multi ? Theme.U(10) : Theme.U(10); Tb.Left = pad; Tb.Width = Math.Max(10, Width - 2 * pad);
        if (Multi) { Tb.Top = Theme.U(8); Tb.Height = Math.Max(10, Height - Theme.U(16)); } else Tb.Top = Math.Max(0, (Height - Tb.PreferredHeight) / 2);
    }
    protected override void OnClick(EventArgs e) { base.OnClick(e); Tb.Focus(); }
    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics; g.Clear(Back); Theme.Smooth(g);
        var r = new RectangleF(0.5f, 0.5f, Width - 1.5f, Height - 1.5f);
        using var p = Theme.RoundRect(r, Theme.UF(6));
        using (var b = new SolidBrush(Fill)) g.FillPath(b, p);
        if (!Stroke.IsEmpty) using (var pen = new Pen(Stroke, Math.Max(1f, Theme.UF(1f)))) g.DrawPath(pen, p);
    }
}

// Sidebar entry: white with a maroon bar when selected.
class NavItem : Gx
{
    public string Icon; public bool Selected, Hot;
    public NavItem(string text, string icon, Font f) { Text = text; Icon = icon; Font = f; Cursor = Cursors.Hand; }
    protected override void OnMouseEnter(EventArgs e) { base.OnMouseEnter(e); Hot = true; Invalidate(); }
    protected override void OnMouseLeave(EventArgs e) { base.OnMouseLeave(e); Hot = false; Invalidate(); }
    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics; g.Clear(Back); Theme.Smooth(g);
        var r = new RectangleF(0.5f, 0.5f, Width - 1.5f, Height - 1.5f);
        if (Selected || Hot)
        {
            using var p = Theme.RoundRect(r, Theme.UF(6)); using var b = new SolidBrush(Selected ? Color.White : Theme.Hover); g.FillPath(b, p);
            if (Selected) { using var pen = new Pen(Theme.LineStrong, Math.Max(1f, Theme.UF(1f))); g.DrawPath(pen, p); using var bar = new SolidBrush(Theme.Maroon); g.FillRectangle(bar, Theme.UF(1), Theme.UF(6), Theme.UF(3), Height - Theme.UF(12)); }
        }
        var ink = Selected ? Theme.Maroon : Theme.Nav; float isz = Height * 0.56f;
        Ico.Draw(g, Icon, new RectangleF(Theme.U(12), (Height - isz) / 2f, isz, isz), ink);
        Theme.Text_(g, Text, Font, ink, new RectangleF(Theme.U(12) + isz + Theme.U(10), 0, Width, Height));
    }
}

// "Server : Up", "Tunnel : Online", ... box at the bottom of the sidebar.
class StatusBox : Gx
{
    public readonly string[] Labels = { "Server", "Tunnel", "Local Connection", "Printer" };
    public readonly string[] Values = { "-", "-", "-", "-" };
    public readonly int[] State = { 0, 0, 0, 0 };   // 0 none, 1 ok (green check), 2 bad (red cross)
    public StatusBox() { Font = Theme.SansPx(12.5f); }
    public void Set(int i, string v, int st) { if (Values[i] != v || State[i] != st) { Values[i] = v; State[i] = st; Invalidate(); } }
    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics; g.Clear(Back); Theme.Smooth(g);
        var r = new RectangleF(0.5f, 0.5f, Width - 1.5f, Height - 1.5f);
        using (var p = Theme.RoundRect(r, Theme.UF(6))) { using (var b = new SolidBrush(Color.White)) g.FillPath(b, p); using var pen = new Pen(Theme.Line, Math.Max(1f, Theme.UF(1f))); g.DrawPath(pen, p); }
        float pad = Theme.UF(10), lh = (Height - pad * 2) / 4f;
        for (int i = 0; i < 4; i++)
        {
            string s = Labels[i] + " : " + Values[i]; float y = pad + i * lh;
            Theme.Text_(g, s, Font, Theme.Nav, new RectangleF(pad, y, Width - pad * 2 - Theme.UF(18), lh));
            if (State[i] != 0)
            {
                float tw = Math.Min(Theme.Measure(s, Font), Width - pad * 2 - Theme.UF(18)), d = Theme.UF(12);
                var ir = new RectangleF(pad + tw + Theme.UF(5), y + (lh - d) / 2f, d, d);
                using (var b = new SolidBrush(State[i] == 1 ? Theme.Green : Theme.Red)) g.FillEllipse(b, ir);
                var inner = new RectangleF(ir.X + d * 0.12f, ir.Y + d * 0.12f, d * 0.76f, d * 0.76f); Ico.Draw(g, State[i] == 1 ? "check" : "close", inner, Color.White);
            }
        }
    }
}

// Pairing / download QR on a light panel (maroon modules on white scan reliably).
class QrView : Gx
{
    bool[,] mat; string data; bool loading;
    public string Placeholder = "QR code";
    public bool Loading { get => loading; set { loading = value; if (value) Anim.Add(this); else Anim.Remove(this); Invalidate(); } }
    protected override void Dispose(bool disposing) { if (disposing) Anim.Remove(this); base.Dispose(disposing); }
    public string Data
    {
        get => data;
        set
        {
            data = value; mat = null;
            if (!string.IsNullOrEmpty(value))
            {
                try
                {
                    using var gen = new QRCoder.QRCodeGenerator(); using var qd = gen.CreateQrCode(value, QRCoder.QRCodeGenerator.ECCLevel.M);
                    var m = qd.ModuleMatrix; int n = m.Count; mat = new bool[n, n];
                    for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) mat[y, x] = m[y][x];
                }
                catch (Exception ex) { AdminLog.Write(ex); }
            }
            Invalidate();
        }
    }
    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics; g.Clear(Back); Theme.Smooth(g);
        var outer = new RectangleF(0.5f, 0.5f, Width - 1.5f, Height - 1.5f);
        using (var p = Theme.RoundRect(outer, Theme.UF(8))) { using var b = new SolidBrush(Theme.Side); g.FillPath(b, p); using var pen = new Pen(Theme.Line, Math.Max(1f, Theme.UF(1f))); g.DrawPath(pen, p); }
        if (mat == null)
        {
            if (loading) { float s = Math.Min(Width, Height) * 0.2f; Anim.Spinner(g, new RectangleF((Width - s) / 2f, (Height - s) / 2f, s, s), Theme.Maroon, Theme.Line, Math.Max(3f, s * 0.1f)); }
            else Theme.Text_(g, Placeholder, Theme.SansPx(30), Theme.Sub, outer, StringAlignment.Center, StringAlignment.Center, true);
            return;
        }
        int n = mat.GetLength(0); int inset = Theme.U(10); int avail = Math.Min(Width, Height) - 2 * inset; int cell = Math.Max(1, avail / n); int size = cell * n;
        int ox = (Width - size) / 2, oy = (Height - size) / 2; int padTile = Theme.U(4);
        var tile = new RectangleF(ox - padTile, oy - padTile, size + 2 * padTile, size + 2 * padTile);
        using (var p = Theme.RoundRect(tile, Theme.UF(5))) using (var b = new SolidBrush(Color.White)) g.FillPath(b, p);
        g.SmoothingMode = SmoothingMode.None;
        using (var b = new SolidBrush(Theme.Maroon))
            for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) if (mat[y, x]) g.FillRectangle(b, ox + x * cell, oy + y * cell, cell, cell);
    }
}

class SlotItem { public double X, Y, W, H, R; public int Source = -1; }

// Frame preview where dragging draws a photo slot (0..1 fractions of the frame image, same as the Android editor).
class SlotCanvas : Gx
{
    public Image Img; public readonly List<SlotItem> Slots = new(); Point? down; Point cur;
    public event Action Changed;
    public SlotCanvas() { Cursor = Cursors.Cross; BackColor = Theme.Tint; }
    RectangleF Box()
    {
        if (Img == null) return new RectangleF(0, 0, Width, Height);
        float sc = Math.Min((float)Width / Img.Width, (float)Height / Img.Height); float w = Img.Width * sc, h = Img.Height * sc;
        return new RectangleF((Width - w) / 2f, (Height - h) / 2f, w, h);
    }
    protected override void OnMouseDown(MouseEventArgs e) { base.OnMouseDown(e); if (e.Button == MouseButtons.Left && Img != null) { down = e.Location; cur = e.Location; } }
    protected override void OnMouseMove(MouseEventArgs e) { base.OnMouseMove(e); if (down != null) { cur = e.Location; Invalidate(); } }
    protected override void OnMouseUp(MouseEventArgs e)
    {
        base.OnMouseUp(e); if (down == null) return; var a = down.Value; down = null; var b = Box();
        double x0 = Math.Max(0, Math.Min(1, (Math.Min(a.X, e.X) - b.X) / b.Width)), x1 = Math.Max(0, Math.Min(1, (Math.Max(a.X, e.X) - b.X) / b.Width));
        double y0 = Math.Max(0, Math.Min(1, (Math.Min(a.Y, e.Y) - b.Y) / b.Height)), y1 = Math.Max(0, Math.Min(1, (Math.Max(a.Y, e.Y) - b.Y) / b.Height));
        if (x1 - x0 > 0.03 && y1 - y0 > 0.03) { Slots.Add(new SlotItem { X = x0, Y = y0, W = x1 - x0, H = y1 - y0, R = 0 }); Changed?.Invoke(); }
        Invalidate();
    }
    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics; g.Clear(Theme.Tint); Theme.Smooth(g); var b = Box();
        if (Img != null) { g.InterpolationMode = InterpolationMode.HighQualityBicubic; g.DrawImage(Img, b); }
        int n = 1; using var pen = new Pen(Theme.Maroon, Theme.UF(2f)); using var fill = new SolidBrush(Color.FromArgb(70, 128, 0, 0)); var f = Theme.SansPx(18);
        foreach (var sl in Slots)
        {
            var r = new RectangleF(b.X + (float)sl.X * b.Width, b.Y + (float)sl.Y * b.Height, (float)sl.W * b.Width, (float)sl.H * b.Height);
            g.FillRectangle(fill, r); g.DrawRectangle(pen, r.X, r.Y, r.Width, r.Height);
            Theme.Text_(g, (n++).ToString(), f, Color.White, new RectangleF(r.X + 6, r.Y + 2, 60, 30));
        }
        if (down != null)
        {
            var a = down.Value; using var dp = new Pen(Theme.Maroon, Theme.UF(2f)) { DashStyle = DashStyle.Dash };
            g.DrawRectangle(dp, Math.Min(a.X, cur.X), Math.Min(a.Y, cur.Y), Math.Abs(a.X - cur.X), Math.Abs(a.Y - cur.Y));
        }
    }
}

// Just an icon.
class IconView : Gx
{
    public string Icon; public Color Ink;
    public IconView(string icon, Color ink) { Icon = icon; Ink = ink; }
    protected override void OnPaint(PaintEventArgs e) { var g = e.Graphics; g.Clear(Back); Theme.Smooth(g); Ico.Draw(g, Icon, new RectangleF(0, 0, Width, Height), Ink); }
}
