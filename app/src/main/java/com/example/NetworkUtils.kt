package com.example

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {

    /**
     * Finds the local IP address on the active network interface (Wi-Fi or Mobile Hotspot).
     * Automatically filters loopbacks and returns the first eligible IPv4 address.
     * Defaults to the standard Android Hotspot gateway IP (192.168.43.1) if not found.
     */
    fun getLocalIPAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            // First pass: Prioritize Wi-Fi and Hotspot interfaces ('wlan', 'ap', 'rndis', 'p2p')
            for (networkInterface in interfaces) {
                val name = networkInterface.name.lowercase()
                if (name.contains("wlan") || name.contains("ap") || name.contains("rndis") || name.contains("p2p")) {
                    val addresses = Collections.list(networkInterface.inetAddresses)
                    for (address in addresses) {
                        if (!address.isLoopbackAddress) {
                            val hostAddress = address.hostAddress ?: ""
                            val isIPv4 = !hostAddress.contains(':')
                            if (isIPv4) {
                                return hostAddress
                            }
                        }
                    }
                }
            }
            
            // Second pass: general local network interfaces, excluding virtual tunnels/mobile carrier cellular known interfaces
            for (networkInterface in interfaces) {
                val name = networkInterface.name.lowercase()
                if (name.contains("tun") || name.contains("ppp") || name.contains("rmnet") || name.contains("ccmni")) {
                    continue
                }
                val addresses = Collections.list(networkInterface.inetAddresses)
                for (address in addresses) {
                    if (!address.isLoopbackAddress) {
                        val hostAddress = address.hostAddress ?: ""
                        val isIPv4 = !hostAddress.contains(':')
                        if (isIPv4) {
                            return hostAddress
                        }
                    }
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return "192.168.43.1" // Standard android AP gateway fallback
    }

    /**
     * Renders a QR code bit-matrix representing the client connection url into an Android Bitmap
     * that can be natively displayed in Compose.
     */
    fun generateQRCode(content: String, size: Int = 400): Bitmap {
        return try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(
                        x, 
                        y, 
                        if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE
                    )
                }
            }
            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            // Create a small empty placeholder bitmap in case of errors
            Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        }
    }
}
