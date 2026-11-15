using System.Drawing;
using System.Drawing.Imaging;
using System.Text;

namespace PhoneCamReceiver;

/// <summary>
/// Self-contained ISO/IEC 18004 QR Code encoder (Byte mode, ECC Level M, Versions 1-10).
/// </summary>
public static class QrCodeGenerator
{
    private readonly record struct VersionSpec(
        int Version,
        int TotalDataBytes,
        int EcBytesPerBlock,
        int Group1Blocks,
        int Group1DataBytes,
        int Group2Blocks,
        int Group2DataBytes,
        int[] AlignmentCenters);

    // ISO/IEC 18004 Table 9 & Table E.1 for ECC Level M (Versions 1..10, max ~213 UTF-8 bytes)
    private static readonly VersionSpec[] VersionsM =
    [
        new(1, 16, 10, 1, 16, 0, 0, []),
        new(2, 28, 16, 1, 28, 0, 0, [6, 18]),
        new(3, 44, 26, 1, 44, 0, 0, [6, 22]),
        new(4, 64, 18, 2, 32, 0, 0, [6, 26]),
        new(5, 86, 24, 2, 43, 0, 0, [6, 30]),
        new(6, 108, 16, 4, 27, 0, 0, [6, 34]),
        new(7, 124, 18, 4, 31, 0, 0, [6, 22, 38]),
        new(8, 154, 22, 2, 38, 2, 39, [6, 24, 42]),
        new(9, 182, 22, 3, 36, 2, 37, [6, 26, 46]),
        new(10, 216, 26, 4, 43, 1, 44, [6, 28, 50]),
    ];

    public static bool[,] Encode(string text)
    {
        byte[] payload = Encoding.UTF8.GetBytes(text);
        VersionSpec spec = SelectVersion(payload.Length);
        byte[] dataCodewords = BuildDataCodewords(payload, spec);
        byte[] finalStream = BuildErrorCorrectionAndInterleave(dataCodewords, spec);

        int size = spec.Version * 4 + 17;
        var modules = new bool[size, size];
        var isFunction = new bool[size, size];

        DrawFunctionPatterns(modules, isFunction, spec, size);
        DrawCodewords(modules, isFunction, finalStream, size);

        int bestMask = 0;
        int bestPenalty = int.MaxValue;
        for (int mask = 0; mask < 8; mask++)
        {
            ApplyMask(modules, isFunction, mask, size);
            DrawFormatBits(modules, mask, size);
            int penalty = EvaluatePenalty(modules, size);
            if (penalty < bestPenalty)
            {
                bestPenalty = penalty;
                bestMask = mask;
            }
            ApplyMask(modules, isFunction, mask, size);
        }

        ApplyMask(modules, isFunction, bestMask, size);
        DrawFormatBits(modules, bestMask, size);
        return modules;
    }

    public static Bitmap RenderBitmap(string text, int targetPixels = 260, int quietModules = 4)
    {
        bool[,] matrix = Encode(text);
        int modCount = matrix.GetLength(0);
        int totalMods = modCount + quietModules * 2;
        int scale = Math.Max(2, targetPixels / totalMods);
        int imgSize = totalMods * scale;

        var bmp = new Bitmap(imgSize, imgSize, PixelFormat.Format24bppRgb);
        using (Graphics g = Graphics.FromImage(bmp))
        {
            g.Clear(Color.White);
            using var blackBrush = new SolidBrush(Color.Black);
            for (int y = 0; y < modCount; y++)
            {
                for (int x = 0; x < modCount; x++)
                {
                    if (matrix[y, x])
                    {
                        g.FillRectangle(
                            blackBrush,
                            (x + quietModules) * scale,
                            (y + quietModules) * scale,
                            scale,
                            scale);
                    }
                }
            }
        }
        return bmp;
    }

    private static VersionSpec SelectVersion(int byteCount)
    {
        foreach (VersionSpec spec in VersionsM)
        {
            int countBits = spec.Version <= 9 ? 8 : 16;
            int headerBits = 4 + countBits;
            int capacityBytes = (spec.TotalDataBytes * 8 - headerBits) / 8;
            if (byteCount <= capacityBytes)
            {
                return spec;
            }
        }
        throw new ArgumentException($"QR payload too long ({byteCount} UTF-8 bytes; max supported 213 bytes).");
    }

    private static byte[] BuildDataCodewords(byte[] payload, VersionSpec spec)
    {
        var bits = new List<int>(spec.TotalDataBytes * 8);
        AppendBits(bits, 0b0100, 4); // Byte mode
        AppendBits(bits, payload.Length, spec.Version <= 9 ? 8 : 16);
        foreach (byte b in payload)
        {
            AppendBits(bits, b, 8);
        }

        int capBits = spec.TotalDataBytes * 8;
        int terminator = Math.Min(4, capBits - bits.Count);
        AppendBits(bits, 0, terminator);

        while (bits.Count % 8 != 0)
        {
            bits.Add(0);
        }

        var result = new byte[spec.TotalDataBytes];
        int idx = 0;
        for (int i = 0; i < bits.Count; i += 8)
        {
            byte val = 0;
            for (int b = 0; b < 8; b++)
            {
                val = (byte)((val << 1) | bits[i + b]);
            }
            result[idx++] = val;
        }

        byte[] padBytes = [0xEC, 0x11];
        int padIdx = 0;
        while (idx < result.Length)
        {
            result[idx++] = padBytes[padIdx];
            padIdx ^= 1;
        }
        return result;
    }

    private static void AppendBits(List<int> bits, int val, int count)
    {
        for (int i = count - 1; i >= 0; i--)
        {
            bits.Add((val >> i) & 1);
        }
    }

    private static byte[] BuildErrorCorrectionAndInterleave(byte[] data, VersionSpec spec)
    {
        int totalBlocks = spec.Group1Blocks + spec.Group2Blocks;
        var dataBlocks = new byte[totalBlocks][];
        var ecBlocks = new byte[totalBlocks][];
        byte[] poly = ComputeReedSolomonDivisor(spec.EcBytesPerBlock);

        int offset = 0;
        for (int b = 0; b < totalBlocks; b++)
        {
            int blockDataLen = b < spec.Group1Blocks ? spec.Group1DataBytes : spec.Group2DataBytes;
            dataBlocks[b] = new byte[blockDataLen];
            Array.Copy(data, offset, dataBlocks[b], 0, blockDataLen);
            offset += blockDataLen;
            ecBlocks[b] = ComputeReedSolomonRemainder(dataBlocks[b], poly);
        }

        var interleaved = new List<byte>(spec.TotalDataBytes + totalBlocks * spec.EcBytesPerBlock);
        int maxDataLen = Math.Max(spec.Group1DataBytes, spec.Group2DataBytes);
        for (int i = 0; i < maxDataLen; i++)
        {
            for (int b = 0; b < totalBlocks; b++)
            {
                if (i < dataBlocks[b].Length)
                {
                    interleaved.Add(dataBlocks[b][i]);
                }
            }
        }
        for (int i = 0; i < spec.EcBytesPerBlock; i++)
        {
            for (int b = 0; b < totalBlocks; b++)
            {
                interleaved.Add(ecBlocks[b][i]);
            }
        }

        return interleaved.ToArray();
    }

    private static byte[] ComputeReedSolomonDivisor(int degree)
    {
        var result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++)
        {
            for (int j = 0; j < degree; j++)
            {
                result[j] = GfMultiply(result[j], (byte)root);
                if (j + 1 < degree)
                {
                    result[j] ^= result[j + 1];
                }
            }
            root = GfMultiply((byte)root, 2);
        }
        return result;
    }

    private static byte[] ComputeReedSolomonRemainder(byte[] data, byte[] divisor)
    {
        var result = new byte[divisor.Length];
        foreach (byte b in data)
        {
            byte factor = (byte)(b ^ result[0]);
            Array.Copy(result, 1, result, 0, result.Length - 1);
            result[^1] = 0;
            for (int i = 0; i < divisor.Length; i++)
            {
                result[i] ^= GfMultiply(divisor[i], factor);
            }
        }
        return result;
    }

    private static byte GfMultiply(byte x, byte y)
    {
        int z = 0;
        for (int i = 7; i >= 0; i--)
        {
            z = (z << 1) ^ ((z >> 7) * 0x11D);
            z ^= ((y >> i) & 1) * x;
        }
        return (byte)z;
    }

    private static void DrawFunctionPatterns(bool[,] modules, bool[,] isFunction, VersionSpec spec, int size)
    {
        for (int i = 0; i < size; i++)
        {
            bool dark = i % 2 == 0;
            SetFunctionModule(modules, isFunction, 6, i, dark);
            SetFunctionModule(modules, isFunction, i, 6, dark);
        }

        DrawFinderPattern(modules, isFunction, 3, 3, size);
        DrawFinderPattern(modules, isFunction, size - 4, 3, size);
        DrawFinderPattern(modules, isFunction, 3, size - 4, size);

        int[] centers = spec.AlignmentCenters;
        for (int i = 0; i < centers.Length; i++)
        {
            for (int j = 0; j < centers.Length; j++)
            {
                bool isCornerFinder = (i == 0 && j == 0) ||
                                      (i == 0 && j == centers.Length - 1) ||
                                      (i == centers.Length - 1 && j == 0);
                if (!isCornerFinder)
                {
                    DrawAlignmentPattern(modules, isFunction, centers[j], centers[i]);
                }
            }
        }

        // Format info placeholders + dark module
        for (int i = 0; i <= 8; i++)
        {
            if (i != 6)
            {
                SetFunctionModule(modules, isFunction, 8, i, false);
                SetFunctionModule(modules, isFunction, i, 8, false);
            }
        }
        for (int i = size - 8; i < size; i++)
        {
            SetFunctionModule(modules, isFunction, 8, i, false);
            SetFunctionModule(modules, isFunction, i, 8, false);
        }
        SetFunctionModule(modules, isFunction, 8, size - 8, true);

        if (spec.Version >= 7)
        {
            DrawVersionBits(modules, isFunction, spec.Version, size);
        }
    }

    private static void DrawFinderPattern(bool[,] modules, bool[,] isFunction, int cx, int cy, int size)
    {
        for (int dy = -4; dy <= 4; dy++)
        {
            for (int dx = -4; dx <= 4; dx++)
            {
                int x = cx + dx;
                int y = cy + dy;
                if (x < 0 || x >= size || y < 0 || y >= size) continue;
                int dist = Math.Max(Math.Abs(dx), Math.Abs(dy));
                SetFunctionModule(modules, isFunction, x, y, dist != 2 && dist != 4);
            }
        }
    }

    private static void DrawAlignmentPattern(bool[,] modules, bool[,] isFunction, int cx, int cy)
    {
        for (int dy = -2; dy <= 2; dy++)
        {
            for (int dx = -2; dx <= 2; dx++)
            {
                int dist = Math.Max(Math.Abs(dx), Math.Abs(dy));
                SetFunctionModule(modules, isFunction, cx + dx, cy + dy, dist != 1);
            }
        }
    }

    private static void DrawVersionBits(bool[,] modules, bool[,] isFunction, int version, int size)
    {
        int rem = version;
        for (int i = 0; i < 12; i++)
        {
            rem = (rem << 1) ^ ((rem >> 11) * 0x1F25);
        }
        int bits = (version << 12) | rem;
        for (int i = 0; i < 18; i++)
        {
            bool bit = ((bits >> i) & 1) != 0;
            int a = size - 11 + i % 3;
            int b = i / 3;
            SetFunctionModule(modules, isFunction, a, b, bit);
            SetFunctionModule(modules, isFunction, b, a, bit);
        }
    }

    private static void DrawFormatBits(bool[,] modules, int mask, int size)
    {
        // ECC Level M = 00 in format code
        int data = (0 << 3) | mask;
        int rem = data;
        for (int i = 0; i < 10; i++)
        {
            rem = (rem << 1) ^ ((rem >> 9) * 0x537);
        }
        int bits = ((data << 10) | rem) ^ 0x5412;

        for (int i = 0; i <= 5; i++) modules[i, 8] = GetBit(bits, i);
        modules[7, 8] = GetBit(bits, 6);
        modules[8, 8] = GetBit(bits, 7);
        modules[8, 7] = GetBit(bits, 8);
        for (int i = 9; i < 15; i++) modules[8, 14 - i] = GetBit(bits, i);

        for (int i = 0; i < 8; i++) modules[8, size - 1 - i] = GetBit(bits, i);
        for (int i = 8; i < 15; i++) modules[size - 15 + i, 8] = GetBit(bits, i);
        modules[size - 8, 8] = true;
    }

    private static bool GetBit(int value, int index) => ((value >> index) & 1) != 0;

    private static void DrawCodewords(bool[,] modules, bool[,] isFunction, byte[] data, int size)
    {
        int bitIdx = 0;
        int totalBits = data.Length * 8;
        for (int right = size - 1; right >= 1; right -= 2)
        {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++)
            {
                for (int j = 0; j < 2; j++)
                {
                    int x = right - j;
                    bool upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!isFunction[y, x] && bitIdx < totalBits)
                    {
                        modules[y, x] = ((data[bitIdx >> 3] >> (7 - (bitIdx & 7))) & 1) != 0;
                        bitIdx++;
                    }
                }
            }
        }
    }

    private static void ApplyMask(bool[,] modules, bool[,] isFunction, int mask, int size)
    {
        for (int y = 0; y < size; y++)
        {
            for (int x = 0; x < size; x++)
            {
                if (isFunction[y, x]) continue;
                bool invert = mask switch
                {
                    0 => (x + y) % 2 == 0,
                    1 => y % 2 == 0,
                    2 => x % 3 == 0,
                    3 => (x + y) % 3 == 0,
                    4 => (x / 3 + y / 2) % 2 == 0,
                    5 => x * y % 2 + x * y % 3 == 0,
                    6 => (x * y % 2 + x * y % 3) % 2 == 0,
                    7 => ((x + y) % 2 + x * y % 3) % 2 == 0,
                    _ => false,
                };
                if (invert)
                {
                    modules[y, x] = !modules[y, x];
                }
            }
        }
    }

    private static int EvaluatePenalty(bool[,] modules, int size)
    {
        int penalty = 0;
        for (int y = 0; y < size; y++)
        {
            int runColor = -1;
            int runLen = 0;
            for (int x = 0; x < size; x++)
            {
                int c = modules[y, x] ? 1 : 0;
                if (c == runColor) runLen++;
                else
                {
                    if (runLen >= 5) penalty += 3 + (runLen - 5);
                    runColor = c;
                    runLen = 1;
                }
            }
            if (runLen >= 5) penalty += 3 + (runLen - 5);
        }
        for (int x = 0; x < size; x++)
        {
            int runColor = -1;
            int runLen = 0;
            for (int y = 0; y < size; y++)
            {
                int c = modules[y, x] ? 1 : 0;
                if (c == runColor) runLen++;
                else
                {
                    if (runLen >= 5) penalty += 3 + (runLen - 5);
                    runColor = c;
                    runLen = 1;
                }
            }
            if (runLen >= 5) penalty += 3 + (runLen - 5);
        }
        for (int y = 0; y < size - 1; y++)
        {
            for (int x = 0; x < size - 1; x++)
            {
                bool c = modules[y, x];
                if (c == modules[y, x + 1] && c == modules[y + 1, x] && c == modules[y + 1, x + 1])
                {
                    penalty += 3;
                }
            }
        }
        int darkCount = 0;
        for (int y = 0; y < size; y++)
        {
            for (int x = 0; x < size; x++)
            {
                if (modules[y, x]) darkCount++;
            }
        }
        int total = size * size;
        int k = Math.Abs(darkCount * 20 - total * 10) / total;
        penalty += k * 10;
        return penalty;
    }

    private static void SetFunctionModule(bool[,] modules, bool[,] isFunction, int x, int y, bool dark)
    {
        modules[y, x] = dark;
        isFunction[y, x] = true;
    }
}
