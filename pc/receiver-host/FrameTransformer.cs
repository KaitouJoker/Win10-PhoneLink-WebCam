using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.CompilerServices;
using System.Threading.Tasks;

namespace PhoneCamReceiver;

/// <summary>
/// Normalizes BGRA32 / NV12 frames (rotation 0/90/180/270 and clamping within 1920x1080 SHM limits)
/// and copies them into WinForms preview bitmaps.
/// </summary>
public static unsafe class FrameTransformer
{
    private readonly record struct TransformLayout(
        int Rot,
        int OrientedW,
        int OrientedH,
        int OutW,
        int OutH,
        int DrawW,
        int DrawH,
        int OffX,
        int OffY);

    private static TransformLayout ComputeLayout(
        int srcW,
        int srcH,
        int rotationQuarterTurns,
        int targetWidth,
        int targetHeight,
        out bool isIdentity)
    {
        int rot = ((rotationQuarterTurns % 4) + 4) % 4;
        bool hasExplicitTarget = targetWidth > 0 && targetHeight > 0;
        bool fitsDirectly = srcW <= ShmWriter.MaxWidth && srcH <= ShmWriter.MaxHeight;

        isIdentity = rot == 0 && fitsDirectly && (!hasExplicitTarget || (srcW == targetWidth && srcH == targetHeight));
        int outW;
        int outH;
        if (isIdentity)
        {
            outW = srcW;
            outH = srcH;
        }
        else if (hasExplicitTarget)
        {
            FitWithinMax(targetWidth, targetHeight, out outW, out outH);
        }
        else if (rot == 0 || rot == 2)
        {
            FitWithinMax(srcW, srcH, out outW, out outH);
        }
        else
        {
            // For 90/270 portrait output, keep landscape container (e.g., 1280x720 or 1920x1080) with centered portrait image.
            if (srcW > srcH)
            {
                FitWithinMax(srcW, srcH, out outW, out outH);
            }
            else
            {
                FitWithinMax(srcH, srcW, out outW, out outH);
            }
        }

        int orientedW = (rot == 1 || rot == 3) ? srcH : srcW;
        int orientedH = (rot == 1 || rot == 3) ? srcW : srcH;

        double scale = Math.Min((double)outW / orientedW, (double)outH / orientedH);
        int drawW = Math.Max(1, (int)Math.Round(orientedW * scale));
        int drawH = Math.Max(1, (int)Math.Round(orientedH * scale));
        int offX = (outW - drawW) / 2;
        int offY = (outH - drawH) / 2;

        return new TransformLayout(rot, orientedW, orientedH, outW, outH, drawW, drawH, offX, offY);
    }

    private static void EnsureScratchAndClearBorders(ref byte[] scratch, in TransformLayout layout, out int outBytes)
    {
        outBytes = layout.OutW * layout.OutH * 4;
        if (scratch.Length < outBytes)
        {
            scratch = new byte[outBytes];
        }

        if (layout.OffY > 0)
        {
            Array.Clear(scratch, 0, layout.OffY * layout.OutW * 4);
        }
        int bottomRow = layout.OffY + layout.DrawH;
        if (bottomRow < layout.OutH)
        {
            int bottomStart = bottomRow * layout.OutW * 4;
            Array.Clear(scratch, bottomStart, outBytes - bottomStart);
        }
    }

    /// <summary>
    /// Converts a raw NV12 frame into normalized top-down BGRA32 in a single parallel pass
    /// (color conversion + rotation + scaling + letterbox padding).
    /// </summary>
    public static ReadOnlySpan<byte> NormalizeNv12ToBgra32(
        ReadOnlySpan<byte> srcNv12,
        int srcW,
        int srcH,
        int rotationQuarterTurns,
        ref byte[] scratch,
        out int outW,
        out int outH,
        int targetWidth = 0,
        int targetHeight = 0)
    {
        if (srcW <= 0 || srcH <= 0 || srcNv12.Length < (srcW * srcH * 3) / 2)
        {
            throw new ArgumentException($"Invalid NV12 frame size {srcW}x{srcH} (len={srcNv12.Length}).");
        }

        TransformLayout layout = ComputeLayout(srcW, srcH, rotationQuarterTurns, targetWidth, targetHeight, out _);
        outW = layout.OutW;
        outH = layout.OutH;
        EnsureScratchAndClearBorders(ref scratch, layout, out int outBytes);

        int ySize = srcW * srcH;
        int maxUvRow = Math.Max(0, (srcH >> 1) - 1);
        int maxUvCol = Math.Max(0, srcW - 2);

        fixed (byte* sPtr = srcNv12)
        fixed (byte* dBase = scratch)
        {
            nint sAddr = (nint)sPtr;
            nint dAddr = (nint)dBase;

            if (layout.DrawH >= 240)
            {
                Parallel.For(0, layout.DrawH, dy =>
                {
                    TransformNv12Row((byte*)sAddr, (uint*)dAddr, srcW, srcH, ySize, maxUvRow, maxUvCol, layout, dy);
                });
            }
            else
            {
                for (int dy = 0; dy < layout.DrawH; dy++)
                {
                    TransformNv12Row(sPtr, (uint*)dBase, srcW, srcH, ySize, maxUvRow, maxUvCol, layout, dy);
                }
            }
        }

        return scratch.AsSpan(0, outBytes);
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    private static void TransformNv12Row(
        byte* s,
        uint* d,
        int srcW,
        int srcH,
        int ySize,
        int maxUvRow,
        int maxUvCol,
        in TransformLayout layout,
        int dy)
    {
        uint* rowStart = d + (layout.OffY + dy) * layout.OutW;
        if (layout.OffX > 0)
        {
            new Span<uint>(rowStart, layout.OffX).Clear();
        }
        int rightX = layout.OffX + layout.DrawW;
        if (rightX < layout.OutW)
        {
            new Span<uint>(rowStart + rightX, layout.OutW - rightX).Clear();
        }

        int oy = (int)((long)dy * layout.OrientedH / layout.DrawH);
        uint* dstRow = rowStart + layout.OffX;
        byte* uvPlane = s + ySize;

        if (layout.Rot == 0 && layout.OrientedW == layout.DrawW && layout.OrientedH == layout.DrawH)
        {
            byte* yRow = s + oy * srcW;
            byte* uvRowPtr = uvPlane + Math.Min(oy >> 1, maxUvRow) * srcW;
            int dx = 0;
            int evenW = layout.DrawW & ~1;
            for (; dx < evenW; dx += 2)
            {
                int uvOff = Math.Min(dx, maxUvCol);
                int dU = uvRowPtr[uvOff] - 128;
                int eV = uvRowPtr[uvOff + 1] - 128;
                dstRow[dx] = YuvToBgra32(yRow[dx], dU, eV);
                dstRow[dx + 1] = YuvToBgra32(yRow[dx + 1], dU, eV);
            }
            if (dx < layout.DrawW)
            {
                int uvOff = Math.Min(dx & ~1, maxUvCol);
                dstRow[dx] = YuvToBgra32(yRow[dx], uvRowPtr[uvOff] - 128, uvRowPtr[uvOff + 1] - 128);
            }
            return;
        }

        if (layout.Rot == 3)
        {
            int sx = srcW - 1 - oy;
            int uvCol = Math.Min(sx & ~1, maxUvCol);
            for (int dx = 0; dx < layout.DrawW; dx++)
            {
                int sy = (int)((long)dx * layout.OrientedW / layout.DrawW);
                byte y = s[sy * srcW + sx];
                int uvOff = Math.Min(sy >> 1, maxUvRow) * srcW + uvCol;
                dstRow[dx] = YuvToBgra32(y, uvPlane[uvOff] - 128, uvPlane[uvOff + 1] - 128);
            }
        }
        else if (layout.Rot == 1)
        {
            int sx = oy;
            int uvCol = Math.Min(sx & ~1, maxUvCol);
            for (int dx = 0; dx < layout.DrawW; dx++)
            {
                int sy = srcH - 1 - (int)((long)dx * layout.OrientedW / layout.DrawW);
                byte y = s[sy * srcW + sx];
                int uvOff = Math.Min(sy >> 1, maxUvRow) * srcW + uvCol;
                dstRow[dx] = YuvToBgra32(y, uvPlane[uvOff] - 128, uvPlane[uvOff + 1] - 128);
            }
        }
        else if (layout.Rot == 2)
        {
            int sy = srcH - 1 - oy;
            byte* yRow = s + sy * srcW;
            byte* uvRowPtr = uvPlane + Math.Min(sy >> 1, maxUvRow) * srcW;
            for (int dx = 0; dx < layout.DrawW; dx++)
            {
                int sx = srcW - 1 - (int)((long)dx * layout.OrientedW / layout.DrawW);
                int uvOff = Math.Min(sx & ~1, maxUvCol);
                dstRow[dx] = YuvToBgra32(yRow[sx], uvRowPtr[uvOff] - 128, uvRowPtr[uvOff + 1] - 128);
            }
        }
        else
        {
            int sy = oy;
            byte* yRow = s + sy * srcW;
            byte* uvRowPtr = uvPlane + Math.Min(sy >> 1, maxUvRow) * srcW;
            for (int dx = 0; dx < layout.DrawW; dx++)
            {
                int sx = (int)((long)dx * layout.OrientedW / layout.DrawW);
                int uvOff = Math.Min(sx & ~1, maxUvCol);
                dstRow[dx] = YuvToBgra32(yRow[sx], uvRowPtr[uvOff] - 128, uvRowPtr[uvOff + 1] - 128);
            }
        }
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    private static uint YuvToBgra32(int y, int d, int e)
    {
        int c = y - 16;
        if (c < 0) c = 0;
        int c298 = 298 * c + 128;
        int r = (c298 + 409 * e) >> 8;
        int g = (c298 - 100 * d - 208 * e) >> 8;
        int b = (c298 + 516 * d) >> 8;
        if ((uint)r > 255u) r = r < 0 ? 0 : 255;
        if ((uint)g > 255u) g = g < 0 ? 0 : 255;
        if ((uint)b > 255u) b = b < 0 ? 0 : 255;
        return 0xFF000000u | ((uint)r << 16) | ((uint)g << 8) | (uint)b;
    }

    /// <summary>
    /// Transforms a top-down BGRA32 frame so its output dimensions fit ShmWriter bounds (<= 1920x1080).
    /// Returns the output span (either src directly or scratch) plus resulting dimensions.
    /// </summary>
    public static ReadOnlySpan<byte> NormalizeBgra32(
        ReadOnlySpan<byte> src,
        int srcW,
        int srcH,
        int rotationQuarterTurns,
        ref byte[] scratch,
        out int outW,
        out int outH,
        int targetWidth = 0,
        int targetHeight = 0)
    {
        TransformLayout layout = ComputeLayout(srcW, srcH, rotationQuarterTurns, targetWidth, targetHeight, out bool isIdentity);
        outW = layout.OutW;
        outH = layout.OutH;
        if (isIdentity)
        {
            return src.Slice(0, srcW * srcH * 4);
        }

        EnsureScratchAndClearBorders(ref scratch, layout, out int outBytes);

        fixed (byte* sBase = src)
        fixed (byte* dBase = scratch)
        {
            uint* s = (uint*)sBase;
            uint* d = (uint*)dBase;

            if (layout.Rot == 2 && srcW == outW && srcH == outH)
            {
                int total = srcW * srcH;
                for (int i = 0; i < total; i++)
                {
                    d[i] = s[total - 1 - i];
                }
                return scratch.AsSpan(0, outBytes);
            }

            nint sAddr = (nint)s;
            nint dAddr = (nint)d;
            int rightX = layout.OffX + layout.DrawW;

            if (layout.DrawH >= 240)
            {
                Parallel.For(0, layout.DrawH, dy =>
                {
                    TransformRow((uint*)sAddr, (uint*)dAddr, srcW, srcH, layout.OutW, layout.Rot, layout.OrientedW, layout.OrientedH, layout.DrawW, layout.DrawH, layout.OffX, layout.OffY, rightX, dy);
                });
            }
            else
            {
                for (int dy = 0; dy < layout.DrawH; dy++)
                {
                    TransformRow(s, d, srcW, srcH, layout.OutW, layout.Rot, layout.OrientedW, layout.OrientedH, layout.DrawW, layout.DrawH, layout.OffX, layout.OffY, rightX, dy);
                }
            }
        }

        return scratch.AsSpan(0, outBytes);
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    private static void TransformRow(
        uint* s,
        uint* d,
        int srcW,
        int srcH,
        int outW,
        int rot,
        int orientedW,
        int orientedH,
        int drawW,
        int drawH,
        int offX,
        int offY,
        int rightX,
        int dy)
    {
        uint* rowStart = d + (offY + dy) * outW;
        if (offX > 0)
        {
            new Span<uint>(rowStart, offX).Clear();
        }
        if (rightX < outW)
        {
            new Span<uint>(rowStart + rightX, outW - rightX).Clear();
        }

        int oy = (int)((long)dy * orientedH / drawH);
        uint* dstRow = rowStart + offX;

        if (rot == 3)
        {
            int sx = srcW - 1 - oy;
            uint* srcColBase = s + sx;
            for (int dx = 0; dx < drawW; dx++)
            {
                int ox = (int)((long)dx * orientedW / drawW);
                dstRow[dx] = srcColBase[ox * srcW];
            }
        }
        else if (rot == 1)
        {
            int sx = oy;
            uint* srcColBase = s + sx;
            for (int dx = 0; dx < drawW; dx++)
            {
                int ox = (int)((long)dx * orientedW / drawW);
                dstRow[dx] = srcColBase[(srcH - 1 - ox) * srcW];
            }
        }
        else if (rot == 2)
        {
            uint* srcRow = s + (srcH - 1 - oy) * srcW;
            for (int dx = 0; dx < drawW; dx++)
            {
                int ox = (int)((long)dx * orientedW / drawW);
                dstRow[dx] = srcRow[srcW - 1 - ox];
            }
        }
        else
        {
            uint* srcRow = s + oy * srcW;
            for (int dx = 0; dx < drawW; dx++)
            {
                int ox = (int)((long)dx * orientedW / drawW);
                dstRow[dx] = srcRow[ox];
            }
        }
    }

    public static void CopyBgra32ToBitmap(ReadOnlySpan<byte> bgra, int width, int height, ref Bitmap? target)
    {
        if (target == null || target.Width != width || target.Height != height)
        {
            target?.Dispose();
            target = new Bitmap(width, height, PixelFormat.Format32bppRgb);
        }

        var rect = new Rectangle(0, 0, width, height);
        BitmapData bmpData = target.LockBits(rect, ImageLockMode.WriteOnly, PixelFormat.Format32bppRgb);
        try
        {
            int srcStride = width * 4;
            fixed (byte* srcPtr = bgra)
            {
                if (bmpData.Stride == srcStride)
                {
                    Buffer.MemoryCopy(srcPtr, (void*)bmpData.Scan0, (long)srcStride * height, (long)srcStride * height);
                }
                else
                {
                    byte* dst = (byte*)bmpData.Scan0;
                    for (int y = 0; y < height; y++)
                    {
                        Buffer.MemoryCopy(srcPtr + y * srcStride, dst + y * bmpData.Stride, srcStride, srcStride);
                    }
                }
            }
        }
        finally
        {
            target.UnlockBits(bmpData);
        }
    }

    private static void FitWithinMax(int w, int h, out int outW, out int outH)
    {
        if (w <= ShmWriter.MaxWidth && h <= ShmWriter.MaxHeight)
        {
            outW = w;
            outH = h;
            return;
        }
        double scale = Math.Min((double)ShmWriter.MaxWidth / w, (double)ShmWriter.MaxHeight / h);
        outW = Math.Max(2, ((int)(w * scale)) & ~1);
        outH = Math.Max(2, ((int)(h * scale)) & ~1);
    }
}
