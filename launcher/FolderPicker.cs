using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace WarioLauncher
{
    // Selector de carpetas moderno (el mismo explorador de Windows de "Abrir archivo", con barra de
    // direcciones, accesos rapidos y busqueda) en lugar del arbol antiguo de FolderBrowserDialog.
    // .NET Framework no lo trae, asi que se usa IFileOpenDialog con FOS_PICKFOLDERS.
    public sealed class FolderPicker : IDisposable
    {
        public string Description = "";
        public string SelectedPath = "";
        public bool ShowNewFolderButton = true; // compatibilidad: el dialogo moderno siempre deja crear carpetas

        public DialogResult ShowDialog(IWin32Window owner)
        {
            IFileOpenDialog dlg = null;
            try
            {
                dlg = (IFileOpenDialog)new FileOpenDialogRCW();
                dlg.GetOptions(out uint opts);
                dlg.SetOptions(opts | FOS_PICKFOLDERS | FOS_FORCEFILESYSTEM | FOS_PATHMUSTEXIST);
                if (!string.IsNullOrEmpty(Description)) dlg.SetTitle(Description);
                string start = SelectedPath;
                while (!string.IsNullOrEmpty(start) && !Directory.Exists(start)) start = Path.GetDirectoryName(start);
                if (!string.IsNullOrEmpty(start) &&
                    SHCreateItemFromParsingName(start, IntPtr.Zero, typeof(IShellItem).GUID, out IShellItem folder) == 0)
                    dlg.SetFolder(folder);
                int hr = dlg.Show(owner?.Handle ?? IntPtr.Zero);
                if (hr != 0) return DialogResult.Cancel;
                dlg.GetResult(out IShellItem item);
                item.GetDisplayName(SIGDN_FILESYSPATH, out IntPtr p);
                try { SelectedPath = Marshal.PtrToStringUni(p); }
                finally { Marshal.FreeCoTaskMem(p); }
                return DialogResult.OK;
            }
            catch (COMException)
            {
                // Si el dialogo moderno no esta disponible, se usa el clasico.
                using (var fb = new FolderBrowserDialog { Description = Description, SelectedPath = SelectedPath, ShowNewFolderButton = ShowNewFolderButton })
                {
                    var r = fb.ShowDialog(owner);
                    if (r == DialogResult.OK) SelectedPath = fb.SelectedPath;
                    return r;
                }
            }
            finally
            {
                if (dlg != null) Marshal.ReleaseComObject(dlg);
            }
        }

        public void Dispose() { }

        private const uint FOS_PICKFOLDERS = 0x20, FOS_FORCEFILESYSTEM = 0x40, FOS_PATHMUSTEXIST = 0x800;
        private const uint SIGDN_FILESYSPATH = 0x80058000;

        [DllImport("shell32.dll", CharSet = CharSet.Unicode, PreserveSig = true)]
        private static extern int SHCreateItemFromParsingName(string path, IntPtr pbc, [MarshalAs(UnmanagedType.LPStruct)] Guid riid, out IShellItem item);

        [ComImport, Guid("DC1C5A9C-E88A-4dde-A5A1-60F82A20AEF7")]
        private class FileOpenDialogRCW { }

        [ComImport, Guid("42f85136-db7e-439c-85f1-e4075d135fc8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        private interface IFileOpenDialog
        {
            [PreserveSig] int Show(IntPtr parent);
            void SetFileTypes(uint cFileTypes, IntPtr rgFilterSpec);
            void SetFileTypeIndex(uint iFileType);
            void GetFileTypeIndex(out uint piFileType);
            void Advise(IntPtr pfde, out uint pdwCookie);
            void Unadvise(uint dwCookie);
            void SetOptions(uint fos);
            void GetOptions(out uint pfos);
            void SetDefaultFolder(IShellItem psi);
            void SetFolder(IShellItem psi);
            void GetFolder(out IShellItem ppsi);
            void GetCurrentSelection(out IShellItem ppsi);
            void SetFileName([MarshalAs(UnmanagedType.LPWStr)] string pszName);
            void GetFileName([MarshalAs(UnmanagedType.LPWStr)] out string pszName);
            void SetTitle([MarshalAs(UnmanagedType.LPWStr)] string pszTitle);
            void SetOkButtonLabel([MarshalAs(UnmanagedType.LPWStr)] string pszText);
            void SetFileNameLabel([MarshalAs(UnmanagedType.LPWStr)] string pszLabel);
            void GetResult(out IShellItem ppsi);
            void AddPlace(IShellItem psi, int fdap);
            void SetDefaultExtension([MarshalAs(UnmanagedType.LPWStr)] string pszDefaultExtension);
            void Close(int hr);
            void SetClientGuid(ref Guid guid);
            void ClearClientData();
            void SetFilter(IntPtr pFilter);
            void GetResults(out IntPtr ppenum);
            void GetSelectedItems(out IntPtr ppsai);
        }

        [ComImport, Guid("43826D1E-E718-42EE-BC55-A1E261C37BFE"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        private interface IShellItem
        {
            void BindToHandler(IntPtr pbc, ref Guid bhid, ref Guid riid, out IntPtr ppv);
            void GetParent(out IShellItem ppsi);
            void GetDisplayName(uint sigdnName, out IntPtr ppszName);
            void GetAttributes(uint sfgaoMask, out uint psfgaoAttribs);
            void Compare(IShellItem psi, uint hint, out int piOrder);
        }
    }
}
