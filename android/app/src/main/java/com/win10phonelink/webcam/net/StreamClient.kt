package com.win10phonelink.webcam.net

import android.os.Build
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class HandshakeRejectedException(message: String) : IOException(message)

class StreamClient(
    private val pairing: PairingPayload,
    private val codecWireName: String,
    private val encoderName: String,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val onConnected: () -> Unit,
    private val onDisconnected: (Throwable) -> Unit,
    private val onNeedSyncFrame: () -> Unit,
    private val onStatsSample: (fps: Double, kbps: Double, sentTotal: Long, droppedTotal: Long) -> Unit
) {

    private data class QueuedPacket(
        val type: Byte,
        val flags: Int,
        val ptsUs: Long,
        val payload: ByteArray
    )

    private val running = AtomicBoolean(false)
    private val disconnectReported = AtomicBoolean(false)
    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val queue = ArrayDeque<QueuedPacket>(MAX_QUEUE_SIZE + 4)

    @Volatile
    private var socket: Socket? = null
    private var writerThread: Thread? = null
    private var readerThread: Thread? = null

    @Volatile
    private var droppedTotal: Long = 0L

    @Volatile
    private var sentTotal: Long = 0L

    @Volatile
    private var lastWriteMs: Long = 0L

    fun start() {
        if (!running.compareAndSet(false, true)) return

        writerThread = Thread({
            runWriterLoop()
        }, "PlwcWriter").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        disconnectReported.set(true)
        if (!running.compareAndSet(true, false)) return
        lock.withLock {
            queue.clear()
            notEmpty.signalAll()
        }
        closeSocketQuietly()
        writerThread?.interrupt()
        readerThread?.interrupt()
        writerThread = null
        readerThread = null
    }

    fun sendVideoFrame(isKeyframe: Boolean, isCodecConfig: Boolean, rotationDegrees: Int, ptsUs: Long, payload: ByteArray) {
        if (!running.get() || payload.isEmpty()) return
        val flags = buildFlags(isKeyframe, isCodecConfig, rotationDegrees)
        enqueuePacket(QueuedPacket(PACKET_TYPE_VIDEO, flags, ptsUs, payload))
    }

    fun sendControl(rotationDegrees: Int, cameraWireName: String) {
        if (!running.get()) return
        val json = JSONObject().apply {
            put("rotation", normalizeDegrees(rotationDegrees))
            put("camera", cameraWireName)
        }.toString().toByteArray(Charsets.UTF_8)
        val flags = buildFlags(isKeyframe = false, isCodecConfig = false, rotationDegrees = rotationDegrees)
        val ptsUs = System.nanoTime() / 1000L
        enqueuePacket(QueuedPacket(PACKET_TYPE_CONTROL, flags, ptsUs, json))
    }

    private fun enqueuePacket(packet: QueuedPacket) {
        var dropped = false
        lock.withLock {
            if (!running.get()) return
            if (packet.type == PACKET_TYPE_VIDEO && queue.size >= MAX_QUEUE_SIZE) {
                val isCritical = (packet.flags and (FLAG_KEYFRAME or FLAG_CODEC_CONFIG)) != 0
                if (isCritical) {
                    // Evict oldest non-critical video packet to make room for keyframe/config.
                    val idx = queue.indexOfFirst {
                        it.type == PACKET_TYPE_VIDEO &&
                            (it.flags and (FLAG_KEYFRAME or FLAG_CODEC_CONFIG)) == 0
                    }
                    if (idx >= 0) {
                        queue.removeAt(idx)
                        droppedTotal++
                        dropped = true
                    } else if (queue.size >= MAX_QUEUE_SIZE + 4) {
                        queue.removeFirst()
                        droppedTotal++
                        dropped = true
                    }
                    queue.addLast(packet)
                } else {
                    // Drop current P-frame when congested and request an IDR refresh.
                    droppedTotal++
                    dropped = true
                }
            } else {
                queue.addLast(packet)
            }
            notEmpty.signal()
        }
        if (dropped) {
            onNeedSyncFrame()
        }
    }

    private fun runWriterLoop() {
        var sock: Socket? = null
        try {
            sock = Socket().apply {
                tcpNoDelay = true
                keepAlive = true
                trafficClass = 0x10
                sendBufferSize = 524_288
                soTimeout = HANDSHAKE_TIMEOUT_MS
                connect(InetSocketAddress(pairing.ip, pairing.port), CONNECT_TIMEOUT_MS)
            }
            socket = sock

            val rawOut = BufferedOutputStream(sock.getOutputStream(), 65_536)
            val input = DataInputStream(sock.getInputStream())

            val handshakeJson = JSONObject().apply {
                put("token", pairing.token)
                put("device", buildDeviceName())
                put("codec", codecWireName)
                put("encoder", encoderName)
                put("width", width)
                put("height", height)
                put("fps", fps)
            }.toString().toByteArray(Charsets.UTF_8)

            val prefix = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).apply {
                put(MAGIC_PLWC)
                putInt(handshakeJson.size)
            }
            rawOut.write(prefix.array())
            rawOut.write(handshakeJson)
            rawOut.flush()

            val ack = ByteArray(4)
            try {
                input.readFully(ack)
            } catch (eof: EOFException) {
                throw HandshakeRejectedException("PC closed connection during handshake (check pairing token)")
            }
            if (!ack.contentEquals(HANDSHAKE_OK)) {
                throw HandshakeRejectedException("Unexpected handshake response from PC")
            }

            sock.soTimeout = 0
            lastWriteMs = System.currentTimeMillis()
            startDisconnectMonitor(input)
            onConnected()

            val headerBuf = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            val batch = ArrayList<QueuedPacket>(16)
            var statsWindowStart = System.currentTimeMillis()
            var windowFrames = 0
            var windowBytes = 0L

            while (running.get()) {
                batch.clear()
                lock.withLock {
                    if (queue.isEmpty() && running.get()) {
                        notEmpty.await(HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS)
                    }
                    if (running.get()) {
                        while (queue.isNotEmpty()) {
                            batch.add(queue.removeFirst())
                        }
                    }
                }

                if (!running.get()) break

                val nowMs = System.currentTimeMillis()
                if (batch.isNotEmpty()) {
                    for (pkt in batch) {
                        writeFramedPacket(rawOut, headerBuf, pkt)
                        sentTotal++
                        windowBytes += 16L + pkt.payload.size
                        if (pkt.type == PACKET_TYPE_VIDEO &&
                            (pkt.flags and FLAG_CODEC_CONFIG) == 0
                        ) {
                            windowFrames++
                        }
                    }
                    rawOut.flush()
                    lastWriteMs = nowMs
                } else if (nowMs - lastWriteMs >= HEARTBEAT_INTERVAL_MS) {
                    val hb = QueuedPacket(
                        type = PACKET_TYPE_HEARTBEAT,
                        flags = 0,
                        ptsUs = System.nanoTime() / 1000L,
                        payload = ByteArray(0)
                    )
                    writeFramedPacket(rawOut, headerBuf, hb)
                    rawOut.flush()
                    lastWriteMs = nowMs
                    windowBytes += 16L
                }

                val elapsedMs = nowMs - statsWindowStart
                if (elapsedMs >= 1000L) {
                    val measuredFps = windowFrames * 1000.0 / elapsedMs
                    val measuredKbps = (windowBytes * 8.0) / elapsedMs
                    onStatsSample(measuredFps, measuredKbps, sentTotal, droppedTotal)
                    statsWindowStart = nowMs
                    windowFrames = 0
                    windowBytes = 0L
                }
            }
        } catch (t: Throwable) {
            notifyDisconnected(t)
        } finally {
            running.set(false)
            closeSocketQuietly()
            sock?.let {
                try {
                    it.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun startDisconnectMonitor(input: DataInputStream) {
        readerThread = Thread({
            try {
                while (running.get()) {
                    val b = input.read()
                    if (b < 0) {
                        if (running.get()) {
                            throw EOFException("PC receiver closed socket")
                        }
                        break
                    }
                }
            } catch (t: Throwable) {
                if (running.get()) {
                    closeSocketQuietly()
                    lock.withLock { notEmpty.signalAll() }
                    notifyDisconnected(t)
                }
            }
        }, "PlwcReader").apply {
            isDaemon = true
            start()
        }
    }

    private fun notifyDisconnected(t: Throwable) {
        if (running.get() && disconnectReported.compareAndSet(false, true)) {
            running.set(false)
            onDisconnected(t)
        }
    }

    private fun writeFramedPacket(
        out: BufferedOutputStream,
        headerBuf: ByteBuffer,
        packet: QueuedPacket
    ) {
        headerBuf.clear()
        headerBuf.put(packet.type)
        headerBuf.put((packet.flags and 0xFF).toByte())
        headerBuf.putShort(0)
        headerBuf.putInt(packet.payload.size)
        headerBuf.putLong(packet.ptsUs)
        out.write(headerBuf.array(), 0, 16)
        if (packet.payload.isNotEmpty()) {
            out.write(packet.payload)
        }
    }

    private fun closeSocketQuietly() {
        val s = socket
        socket = null
        if (s != null) {
            try {
                s.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun buildDeviceName(): String {
        val model = Build.MODEL.orEmpty().trim()
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        return when {
            model.isNotEmpty() && manufacturer.isNotEmpty() && !model.startsWith(manufacturer, true) ->
                "$manufacturer $model"
            model.isNotEmpty() -> model
            else -> "Android Camera"
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val HANDSHAKE_TIMEOUT_MS = 8_000
        private const val HEARTBEAT_INTERVAL_MS = 2_000L
        private const val MAX_QUEUE_SIZE = 60

        private val MAGIC_PLWC = byteArrayOf(0x50, 0x4C, 0x57, 0x43) // "PLWC"
        private val HANDSHAKE_OK = byteArrayOf(0x4F, 0x4B, 0x0D, 0x0A) // "OK\r\n"

        const val PACKET_TYPE_VIDEO: Byte = 1
        const val PACKET_TYPE_CONTROL: Byte = 2
        const val PACKET_TYPE_HEARTBEAT: Byte = 3

        const val FLAG_KEYFRAME: Int = 0x01
        const val FLAG_CODEC_CONFIG: Int = 0x02

        fun normalizeDegrees(degrees: Int): Int = ((degrees % 360) + 360) % 360

        fun buildFlags(isKeyframe: Boolean, isCodecConfig: Boolean, rotationDegrees: Int): Int {
            val rotCode = when (normalizeDegrees(rotationDegrees)) {
                90 -> 1
                180 -> 2
                270 -> 3
                else -> 0
            }
            var flags = (rotCode and 0x03) shl 2
            if (isKeyframe) flags = flags or FLAG_KEYFRAME
            if (isCodecConfig) flags = flags or FLAG_CODEC_CONFIG
            return flags
        }
    }
}
