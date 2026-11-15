using System.Diagnostics;
using System.IO.MemoryMappedFiles;
using System.Runtime.InteropServices;

namespace PhoneCamReceiver;

[StructLayout(LayoutKind.Explicit, Size = 64)]
public unsafe struct ShmHeader
{
    [FieldOffset(0)] public uint Magic;
    [FieldOffset(4)] public uint Version;
    [FieldOffset(8)] public ulong WriteSeq;
    [FieldOffset(16)] public uint ActiveSlot;
    [FieldOffset(20)] public uint FrameState;
    [FieldOffset(24)] public uint Width;
    [FieldOffset(28)] public uint Height;
    [FieldOffset(32)] public uint PixelFormat;
    [FieldOffset(36)] public uint Stride;
    [FieldOffset(40)] public long Timestamp100Ns;
    [FieldOffset(48)] public uint PayloadSize;
    [FieldOffset(52)] public fixed byte Reserved[12];
}

/// <summary>
/// Lock-free triple-buffered producer for Local\PhoneLinkCam_Shm and Local\PhoneLinkCam_Event.
/// </summary>
public sealed unsafe class ShmWriter : IDisposable
{
    public const string MappingName = @"Local\PhoneLinkCam_Shm";
    public const string EventName = @"Local\PhoneLinkCam_Event";
    public const uint ShmMagic = 0x504C434Du;
    public const uint ShmVersion = 1u;
    public const int MaxWidth = 1920;
    public const int MaxHeight = 1080;
    public const int SlotCount = 3;
    public const int HeaderSize = 64;
    public const int SlotSize = MaxWidth * MaxHeight * 4; // 8,294,400 bytes
    public const long TotalSize = HeaderSize + (long)SlotCount * SlotSize; // 24,883,264 bytes

    private readonly object _writeLock = new();
    private readonly MemoryMappedFile _mmf;
    private readonly MemoryMappedViewAccessor _accessor;
    private readonly EventWaitHandle _frameEvent;
    private byte* _basePtr;
    private ulong _writeSeq;
    private uint _activeSlot;
    private bool _disposed;

    public ShmWriter()
    {
        if (sizeof(ShmHeader) != HeaderSize)
        {
            throw new InvalidOperationException($"ShmHeader size mismatch: {sizeof(ShmHeader)}");
        }

        _mmf = MemoryMappedFile.CreateOrOpen(MappingName, TotalSize, MemoryMappedFileAccess.ReadWrite);
        _accessor = _mmf.CreateViewAccessor(0, TotalSize, MemoryMappedFileAccess.ReadWrite);
        _frameEvent = new EventWaitHandle(false, EventResetMode.ManualReset, EventName);

        byte* ptr = null;
        _accessor.SafeMemoryMappedViewHandle.AcquirePointer(ref ptr);
        _basePtr = ptr;

        InitializeHeader();
    }

    public ulong CurrentWriteSeq
    {
        get
        {
            lock (_writeLock)
            {
                return _writeSeq;
            }
        }
    }

    private void InitializeHeader()
    {
        lock (_writeLock)
        {
            var hdr = (ShmHeader*)_basePtr;
            // Preserve existing write_seq if reattaching to an existing mapping so consumers see forward progress.
            ulong existingSeq = (hdr->Magic == ShmMagic && hdr->Version == ShmVersion) ? hdr->WriteSeq : 0;
            _writeSeq = existingSeq;
            _activeSlot = 0;

            hdr->Magic = ShmMagic;
            hdr->Version = ShmVersion;
            hdr->ActiveSlot = 0;
            hdr->FrameState = (uint)FrameState.Disconnected;
            hdr->Width = 0;
            hdr->Height = 0;
            hdr->PixelFormat = (uint)ShmPixelFormat.Bgra32;
            hdr->Stride = 0;
            hdr->Timestamp100Ns = GetQpcTimestamp100Ns();
            hdr->PayloadSize = 0;
            for (int i = 0; i < 12; i++)
            {
                hdr->Reserved[i] = 0;
            }
            Thread.MemoryBarrier();
            Volatile.Write(ref hdr->WriteSeq, _writeSeq);
        }
    }

    public void WriteBgra32Frame(ReadOnlySpan<byte> frameBytes, int width, int height, FrameState state = FrameState.Live)
    {
        if (width <= 0 || height <= 0 || width > MaxWidth || height > MaxHeight)
        {
            throw new ArgumentOutOfRangeException(nameof(width), $"Frame dimensions {width}x{height} exceed {MaxWidth}x{MaxHeight}");
        }

        int stride = width * 4;
        int required = stride * height;
        if (required > SlotSize || frameBytes.Length < required)
        {
            throw new ArgumentException($"Invalid frame byte length {frameBytes.Length} for {width}x{height} BGRA32", nameof(frameBytes));
        }

        lock (_writeLock)
        {
            if (_disposed || _basePtr == null) return;

            uint nextSlot = (_activeSlot + 1) % SlotCount;
            byte* slotPtr = _basePtr + HeaderSize + (long)nextSlot * SlotSize;

            fixed (byte* srcPtr = frameBytes)
            {
                Buffer.MemoryCopy(srcPtr, slotPtr, SlotSize, required);
            }

            // Publish slot pixels before updating header fields and write_seq.
            Thread.MemoryBarrier();

            var hdr = (ShmHeader*)_basePtr;
            hdr->Magic = ShmMagic;
            hdr->Version = ShmVersion;
            hdr->ActiveSlot = nextSlot;
            hdr->FrameState = (uint)state;
            hdr->Width = (uint)width;
            hdr->Height = (uint)height;
            hdr->PixelFormat = (uint)ShmPixelFormat.Bgra32;
            hdr->Stride = (uint)stride;
            hdr->Timestamp100Ns = GetQpcTimestamp100Ns();
            hdr->PayloadSize = (uint)required;

            Thread.MemoryBarrier();
            _activeSlot = nextSlot;
            _writeSeq++;
            Volatile.Write(ref hdr->WriteSeq, _writeSeq);

            _frameEvent.Set();
            _frameEvent.Reset();
        }
    }

    public void SetState(FrameState state)
    {
        lock (_writeLock)
        {
            if (_disposed || _basePtr == null) return;

            var hdr = (ShmHeader*)_basePtr;
            hdr->Magic = ShmMagic;
            hdr->Version = ShmVersion;
            hdr->FrameState = (uint)state;
            hdr->Timestamp100Ns = GetQpcTimestamp100Ns();
            Thread.MemoryBarrier();
            _writeSeq++;
            Volatile.Write(ref hdr->WriteSeq, _writeSeq);

            _frameEvent.Set();
            _frameEvent.Reset();
        }
    }

    private static long GetQpcTimestamp100Ns()
    {
        return (long)((double)Stopwatch.GetTimestamp() * 10_000_000.0 / Stopwatch.Frequency);
    }

    public void Dispose()
    {
        lock (_writeLock)
        {
            if (_disposed) return;
            _disposed = true;

            if (_basePtr != null)
            {
                var hdr = (ShmHeader*)_basePtr;
                hdr->FrameState = (uint)FrameState.Disconnected;
                Thread.MemoryBarrier();
                _writeSeq++;
                Volatile.Write(ref hdr->WriteSeq, _writeSeq);

                _accessor.SafeMemoryMappedViewHandle.ReleasePointer();
                _basePtr = null;
            }

            _frameEvent.Set();
            _frameEvent.Dispose();
            _accessor.Dispose();
            _mmf.Dispose();
        }
    }
}
