using System.Buffers.Binary;
using System.Diagnostics;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace PhoneCamReceiver;

public enum DecoderMode
{
    Nvdec = 0,
    D3d11va = 1,
    Cpu = 2,
}

public enum CodecPreference
{
    Auto = 0,
    H264 = 1,
    Hevc = 2,
}

public enum FrameState : uint
{
    Disconnected = 0,
    Live = 1,
    Paused = 2,
}

public enum ShmPixelFormat : uint
{
    Unknown = 0,
    Nv12 = 1,
    Bgra32 = 2,
    Rgb24 = 3,
}

public enum PacketType : byte
{
    Video = 1,
    Control = 2,
    Heartbeat = 3,
}

public sealed record ResolutionOption(string Id, int Width, int Height, string Label)
{
    public override string ToString() => Label;
}

public sealed record GpuDeviceOption(int DeviceIndex, string Name, bool IsCudaCapable)
{
    public override string ToString() => Name;
}

public sealed class ReceiverSettings
{
    public int Port { get; set; } = Protocol.DefaultPort;
    public string ResolutionId { get; set; } = "1280x720";
    public CodecPreference Codec { get; set; } = CodecPreference.H264;
    public DecoderMode Decoder { get; set; } = DecoderMode.Nvdec;
    public int GpuDeviceIndex { get; set; } = 0;
    public bool PreviewEnabled { get; set; } = true;
    public bool FlipHorizontal { get; set; } = false;
    public bool FlipVertical { get; set; } = false;

    private static string SettingsFilePath => Path.Combine(AppContext.BaseDirectory, "settings.json");

    public static ReceiverSettings Load()
    {
        try
        {
            string path = SettingsFilePath;
            if (File.Exists(path))
            {
                string json = File.ReadAllText(path, Encoding.UTF8);
                ReceiverSettings? loaded = JsonSerializer.Deserialize<ReceiverSettings>(json);
                if (loaded != null)
                {
                    if (loaded.Port is < 1024 or > 65535) loaded.Port = Protocol.DefaultPort;
                    return loaded;
                }
            }
        }
        catch
        {
        }
        return new ReceiverSettings();
    }

    public void Save()
    {
        try
        {
            var opts = new JsonSerializerOptions { WriteIndented = true };
            string json = JsonSerializer.Serialize(this, opts);
            File.WriteAllText(SettingsFilePath, json, Encoding.UTF8);
        }
        catch
        {
        }
    }
}

public sealed class HandshakeMessage
{
    [JsonPropertyName("token")]
    public string Token { get; set; } = string.Empty;

    [JsonPropertyName("device")]
    public string Device { get; set; } = "Android Device";

    [JsonPropertyName("codec")]
    public string Codec { get; set; } = "h264";

    [JsonPropertyName("encoder")]
    public string Encoder { get; set; } = string.Empty;

    [JsonPropertyName("width")]
    public int Width { get; set; } = 1280;

    [JsonPropertyName("height")]
    public int Height { get; set; } = 720;

    [JsonPropertyName("fps")]
    public int Fps { get; set; } = 30;
}

public sealed class ControlMessage
{
    [JsonPropertyName("rotation")]
    public int? Rotation { get; set; }

    [JsonPropertyName("camera")]
    public string? Camera { get; set; }

    [JsonPropertyName("paused")]
    public bool? Paused { get; set; }

    [JsonPropertyName("flip_h")]
    public bool? FlipHorizontal { get; set; }

    [JsonPropertyName("flip_v")]
    public bool? FlipVertical { get; set; }
}

public sealed class QrPairingPayload
{
    [JsonPropertyName("v")]
    public int Version { get; set; } = 1;

    [JsonPropertyName("ip")]
    public string Ip { get; set; } = string.Empty;

    [JsonPropertyName("port")]
    public int Port { get; set; } = 9876;

    [JsonPropertyName("token")]
    public string Token { get; set; } = string.Empty;

    [JsonPropertyName("name")]
    public string Name { get; set; } = Environment.MachineName;

    [JsonPropertyName("codec")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Codec { get; set; }

    [JsonPropertyName("width")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? Width { get; set; }

    [JsonPropertyName("height")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? Height { get; set; }
}

public readonly record struct PacketHeader(
    PacketType Type,
    byte Flags,
    ushort Reserved,
    uint PayloadLength,
    long PtsUs)
{
    public bool IsKeyframe => (Flags & 0x01) != 0;
    public bool IsCodecConfig => (Flags & 0x02) != 0;
    public int RotationQuarterTurns => (Flags >> 2) & 0x03;

    public static PacketHeader Parse(ReadOnlySpan<byte> buffer)
    {
        return new PacketHeader(
            Type: (PacketType)buffer[0],
            Flags: buffer[1],
            Reserved: BinaryPrimitives.ReadUInt16BigEndian(buffer.Slice(2, 2)),
            PayloadLength: BinaryPrimitives.ReadUInt32BigEndian(buffer.Slice(4, 4)),
            PtsUs: BinaryPrimitives.ReadInt64BigEndian(buffer.Slice(8, 8)));
    }
}

public static class Protocol
{
    public static readonly byte[] MagicBytes = Encoding.ASCII.GetBytes("PLWC");
    public static readonly byte[] OkReplyBytes = Encoding.ASCII.GetBytes("OK\r\n");

    public const int DefaultPort = 9876;
    public const int PacketHeaderSize = 16;
    public const int MaxHandshakeJsonBytes = 64 * 1024;
    public const uint MaxPacketPayloadBytes = 16 * 1024 * 1024;

    public static readonly IReadOnlyList<ResolutionOption> ResolutionPresets =
    [
        new("1280x720", 1280, 720, "1280×720 (HD 720p - 권장)"),
        new("1920x1080", 1920, 1080, "1920×1080 (Full HD 1080p)"),
        new("960x540", 960, 540, "960×540 (qHD 540p)"),
        new("640x480", 640, 480, "640×480 (VGA 480p)"),
        new("640x360", 640, 360, "640×360 (nHD 360p)"),
        new("auto", 0, 0, "Auto (휴대폰 전송 원본 해상도 유지)"),
    ];

    public static ResolutionOption FindResolutionOption(string? id)
    {
        return ResolutionPresets.FirstOrDefault(r =>
            string.Equals(r.Id, id, StringComparison.OrdinalIgnoreCase))
            ?? ResolutionPresets[0];
    }

    public static string GenerateHexToken16()
    {
        Span<byte> bytes = stackalloc byte[8];
        RandomNumberGenerator.Fill(bytes);
        return Convert.ToHexString(bytes);
    }

    public static string BuildQrJson(
        string ip,
        int port,
        string token,
        string? hostName = null,
        CodecPreference codecPref = CodecPreference.Auto,
        int targetWidth = 0,
        int targetHeight = 0)
    {
        string? codecStr = codecPref switch
        {
            CodecPreference.H264 => "h264",
            CodecPreference.Hevc => "hevc",
            _ => null,
        };

        var payload = new QrPairingPayload
        {
            Version = 1,
            Ip = ip,
            Port = port,
            Token = token,
            Name = string.IsNullOrWhiteSpace(hostName) ? Environment.MachineName : hostName,
            Codec = codecStr,
            Width = targetWidth > 0 ? targetWidth : null,
            Height = targetHeight > 0 ? targetHeight : null,
        };
        return JsonSerializer.Serialize(payload);
    }

    public static DecoderMode ParseDecoderMode(string? value)
    {
        return value?.Trim().ToLowerInvariant() switch
        {
            "nvdec" or "cuda" or "cuvid" => DecoderMode.Nvdec,
            "d3d11va" or "d3d11" => DecoderMode.D3d11va,
            "cpu" or "sw" or "software" => DecoderMode.Cpu,
            _ => DecoderMode.Nvdec,
        };
    }

    public static CodecPreference ParseCodecPreference(string? value)
    {
        return value?.Trim().ToLowerInvariant() switch
        {
            "h264" or "avc" => CodecPreference.H264,
            "hevc" or "h265" => CodecPreference.Hevc,
            _ => CodecPreference.Auto,
        };
    }

    public static string NormalizeCodec(string? codec)
    {
        return codec?.Trim().ToLowerInvariant() switch
        {
            "hevc" or "h265" or "h.265" or "video/hevc" => "hevc",
            _ => "h264",
        };
    }

    public static int NormalizeRotationQuarterTurns(int value)
    {
        return value switch
        {
            0 => 0,
            1 or 90 => 1,
            2 or 180 => 2,
            3 or 270 => 3,
            _ => ((value / 90) % 4 + 4) % 4,
        };
    }

    public static string FormatDecoderDisplayName(DecoderMode mode, string codec, int gpuIndex = -1, string? gpuName = null)
    {
        string c = NormalizeCodec(codec);
        string gpuSuffix = gpuIndex >= 0
            ? $" [GPU {gpuIndex}{(string.IsNullOrWhiteSpace(gpuName) ? "" : $": {gpuName}")}]"
            : "";
        return mode switch
        {
            DecoderMode.Nvdec => $"NVIDIA NVDEC ({(c == "hevc" ? "hevc_cuvid" : "h264_cuvid")}){gpuSuffix}",
            DecoderMode.D3d11va => $"Direct3D 11 (d3d11va {c}){gpuSuffix}",
            DecoderMode.Cpu => $"Software CPU (FFmpeg {c})",
            _ => mode.ToString(),
        };
    }

    public static List<GpuDeviceOption> EnumerateGpuDevices()
    {
        var list = new List<GpuDeviceOption>();
        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = "nvidia-smi",
                Arguments = "--query-gpu=index,name --format=csv,noheader",
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
            };
            using Process? proc = Process.Start(psi);
            if (proc != null && proc.WaitForExit(1500) && proc.ExitCode == 0)
            {
                string output = proc.StandardOutput.ReadToEnd();
                foreach (string rawLine in output.Split(['\r', '\n'], StringSplitOptions.RemoveEmptyEntries))
                {
                    string[] parts = rawLine.Split(',', 2, StringSplitOptions.TrimEntries);
                    if (parts.Length == 2 && int.TryParse(parts[0], out int idx))
                    {
                        list.Add(new GpuDeviceOption(idx, $"GPU {idx}: {parts[1]} (CUDA / NVDEC)", true));
                    }
                }
            }
        }
        catch
        {
        }

        if (list.Count == 0)
        {
            foreach (string adapterName in EnumerateDisplayAdaptersUser32())
            {
                int idx = list.Count;
                bool isNvidia = adapterName.Contains("NVIDIA", StringComparison.OrdinalIgnoreCase);
                list.Add(new GpuDeviceOption(idx, $"GPU {idx}: {adapterName}", isNvidia));
            }
        }

        list.Add(new GpuDeviceOption(-1, "Auto (시스템 기본 GPU 장치 자동 선택)", list.Any(x => x.IsCudaCapable)));
        return list;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct DISPLAY_DEVICE
    {
        public int cb;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)]
        public string DeviceName;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)]
        public string DeviceString;
        public int StateFlags;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)]
        public string DeviceID;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)]
        public string DeviceKey;
    }

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern bool EnumDisplayDevicesW(string? lpDevice, uint iDevNum, ref DISPLAY_DEVICE lpDisplayDevice, uint dwFlags);

    private static List<string> EnumerateDisplayAdaptersUser32()
    {
        var names = new List<string>();
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var dd = new DISPLAY_DEVICE { cb = Marshal.SizeOf<DISPLAY_DEVICE>() };

        for (uint i = 0; EnumDisplayDevicesW(null, i, ref dd, 0); i++)
        {
            string s = dd.DeviceString?.Trim() ?? string.Empty;
            if (!string.IsNullOrEmpty(s) &&
                !s.Contains("Microsoft Basic", StringComparison.OrdinalIgnoreCase) &&
                seen.Add(s))
            {
                names.Add(s);
            }
            dd.cb = Marshal.SizeOf<DISPLAY_DEVICE>();
        }
        return names;
    }

    public static List<string> EnumerateLanIps()
    {
        var preferred = new List<(int Score, string Ip)>();

        foreach (NetworkInterface nic in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (nic.OperationalStatus != OperationalStatus.Up)
            {
                continue;
            }
            if (nic.NetworkInterfaceType == NetworkInterfaceType.Loopback ||
                nic.NetworkInterfaceType == NetworkInterfaceType.Tunnel)
            {
                continue;
            }

            IPInterfaceProperties props = nic.GetIPProperties();
            bool hasGateway = props.GatewayAddresses.Any(g =>
                g.Address.AddressFamily == AddressFamily.InterNetwork &&
                !IPAddress.IsLoopback(g.Address) &&
                !g.Address.Equals(IPAddress.Any));

            string desc = (nic.Name + " " + nic.Description).ToLowerInvariant();
            bool isVirtual = desc.Contains("virtual") || desc.Contains("hyper-v") ||
                             desc.Contains("vethernet") || desc.Contains("vmware") ||
                             desc.Contains("vbox") || desc.Contains("tailscale") ||
                             desc.Contains("wsl");

            foreach (UnicastIPAddressInformation ua in props.UnicastAddresses)
            {
                if (ua.Address.AddressFamily != AddressFamily.InterNetwork)
                {
                    continue;
                }
                byte[] bytes = ua.Address.GetAddressBytes();
                if (bytes[0] == 127 || (bytes[0] == 169 && bytes[1] == 254))
                {
                    continue;
                }

                int score = 0;
                if (hasGateway) score += 100;
                if (!isVirtual) score += 50;
                if (nic.NetworkInterfaceType == NetworkInterfaceType.Wireless80211 ||
                    nic.NetworkInterfaceType == NetworkInterfaceType.Ethernet)
                {
                    score += 20;
                }
                if (bytes[0] == 192 && bytes[1] == 168) score += 10;
                else if (bytes[0] == 10) score += 5;

                preferred.Add((score, ua.Address.ToString()));
            }
        }

        var ordered = preferred
            .OrderByDescending(x => x.Score)
            .Select(x => x.Ip)
            .Distinct()
            .ToList();

        if (ordered.Count == 0)
        {
            ordered.Add("127.0.0.1");
        }
        return ordered;
    }
}
