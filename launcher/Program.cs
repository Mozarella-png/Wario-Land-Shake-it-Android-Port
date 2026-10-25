using System;
using System.Drawing;
using System.Windows.Forms;

namespace WarioLauncher
{
    internal static class Program
    {
        [STAThread]
        private static void Main()
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            using (var g = Graphics.FromHwnd(IntPtr.Zero)) Dpi.Factor = Math.Max(1f, g.DpiX / 96f);
            Application.Run(new MainForm());
        }
    }
}
