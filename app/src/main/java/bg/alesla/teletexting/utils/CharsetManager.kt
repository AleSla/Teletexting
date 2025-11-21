package bg.alesla.teletexting.utils

/**
 * Manager for Teletext Level 1 character sets (G0)
 * Based on ETSI EN 300 706 specification
 */
object CharsetManager {

    data class Charset(
        val id: Int,
        val name: String,
        val region: String,
        val substitutions: Map<Int, String>
    )

    // Teletext G0 character sets (ETSI EN 300 706)
    val CHARSETS = listOf(
        Charset(
            id = 0,
            name = "English",
            region = "United Kingdom",
            substitutions = mapOf(
                0x23 to "£",
                0x24 to "$",
                0x40 to "@",
                0x5B to "←",
                0x5C to "½",
                0x5D to "→",
                0x5E to "↑",
                0x5F to "#",
                0x60 to "―",
                0x7B to "¼",
                0x7C to "‖",
                0x7D to "¾",
                0x7E to "÷"
            )
        ),
        Charset(
            id = 1,
            name = "German",
            region = "Germany",
            substitutions = mapOf(
                0x23 to "#",
                0x24 to "$",
                0x40 to "@",
                0x5B to "Ä",
                0x5C to "Ö",
                0x5D to "Ü",
                0x5E to "^",
                0x5F to "_",
                0x60 to "°",
                0x7B to "ä",
                0x7C to "ö",
                0x7D to "ü",
                0x7E to "ß"
            )
        ),
        Charset(
            id = 2,
            name = "Swedish/Finnish",
            region = "Sweden/Finland",
            substitutions = mapOf(
                0x23 to "#",
                0x24 to "¤",
                0x40 to "É",
                0x5B to "Ä",
                0x5C to "Ö",
                0x5D to "Å",
                0x5E to "Ü",
                0x5F to "_",
                0x60 to "é",
                0x7B to "ä",
                0x7C to "ö",
                0x7D to "å",
                0x7E to "ü"
            )
        ),
        Charset(
            id = 3,
            name = "Italian",
            region = "Italy",
            substitutions = mapOf(
                0x23 to "£",
                0x24 to "$",
                0x40 to "é",
                0x5B to "°",
                0x5C to "ç",
                0x5D to "→",
                0x5E to "↑",
                0x5F to "#",
                0x60 to "ù",
                0x7B to "à",
                0x7C to "ò",
                0x7D to "è",
                0x7E to "ì"
            )
        ),
        Charset(
            id = 4,
            name = "French",
            region = "France",
            substitutions = mapOf(
                0x23 to "é",
                0x24 to "ï",
                0x40 to "à",
                0x5B to "ë",
                0x5C to "ê",
                0x5D to "ù",
                0x5E to "î",
                0x5F to "#",
                0x60 to "è",
                0x7B to "â",
                0x7C to "ô",
                0x7D to "û",
                0x7E to "ç"
            )
        ),
        Charset(
            id = 5,
            name = "Portuguese/Spanish",
            region = "Portugal/Spain",
            substitutions = mapOf(
                0x23 to "ç",
                0x24 to "$",
                0x40 to "¡",
                0x5B to "á",
                0x5C to "é",
                0x5D to "í",
                0x5E to "ó",
                0x5F to "ú",
                0x60 to "¿",
                0x7B to "ü",
                0x7C to "ñ",
                0x7D to "è",
                0x7E to "à"
            )
        ),
        Charset(
            id = 6,
            name = "Czech/Slovak",
            region = "Czech Republic/Slovakia",
            substitutions = mapOf(
                0x23 to "#",
                0x24 to "ů",
                0x40 to "č",
                0x5B to "ť",
                0x5C to "ž",
                0x5D to "ý",
                0x5E to "í",
                0x5F to "ř",
                0x60 to "é",
                0x7B to "á",
                0x7C to "ě",
                0x7D to "ú",
                0x7E to "š"
            )
        ),
        Charset(
            id = 7,
            name = "Romanian",
            region = "Romania",
            substitutions = mapOf(
                0x23 to "#",
                0x24 to "¤",
                0x40 to "Ţ",
                0x5B to "Â",
                0x5C to "Ş",
                0x5D to "Ă",
                0x5E to "Î",
                0x5F to "ı",
                0x60 to "ţ",
                0x7B to "â",
                0x7C to "ş",
                0x7D to "ă",
                0x7E to "î"
            )
        ),
        Charset(
            id = 8,
            name = "Serbian/Croatian/Slovenian",
            region = "Serbia/Croatia/Slovenia",
            substitutions = mapOf(
                0x23 to "#",
                0x24 to "Ë",
                0x40 to "Č",
                0x5B to "Ć",
                0x5C to "Ž",
                0x5D to "Đ",
                0x5E to "Š",
                0x5F to "Ł",
                0x60 to "ë",
                0x7B to "ć",
                0x7C to "ž",
                0x7D to "đ",
                0x7E to "š"
            )
        ),
        Charset(
            id = 9,
            name = "Turkish",
            region = "Turkey",
            substitutions = mapOf(
                0x23 to "T",
                0x24 to "¤",
                0x40 to "É",
                0x5B to "Ğ",
                0x5C to "İ",
                0x5D to "Ş",
                0x5E to "Ö",
                0x5F to "Ç",
                0x60 to "Ü",
                0x7B to "ğ",
                0x7C to "ı",
                0x7D to "ş",
                0x7E to "ö"
            )
        ),
        Charset(
            id = 10,
            name = "Cyrillic (Russian/Bulgarian)",
            region = "Russia/Bulgaria",
            substitutions = mapOf(
                0x26 to "ы",
                0x40 to "Ю",
                0x41 to "А",
                0x42 to "Б",
                0x43 to "Ц",
                0x44 to "Д",
                0x45 to "Е",
                0x46 to "Ф",
                0x47 to "Г",
                0x48 to "Х",
                0x49 to "И",
                0x4A to "Й",
                0x4B to "К",
                0x4C to "Л",
                0x4D to "М",
                0x4E to "Н",
                0x4F to "О",
                0x50 to "П",
                0x51 to "Я",
                0x52 to "Р",
                0x53 to "С",
                0x54 to "Т",
                0x55 to "У",
                0x56 to "Ж",
                0x57 to "В",
                0x58 to "Ь",
                0x59 to "Ъ",
                0x5A to "З",
                0x5B to "Ш",
                0x5C to "Э",
                0x5D to "Щ",
                0x5E to "Ч",
                0x5F to "Ы",
                0x60 to "ю",
                0x61 to "а",
                0x62 to "б",
                0x63 to "ц",
                0x64 to "д",
                0x65 to "е",
                0x66 to "ф",
                0x67 to "г",
                0x68 to "х",
                0x69 to "и",
                0x6A to "й",
                0x6B to "к",
                0x6C to "л",
                0x6D to "м",
                0x6E to "н",
                0x6F to "о",
                0x70 to "п",
                0x71 to "я",
                0x72 to "р",
                0x73 to "с",
                0x74 to "т",
                0x75 to "у",
                0x76 to "ж",
                0x77 to "в",
                0x78 to "ь",
                0x79 to "ъ",
                0x7A to "з",
                0x7B to "ш",
                0x7C to "э",
                0x7D to "щ",
                0x7E to "ч"
            )
        ),
        Charset(
            id = 11,
            name = "Greek",
            region = "Greece",
            substitutions = mapOf(
                0x20 to " ",
                0x23 to "#",
                0x24 to "$",
                0x40 to "Ώ",
                0x41 to "Α",
                0x42 to "Β",
                0x43 to "Γ",
                0x44 to "Δ",
                0x45 to "Ε",
                0x46 to "Ζ",
                0x47 to "Η",
                0x48 to "Θ",
                0x49 to "Ι",
                0x4A to "Κ",
                0x4B to "Λ",
                0x4C to "Μ",
                0x4D to "Ν",
                0x4E to "Ξ",
                0x4F to "Ο",
                0x50 to "Π",
                0x51 to "Ρ",
                0x53 to "Σ",
                0x54 to "Τ",
                0x55 to "Υ",
                0x56 to "Φ",
                0x57 to "Χ",
                0x58 to "Ψ",
                0x59 to "Ω",
                0x60 to "ώ",
                0x61 to "α",
                0x62 to "β",
                0x63 to "γ",
                0x64 to "δ",
                0x65 to "ε",
                0x66 to "ζ",
                0x67 to "η",
                0x68 to "θ",
                0x69 to "ι",
                0x6A to "κ",
                0x6B to "λ",
                0x6C to "μ",
                0x6D to "ν",
                0x6E to "ξ",
                0x6F to "ο",
                0x70 to "π",
                0x71 to "ρ",
                0x72 to "ς",
                0x73 to "σ",
                0x74 to "τ",
                0x75 to "υ",
                0x76 to "φ",
                0x77 to "χ",
                0x78 to "ψ",
                0x79 to "ω"
            )
        ),
        Charset(
            id = 12,
            name = "Arabic",
            region = "Arabic Countries",
            substitutions = mapOf(
                0x20 to " ",
                0x23 to "#",
                0x24 to "ريال",
                0x40 to "ـ",
                0x41 to "ا",
                0x42 to "ب",
                0x43 to "ت",
                0x44 to "ث",
                0x45 to "ج",
                0x46 to "ح",
                0x47 to "خ",
                0x48 to "د",
                0x49 to "ذ",
                0x4A to "ر",
                0x4B to "ز",
                0x4C to "س",
                0x4D to "ش",
                0x4E to "ص",
                0x4F to "ض",
                0x50 to "ط",
                0x51 to "ظ",
                0x52 to "ع",
                0x53 to "غ",
                0x60 to "ـ",
                0x61 to "ف",
                0x62 to "ق",
                0x63 to "ك",
                0x64 to "ل",
                0x65 to "م",
                0x66 to "ن",
                0x67 to "ه",
                0x68 to "و",
                0x69 to "ى",
                0x6A to "ي"
            )
        ),
        Charset(
            id = 13,
            name = "Hebrew",
            region = "Israel",
            substitutions = mapOf(
                0x20 to " ",
                0x23 to "£",
                0x40 to "ח",
                0x41 to "א",
                0x42 to "ב",
                0x43 to "ג",
                0x44 to "ד",
                0x45 to "ה",
                0x46 to "ו",
                0x47 to "ז",
                0x48 to "ח",
                0x49 to "ט",
                0x4A to "י",
                0x4B to "ך",
                0x4C to "כ",
                0x4D to "ל",
                0x4E to "ם",
                0x4F to "מ",
                0x50 to "ן",
                0x51 to "נ",
                0x52 to "ס",
                0x53 to "ע",
                0x54 to "ף",
                0x55 to "פ",
                0x56 to "ץ",
                0x57 to "צ",
                0x58 to "ק",
                0x59 to "ר",
                0x5A to "ש",
                0x5B to "ת"
            )
        )
    )

    /**
     * Get charset by ID
     */
    fun getCharset(id: Int): Charset {
        return CHARSETS.find { it.id == id } ?: CHARSETS[0]
    }

    /**
     * Map character code using specified charset
     */
    fun mapChar(charCode: Int, charsetId: Int): String {
        val charset = getCharset(charsetId)

        // Use substitution if available
        return charset.substitutions[charCode]
            ?: charCode.toChar().toString() // Default to ASCII
    }

    /**
     * Get all charset names for UI
     */
    fun getCharsetNames(): List<String> {
        return CHARSETS.map { "${it.name} (${it.region})" }
    }
}