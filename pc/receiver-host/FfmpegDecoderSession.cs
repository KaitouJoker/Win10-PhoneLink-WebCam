using System.Diagnostics;
using System.Text.RegularExpressions;

namespace PhoneCamReceiver;

/// <summary>
/// Manages an FFmpeg child process that decodes raw Annex-B H.264/HEVC stdin into BGRA32 stdout frames.
/// </summary>
public sealed partial class FfmpegDecoderSession : IDisposable
{
    private readonly Process _process;
    private readonly Stream _stdin;
    private readonly Stream _stdout;
    private readonly object _writeLock = new();
    private readonly CancellationTokenSource _cts = new();
    private readonly TaskCompletionSource<bool> _sizeReadyTcs = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private readonly Task _stderrTask;
    private readonly Task _stdoutTask;
    private readonly Action<byte[], int, int> _onFrame;
    private readonly Action<string> _onLog;

    private volatile int _width;
    private volatile int _height;
    private int _disposed;

    [GeneratedRegex(@"Video:\s+rawvideo\b.*?,\s*(\d{2,5})x(\d{2,5})\b", RegexOptions.IgnoreCase)]
    private static partial Regex OutputSizeRegex();

    private FfmpegDecoderSession(
        string ffmpegPath,
        string codec,
        DecoderMode mode,
        int gpuDeviceIndex,
        int fps,
        int initialWidth,
        int initialHeight,
        Action<byte[], int, int> onFrame,
        Action<string> onLog)
    {
        _width = Math.Clamp(initialWidth, 64, 3840);
        _height = Math.Clamp(initialHeight, 64, 2160);
        _onFrame = onFrame;
        _onLog = onLog;

        string args = BuildArguments(codec, mode, gpuDeviceIndex, fps);
        _onLog($"[FFmpeg] Starting: \"{ffmpegPath}\" {args}");

        var psi = new ProcessStartInfo
        {
            FileName = ffmpegPath,
            Arguments = args,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardInput = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        };

        _process = Process.Start(psi) ?? throw new InvalidOperationException("Failed to start ffmpeg.exe");
        _stdin = _process.StandardInput.BaseStream;
        _stdout = _process.StandardOutput.BaseStream;

        _stderrTask = Task.Run(() => ReadStderrLoop(_cts.Token));
        _stdoutTask = Task.Run(() => ReadStdoutLoop(_cts.Token));
    }

    public static string ResolveFfmpegPath()
    {
        string local = Path.Combine(AppContext.BaseDirectory, "ffmpeg.exe");
        if (File.Exists(local)) return local;

        const string defaultPath = @"D:\ffmpeg\bin\ffmpeg.exe";
        if (File.Exists(defaultPath)) return defaultPath;

        string? pathEnv = Environment.GetEnvironmentVariable("PATH");
        if (!string.IsNullOrEmpty(pathEnv))
        {
            foreach (string dir in pathEnv.Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries))
            {
                string candidate = Path.Combine(dir.Trim(), "ffmpeg.exe");
                if (File.Exists(candidate)) return candidate;
            }
        }
        throw new FileNotFoundException("ffmpeg.exe was not found next to PhoneCamReceiver.exe, in D:\\ffmpeg\\bin\\ffmpeg.exe, or in PATH.");
    }

    public static string BuildArguments(string codec, DecoderMode mode, int gpuDeviceIndex = -1, int fps = 60)
    {
        string c = Protocol.NormalizeCodec(codec);
        int safeFps = fps > 0 ? Math.Clamp(fps, 15, 120) : 60;
        string devFlag = gpuDeviceIndex >= 0 ? $" -hwaccel_device {gpuDeviceIndex}" : string.Empty;
        string decoderPart = mode switch
        {
            DecoderMode.Nvdec => c == "hevc"
                ? $"-hwaccel cuda{devFlag} -c:v hevc_cuvid"
                : $"-hwaccel cuda{devFlag} -c:v h264_cuvid",
            DecoderMode.D3d11va => $"-hwaccel d3d11va{devFlag} -c:v {c}",
            _ => $"-c:v {c}",
        };

        // Raw Annex-B streams lack container timestamps and default to 25 fps CFR in FFmpeg (dropping frames >25 fps).
        // Pass -framerate on input and -fps_mode passthrough on output so every decoded frame emits immediately.
        return $"-hide_banner -loglevel info -flags low_delay -fpsprobesize 0 -probesize 4096 -analyzeduration 0 " +
               $"{decoderPart} -framerate {safeFps} -f {c} -i pipe:0 -flush_packets 1 -fps_mode passthrough -f rawvideo -pix_fmt bgra pipe:1";
    }

    public static FfmpegDecoderSession Start(
        string codec,
        DecoderMode mode,
        int gpuDeviceIndex,
        int initialWidth,
        int initialHeight,
        Action<byte[], int, int> onFrame,
        Action<string> onLog,
        int fps = 60)
    {
        string ffmpegPath = ResolveFfmpegPath();
        return new FfmpegDecoderSession(ffmpegPath, codec, mode, gpuDeviceIndex, fps, initialWidth, initialHeight, onFrame, onLog);
    }

    public bool HasExited => _process.HasExited;

    public void WriteAnnexBPacket(ReadOnlySpan<byte> data)
    {
        if (data.IsEmpty) return;
        lock (_writeLock)
        {
            if (_disposed != 0 || _process.HasExited) return;
            try
            {
                _stdin.Write(data);
                _stdin.Flush();
            }
            catch (IOException)
            {
            }
            catch (ObjectDisposedException)
            {
            }
        }
    }

    private async Task ReadStderrLoop(CancellationToken ct)
    {
        try
        {
            using StreamReader reader = _process.StandardError;
            while (!ct.IsCancellationRequested)
            {
                string? line = await reader.ReadLineAsync(ct).ConfigureAwait(false);
                if (line == null) break;

                Match m = OutputSizeRegex().Match(line);
                if (m.Success &&
                    int.TryParse(m.Groups[1].Value, out int parsedW) &&
                    int.TryParse(m.Groups[2].Value, out int parsedH) &&
                    parsedW > 0 && parsedH > 0)
                {
                    _width = parsedW;
                    _height = parsedH;
                    _sizeReadyTcs.TrySetResult(true);
                }

                if (!string.IsNullOrWhiteSpace(line))
                {
                    _onLog($"[FFmpeg] {line}");
                }
            }
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception ex)
        {
            _onLog($"[FFmpeg stderr error] {ex.Message}");
        }
        finally
        {
            _sizeReadyTcs.TrySetResult(false);
        }
    }

    private async Task ReadStdoutLoop(CancellationToken ct)
    {
        try
        {
            byte[] firstByte = new byte[1];
            int firstRead = await _stdout.ReadAsync(firstByte.AsMemory(0, 1), ct).ConfigureAwait(false);
            if (firstRead == 0) return;

            if (!_sizeReadyTcs.Task.IsCompleted)
            {
                await Task.WhenAny(_sizeReadyTcs.Task, Task.Delay(50, ct)).ConfigureAwait(false);
            }

            byte[] frameBuf = new byte[_width * _height * 4];
            frameBuf[0] = firstByte[0];
            int initialOffset = 1;

            while (!ct.IsCancellationRequested)
            {
                int w = _width;
                int h = _height;
                int frameBytes = w * h * 4;
                if (frameBuf.Length != frameBytes)
                {
                    var resized = new byte[frameBytes];
                    if (initialOffset > 0)
                    {
                        Buffer.BlockCopy(frameBuf, 0, resized, 0, Math.Min(initialOffset, frameBytes));
                    }
                    frameBuf = resized;
                }

                int readTotal = initialOffset;
                initialOffset = 0;
                while (readTotal < frameBytes)
                {
                    int n = await _stdout.ReadAsync(frameBuf.AsMemory(readTotal, frameBytes - readTotal), ct).ConfigureAwait(false);
                    if (n == 0) return;
                    readTotal += n;
                }

                _onFrame(frameBuf, w, h);
            }
        }
        catch (OperationCanceledException)
        {
        }
        catch (IOException)
        {
        }
        catch (Exception ex)
        {
            _onLog($"[FFmpeg stdout error] {ex.Message}");
        }
    }

    public void Dispose()
    {
        lock (_writeLock)
        {
            if (Interlocked.Exchange(ref _disposed, 1) != 0) return;
            _cts.Cancel();
            try { _stdin.Close(); } catch { }
        }

        try
        {
            if (!_process.HasExited)
            {
                if (!_process.WaitForExit(800))
                {
                    _process.Kill(entireProcessTree: true);
                }
            }
        }
        catch { }

        try { Task.WaitAll([_stderrTask, _stdoutTask], 500); } catch { }
        _cts.Dispose();
        _process.Dispose();
    }
}
