package de.pixoo

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.log2
import kotlin.math.max

class PixooManager {
    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private val TAG = "PixooManager"
    private val WIDTH = 32
    private val HEIGHT = 32
    private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice): Boolean {
        Log.d(TAG, "Connecting to ${device.name} (${device.address})...")
        close()

        var success = false

        // 1. Bonded -> Secure
        try {
            if (device.bondState == BluetoothDevice.BOND_BONDED) {
                Log.d(TAG, "Attempt 1: Secure Socket (Bonded)")
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket?.connect()
                success = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Secure connect failed: ${e.message}")
        }

        // 2. Fallback -> Insecure
        if (!success) {
            try {
                Log.d(TAG, "Attempt 2: Insecure Socket")
                socket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                socket?.connect()
                success = true
            } catch (e: Exception) {
                Log.w(TAG, "Insecure connect failed: ${e.message}")
            }
        }

        if (success) {
            outputStream = socket?.outputStream
            inputStream = socket?.inputStream
            Log.d(TAG, "Connected successfully")
            return true
        }
        return false
    }

    private fun close() {
        try {
            socket?.close()
        } catch (e: Exception) {
        }
        socket = null
        outputStream = null
        inputStream = null
    }

    fun sendImage(bitmap: Bitmap?, overlayNumber: Int) {
        if (outputStream == null) return
        if (bitmap == null) return

        // Based on https://github.com/HoroTW/pixoo-awesome/blob/main/modules/pixoo_client.py
        val rawBitmap = Bitmap.createScaledBitmap(bitmap, WIDTH, HEIGHT, false)
            .copy(Bitmap.Config.ARGB_8888, false)
        val bitmapWithText = drawNumberOnBitmap(rawBitmap, overlayNumber, Color.BLACK)

        var colors = mutableListOf<Int>()
        val pixels = getColorPaletteAndColorReferencedImage(colors, bitmapWithText)
        val colorCount = colors.size

        val header = ByteArray(12)
        val innerLength = 8 + pixels.size
        header[0] = 0x00.toByte()
        header[1] = 0x0A.toByte()
        header[2] = 0x0A.toByte()
        header[3] = 0x04.toByte()
        header[4] = 0xAA.toByte()
        header[5] = (innerLength and 0xFF).toByte()      // Length LSB
        header[6] = (innerLength shr 8 and 0xFF).toByte() // Length MSB
        header[7] = 0x00.toByte()
        header[8] = 0x00.toByte()
        header[9] = 0x03.toByte() // Palette mode flag (?)
        header[10] = (colorCount and 0xFF).toByte()       // Color Count LSB
        header[11] = (colorCount shr 8 and 0xFF).toByte()  // Color Count MSB

        val fullPayload = ByteArray(header.size + pixels.size)
        System.arraycopy(header, 0, fullPayload, 0, header.size)
        System.arraycopy(pixels, 0, fullPayload, header.size, pixels.size)

        sendPacket(0x44.toByte(), fullPayload)
    }

    fun drawNumberOnBitmap(
        bitmap: Bitmap,
        value: Int,
        color: Int = Color.BLACK,
        textSize: Float = 7f
    ): Bitmap {
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(mutableBitmap)

        val paint = android.graphics.Paint().apply {
            this.color = color
            this.textSize = textSize
            this.isAntiAlias = false
            this.textAlign = android.graphics.Paint.Align.LEFT
            this.typeface = android.graphics.Typeface.MONOSPACE
        }

        // Draw in top-left corner, offset by (1, text height)
        canvas.drawText(value.toString(), 1f, textSize, paint)

        return mutableBitmap
    }

    fun quantizeBitmap(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val quantizedImage = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        for (i in pixels.indices) {
            val color = pixels[i]
            // Masking the lower 5 bits of R, G, and B to reduce precision
            // This effectively limits the possible color combinations
            val r = (color shr 16 and 0xFF) and 0xE0 // Keep top 3 bits
            val g = (color shr 8 and 0xFF) and 0xE0  // Keep top 3 bits
            val b = (color and 0xFF) and 0xC0         // Keep top 2 bits

            quantizedImage.setPixel(i % width, i / width, Color.rgb(r, g, b))
        }

        return quantizedImage
    }

    private fun getColorPaletteAndColorReferencedImage(
        palette: MutableList<Int>,
        bitmap: Bitmap
    ): ByteArray {
        val width = bitmap.width
        val height = bitmap.height

        var paletteIndexMap = mutableMapOf<Int, Int>()
        var screen = IntArray(width * height)

        buildPaletteAndPaletteIndexMap(palette, paletteIndexMap, bitmap, screen)

        if (palette.size > 256) {
            // Need to quantize colors
            val quantizedBitamp = quantizeBitmap(bitmap)
            screen = IntArray(width * height)
            paletteIndexMap = mutableMapOf<Int, Int>()
            buildPaletteAndPaletteIndexMap(palette, paletteIndexMap, quantizedBitamp, screen)
        }

        val bitLength = max(1, ceil(log2(palette.size.toDouble())).toInt())

        // Encode Image
        val screenBufferSize = ceil((bitLength * screen.size).toDouble() / 8.0).toInt()
        val screenBuffer = ByteArray(screenBufferSize)
        var bufferIndex = 0
        var current = 0
        var currentIndex = 0

        for (paletteIndex in screen) {
            val reference = paletteIndex and ((1 shl bitLength) - 1)
            current = current or (reference shl currentIndex)
            currentIndex += bitLength

            while (currentIndex >= 8) {
                screenBuffer[bufferIndex++] = (current and 0xFF).toByte()
                current = current ushr 8
                currentIndex -= 8
            }
        }
        if (currentIndex != 0) screenBuffer[bufferIndex] = (current and 0xFF).toByte()

        val colorCount = palette.size
        val paletteBytes = colorCount * 3
        val totalSize = paletteBytes + screenBuffer.size
        val result = ByteArray(totalSize)

        // Write Color Palette
        for (i in palette.indices) {
            val rgb = palette[i]
            result[i * 3 + 0] = (rgb shr 16 and 0xFF).toByte() // Red
            result[i * 3 + 1] = (rgb shr 8 and 0xFF).toByte()  // Green
            result[i * 3 + 2] = (rgb and 0xFF).toByte()        // Blue
        }

        // Combine into a single ByteArray
        // Structure: [Palette Bytes] + [Screen Bytes]
        System.arraycopy(screenBuffer, 0, result, paletteBytes, screenBuffer.size)
        return result
    }

    fun buildPaletteAndPaletteIndexMap(
        palette: MutableList<Int>,
        paletteIndexMap: MutableMap<Int, Int>,
        bitmap: Bitmap,
        screen: IntArray
    ) {
        var screenIdx = 0
        val width = bitmap.width
        val height = bitmap.height
        for (y in 0 until height) {
            for (x in 0 until width) {
                val rgb = bitmap.getPixel(x, y) and 0x00FFFFFF
                val index = paletteIndexMap.getOrPut(rgb) {
                    palette.add(rgb)
                    palette.size - 1
                }
                screen[screenIdx++] = index
            }
        }
    }


    fun printByteArrayHex(bytes: ByteArray, endAfterbytes: Int = 0, bytesPerLine: Int = 32) {
        val sb = StringBuilder()
        for (i in bytes.indices) {
            if (endAfterbytes > 0 && i > endAfterbytes) {
                sb.append("...")
                break
            }
            sb.append(String.format("%02X ", bytes[i]))
            if ((i + 1) % bytesPerLine == 0 && i != bytes.size - 1) {
                sb.append("\n")
            }
        }
        Log.d("Pixoo", sb.toString())
    }

    private fun sendPacket(cmd: Byte, payload: ByteArray?) {
        if (outputStream == null) return
        val buffer = constructBufferEscaped(cmd, payload)
        try {
            // Send in chunks to prevent buffer overflow on Pixoo
            val chunkSize = 200
            var offset = 0

            while (offset < buffer.size) {
                val length = Math.min(chunkSize, buffer.size - offset)
                outputStream?.write(buffer, offset, length)
                outputStream?.flush()
                offset += length

                // Give the Pixoo a tiny moment to process the buffer
                Thread.sleep(20)
            }
            Log.d(TAG, ">> TX: Packet Sent (${buffer.size} bytes in chunks)")
        } catch (e: Exception) {
            Log.e(TAG, "Write Failed: ${e.message}")
            close()
        }
    }


    private fun constructBufferEscaped(cmd: Byte, payload: ByteArray?): ByteArray {
        // documentation:
        // https://docin.divoom-gz.com/web/#/5/289
        // https://docin.divoom-gz.com/web/#/5/146
        val payloadLen = payload?.size ?: 0
        // Length: Cmd(1) + Payload(N) + Length(2)
        val internalLength = 1 + payloadLen + 2

        // rawInnerSize includes the check sum (2 bytes)
        val rawInnerSize = internalLength + 2
        val innerBuffer = ByteArray(rawInnerSize)
        var idx = 0

        // Length (Little Endian)
        innerBuffer[idx++] = (internalLength and 0xFF).toByte()
        innerBuffer[idx++] = ((internalLength shr 8) and 0xFF).toByte()

        // Command
        innerBuffer[idx++] = cmd

        // Payload / Data
        if (payload != null) {
            System.arraycopy(payload, 0, innerBuffer, idx, payload.size)
            idx += payload.size
        }

        // Checksum
        // the sum of the packet length, command, and data
        var checksumSum = 0
        for (i in 0 until idx) {
            checksumSum += (innerBuffer[i].toInt() and 0xFF)
        }
        val checksum16bit = checksumSum and 0xFFFF

        // Checksum (Little Endian)
        innerBuffer[idx++] = (checksum16bit and 0xFF).toByte()
        innerBuffer[idx++] = ((checksum16bit shr 8) and 0xFF).toByte()

        val outStream = ByteArrayOutputStream()

        // Signal start of package
        outStream.write(0x01)

        for (b in innerBuffer) {
            val byteVal = b.toInt() and 0xFF
            outStream.write(byteVal)
        }

        // Signal end of package
        outStream.write(0x02)

        val bytes = outStream.toByteArray()

        Log.d(TAG, "Sending Package (${bytes.size} bytes)...")
        printByteArrayHex(bytes, 20)

        return bytes
    }
}