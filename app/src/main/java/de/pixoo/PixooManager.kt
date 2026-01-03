package de.pixoo

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.core.graphics.blue
import androidx.core.graphics.get
import androidx.core.graphics.green
import androidx.core.graphics.red
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

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
        try { socket?.close() } catch (e: Exception) {}
        socket = null
        outputStream = null
        inputStream = null
    }

    fun sendImage(bitmap: Bitmap) {
        if (outputStream == null) return

        val standardBitmap = Bitmap.createScaledBitmap(bitmap, WIDTH, HEIGHT, true)
            .copy(Bitmap.Config.ARGB_8888, false)
        val pixels = getPixelsFromBitmap(WIDTH, HEIGHT, standardBitmap)

        val header = ByteArray(11)
        header[0] = 0x00.toByte()
        header[1] = 0x00.toByte()
        header[2] = 0x00.toByte()       // Padding
        header[3] = WIDTH.toByte()      // Width LSB
        header[4] = 0x00.toByte()       // Width MSB
        header[5] = HEIGHT.toByte()     // Height LSB
        header[6] = 0x00.toByte()       // Height MSB
        // Padding because most commands don't work without it
        header[7] = 0x00.toByte()
        header[8] = 0x00.toByte()
        header[9] = 0x00.toByte()
        header[10] = 0x00.toByte()

        val fullPayload = ByteArray(header.size + pixels.size)
        System.arraycopy(header, 0, fullPayload, 0, header.size)
        System.arraycopy(pixels, 0, fullPayload, header.size, pixels.size)

        Log.d(TAG, "Sending Image Packet (${fullPayload.size} bytes)...")
        sendPacket(0x44.toByte(), fullPayload)
    }

    private fun getPixelsFromBitmap(
        width: Int,
        height: Int,
        bitmap: Bitmap
    ): ByteArray {
        val pixels = ByteArray(width*height*3)
        var index = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = bitmap.get(x, y)
                // Conversion from ARGB888 to RGB888
                pixels[index++] = pixel.red.toByte()   // R
                pixels[index++] = pixel.green.toByte() // G
                pixels[index++] = pixel.blue.toByte()  // B
            }
        }
        printByteArrayHex(pixels, WIDTH*3)
        return pixels
    }

    fun printByteArrayHex(bytes: ByteArray, bytesPerLine: Int = 32) {
        val sb = StringBuilder()
        for (i in bytes.indices) {
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

        // Escaping
        for (b in innerBuffer) {
            val byteVal = b.toInt() and 0xFF
            when (byteVal) {
                0x01 -> { outStream.write(0x03); outStream.write(0x04) }
                0x02 -> { outStream.write(0x03); outStream.write(0x05) }
                0x03 -> { outStream.write(0x03); outStream.write(0x06) }
                else -> { outStream.write(byteVal) }
            }
        }

        // Signal end of package
        outStream.write(0x02)

        val bytes = outStream.toByteArray()
        printByteArrayHex(bytes)

        return bytes
    }

    // -------------------------------------------
    // Helper functions to create test patterns
    // -------------------------------------------

    suspend fun drawGradient(): Bitmap = withContext(Dispatchers.IO) {
        Log.d(TAG, "Filling Screen with red-green Gradient (Corrected Palette Mode)...")
        val bitmap = createRedGreenGradient(WIDTH, HEIGHT)
        sendImage(bitmap)
        return@withContext bitmap
    }

    suspend fun drawSquares(): Bitmap = withContext(Dispatchers.IO) {
        Log.d(TAG, "Filling screen with 16 blue and white squares (Corrected Palette Mode)...")
        val bitmap = generate16QuadrantImage(WIDTH, HEIGHT)
        sendImage(bitmap)
        return@withContext bitmap
    }

    suspend fun fillRed() : Bitmap = withContext(Dispatchers.IO) {
        Log.d(TAG, "Filling Screen RED")
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        for (y in 0 until WIDTH) {
            for (x in 0 until HEIGHT) {
                bitmap.setPixel(x, y, Color.RED)
            }
        }
        sendImage(bitmap)
        return@withContext bitmap
    }

    suspend fun fillBlue() : Bitmap = withContext(Dispatchers.IO) {
        Log.d(TAG, "Filling Screen BLUE")
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        for (y in 0 until WIDTH) {
            for (x in 0 until HEIGHT) {
                bitmap.setPixel(x, y, Color.BLUE)
            }
        }
        sendImage(bitmap)
        return@withContext bitmap
    }

    suspend fun pixelTest(): Bitmap = withContext(Dispatchers.IO) {
        Log.d(TAG, "Filling Screen RED with 1 GREEN pixel (1st pixel)")
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        for (y in 0 until WIDTH) {
            for (x in 0 until HEIGHT) {
                bitmap.setPixel(x, y, Color.RED)
            }
        }
        bitmap.setPixel(0,0, Color.GREEN)
        sendImage(bitmap)
        return@withContext bitmap
    }

    suspend fun pixelTest2(): Bitmap = withContext(Dispatchers.IO) {
        Log.d(TAG, "Filling Screen RED with 1 GREEN pixel (2nd pixel)")
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        for (y in 0 until WIDTH) {
            for (x in 0 until HEIGHT) {
                bitmap.setPixel(x, y, Color.RED)
            }
        }
        bitmap.setPixel(1,0, Color.GREEN)
        sendImage(bitmap)
        return@withContext bitmap
    }
}

private fun generate16QuadrantImage(width: Int, height: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val squareSize = width / 4

    for (y in 0 until width) {
        for (x in 0 until height) {
            val col = x / squareSize
            val row = y / squareSize

            // Checkerboard logic:
            // If (col + row) is even -> Blue
            // If (col + row) is odd  -> White
            val isBlue = (col + row) % 2 == 0

            val color = if (isBlue) Color.BLUE else Color.WHITE
            bitmap.setPixel(x, y, color)
        }
    }
    return bitmap
}


private fun createRedGreenGradient(width: Int, height: Int): Bitmap {
    val loadedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    for (y in 0 until width) {
        for (x in 0 until height) {
            // Create a Green/Red gradient
            loadedBitmap.setPixel(x, y, Color.rgb(x * 8, y * 8, 0))
        }
    }
    return loadedBitmap
}
