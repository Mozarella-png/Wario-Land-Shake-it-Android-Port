using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Windows.Forms;

namespace WarioLauncher
{
    // Importa la imagen del disco (.iso, .ciso, .wbfs, .rvz, .wia, .gcz) con nodtool (encounter/nod, licencia MIT),
    // la misma herramienta que usa el instalador de WiiCompiled: la extrae a una carpeta DATA (sys\ + files\)
    // y comprueba que es Wario Land: Shake It! de EE. UU. con el main.dol exacto que se recompilo.
    public static class DiscImport
    {
        public const string NodVersion = "v2.0.0-alpha.10";
        public const string NodUrl = "https://github.com/encounter/nod/releases/download/" + NodVersion + "/nodtool-windows-x86_64.exe";
        public const string GameId = "RWLE01";
        public const string MainDolSha256 = "053BE60DDE84C8042F45ED2F4BA80C17EFB2D20FEDF95A414C2C7E37D336912A";
        public const string Filter = "Discos de Wii (*.iso;*.ciso;*.wbfs;*.rvz;*.wia;*.gcz)|*.iso;*.ciso;*.wbfs;*.rvz;*.wia;*.gcz|Todos (*.*)|*.*";

        public static string NodPath(string playDir) => Path.Combine(playDir, "Tools", "nodtool.exe");

        // Devuelve la carpeta DATA extraida, o null si se cancelo / fallo (el error ya se mostro).
        public static string Run(IWin32Window owner, string playDir, string imagePath, string destRoot)
        {
            string nod = NodPath(playDir);
            if (!File.Exists(nod))
            {
                if (MessageBox.Show(owner,
                        Loc.T("Para leer el disco hace falta nodtool (" + NodVersion + ", unos 3 MB, de github.com/encounter/nod, licencia MIT). ¿Descargarlo ahora?",
                              "Reading the disc needs nodtool (" + NodVersion + ", about 3 MB, from github.com/encounter/nod, MIT license). Download it now?"),
                        "nodtool", MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes) return null;
                string err = WithProgress(owner, Loc.T("Descargando nodtool…", "Downloading nodtool…"), () => Download(nod));
                if (err != null) { Error(owner, Loc.T("No se pudo descargar nodtool: ", "Could not download nodtool: ") + err); return null; }
            }

            string info = null, infoErr = null;
            infoErr = WithProgress(owner, Loc.T("Leyendo el disco…", "Reading the disc…"), () =>
            {
                var r = RunTool(nod, "info \"" + imagePath + "\"");
                if (r.Item1 != 0) return r.Item2.Trim();
                info = r.Item2;
                return null;
            });
            if (infoErr != null) { Error(owner, Loc.T("nodtool no pudo leer el disco:\n", "nodtool could not read the disc:\n") + infoErr); return null; }
            var id = Regex.Match(info ?? "", @"^Game ID: (\S+)", RegexOptions.Multiline);
            if (!id.Success) { Error(owner, Loc.T("No se reconoce el disco.", "The disc was not recognized.")); return null; }
            if (id.Groups[1].Value != GameId)
            {
                Error(owner, Loc.T("Este disco es " + id.Groups[1].Value + ". El port es para Wario Land: Shake It! de EE. UU. (" + GameId + ").",
                                   "This disc is " + id.Groups[1].Value + ". The port is for US Wario Land: Shake It! (" + GameId + ")."));
                return null;
            }

            string data = Path.Combine(destRoot, "DATA");
            if (Directory.Exists(data) && Directory.GetFileSystemEntries(data).Length > 0)
            {
                if (GameFolderMatches(data))
                {
                    MessageBox.Show(owner, Loc.T("El juego ya estaba extraído en " + data + ". Se usará esa carpeta.",
                        "The game was already extracted to " + data + ". That folder will be used."), "WarioRecomp");
                    return data;
                }
                Error(owner, Loc.T("La carpeta " + data + " ya tiene otros archivos. Elige otra carpeta de destino.",
                                   "The folder " + data + " already has other files. Pick another destination."));
                return null;
            }
            Directory.CreateDirectory(destRoot);
            string exErr = WithProgress(owner, Loc.T("Extrayendo el juego (unos 4 GB, puede tardar unos minutos)…", "Extracting the game (about 4 GB, may take a few minutes)…"), () =>
            {
                var r = RunTool(nod, "extract \"" + imagePath + "\" \"" + data + "\" -q");
                return r.Item1 != 0 ? r.Item2.Trim() : null;
            });
            if (exErr != null) { Error(owner, Loc.T("Falló la extracción:\n", "Extraction failed:\n") + exErr); return null; }
            if (!GameFolderMatches(data))
            {
                Error(owner, Loc.T("El disco se extrajo, pero su main.dol no coincide con la versión que usa el port (RWLE01, revisión 0 sin modificar).",
                                   "The disc was extracted, but its main.dol does not match the version the port uses (unmodified RWLE01, revision 0)."));
                return null;
            }
            return data;
        }

        public static bool GameFolderMatches(string data)
        {
            string dol = Path.Combine(data, "sys", "main.dol");
            if (!File.Exists(dol)) return false;
            try
            {
                using (var sha = SHA256.Create())
                using (var f = File.OpenRead(dol))
                {
                    var sb = new StringBuilder();
                    foreach (byte b in sha.ComputeHash(f)) sb.Append(b.ToString("X2"));
                    return sb.ToString() == MainDolSha256;
                }
            }
            catch { return false; }
        }

        private static string Download(string dest)
        {
            try
            {
                ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls12;
                Directory.CreateDirectory(Path.GetDirectoryName(dest));
                string tmp = dest + ".tmp";
                using (var wc = new WebClient()) wc.DownloadFile(NodUrl, tmp);
                if (new FileInfo(tmp).Length < 100000) { File.Delete(tmp); return "archivo incompleto"; }
                if (File.Exists(dest)) File.Delete(dest);
                File.Move(tmp, dest);
                return null;
            }
            catch (Exception ex) { return ex.Message; }
        }

        private static Tuple<int, string> RunTool(string exe, string args)
        {
            var psi = new ProcessStartInfo(exe, args)
            {
                UseShellExecute = false, CreateNoWindow = true,
                RedirectStandardOutput = true, RedirectStandardError = true,
                StandardOutputEncoding = Encoding.UTF8, StandardErrorEncoding = Encoding.UTF8,
            };
            using (var p = Process.Start(psi))
            {
                var err = new StringBuilder();
                p.ErrorDataReceived += (s, e) => { if (e.Data != null) lock (err) err.AppendLine(e.Data); };
                p.BeginErrorReadLine();
                string output = p.StandardOutput.ReadToEnd();
                p.WaitForExit();
                return Tuple.Create(p.ExitCode, output + err);
            }
        }

        // Ejecuta work en otro hilo mostrando una ventanita de progreso; devuelve el error o null.
        private static string WithProgress(IWin32Window owner, string text, Func<string> work)
        {
            string result = null;
            using (var dlg = new Form
            {
                Text = "WarioRecomp", FormBorderStyle = FormBorderStyle.FixedDialog, ControlBox = false,
                StartPosition = FormStartPosition.CenterParent, ClientSize = new Size(Dpi.S(440), Dpi.S(110)),
                BackColor = Theme.Back, ForeColor = Theme.Text, Font = Theme.UI(), ShowInTaskbar = false,
            })
            {
                dlg.Controls.Add(new Label { Text = text, AutoSize = false, Bounds = new Rectangle(Dpi.S(16), Dpi.S(14), Dpi.S(408), Dpi.S(40)) });
                dlg.Controls.Add(new ProgressBar { Style = ProgressBarStyle.Marquee, Bounds = new Rectangle(Dpi.S(16), Dpi.S(62), Dpi.S(408), Dpi.S(22)) });
                dlg.Shown += (s, e) =>
                {
                    var t = new Thread(() =>
                    {
                        try { result = work(); } catch (Exception ex) { result = ex.Message; }
                        dlg.BeginInvoke(new Action(dlg.Close));
                    }) { IsBackground = true };
                    t.Start();
                };
                dlg.ShowDialog(owner);
            }
            return result;
        }

        private static void Error(IWin32Window owner, string text) =>
            MessageBox.Show(owner, text, "WarioRecomp", MessageBoxButtons.OK, MessageBoxIcon.Warning);
    }
}
