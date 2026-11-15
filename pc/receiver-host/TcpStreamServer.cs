using System.Buffers.Binary;
using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;

namespace PhoneCamReceiver;

public sealed record StreamTelemetry(
    bool Connected,
    string DeviceName,
    string Codec,
    string EncoderName,
    string ActiveDecoder,
    int Width,
    int Height,
    double Fps,
    long TotalFrames,
    bool Paused,
    string Camera,
    int RotationDegrees);

public delegate void FramePreviewHandler(ReadOnlySpan<byte> bgraFrame, int width, int height);

/// <summary>
/// Listens on TCP for Android companion connections ("PLWC" handshake + 16-byte framed Annex-B stream),
/// feeds FFmpeg hardware/software decoder, and publishes BGRA32 frames to ShmWriter.
/// </summary>
public sealed class TcpStreamServer : IDisposable
{
    private readonly ShmWriter _shm;
    private readonly Action<string> _log;
    private readonly object _sessionLock = new();

    private TcpListener? _listener;
    private CancellationTokenSource? _serverCts;
    private Task? _acceptTask;

    private CancellationTokenSource? _clientCts;
    private Task? _clientTask;

    private FfmpegDecoderSession? _decoder;
    private byte[]? _cachedCodecConfig;
    private byte[] _transformScratch = [];
    private string _currentCodec = "h264";
    private int _currentWidth = 1280;
    private int _currentHeight = 720;
    private int _currentFps = 60;
    private int _rotationQuarterTurns;
    private int _lastPacketFlagsRotation = -1;
    private bool _remotePaused;
    private bool _manualPaused;
    private string _camera = "back";
    private string _deviceName = "-";
    private string _encoderName = "-";
    private bool _connected;

    private long _totalFrames;
    private long _fpsWindowFrames;
    private long _fpsWindowStartTs = Stopwatch.GetTimestamp();
    private double _measuredFps;

    private volatile int _port;
    private volatile string _token;
    private volatile DecoderMode _decoderMode;
    private volatile int _gpuDeviceIndex;
    private volatile string? _gpuDeviceName;
    private volatile CodecPreference _codecPreference;
    private volatile int _targetWidth;
    private volatile int _targetHeight;
    private bool _disposed;

    public event FramePreviewHandler? FrameAvailable;

    public TcpStreamServer(
        ShmWriter shm,
        int port,
        string token,
        DecoderMode decoderMode,
        Action<string> log,
        int gpuDeviceIndex = -1,
        string? gpuDeviceName = null,
        CodecPreference codecPreference = CodecPreference.Auto,
        int targetWidth = 0,
        int targetHeight = 0)
    {
        _shm = shm;
        _port = port;
        _token = token;
        _decoderMode = decoderMode;
        _gpuDeviceIndex = gpuDeviceIndex;
        _gpuDeviceName = gpuDeviceName;
        _codecPreference = codecPreference;
        _targetWidth = targetWidth;
        _targetHeight = targetHeight;
        _log = log;
    }

    public int Port => _port;
    public string Token => _token;
    public DecoderMode DecoderMode => _decoderMode;
    public int GpuDeviceIndex => _gpuDeviceIndex;
    public CodecPreference CodecPreference => _codecPreference;
    public int TargetWidth => _targetWidth;
    public int TargetHeight => _targetHeight;

    public void Start()
    {
        Stop();
        _serverCts = new CancellationTokenSource();
        _listener = new TcpListener(IPAddress.Any, _port);
        _listener.Start();
        _log($"[Server] Listening on 0.0.0.0:{_port} (Decoder={_decoderMode})");
        _acceptTask = Task.Run(() => AcceptLoopAsync(_listener, _serverCts.Token));
    }

    public void Reconfigure(int newPort, string newToken)
    {
        bool portChanged = newPort != _port;
        _token = newToken;
        if (portChanged)
        {
            _port = newPort;
            Start();
        }
        else
        {
            _log($"[Server] Pairing token updated.");
        }
    }

    public void SetDecoderMode(DecoderMode mode)
    {
        SetDecoderConfig(mode, _gpuDeviceIndex, _gpuDeviceName);
    }

    public void SetDecoderConfig(DecoderMode mode, int gpuDeviceIndex, string? gpuDeviceName)
    {
        bool changed = _decoderMode != mode || _gpuDeviceIndex != gpuDeviceIndex;
        _decoderMode = mode;
        _gpuDeviceIndex = gpuDeviceIndex;
        _gpuDeviceName = gpuDeviceName;
        if (!changed) return;

        _log($"[Server] Decoder updated: mode={mode}, gpu={gpuDeviceIndex} ({gpuDeviceName ?? "Auto"})");
        lock (_sessionLock)
        {
            if (_connected && _decoder != null)
            {
                RestartDecoderLocked();
            }
        }
    }

    public void SetCodecPreference(CodecPreference codecPref)
    {
        if (_codecPreference == codecPref) return;
        _codecPreference = codecPref;
        _log($"[Server] Codec preference updated: {codecPref}");

        lock (_sessionLock)
        {
            if (_connected && _decoder != null && codecPref != CodecPreference.Auto)
            {
                string nextCodec = codecPref == CodecPreference.Hevc ? "hevc" : "h264";
                if (!string.Equals(_currentCodec, nextCodec, StringComparison.OrdinalIgnoreCase))
                {
                    _currentCodec = nextCodec;
                    RestartDecoderLocked();
                }
            }
        }
    }

    public void SetTargetResolution(int targetWidth, int targetHeight)
    {
        _targetWidth = targetWidth;
        _targetHeight = targetHeight;
        _log(targetWidth > 0 && targetHeight > 0
            ? $"[Server] Target output resolution set to {targetWidth}x{targetHeight}"
            : "[Server] Target output resolution set to Auto (source native)");
    }

    public void SetManualPaused(bool paused)
    {
        _manualPaused = paused;
        if (paused || _remotePaused)
        {
            _shm.SetState(FrameState.Paused);
        }
    }

    public StreamTelemetry GetTelemetry()
    {
        long now = Stopwatch.GetTimestamp();
        double elapsed = (double)(now - Volatile.Read(ref _fpsWindowStartTs)) / Stopwatch.Frequency;
        if (elapsed >= 1.0)
        {
            long frames = Interlocked.Exchange(ref _fpsWindowFrames, 0);
            Volatile.Write(ref _fpsWindowStartTs, now);
            _measuredFps = frames / elapsed;
        }

        lock (_sessionLock)
        {
            return new StreamTelemetry(
                Connected: _connected,
                DeviceName: _connected ? _deviceName : "Waiting for connection...",
                Codec: _connected ? _currentCodec.ToUpperInvariant() : "-",
                EncoderName: _connected ? _encoderName : "-",
                ActiveDecoder: Protocol.FormatDecoderDisplayName(_decoderMode, _currentCodec, _gpuDeviceIndex, _gpuDeviceName),
                Width: _targetWidth > 0 ? _targetWidth : _currentWidth,
                Height: _targetHeight > 0 ? _targetHeight : _currentHeight,
                Fps: _connected ? _measuredFps : 0.0,
                TotalFrames: Interlocked.Read(ref _totalFrames),
                Paused: _manualPaused || _remotePaused,
                Camera: _connected ? _camera : "-",
                RotationDegrees: _rotationQuarterTurns * 90);
        }
    }

    private async Task AcceptLoopAsync(TcpListener listener, CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            TcpClient client;
            try
            {
                client = await listener.AcceptTcpClientAsync(ct).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (ObjectDisposedException)
            {
                break;
            }
            catch (SocketException ex)
            {
                if (!ct.IsCancellationRequested)
                {
                    _log($"[Server] Accept error: {ex.Message}");
                }
                break;
            }

            CancellationTokenSource? prevCts;
            Task? prevTask;
            lock (_sessionLock)
            {
                prevCts = _clientCts;
                prevTask = _clientTask;
            }

            if (prevCts != null)
            {
                try { prevCts.Cancel(); } catch { }
                if (prevTask != null)
                {
                    try { await prevTask.WaitAsync(TimeSpan.FromSeconds(1), ct).ConfigureAwait(false); } catch { }
                }
            }

            var clientCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            Task clientTask = Task.Run(() => HandleClientAsync(client, clientCts.Token), ct);
            lock (_sessionLock)
            {
                _clientCts = clientCts;
                _clientTask = clientTask;
            }
        }
    }

    private async Task HandleClientAsync(TcpClient client, CancellationToken ct)
    {
        string remoteEndPoint = client.Client.RemoteEndPoint?.ToString() ?? "unknown";
        try
        {
            using (client)
            {
                client.NoDelay = true;
                client.ReceiveBufferSize = 1024 * 1024;
                NetworkStream stream = client.GetStream();

                byte[] magicBuf = new byte[4];
                if (!await ReadExactAsync(stream, magicBuf, ct).ConfigureAwait(false))
                {
                    return;
                }
                if (!magicBuf.AsSpan().SequenceEqual(Protocol.MagicBytes))
                {
                    _log($"[Server] Rejected {remoteEndPoint}: invalid magic");
                    return;
                }

                byte[] lenBuf = new byte[4];
                if (!await ReadExactAsync(stream, lenBuf, ct).ConfigureAwait(false))
                {
                    return;
                }
                uint jsonLen = BinaryPrimitives.ReadUInt32BigEndian(lenBuf);
                if (jsonLen == 0 || jsonLen > Protocol.MaxHandshakeJsonBytes)
                {
                    _log($"[Server] Rejected {remoteEndPoint}: invalid handshake length {jsonLen}");
                    return;
                }

                byte[] jsonBuf = new byte[jsonLen];
                if (!await ReadExactAsync(stream, jsonBuf, ct).ConfigureAwait(false))
                {
                    return;
                }

                HandshakeMessage? handshake;
                try
                {
                    handshake = JsonSerializer.Deserialize<HandshakeMessage>(jsonBuf);
                }
                catch (JsonException ex)
                {
                    _log($"[Server] Rejected {remoteEndPoint}: malformed handshake JSON ({ex.Message})");
                    return;
                }

                if (handshake == null || !string.Equals(handshake.Token, _token, StringComparison.Ordinal))
                {
                    _log($"[Server] Rejected {remoteEndPoint}: token mismatch");
                    return;
                }

                await stream.WriteAsync(Protocol.OkReplyBytes, ct).ConfigureAwait(false);
                await stream.FlushAsync(ct).ConfigureAwait(false);

                string handshakeCodec = Protocol.NormalizeCodec(handshake.Codec);
                string codec = _codecPreference switch
                {
                    CodecPreference.H264 => "h264",
                    CodecPreference.Hevc => "hevc",
                    _ => handshakeCodec,
                };
                if (!string.Equals(codec, handshakeCodec, StringComparison.OrdinalIgnoreCase))
                {
                    _log($"[Server] Notice: PC Codec setting ({codec}) overrides phone handshake ({handshakeCodec}).");
                }
                int width = handshake.Width > 0 ? handshake.Width : 1280;
                int height = handshake.Height > 0 ? handshake.Height : 720;
                int fps = handshake.Fps > 0 ? Math.Clamp(handshake.Fps, 15, 120) : 60;
                string device = string.IsNullOrWhiteSpace(handshake.Device) ? "Android Device" : handshake.Device;
                string encoder = string.IsNullOrWhiteSpace(handshake.Encoder) ? "MediaCodec" : handshake.Encoder;

                _log($"[Server] Connected {remoteEndPoint}: device=\"{device}\", codec={codec}, encoder={encoder}, {width}x{height}@{fps}fps");

                lock (_sessionLock)
                {
                    _connected = true;
                    _deviceName = device;
                    _encoderName = encoder;
                    _currentCodec = codec;
                    _currentWidth = width;
                    _currentHeight = height;
                    _currentFps = fps;
                    _cachedCodecConfig = null;
                    _rotationQuarterTurns = 0;
                    _lastPacketFlagsRotation = -1;
                    _remotePaused = false;
                    _camera = "back";
                    RestartDecoderLocked();
                }

                byte[] headerBuf = new byte[Protocol.PacketHeaderSize];
                byte[] payloadBuf = new byte[64 * 1024];

                while (!ct.IsCancellationRequested)
                {
                    if (!await ReadExactAsync(stream, headerBuf, ct).ConfigureAwait(false))
                    {
                        break;
                    }

                    PacketHeader pkt = PacketHeader.Parse(headerBuf);
                    if (pkt.PayloadLength > Protocol.MaxPacketPayloadBytes)
                    {
                        _log($"[Server] Closing {remoteEndPoint}: oversized packet {pkt.PayloadLength}");
                        break;
                    }

                    if (pkt.PayloadLength > 0)
                    {
                        if (payloadBuf.Length < pkt.PayloadLength)
                        {
                            payloadBuf = new byte[Math.Max(pkt.PayloadLength, (uint)payloadBuf.Length * 2)];
                        }
                        if (!await ReadExactAsync(stream, payloadBuf.AsMemory(0, (int)pkt.PayloadLength), ct).ConfigureAwait(false))
                        {
                            break;
                        }
                    }

                    ReadOnlySpan<byte> payload = payloadBuf.AsSpan(0, (int)pkt.PayloadLength);
                    switch (pkt.Type)
                    {
                        case PacketType.Video:
                            HandleVideoPacket(pkt, payload);
                            break;
                        case PacketType.Control:
                            HandleControlPacket(payload);
                            break;
                        case PacketType.Heartbeat:
                            break;
                    }
                }
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
            _log($"[Server] Client {remoteEndPoint} error: {ex.Message}");
        }
        finally
        {
            _log($"[Server] Disconnected {remoteEndPoint}");
            lock (_sessionLock)
            {
                _connected = false;
                _cachedCodecConfig = null;
                _decoder?.Dispose();
                _decoder = null;
            }
            _shm.SetState(FrameState.Disconnected);
        }
    }

    private void HandleVideoPacket(PacketHeader pkt, ReadOnlySpan<byte> payload)
    {
        if (payload.IsEmpty) return;

        int pktRot = pkt.RotationQuarterTurns;
        if (_lastPacketFlagsRotation < 0)
        {
            _lastPacketFlagsRotation = pktRot;
            if (pktRot != 0) _rotationQuarterTurns = pktRot;
        }
        else if (pktRot != _lastPacketFlagsRotation)
        {
            _lastPacketFlagsRotation = pktRot;
            _rotationQuarterTurns = pktRot;
        }

        FfmpegDecoderSession? dec;
        lock (_sessionLock)
        {
            if (pkt.IsCodecConfig)
            {
                _cachedCodecConfig = payload.ToArray();
            }

            if (_decoder == null || _decoder.HasExited)
            {
                _log("[Server] Decoder session missing or exited, restarting...");
                RestartDecoderLocked();
                if (!pkt.IsCodecConfig && _cachedCodecConfig != null)
                {
                    _decoder?.WriteAnnexBPacket(_cachedCodecConfig);
                }
            }
            dec = _decoder;
        }

        dec?.WriteAnnexBPacket(payload);
    }

    private void HandleControlPacket(ReadOnlySpan<byte> payload)
    {
        if (payload.IsEmpty) return;
        try
        {
            ControlMessage? ctrl = JsonSerializer.Deserialize<ControlMessage>(payload);
            if (ctrl == null) return;

            lock (_sessionLock)
            {
                if (ctrl.Rotation.HasValue)
                {
                    _rotationQuarterTurns = Protocol.NormalizeRotationQuarterTurns(ctrl.Rotation.Value);
                }
                if (!string.IsNullOrWhiteSpace(ctrl.Camera))
                {
                    _camera = ctrl.Camera;
                }
                if (ctrl.Paused.HasValue)
                {
                    _remotePaused = ctrl.Paused.Value;
                    if (_remotePaused)
                    {
                        _shm.SetState(FrameState.Paused);
                    }
                }
            }
            _log($"[Control] camera={_camera}, rotation={_rotationQuarterTurns * 90}deg, paused={_remotePaused}");
        }
        catch (JsonException ex)
        {
            _log($"[Control] Invalid JSON: {ex.Message}");
        }
    }

    private void RestartDecoderLocked()
    {
        _decoder?.Dispose();
        _decoder = FfmpegDecoderSession.Start(
            _currentCodec,
            _decoderMode,
            _gpuDeviceIndex,
            _currentWidth,
            _currentHeight,
            OnDecodedFrame,
            _log,
            _currentFps);

        if (_cachedCodecConfig != null)
        {
            _decoder.WriteAnnexBPacket(_cachedCodecConfig);
        }
    }

    private void OnDecodedFrame(byte[] bgraFrame, int w, int h)
    {
        _currentWidth = w;
        _currentHeight = h;

        Interlocked.Increment(ref _totalFrames);
        Interlocked.Increment(ref _fpsWindowFrames);

        if (_manualPaused || _remotePaused)
        {
            _shm.SetState(FrameState.Paused);
            return;
        }

        ReadOnlySpan<byte> normalized = FrameTransformer.NormalizeBgra32(
            bgraFrame,
            w,
            h,
            _rotationQuarterTurns,
            ref _transformScratch,
            out int outW,
            out int outH,
            _targetWidth,
            _targetHeight);

        _shm.WriteBgra32Frame(normalized, outW, outH, FrameState.Live);
        FrameAvailable?.Invoke(normalized, outW, outH);
    }

    private static async Task<bool> ReadExactAsync(Stream stream, Memory<byte> dest, CancellationToken ct)
    {
        int read = 0;
        while (read < dest.Length)
        {
            int n = await stream.ReadAsync(dest.Slice(read), ct).ConfigureAwait(false);
            if (n == 0) return false;
            read += n;
        }
        return true;
    }

    public void Stop()
    {
        try { _serverCts?.Cancel(); } catch { }
        try { _clientCts?.Cancel(); } catch { }
        try { _listener?.Stop(); } catch { }

        try { _acceptTask?.Wait(500); } catch { }
        try { _clientTask?.Wait(500); } catch { }

        lock (_sessionLock)
        {
            _decoder?.Dispose();
            _decoder = null;
            _connected = false;
        }

        _serverCts?.Dispose();
        _serverCts = null;
        _clientCts?.Dispose();
        _clientCts = null;
        _listener = null;
        _acceptTask = null;
        _clientTask = null;
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        Stop();
    }
}
