using System.Diagnostics;
using System.Drawing.Drawing2D;
using System.Drawing.Text;
using System.Globalization;
using System.Net.Http.Headers;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;

static class AdminLog { public static void Write(object o) { try { File.AppendAllText(Path.Combine(Path.GetTempPath(), "photobooth-admin.log"), DateTime.Now + " " + o + "\r\n"); } catch { } } }

// Light theme: white surfaces, maroon accent, soft maroon-tinted outlines, Segoe UI (the Windows system font).
// Sizes in the UI code are "design units" (a 1200 x 638 window) multiplied by Scale.
static class Theme
{
    public static readonly Color Maroon = Color.FromArgb(128, 0, 0), MaroonHot = Color.FromArgb(150, 24, 24), White = Color.White,
        Side = Color.FromArgb(250, 246, 246), Line = Color.FromArgb(232, 214, 214), LineStrong = Color.FromArgb(217, 185, 185),
        Text = Color.FromArgb(42, 32, 32), Nav = Color.FromArgb(74, 58, 58), Sub = Color.FromArgb(122, 106, 106), Tint = Color.FromArgb(239, 230, 230),
        Hover = Color.FromArgb(243, 234, 234), Green = Color.FromArgb(46, 125, 50), Red = Color.FromArgb(198, 40, 40);
    public static float Scale = 1f;
    public static int U(float v) => (int)Math.Round(v * Scale);
    public static float UF(float v) => v * Scale;

    public static readonly Image Logo;
    static readonly Dictionary<string, Font> fonts = new();
    static readonly Graphics mg = Graphics.FromImage(new Bitmap(1, 1));
    static readonly StringFormat typo = new(StringFormat.GenericTypographic);

    static Theme()
    {
        try { using var s = System.Reflection.Assembly.GetExecutingAssembly().GetManifestResourceStream("logo.png"); if (s != null) { using var im = Image.FromStream(s); Logo = new Bitmap(im); } } catch { }
    }
    static Font Px(string family, float px)
    {
        var k = family + px.ToString(CultureInfo.InvariantCulture) + "@" + Scale.ToString(CultureInfo.InvariantCulture);
        lock (fonts)
        {
            if (fonts.TryGetValue(k, out var cached)) return cached;
            var size = Math.Max(6f, px * Scale); Font made;
            try { made = new Font(family, size, FontStyle.Regular, GraphicsUnit.Pixel); }
            catch { made = new Font("Segoe UI", size, FontStyle.Regular, GraphicsUnit.Pixel); }
            fonts[k] = made; return made;
        }
    }
    // The three names are kept from the first version; all of them are Segoe UI now.
    public static Font SerifPx(float px) => Px("Segoe UI", px * 0.80f);
    public static Font SerifBoldPx(float px) => Px("Segoe UI Semibold", px * 0.80f);
    public static Font SansPx(float px) => Px("Segoe UI", px * 0.88f);

    public static int Measure(string s, Font f)
    {
        if (string.IsNullOrEmpty(s)) return 0;
        lock (mg) return (int)Math.Ceiling(mg.MeasureString(s, f, 100000, typo).Width);
    }
    public static GraphicsPath RoundRect(RectangleF r, float rad)
    {
        var p = new GraphicsPath(); float d = Math.Min(rad * 2, Math.Min(r.Width, r.Height));
        if (d <= 0.5f) { p.AddRectangle(r); return p; }
        p.AddArc(r.X, r.Y, d, d, 180, 90); p.AddArc(r.Right - d, r.Y, d, d, 270, 90); p.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90); p.AddArc(r.X, r.Bottom - d, d, d, 90, 90); p.CloseFigure();
        return p;
    }
    public static void Smooth(Graphics g) { g.SmoothingMode = SmoothingMode.AntiAlias; g.PixelOffsetMode = PixelOffsetMode.HighQuality; g.TextRenderingHint = TextRenderingHint.ClearTypeGridFit; }
    public static void Text_(Graphics g, string s, Font f, Color c, RectangleF r, StringAlignment h = StringAlignment.Near, StringAlignment v = StringAlignment.Center, bool wrap = false)
    {
        if (string.IsNullOrEmpty(s)) return;
        using var br = new SolidBrush(c);
        using var sf = new StringFormat(StringFormat.GenericTypographic) { Alignment = h, LineAlignment = v, Trimming = StringTrimming.EllipsisCharacter };
        if (!wrap) sf.FormatFlags |= StringFormatFlags.NoWrap;
        g.DrawString(s, f, br, r, sf);
    }
}

static class Program
{
    [STAThread]
    static void Main()
    {
        ApplicationConfiguration.Initialize();
        try
        {
            var wa = Screen.PrimaryScreen.WorkingArea; float dpi = 1f;
            using (var g0 = Graphics.FromHwnd(IntPtr.Zero)) dpi = g0.DpiX / 96f;
            Theme.Scale = Math.Max(0.7f, Math.Min(Math.Min(wa.Width * 0.92f / 1200f, wa.Height * 0.84f / 638f), 1.2f * dpi));
        }
        catch { Theme.Scale = 1f; }
        Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);
        Application.ThreadException += (s, e) => { AdminLog.Write(e.Exception); MessageBox.Show("Error: " + e.Exception.Message + "\n\nDetails: " + Path.Combine(Path.GetTempPath(), "photobooth-admin.log")); };
        AppDomain.CurrentDomain.UnhandledException += (s, e) => AdminLog.Write(e.ExceptionObject);
        Application.Run(new AdminForm());
    }
}

static class AdminSettings
{
    static string Dir => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "PhotoboothAdmin");
    // the printer the admin picked on the Print page ("" = Windows default printer)
    public static string Printer
    {
        get { try { return File.ReadAllText(Path.Combine(Dir, "printer.txt")).Trim(); } catch { return ""; } }
        set { try { Directory.CreateDirectory(Dir); File.WriteAllText(Path.Combine(Dir, "printer.txt"), value ?? ""); } catch (Exception ex) { AdminLog.Write(ex); } }
    }
}

static class Api
{
    public static readonly HttpClient H = new() { BaseAddress = new Uri("http://localhost:5080"), Timeout = TimeSpan.FromMinutes(10) };
    static string tok = "";
    public static string Pw = "";
    static string PwFile => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "PhotoboothAdmin", "pw.bin");
    public static string Saved() { try { return Encoding.UTF8.GetString(ProtectedData.Unprotect(File.ReadAllBytes(PwFile), null, DataProtectionScope.CurrentUser)); } catch { return ""; } }
    public static void Save(string p) { Directory.CreateDirectory(Path.GetDirectoryName(PwFile)); File.WriteAllBytes(PwFile, ProtectedData.Protect(Encoding.UTF8.GetBytes(p), null, DataProtectionScope.CurrentUser)); }
    public static HttpContent C(object o) => new StringContent(o is JsonNode n ? n.ToJsonString() : System.Text.Json.JsonSerializer.Serialize(o), Encoding.UTF8, "application/json");
    public static async Task<int> Login(string pw)
    {
        try
        {
            var r = await H.PostAsync("/api/v1/admin/login", C(new { password = pw }));
            if (!r.IsSuccessStatusCode) return (int)r.StatusCode;
            tok = JsonNode.Parse(await r.Content.ReadAsStringAsync())["token"].ToString(); Pw = pw; return 200;
        }
        catch { return 0; }
    }
    public static async Task<HttpResponseMessage> Req(HttpMethod m, string path, Func<HttpContent> body = null)
    {
        for (var i = 0; ; i++)
        {
            var q = new HttpRequestMessage(m, path) { Content = body?.Invoke() };
            q.Headers.Authorization = new AuthenticationHeaderValue("Bearer", tok);
            var r = await H.SendAsync(q, HttpCompletionOption.ResponseHeadersRead);
            if (r.StatusCode != System.Net.HttpStatusCode.Unauthorized || i == 1 || Pw == "" || await Login(Pw) != 200) return r;
        }
    }
    public static async Task<JsonNode> J(string path, string method = "GET", object body = null)
    {
        try
        {
            var r = await Req(new HttpMethod(method), "/api/v1" + path, body == null ? null : () => C(body));
            if (!r.IsSuccessStatusCode) return null;
            var s = await r.Content.ReadAsStringAsync();
            return s.Length > 0 && (s[0] == '{' || s[0] == '[') ? JsonNode.Parse(s) : new JsonObject();
        }
        catch { return null; }
    }
    public static async Task<bool> Upload(string path, string file, Dictionary<string, string> f)
    {
        try
        {
            var bytes = File.ReadAllBytes(file);
            var r = await Req(HttpMethod.Post, "/api/v1" + path, () =>
            {
                var c = new MultipartFormDataContent();
                foreach (var kv in f) c.Add(new StringContent(kv.Value), kv.Key);
                c.Add(new ByteArrayContent(bytes), "f", Path.GetFileName(file)); return c;
            });
            return r.IsSuccessStatusCode;
        }
        catch (Exception ex) { AdminLog.Write(ex); return false; }
    }
    public static async Task<byte[]> Bytes(string url) { try { return await H.GetByteArrayAsync(url); } catch { return null; } }
    public static async Task<byte[]> GetBytes(string path) { try { var r = await Req(HttpMethod.Get, path); return r.IsSuccessStatusCode ? await r.Content.ReadAsByteArrayAsync() : null; } catch { return null; } }
}

