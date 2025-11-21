package bg.alesla.teletexting.parsers

import java.io.ByteArrayOutputStream

/**
 * Parser for Softel EP1 (Softel Page 1) format
 */
object EP1Parser {

    /**
     * Parse EP1 format from byte array
     * Format: 0xFE 0x01 [4 header bytes] [960 bytes of page data]
     */
    fun parse(data: ByteArray): Array<IntArray>? {
        if (data.size < 6) return null
        if (data[0] != 0xFE.toByte() || data[1] != 0x01.toByte()) return null

        val pageData = Array(25) { IntArray(40) { 0x20 } }
        var offset = 6

        for (row in 0 until 25) {
            for (col in 0 until 40) {
                if (offset >= data.size) break
                pageData[row][col] = data[offset++].toInt() and 0x7F
            }
        }

        return pageData
    }

    /**
     * Serialize page data to EP1 format
     */
    fun serialize(pageData: Array<IntArray>): ByteArray {
        val output = ByteArrayOutputStream()

        // Header
        output.write(0xFE)
        output.write(0x01)
        output.write(0x09) // Format ID
        output.write(0x00) // Reserved
        output.write(0x06) // Header size LSB
        output.write(0x00) // Header size MSB

        // Page data (skip row 0 header in some implementations, but we'll include all)
        for (row in 0 until 25) {
            for (col in 0 until 40) {
                output.write(pageData[row][col] and 0x7F)
            }
        }

        return output.toByteArray()
    }
}