package bg.alesla.teletexting.parsers

import java.io.ByteArrayOutputStream

/**
 * Parser for Cebra Teletext TTX format
 * Similar to T42 but with different structure
 */
object TTXParser {

    /**
     * Decode hamming 8/4 code
     */
    private fun hammingDecode(byte: Int): Int {
        val d1 = (byte shr 1) and 1
        val d2 = (byte shr 3) and 1
        val d3 = (byte shr 5) and 1
        val d4 = (byte shr 7) and 1
        return (d4 shl 3) or (d3 shl 2) or (d2 shl 1) or d1
    }

    /**
     * Encode hamming 8/4 code
     */
    private fun hammingEncode(nibble: Int): Int {
        val d1 = nibble and 1
        val d2 = (nibble shr 1) and 1
        val d3 = (nibble shr 2) and 1
        val d4 = (nibble shr 3) and 1

        val p1 = d1 xor d3 xor d4
        val p2 = d1 xor d2 xor d4
        val p3 = d1 xor d2 xor d3
        val p4 = d2 xor d3 xor d4

        return (d4 shl 7) or (p4 shl 6) or (d3 shl 5) or (p3 shl 4) or
                (d2 shl 3) or (p2 shl 2) or (d1 shl 1) or p1
    }

    /**
     * Parse TTX format from byte array
     * TTX format is similar to T42 with 42-byte packets
     */
    fun parse(data: ByteArray): Array<IntArray>? {
        if (data.size < 42) return null

        val pageData = Array(25) { IntArray(40) { 0x20 } }
        var offset = 0
        var foundData = false

        while (offset + 42 <= data.size) {
            val packet = data.sliceArray(offset until offset + 42)

            // TTX uses same hamming decode as T42
            val ham0 = hammingDecode(packet[0].toInt() and 0xFF)
            val ham1 = hammingDecode(packet[1].toInt() and 0xFF)

            val magazine = ham0 and 0x07
            val y0 = (ham0 shr 3) and 1
            val y1_y4 = ham1 and 0x0F
            val packetNum = (y1_y4 shl 1) or y0

            val row = packetNum

            if (row in 0..24) {
                foundData = true

                var startCol = 0
                var dataOffset = 2

                if (row == 0) {
                    // Page header
                    dataOffset = 10
                    startCol = 8

                    val pageUnits = hammingDecode(packet[2].toInt() and 0xFF)
                    val pageTens = hammingDecode(packet[3].toInt() and 0xFF)

                    if (magazine != 0) {
                        pageData[0][0] = 0x30 + magazine
                    }
                    pageData[0][1] = 0x30 + pageTens
                    pageData[0][2] = 0x30 + pageUnits
                }

                // Copy data
                for (col in startCol until 40) {
                    if (dataOffset >= packet.size) break
                    val charCode = packet[dataOffset++].toInt() and 0x7F
                    pageData[row][col] = charCode
                }
            }

            offset += 42
        }

        return if (foundData) pageData else null
    }

    /**
     * Serialize page data to TTX format
     */
    fun serialize(pageData: Array<IntArray>): ByteArray {
        val output = ByteArrayOutputStream()

        for (row in 0 until 25) {
            val packet = ByteArray(42)

            // Encode address
            val y = row
            val magazine = 1
            val y0 = y and 1
            val y1_y4 = (y shr 1) and 0x0F

            val ham0 = magazine or ((y0 shl 3))
            val ham1 = y1_y4

            packet[0] = hammingEncode(ham0).toByte()
            packet[1] = hammingEncode(ham1).toByte()

            // For row 0, encode page number
            if (row == 0) {
                // Extract page number from first 3 chars
                val pageUnits = if (pageData[0][2] >= 0x30 && pageData[0][2] <= 0x39) {
                    pageData[0][2] - 0x30
                } else 0

                val pageTens = if (pageData[0][1] >= 0x30 && pageData[0][1] <= 0x39) {
                    pageData[0][1] - 0x30
                } else 0

                packet[2] = hammingEncode(pageUnits).toByte()
                packet[3] = hammingEncode(pageTens).toByte()

                // Fill control bits
                for (i in 4..9) {
                    packet[i] = hammingEncode(0).toByte()
                }
            }

            // Copy data with parity bit
            for (col in 0 until 40) {
                packet[2 + col] = (pageData[row][col] or 0x80).toByte()
            }

            output.write(packet)
        }

        return output.toByteArray()
    }
}