package bg.alesla.teletexting.parsers

/**
 * Parser for Binary Dump of Level 1 Page Data
 * Raw 1000 bytes (25 rows x 40 columns)
 */
object BinaryParser {

    /**
     * Parse binary format from byte array
     */
    fun parse(data: ByteArray): Array<IntArray>? {
        if (data.size < 960) return null

        val pageData = Array(25) { IntArray(40) { 0x20 } }
        var offset = 0

        // Handle files with extra header
        if (data.size > 1000) {
            // Check for EP1 signature
            if (data.size >= 2 && data[0] == 0xFE.toByte() && data[1] == 0x01.toByte()) {
                return EP1Parser.parse(data)
            }

            // Skip to last 1000 bytes
            offset = data.size - 1000
        }

        // Read 25 rows x 40 columns
        for (row in 0 until 25) {
            for (col in 0 until 40) {
                if (offset + (row * 40) + col >= data.size) break
                pageData[row][col] = data[offset + (row * 40) + col].toInt() and 0x7F
            }
        }

        return pageData
    }

    /**
     * Serialize page data to binary format (1000 bytes)
     */
    fun serialize(pageData: Array<IntArray>): ByteArray {
        val output = ByteArray(1000)

        for (row in 0 until 25) {
            for (col in 0 until 40) {
                output[row * 40 + col] = (pageData[row][col] and 0x7F).toByte()
            }
        }

        return output
    }
}