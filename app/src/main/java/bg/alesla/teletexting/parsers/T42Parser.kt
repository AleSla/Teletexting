package bg.alesla.teletexting.parsers

import java.io.ByteArrayOutputStream

/**
 * Parser for T42 (Teletext Packets) format
 * Each packet is 42 bytes: 2 address bytes + 40 data bytes
 * Properly handles multi-page files by detecting page headers (row 0)
 */
object T42Parser {

    /**
     * Decode hamming 8/4 code
     * Extracts 4 data bits from 8-bit hamming encoded byte
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

        // Calculate parity bits
        val p1 = d1 xor d3 xor d4
        val p2 = d1 xor d2 xor d4
        val p3 = d1 xor d2 xor d3
        val p4 = d2 xor d3 xor d4

        return (d4 shl 7) or (p4 shl 6) or (d3 shl 5) or (p3 shl 4) or
                (d2 shl 3) or (p2 shl 2) or (d1 shl 1) or p1
    }

    /**
     * Data class to hold parsed packet information
     */
    private data class TeletextPacket(
        val magazine: Int,
        val row: Int,
        val data: ByteArray,
        val pageNumber: String? = null
    )

    /**
     * Parse all packets from T42 file and extract individual pages
     * Returns list of pages found in the file
     */
    fun parseMultiPage(data: ByteArray): List<Array<IntArray>> {
        val pages = mutableListOf<Array<IntArray>>()
        val packets = extractAllPackets(data)

        // Group packets by page (detect when row 0 appears)
        var currentPagePackets = mutableListOf<TeletextPacket>()

        for (packet in packets) {
            if (packet.row == 0 && currentPagePackets.isNotEmpty()) {
                // Start of new page - process previous page
                val pageData = buildPageFromPackets(currentPagePackets)
                if (pageData != null) {
                    pages.add(pageData)
                }
                currentPagePackets.clear()
            }

            currentPagePackets.add(packet)
        }

        // Process last page
        if (currentPagePackets.isNotEmpty()) {
            val pageData = buildPageFromPackets(currentPagePackets)
            if (pageData != null) {
                pages.add(pageData)
            }
        }

        return pages
    }

    /**
     * Extract all packets from raw T42 data
     */
    private fun extractAllPackets(data: ByteArray): List<TeletextPacket> {
        val packets = mutableListOf<TeletextPacket>()
        var offset = 0

        while (offset + 42 <= data.size) {
            val packet = data.sliceArray(offset until offset + 42)

            // Decode address bytes
            val ham0 = hammingDecode(packet[0].toInt() and 0xFF)
            val ham1 = hammingDecode(packet[1].toInt() and 0xFF)

            val magazine = ham0 and 0x07
            val y0 = (ham0 shr 3) and 1
            val y1_y4 = ham1 and 0x0F
            val row = (y1_y4 shl 1) or y0

            // Extract page number if this is row 0 (header)
            var pageNumber: String? = null
            if (row == 0 && packet.size >= 4) {
                val pageUnits = hammingDecode(packet[2].toInt() and 0xFF)
                val pageTens = hammingDecode(packet[3].toInt() and 0xFF)
                val actualMag = if (magazine == 0) 8 else magazine
                pageNumber = "$actualMag${pageTens.toString(16).uppercase()}${pageUnits.toString(16).uppercase()}"
            }

            // Copy data bytes (skip address bytes)
            val packetData = packet.sliceArray(2 until 42)

            packets.add(TeletextPacket(magazine, row, packetData, pageNumber))
            offset += 42
        }

        return packets
    }

    /**
     * Build a complete page from a list of packets
     */
    private fun buildPageFromPackets(packets: List<TeletextPacket>): Array<IntArray>? {
        if (packets.isEmpty()) return null

        val pageData = Array(25) { IntArray(40) { 0x20 } }
        var hasValidData = false

        for (packet in packets) {
            val row = packet.row

            if (row !in 0..24) continue

            hasValidData = true

            if (row == 0) {
                // Header row - decode page number and copy header text
                if (packet.data.size >= 8) {
                    // Decode page number
                    val pageUnits = hammingDecode(packet.data[0].toInt() and 0xFF)
                    val pageTens = hammingDecode(packet.data[1].toInt() and 0xFF)
                    val magazine = if (packet.magazine == 0) 8 else packet.magazine

                    // Set magazine and page number in first 3 positions
                    pageData[0][0] = 0x30 + magazine
                    pageData[0][1] = 0x30 + pageTens
                    pageData[0][2] = 0x30 + pageUnits

                    // Copy header text (skip control bytes 0-7, copy columns 8-39)
                    for (col in 8 until 40) {
                        if (col < packet.data.size) {
                            pageData[0][col] = packet.data[col].toInt() and 0x7F
                        }
                    }
                }
            } else {
                // Data rows 1-24
                for (col in 0 until 40) {
                    if (col < packet.data.size) {
                        pageData[row][col] = packet.data[col].toInt() and 0x7F
                    }
                }
            }
        }

        return if (hasValidData) pageData else null
    }

    /**
     * Parse T42 format from byte array (legacy method for single page)
     * Use parseMultiPage() for files with multiple pages
     */
    fun parse(data: ByteArray): Array<IntArray>? {
        val pages = parseMultiPage(data)
        return pages.firstOrNull()
    }

    /**
     * Serialize page data to T42 format
     */
    fun serialize(pageData: Array<IntArray>): ByteArray {
        val output = ByteArrayOutputStream()

        for (row in 0 until 25) {
            // Create packet
            val packet = ByteArray(42)

            // Encode address
            val y = row
            val magazine = if (pageData[0][0] in 0x30..0x38) {
                pageData[0][0] - 0x30
            } else {
                1 // Default magazine
            }
            val actualMag = if (magazine == 8) 0 else magazine

            val y0 = y and 1
            val y1_y4 = (y shr 1) and 0x0F

            val ham0 = actualMag or (y0 shl 3)
            val ham1 = y1_y4

            packet[0] = hammingEncode(ham0).toByte()
            packet[1] = hammingEncode(ham1).toByte()

            if (row == 0) {
                // Header row - encode page number
                val pageTens = if (pageData[0][1] in 0x30..0x39) {
                    pageData[0][1] - 0x30
                } else 0

                val pageUnits = if (pageData[0][2] in 0x30..0x39) {
                    pageData[0][2] - 0x30
                } else 0

                packet[2] = hammingEncode(pageUnits).toByte()
                packet[3] = hammingEncode(pageTens).toByte()

                // Subpage and control bits (set to defaults)
                for (i in 4..7) {
                    packet[i] = hammingEncode(0).toByte()
                }

                // Copy header text (columns 8-39)
                for (col in 8 until 40) {
                    packet[2 + col] = (pageData[row][col] and 0x7F).toByte()
                }
            } else {
                // Data rows - copy with parity bit cleared
                for (col in 0 until 40) {
                    packet[2 + col] = (pageData[row][col] and 0x7F).toByte()
                }
            }

            output.write(packet)
        }

        return output.toByteArray()
    }
}
