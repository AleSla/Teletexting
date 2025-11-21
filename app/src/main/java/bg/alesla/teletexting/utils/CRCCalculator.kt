package bg.alesla.teletexting.utils

/**
 * CRC Calculator for Teletext pages
 * Implements CRC-16-CCITT for data integrity checking
 */
object CRCCalculator {

    private const val POLYNOMIAL = 0x1021
    private const val INITIAL_VALUE = 0xFFFF

    /**
     * Calculate CRC-16-CCITT for page data
     */
    fun calculateCRC(pageData: Array<IntArray>): Int {
        var crc = INITIAL_VALUE

        for (row in 0 until 25) {
            for (col in 0 until 40) {
                val byte = pageData[row][col] and 0x7F
                crc = updateCRC(crc, byte)
            }
        }

        return crc
    }

    /**
     * Calculate CRC for a specific row
     */
    fun calculateRowCRC(rowData: IntArray): Int {
        var crc = INITIAL_VALUE

        for (byte in rowData) {
            crc = updateCRC(crc, byte and 0x7F)
        }

        return crc
    }

    /**
     * Update CRC with a single byte
     */
    private fun updateCRC(crc: Int, byte: Int): Int {
        var result = crc
        var data = byte

        for (i in 0 until 8) {
            val bit = ((data shr (7 - i)) and 1) == 1
            val c15 = ((result shr 15) and 1) == 1

            result = result shl 1

            if (c15 xor bit) {
                result = result xor POLYNOMIAL
            }
        }

        return result and 0xFFFF
    }

    /**
     * Verify CRC matches expected value
     */
    fun verifyCRC(pageData: Array<IntArray>, expectedCRC: Int): Boolean {
        val calculatedCRC = calculateCRC(pageData)
        return calculatedCRC == expectedCRC
    }

    /**
     * Extract embedded CRC from page header (if present)
     * Teletext pages may store CRC in specific header positions
     */
    fun extractEmbeddedCRC(pageData: Array<IntArray>): Int? {
        // Check if header row contains CRC markers
        // This is format-dependent, so we return null by default
        // Specific formats can override this behavior
        return null
    }

    /**
     * Format CRC as hex string
     */
    fun formatCRC(crc: Int): String {
        return String.format("%04X", crc)
    }
}