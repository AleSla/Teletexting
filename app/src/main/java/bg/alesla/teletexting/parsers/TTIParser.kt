package bg.alesla.teletexting.parsers

import java.io.BufferedReader
import java.io.StringReader

/**
 * Parser for MRG Systems TTI (Teletext Interchange) format
 */
object TTIParser {

    data class TTIPage(
        val pageNumber: String = "100",
        val description: String = "",
        val charset: String = "English",
        val data: Array<IntArray>
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as TTIPage
            return pageNumber == other.pageNumber &&
                    description == other.description &&
                    charset == other.charset &&
                    data.contentDeepEquals(other.data)
        }

        override fun hashCode(): Int {
            var result = pageNumber.hashCode()
            result = 31 * result + description.hashCode()
            result = 31 * result + charset.hashCode()
            result = 31 * result + data.contentDeepHashCode()
            return result
        }
    }

    /**
     * Parse TTI format from string
     */
    fun parse(content: String): TTIPage? {
        val lines = BufferedReader(StringReader(content)).readLines()
        val pageData = Array(25) { IntArray(40) { 0x20 } }
        var pageNumber = "100"
        var description = ""
        var charset = "English"
        var foundData = false

        for (line in lines) {
            val trimmed = line.trim()

            when {
                trimmed.startsWith("PN,") -> {
                    pageNumber = trimmed.substring(3).trim()
                }

                trimmed.startsWith("DE,") -> {
                    description = trimmed.substring(3).trim()
                }

                trimmed.startsWith("CS,") -> {
                    charset = trimmed.substring(3).trim()
                }

                trimmed.startsWith("OL,") -> {
                    // Parse output line: OL,row,data
                    val firstComma = 3
                    val secondComma = trimmed.indexOf(',', firstComma)

                    if (secondComma != -1) {
                        val rowStr = trimmed.substring(firstComma, secondComma)
                        val row = rowStr.trim().toIntOrNull() ?: continue

                        if (row !in 0..24) continue

                        foundData = true
                        val content = if (secondComma + 1 < trimmed.length) {
                            trimmed.substring(secondComma + 1)
                        } else ""

                        // Parse content with escape sequences
                        var col = 0
                        var i = 0
                        while (i < content.length && col < 40) {
                            var charCode = content[i].code

                            // Handle escape sequences (ESC + char)
                            if (charCode == 0x1B && i + 1 < content.length) {
                                i++
                                charCode = content[i].code - 0x40
                            }

                            pageData[row][col] = charCode and 0x7F
                            col++
                            i++
                        }

                        // Fill remaining columns with spaces
                        while (col < 40) {
                            pageData[row][col] = 0x20
                            col++
                        }
                    }
                }
            }
        }

        return if (foundData) {
            TTIPage(pageNumber, description, charset, pageData)
        } else null
    }

    /**
     * Serialize page data to TTI format
     */
    fun serialize(page: TTIPage): String {
        val sb = StringBuilder()

        sb.append("PN,${page.pageNumber}\n")
        sb.append("DE,${page.description}\n")
        sb.append("CS,${page.charset}\n")

        for (row in 0 until 25) {
            sb.append("OL,$row,")

            for (col in 0 until 40) {
                val ch = page.data[row][col]

                // Escape control codes
                if (ch < 0x20) {
                    sb.append('\u001B')
                    sb.append((ch + 0x40).toChar())
                } else {
                    sb.append(ch.toChar())
                }
            }

            sb.append('\n')
        }

        return sb.toString()
    }
}