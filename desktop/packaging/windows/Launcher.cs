// Single-file Windows build. This small launcher carries the whole app (the jpackage folder) as a zip appended to
// the exe. The first start of a version unpacks it into %LOCALAPPDATA%\KmpTest\app\<id>; every start then
// runs the app from there with the same arguments and returns its exit code.
//
// Layout of the exe: [launcher][zip][id (UTF-8)][int32 id length][int64 zip offset][int64 zip length]["KMTPACK1"]
// (little-endian). Written by the desktop module's packageSingleExe task.
//
// Built with the C# compiler that ships with the .NET Framework (C# 5), so no SDK is needed.

using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Text;
using System.Threading;
using System.Windows.Forms;

static class Launcher
{
    const string AppName = "KmpTest";
    const string Title = "Kmp Test";
    const string Marker = ".complete";

    [STAThread]
    static int Main(string[] args)
    {
        try
        {
            string self = Assembly.GetExecutingAssembly().Location;
            Payload payload = Payload.Read(self);
            string root = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), AppName, "app");
            string dir = Path.Combine(root, payload.Id);
            using (Mutex mutex = new Mutex(false, "Local\\" + AppName + "-unpack"))
            {
                try { mutex.WaitOne(); } catch (AbandonedMutexException) { }
                try
                {
                    if (!File.Exists(Path.Combine(dir, Marker))) Unpack(self, payload, root, dir);
                    RemoveOldVersions(root, payload.Id);
                }
                finally { mutex.ReleaseMutex(); }
            }
            return Run(Path.Combine(dir, AppName + ".exe"), args);
        }
        catch (Exception e)
        {
            MessageBox.Show(e.Message, Title, MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }
    }

    /** Unpacks into a temporary folder and renames it when complete, so a broken unpack is never used. */
    static void Unpack(string self, Payload payload, string root, string dir)
    {
        Directory.CreateDirectory(root);
        string tmp = dir + ".tmp" + Process.GetCurrentProcess().Id;
        if (Directory.Exists(tmp)) Directory.Delete(tmp, true);
        if (Directory.Exists(dir)) Directory.Delete(dir, true);

        Exception error = null;
        using (Form form = new Form())
        {
            form.Text = Title;
            form.FormBorderStyle = FormBorderStyle.FixedDialog;
            form.StartPosition = FormStartPosition.CenterScreen;
            form.MaximizeBox = false;
            form.MinimizeBox = false;
            form.ClientSize = new Size(380, 90);
            try { form.Icon = Icon.ExtractAssociatedIcon(self); } catch (Exception) { }
            Label label = new Label();
            label.Text = "Preparing the app (first start of this version only)...";
            label.SetBounds(16, 16, 348, 20);
            ProgressBar bar = new ProgressBar();
            bar.SetBounds(16, 46, 348, 22);
            form.Controls.Add(label);
            form.Controls.Add(bar);
            form.Shown += delegate
            {
                Thread worker = new Thread(delegate()
                {
                    try
                    {
                        Extract(self, payload, tmp, delegate(int percent)
                        {
                            form.BeginInvoke((MethodInvoker)delegate { bar.Value = percent; });
                        });
                    }
                    catch (Exception e) { error = e; }
                    form.BeginInvoke((MethodInvoker)delegate { form.Close(); });
                });
                worker.IsBackground = true;
                worker.Start();
            };
            Application.EnableVisualStyles();
            Application.Run(form);
        }
        if (error != null)
        {
            try { Directory.Delete(tmp, true); } catch (Exception) { }
            throw new IOException("Cannot unpack the app into " + root + ": " + error.Message, error);
        }
        File.WriteAllText(Path.Combine(tmp, Marker), payload.Id);
        Directory.Move(tmp, dir);
    }

    delegate void Progress(int percent);

    static void Extract(string self, Payload payload, string target, Progress progress)
    {
        string full = Path.GetFullPath(target) + Path.DirectorySeparatorChar;
        using (FileStream file = new FileStream(self, FileMode.Open, FileAccess.Read, FileShare.Read))
        using (ZipArchive zip = new ZipArchive(new SubStream(file, payload.ZipOffset, payload.ZipLength), ZipArchiveMode.Read))
        {
            long total = 0, done = 0;
            foreach (ZipArchiveEntry entry in zip.Entries) total += entry.Length;
            int shown = -1;
            foreach (ZipArchiveEntry entry in zip.Entries)
            {
                string path = Path.GetFullPath(Path.Combine(target, entry.FullName.Replace('/', Path.DirectorySeparatorChar)));
                if (!path.StartsWith(full, StringComparison.OrdinalIgnoreCase)) throw new IOException("Bad entry: " + entry.FullName);
                if (entry.FullName.EndsWith("/")) { Directory.CreateDirectory(path); continue; }
                Directory.CreateDirectory(Path.GetDirectoryName(path));
                using (Stream input = entry.Open())
                using (FileStream output = File.Create(path)) input.CopyTo(output);
                done += entry.Length;
                int percent = total == 0 ? 100 : (int)(done * 100 / total);
                if (percent != shown) { shown = percent; progress(percent); }
            }
        }
    }

    /** Deletes folders of other versions. A folder still in use is renamed first, which fails, so it is kept. */
    static void RemoveOldVersions(string root, string current)
    {
        foreach (string old in Directory.GetDirectories(root))
        {
            if (string.Equals(Path.GetFileName(old), current, StringComparison.OrdinalIgnoreCase)) continue;
            try
            {
                string doomed = old + ".delete";
                Directory.Move(old, doomed);
                Directory.Delete(doomed, true);
            }
            catch (Exception) { }
        }
    }

    static int Run(string exe, string[] args)
    {
        ProcessStartInfo info = new ProcessStartInfo(exe);
        StringBuilder line = new StringBuilder();
        foreach (string a in args)
        {
            if (line.Length > 0) line.Append(' ');
            line.Append(Quote(a));
        }
        info.Arguments = line.ToString();
        info.UseShellExecute = false;
        info.WorkingDirectory = Environment.CurrentDirectory;
        using (Process p = Process.Start(info))
        {
            p.WaitForExit();
            return p.ExitCode;
        }
    }

    /** Quotes one argument the way the Microsoft C runtime parses command lines. */
    static string Quote(string a)
    {
        if (a.Length > 0 && a.IndexOfAny(new char[] { ' ', '\t', '"' }) < 0) return a;
        StringBuilder sb = new StringBuilder("\"");
        int backslashes = 0;
        foreach (char c in a)
        {
            if (c == '\\') { backslashes++; continue; }
            if (c == '"') { sb.Append('\\', backslashes * 2 + 1); sb.Append('"'); }
            else { sb.Append('\\', backslashes); sb.Append(c); }
            backslashes = 0;
        }
        sb.Append('\\', backslashes * 2);
        sb.Append('"');
        return sb.ToString();
    }
}

class Payload
{
    const string Magic = "KMTPACK1";
    const int Fixed = 4 + 8 + 8 + 8;

    public string Id;
    public long ZipOffset;
    public long ZipLength;

    public static Payload Read(string path)
    {
        using (FileStream file = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read))
        using (BinaryReader reader = new BinaryReader(file))
        {
            if (file.Length < Fixed) throw new InvalidDataException("This launcher carries no app.");
            file.Seek(-Fixed, SeekOrigin.End);
            int idLength = reader.ReadInt32();
            Payload p = new Payload();
            p.ZipOffset = reader.ReadInt64();
            p.ZipLength = reader.ReadInt64();
            if (Encoding.ASCII.GetString(reader.ReadBytes(8)) != Magic) throw new InvalidDataException("This launcher carries no app.");
            file.Seek(-Fixed - idLength, SeekOrigin.End);
            p.Id = Encoding.UTF8.GetString(reader.ReadBytes(idLength));
            return p;
        }
    }
}

/** A read-only window onto part of another stream. */
class SubStream : Stream
{
    readonly Stream inner;
    readonly long start, length;
    long position;

    public SubStream(Stream inner, long start, long length)
    {
        this.inner = inner;
        this.start = start;
        this.length = length;
    }

    public override bool CanRead { get { return true; } }
    public override bool CanSeek { get { return true; } }
    public override bool CanWrite { get { return false; } }
    public override long Length { get { return length; } }
    public override long Position { get { return position; } set { position = value; } }

    public override int Read(byte[] buffer, int offset, int count)
    {
        long left = length - position;
        if (left <= 0) return 0;
        if (count > left) count = (int)left;
        inner.Seek(start + position, SeekOrigin.Begin);
        int n = inner.Read(buffer, offset, count);
        position += n;
        return n;
    }

    public override long Seek(long offset, SeekOrigin origin)
    {
        if (origin == SeekOrigin.Begin) position = offset;
        else if (origin == SeekOrigin.Current) position += offset;
        else position = length + offset;
        return position;
    }

    public override void Flush() { }
    public override void SetLength(long value) { throw new NotSupportedException(); }
    public override void Write(byte[] buffer, int offset, int count) { throw new NotSupportedException(); }
}
