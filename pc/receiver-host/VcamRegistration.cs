using System.ComponentModel;
using System.Diagnostics;
using System.Security.Principal;
using Microsoft.Win32;

namespace PhoneCamReceiver;

/// <summary>
/// Helpers to inspect COM registration status of Win10 PhoneLink Virtual Camera
/// (x64 vcam-dshow.dll + x86 vcam-dshow32.dll) and invoke regsvr32 for registration.
/// </summary>
public static class VcamRegistration
{
    public const string VcamClsid = "{8E4C7A21-5B93-4F8E-9C12-6A4D3E8F1022}";
    public const string VideoInputCategoryClsid = "{860BB310-5D01-11d0-BD3B-00A0C911CE86}";
    public const string DllFileName = "vcam-dshow.dll";
    public const string Dll32FileName = "vcam-dshow32.dll";

    public static string? FindVcamDllPath(string fileName = DllFileName)
    {
        string nextToExe = Path.Combine(AppContext.BaseDirectory, fileName);
        if (File.Exists(nextToExe)) return nextToExe;

        string cwd = Path.Combine(Directory.GetCurrentDirectory(), fileName);
        if (File.Exists(cwd)) return cwd;

        // Useful fallback when running directly from pc\receiver-host\bin\...
        string dir = AppContext.BaseDirectory;
        for (int i = 0; i < 6; i++)
        {
            string candidate = Path.Combine(dir, "dist", "Portable-Win10-PhoneLink-WebCam", fileName);
            if (File.Exists(candidate)) return Path.GetFullPath(candidate);
            DirectoryInfo? parent = Directory.GetParent(dir);
            if (parent == null) break;
            dir = parent.FullName;
        }

        return null;
    }

    public static bool IsProcessElevated()
    {
        using WindowsIdentity identity = WindowsIdentity.GetCurrent();
        var principal = new WindowsPrincipal(identity);
        return principal.IsInRole(WindowsBuiltInRole.Administrator);
    }

    public static bool IsDiscordRunning()
    {
        try
        {
            return Process.GetProcessesByName("Discord").Length > 0;
        }
        catch
        {
            return false;
        }
    }

    public static bool IsRegisteredInHklm(out string? registeredDllPath)
    {
        registeredDllPath = null;
        try
        {
            using RegistryKey hklm = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64);
            using RegistryKey? inproc = hklm.OpenSubKey($@"SOFTWARE\Classes\CLSID\{VcamClsid}\InprocServer32");
            string? path = inproc?.GetValue(null) as string;
            if (string.IsNullOrWhiteSpace(path)) return false;

            using RegistryKey? inst = hklm.OpenSubKey($@"SOFTWARE\Classes\CLSID\{VideoInputCategoryClsid}\Instance\{VcamClsid}");
            if (inst == null) return false;

            registeredDllPath = path;
            return true;
        }
        catch
        {
            return false;
        }
    }

    public static bool IsRegistered(out string? registeredPath)
    {
        if (IsRegisteredInHklm(out registeredPath))
        {
            return true;
        }
        try
        {
            using RegistryKey? key = Registry.ClassesRoot.OpenSubKey($@"CLSID\{VcamClsid}\InprocServer32");
            registeredPath = key?.GetValue(null) as string;
            return !string.IsNullOrWhiteSpace(registeredPath);
        }
        catch
        {
            return false;
        }
    }

    public static (bool Ok, string Message) Register() => RunRegsvr32(unregister: false);

    public static (bool Ok, string Message) Unregister() => RunRegsvr32(unregister: true);

    private static (bool Ok, string Message) RunRegsvr32(bool unregister)
    {
        string? dll64 = FindVcamDllPath(DllFileName);
        if (dll64 == null)
        {
            return (false, $"{DllFileName} not found in application folder.");
        }

        string? dll32 = FindVcamDllPath(Dll32FileName);
        if (dll32 == null)
        {
            string sibling32 = Path.Combine(Path.GetDirectoryName(dll64)!, Dll32FileName);
            if (File.Exists(sibling32)) dll32 = sibling32;
        }

        string sys64Regsvr = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Windows), "System32", "regsvr32.exe");
        string sys32Regsvr = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Windows), "SysWOW64", "regsvr32.exe");
        if (!File.Exists(sys64Regsvr)) sys64Regsvr = "regsvr32.exe";

        string flag = unregister ? "/u /s" : "/s";
        string cmdChain = $"\"{sys64Regsvr}\" {flag} \"{dll64}\"";
        if (dll32 != null && File.Exists(sys32Regsvr))
        {
            cmdChain += $" && \"{sys32Regsvr}\" {flag} \"{dll32}\"";
        }

        bool elevated = IsProcessElevated();
        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = "cmd.exe",
                Arguments = $"/c \"{cmdChain}\"",
                UseShellExecute = !elevated,
                CreateNoWindow = true,
                WindowStyle = ProcessWindowStyle.Hidden,
                Verb = elevated ? string.Empty : "runas",
            };
            using Process? proc = Process.Start(psi);
            if (proc == null)
            {
                return (false, "Failed to launch regsvr32.exe.");
            }
            proc.WaitForExit();
            if (proc.ExitCode != 0)
            {
                return (false, $"regsvr32 exited with code {proc.ExitCode}.");
            }

            if (unregister)
            {
                return (true, "Virtual camera unregistered (x64 + x86).");
            }

            bool hklmOk = IsRegisteredInHklm(out _);
            string baseMsg = hklmOk
                ? "Virtual camera registered system-wide in HKLM (x64 + x86)."
                : "Virtual camera registered in HKCU.";
            if (IsDiscordRunning())
            {
                baseMsg += " Discord is running: press Ctrl+R or restart Discord to detect the camera.";
            }
            return (true, baseMsg);
        }
        catch (Win32Exception ex) when (ex.NativeErrorCode == 1223)
        {
            if (!unregister)
            {
                // UAC cancelled: fall back to non-elevated HKCU registration so single-user DirectShow apps still work.
                int rc = RunSingleRegsvrNonElevated(sys64Regsvr, $"{flag} \"{dll64}\"");
                if (rc == 0)
                {
                    return (true, "UAC cancelled; registered in HKCU only (admin required for Discord/HKLM).");
                }
            }
            return (false, "Administrator elevation (UAC) was cancelled.");
        }
        catch (Exception ex)
        {
            return (false, $"regsvr32 failed: {ex.Message}");
        }
    }

    private static int RunSingleRegsvrNonElevated(string exe, string args)
    {
        try
        {
            using Process? p = Process.Start(new ProcessStartInfo
            {
                FileName = exe,
                Arguments = args,
                UseShellExecute = false,
                CreateNoWindow = true,
            });
            if (p == null) return -1;
            p.WaitForExit();
            return p.ExitCode;
        }
        catch
        {
            return -1;
        }
    }
}
