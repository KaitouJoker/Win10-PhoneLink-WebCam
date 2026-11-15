using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.CompilerServices;
using System.Threading.Tasks;

namespace PhoneCamReceiver;

/// <summary>
/// Normalizes BGRA32 frames (rotation 0/90/180/270 and clamping within 1920x1080 SHM limits)
/// and copies them into WinForms preview bitmaps.
/// </summary>
public static unsafe class FrameTransformer
{
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
        int rot = ((rotationQuarterTurns % 4) + 4) % 4;
        bool hasExplicitTarget = targetWidth > 0 && targetHeight > 0;
        bool fitsDirectly = srcW <= ShmWriter.MaxWidth && srcH <= ShmWriter.MaxHeight;

        if (rot == 0 && fitsDirectly && (!hasExplicitTarget || (srcW == targetWidth && srcH == targetHeight)))
        {
            outW = srcW;
            outH = srcH;
            return src.Slice(0, srcW * srcH * 4);
        }

        // Compute target dimensions within 1920x1080 so vcam-dshow ShmReader::ComputeLayout always accepts it.
        if (hasExplicitTarget)
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

        int outBytes = outW * outH * 4;
        if (scratch.Length < outBytes)
        {
            scratch = new byte[outBytes];
        }

        fixed (byte* sBase = src)
        fixed (byte* dBase = scratch)
        {
            uint* s = (uint*)sBase;
            uint* d = (uint*)dBase;

            if (rot == 2 && srcW == outW && srcH == outH)
            {
                int total = srcW * srcH;
                for (int i = 0; i < total; i++)
                {
                    d[i] = s[total - 1 - i];
                }
                return scratch.AsSpan(0, outBytes);
            }

            // Oriented source dimensions after rotation.
            int orientedW = (rot == 1 || rot == 3) ? srcH : srcW;
            int orientedH = (rot == 1 || rot == 3) ? srcW : srcH;

            double scale = Math.Min((double)outW / orientedW, (double)outH / orientedH);
            int drawW = Math.Max(1, (int)Math.Round(orientedW * scale));
            int drawH = Math.Max(1, (int)Math.Round(orientedH * scale));
            int offX = (outW - drawW) / 2;
            int offY = (outH - drawH) / 2;

            // Only clear letterbox bars (never memset 8MB when the active area will overwrite it).
            if (offY > 0)
            {
                Array.Clear(scratch, 0, offY * outW * 4);
            }
            int bottomRow = offY + drawH;
            if (bottomRow < outH)
            {
                int bottomStart = bottomRow * outW * 4;
                Array.Clear(scratch, bottomStart, outBytes - bottomStart);
            }

            nint sAddr = (nint)s;
            nint dAddr = (nint)d;
            int dstW = outW;
            int rightX = offX + drawW;

            if (drawH >= 240)
            {
                Parallel.For(0, drawH, dy =>
                {
                    TransformRow((uint*)sAddr, (uint*)dAddr, srcW, srcH, dstW, rot, orientedW, orientedH, drawW, drawH, offX, offY, rightX, dy);
                });
            }
            else
            {
                for (int dy = 0; dy < drawH; dy++)
                {
                    TransformRow(s, d, srcW, srcH, dstW, rot, orientedW, orientedH, drawW, drawH, offX, offY, rightX, dy);
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
