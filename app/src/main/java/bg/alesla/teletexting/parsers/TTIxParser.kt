package bg.alesla.teletexting.parsers

import java.io.ByteArrayOutputStream

/**
 * Parser for TTIx format (Teletext Interchange with raw 7-bit control characters).
 * Similar to TTI, but control codes (0x00-0x1F) are stored as raw bytes
 * instead of being escaped with \x1B.
 */
object TTIxParser {

    data class TTIxPage(
        val pageNumber: String,
        val subPage: String,
        val description: String,
        val data: Array<IntArray>
    )

    fun parseMultiPage(content: String): List<TTIxPage> {
        val pages = mutableListOf<TTIxPage>()

        // Normalize line endings to \n (safely handles \r used by some legacy software)
        val normalizedContent = content.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalizedContent.split("\n")

        var currentPageData: Array<IntArray>? = null
        var pageNumber = "100"
        var subPage = "0000"
        var description = ""

        val saveCurrentPage = {
            if (currentPageData != null) {
                pages.add(TTIxPage(pageNumber, subPage, description, currentPageData!!))
                currentPageData = null
                description = ""
            }
        }

        for (line in lines) {
            val trimmed = line.trim()

            when {
                trimmed.startsWith("PN,") -> {
                    saveCurrentPage()

                    val pnParts = trimmed.substring(3).trim().split(Regex("\\s+"))
                    val pn = pnParts[0]
                    pageNumber = if (pn.length >= 3) pn.substring(0, 3) else pn
                    val rawSub = if (pn.length > 3) pn.substring(3) else "0000"
                    subPage = rawSub.padStart(4, '0')

                    currentPageData = Array(25) { IntArray(40) { 0x20 } }
                }
                trimmed.startsWith("DE,") -> {
                    description = trimmed.substring(3).trim()
                }
                trimmed.startsWith("OL,") -> {
                    if (currentPageData == null) {
                        currentPageData = Array(25) { IntArray(40) { 0x20 } }
                    }

                    val firstComma = line.indexOf(',')
                    val secondComma = line.indexOf(',', firstComma + 1)

                    if (firstComma != -1 && secondComma != -1) {
                        val rowStr = line.substring(firstComma + 1, secondComma).trim()
                        val row = rowStr.toIntOrNull() ?: continue
                        if (row !in 0..24) continue

                        // Extract text directly from the UNTRIMMED line to preserve leading spaces and raw bytes
                        val rowContent = line.substring(secondComma + 1)

                        var col = 0
                        var i = 0
                        while (i < rowContent.length && col < 40) {
                            var charCode = rowContent[i].code

                            // Backwards compatibility: Check for standard TTI escapes just in case the file is mixed
                            if (charCode == 0x1B && i + 1 < rowContent.length) {
                                i++
                                charCode = rowContent[i].code - 0x40
                            }

                            // Raw control codes (0x00-0x1F) will correctly pass straight through here
                            currentPageData!![row][col] = charCode and 0x7F
                            col++
                            i++
                        }
                    }
                }
            }
        }

        saveCurrentPage()
        return pages
    }

    fun serializeMultiPage(pages: List<TTIxPage>): ByteArray {
        val output = ByteArrayOutputStream()
        for (page in pages) {
            output.write(serialize(page).toByteArray(Charsets.UTF_8))
        }
        return output.toByteArray()
    }

    fun serialize(page: TTIxPage): String {
        val sb = StringBuilder()

        // Strip leading zeroes from subpage formatting for TTIX compatibility
        val shortSub = page.subPage.trimStart('0').takeIf { it.isNotEmpty() } ?: "1"
        sb.append("PN,${page.pageNumber}${shortSub}\n")

        if (page.description.isNotEmpty()) {
            sb.append("DE,${page.description}\n")
        }

        for (row in 0 until 25) {
            sb.append("OL,$row,")
            for (col in 0 until 40) {
                val ch = page.data[row][col] and 0x7F
                // TTIx embeds the raw control characters directly
                sb.append(ch.toChar())
            }
            sb.append("\n")
        }

        return sb.toString()
    }
}