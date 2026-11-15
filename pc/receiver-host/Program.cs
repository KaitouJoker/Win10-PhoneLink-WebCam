using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace PhoneCamReceiver;

internal static class Program
{
    [DllImport("kernel32.dll")]
    private static extern bool AttachConsole(int dwProcessId);

    private const int AttachParentProcess = -1;

    [STAThread]
    private static int Main(string[] args)
    {
        if (HasFlag(args, "--help") || HasFlag(args, "-h"))
        {
            EnsureConsoleIfInteractive();
            PrintHelp();
            return 0;
        }

        if (HasFlag(args, "--headless-test"))
        {
            EnsureConsoleIfInteractive();
            return RunHeadlessTest(args);
        }

        if (HasFlag(args, "--print-qr-json"))
        {
            EnsureConsoleIfInteractive();
            int port = GetIntArg(args, "--port", Protocol.DefaultPort);
            string token = GetStringArg(args, "--token", Protocol.GenerateHexToken16());
            string ip = GetStringArg(args, "--ip", Protocol.EnumerateLanIps().FirstOrDefault() ?? "127.0.0.1");
            Console.WriteLine(Protocol.BuildQrJson(ip, port, token));
            return 0;
        }

        string saveQrPath = GetStringArg(args, "--save-qr", string.Empty);
        if (!string.IsNullOrEmpty(saveQrPath))
        {
            EnsureConsoleIfInteractive();
            int port = GetIntArg(args, "--port", Protocol.DefaultPort);
            string token = GetStringArg(args, "--token", Protocol.GenerateHexToken16());
            string ip = GetStringArg(args, "--ip", Protocol.EnumerateLanIps().FirstOrDefault() ?? "127.0.0.1");
            string json = Protocol.BuildQrJson(ip, port, token);
            using var bmp = QrCodeGenerator.RenderBitmap(json, targetPixels: 320);
            bmp.Save(saveQrPath, System.Drawing.Imaging.ImageFormat.Png);
            Console.WriteLine(json);
            return 0;
        }

        ApplicationConfiguration.Initialize();
        Application.Run(new MainForm());
        return 0;
    }

    private static int RunHeadlessTest(string[] args)
    {
        int port = GetIntArg(args, "--port", Protocol.DefaultPort);
        string token = GetStringArg(args, "--token", Protocol.GenerateHexToken16());
        DecoderMode decoder = Protocol.ParseDecoderMode(GetStringArg(args, "--decoder", "nvdec"));
        CodecPreference codecPref = Protocol.ParseCodecPreference(GetStringArg(args, "--codec", "auto"));
        int gpuIdx = GetIntArg(args, "--gpu", 0);
        int targetW = GetIntArg(args, "--width", 0);
        int targetH = GetIntArg(args, "--height", 0);
        int durationSec = Math.Max(1, GetIntArg(args, "--duration", 10));
        bool flipH = HasFlag(args, "--flip-h");
        bool flipV = HasFlag(args, "--flip-v");

        Console.WriteLine($"[Headless] Starting PhoneCamReceiver port={port} decoder={decoder} gpu={gpuIdx} codec={codecPref} flipH={flipH} flipV={flipV} duration={durationSec}s");
        Console.Out.Flush();

        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(durationSec));
        Console.CancelKeyPress += (_, e) =>
        {
            e.Cancel = true;
            cts.Cancel();
        };

        using var shm = new ShmWriter();
        using var server = new TcpStreamServer(shm, port, token, decoder, line =>
        {
            Console.WriteLine(line);
            Console.Out.Flush();
        }, gpuDeviceIndex: gpuIdx, codecPreference: codecPref, targetWidth: targetW, targetHeight: targetH, flipHorizontal: flipH, flipVertical: flipV);

        try
        {
            server.Start();
            var sw = Stopwatch.StartNew();
            while (!cts.IsCancellationRequested)
            {
                Thread.Sleep(500);
                StreamTelemetry t = server.GetTelemetry();
                if (t.Connected)
                {
                    Console.WriteLine(
                        $"[Headless] t={sw.Elapsed.TotalSeconds:F1}s device=\"{t.DeviceName}\" codec={t.Codec} " +
                        $"decoder=\"{t.ActiveDecoder}\" {t.Width}x{t.Height} fps={t.Fps:F1} frames={t.TotalFrames} write_seq={shm.CurrentWriteSeq}");
                    Console.Out.Flush();
                }
            }

            StreamTelemetry finalT = server.GetTelemetry();
            Console.WriteLine($"[Headless] Finished. TotalFrames={finalT.TotalFrames} FinalWriteSeq={shm.CurrentWriteSeq}");
            Console.Out.Flush();
            return 0;
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"[Headless] Error: {ex}");
            return 1;
        }
    }

    private static void EnsureConsoleIfInteractive()
    {
        if (!Console.IsOutputRedirected && !Console.IsErrorRedirected)
        {
            AttachConsole(AttachParentProcess);
        }
    }

    private static bool HasFlag(string[] args, string flag) =>
        args.Any(a => string.Equals(a, flag, StringComparison.OrdinalIgnoreCase));

    private static string GetStringArg(string[] args, string name, string defaultValue)
    {
        for (int i = 0; i < args.Length - 1; i++)
        {
            if (string.Equals(args[i], name, StringComparison.OrdinalIgnoreCase))
            {
                return args[i + 1];
            }
        }
        return defaultValue;
    }

    private static int GetIntArg(string[] args, string name, int defaultValue)
    {
        string raw = GetStringArg(args, name, string.Empty);
        return int.TryParse(raw, out int parsed) ? parsed : defaultValue;
    }

    private static void PrintHelp()
    {
        Console.WriteLine("PhoneCamReceiver.exe options:");
        Console.WriteLine("  (no args)                   Launch WinForms GUI");
        Console.WriteLine("  --headless-test             Run without GUI for automated E2E tests");
        Console.WriteLine("    --port <port>             TCP listen port (default: 9876)");
        Console.WriteLine("    --token <hex>             Expected pairing token");
        Console.WriteLine("    --decoder <mode>          nvdec | d3d11va | cpu (default: nvdec)");
        Console.WriteLine("    --duration <seconds>      Run duration before clean exit (default: 10)");
        Console.WriteLine("  --print-qr-json             Print QR pairing JSON to stdout");
    }
}
