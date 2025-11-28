package bg.alesla.teletexting

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import bg.alesla.teletexting.parsers.BinaryParser
import bg.alesla.teletexting.parsers.EP1Parser
import bg.alesla.teletexting.parsers.T42Parser
import bg.alesla.teletexting.parsers.TTIParser
import bg.alesla.teletexting.parsers.TTXParser
import bg.alesla.teletexting.utils.CRCCalculator
import bg.alesla.teletexting.utils.CharsetManager
import bg.alesla.teletexting.view.TeletextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {

    private lateinit var teletextView: TeletextView
    private var currentUri: Uri? = null
    private var currentFormat: FileFormat = FileFormat.TTI
    private var currentPageNumber = "100"
    private var currentSubPage = "1"
    private var currentDescription = "Teletext Page"
    private var currentFileName = "No file opened"

    // Multi-page support
    private var allPages = mutableListOf<PageData>()
    private var currentPageIndex = 0

    data class PageData(
        val pageNumber: String,
        val subPage: String,
        val data: Array<IntArray>
    )

    enum class FileFormat {
        TTI, EP1, T42, BIN, TTX
    }

    private val openFileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                loadFile(uri)
            }
        }
    }

    private val saveFileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                saveFile(uri)
            }
        }
    }

    private val exportPngLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                exportToPng(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setSupportActionBar(findViewById(R.id.toolbar))

        teletextView = findViewById(R.id.teletextView)

        // Setup cell click listener (no dialog, just focus)
        teletextView.onCellClicked = { x, y ->
            // Keyboard opens automatically via showKeyboard() in TeletextView
        }

        // Setup history change listener
        teletextView.onHistoryChanged = {
            invalidateOptionsMenu() // Update undo/redo button states
        }

        // Setup color buttons
        setupColorButtons()

        // Setup control code editor (includes cursor change listener)
        setupControlCodeEditor()

        // Setup charset selector
        setupCharsetSelector()

        // Setup page navigation buttons
        setupPageNavigation()

        updateStatusBar()
        updatePageDisplay()
        requestStoragePermissions()
    }

    private fun requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ),
                    100
                )
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        updateUndoRedoButtons(menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        updateUndoRedoButtons(menu)
        return super.onPrepareOptionsMenu(menu)
    }

    private fun updateUndoRedoButtons(menu: Menu) {
        menu.findItem(R.id.action_undo)?.isEnabled = teletextView.canUndo()
        menu.findItem(R.id.action_redo)?.isEnabled = teletextView.canRedo()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_undo -> {
                if (teletextView.undo()) {
                    Toast.makeText(this, "Undo", Toast.LENGTH_SHORT).show()
                    invalidateOptionsMenu() // Update button states
                }
                true
            }

            R.id.action_redo -> {
                if (teletextView.redo()) {
                    Toast.makeText(this, "Redo", Toast.LENGTH_SHORT).show()
                    invalidateOptionsMenu() // Update button states
                }
                true
            }

            R.id.action_open -> {
                openFile()
                true
            }

            R.id.action_save -> {
                if (currentUri != null) {
                    saveFile(currentUri!!)
                } else {
                    saveFileAs()
                }
                true
            }

            R.id.action_save_as -> {
                saveFileAs()
                true
            }

            R.id.action_export_png -> {
                exportPng()
                true
            }

            R.id.action_clear -> {
                clearPage()
                true
            }

            R.id.action_toggle_grid -> {
                teletextView.showGrid = !teletextView.showGrid
                true
            }

            R.id.action_toggle_codes -> {
                teletextView.showControlCodes = !teletextView.showControlCodes
                true
            }

            R.id.action_insert_flash -> {
                insertControlCode(0x08)
                true
            }

            R.id.action_hide_keyboard -> {
                teletextView.clearFocus()
                val imm =
                    getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(teletextView.windowToken, 0)
                true
            }

            R.id.action_delete_page -> {
                deleteCurrentPage()
                true
            }

            R.id.action_bw_mode -> {
                item.isChecked = !item.isChecked
                teletextView.blackWhiteMode = item.isChecked
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun openFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES, arrayOf(
                    "text/plain",
                    "application/octet-stream"
                )
            )
        }
        openFileLauncher.launch(intent)
    }

    private fun loadFile(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val data = inputStream?.readBytes()
            inputStream?.close()

            if (data == null) {
                showError("Failed to read file")
                return
            }

            // Get filename
            currentFileName = getFileName(uri)

            // Detect format from extension or content
            val filename = getFileName(uri)
            val ext = filename.substringAfterLast('.', "").lowercase()

            when (ext) {
                "tti", "txt" -> {
                    currentFormat = FileFormat.TTI
                    val content = String(data, Charsets.UTF_8)
                    loadTTIPages(content)
                }

                "ep1" -> {
                    currentFormat = FileFormat.EP1
                    val pageData = EP1Parser.parse(data)
                    if (pageData != null) {
                        allPages.clear()
                        val pageNum = extractPageNumber(pageData)
                        allPages.add(PageData(pageNum, "1", pageData))
                    }
                }

                "t42" -> {
                    currentFormat = FileFormat.T42
                    loadT42Pages(data)
                }

                "ttx" -> {
                    currentFormat = FileFormat.TTX
                    loadTTXPages(data)
                }

                else -> {
                    currentFormat = FileFormat.BIN
                    val pageData = BinaryParser.parse(data)
                    if (pageData != null) {
                        allPages.clear()
                        val pageNum = extractPageNumber(pageData)
                        allPages.add(PageData(pageNum, "1", pageData))
                    }
                }
            }

            if (allPages.isNotEmpty()) {
                currentUri = uri
                currentPageIndex = 0
                displayCurrentPage()

                supportActionBar?.subtitle = currentFileName

                Toast.makeText(this, "File loaded: ${allPages.size} page(s)", Toast.LENGTH_SHORT)
                    .show()
            } else {
                showError("Failed to parse file")
            }

        } catch (e: Exception) {
            showError("Error loading file: ${e.message}")
        }
    }

    private fun loadTTIPages(content: String) {
        allPages.clear()
        val lines = content.split("\n")
        var currentPage: MutableMap<Int, String> = mutableMapOf()
        var pageNumber = "100"
        var subPage = "1"

        for (line in lines) {
            val trimmed = line.trim()

            when {
                trimmed.startsWith("PN,") -> {
                    // Save previous page if exists
                    if (currentPage.isNotEmpty()) {
                        val pageData = convertTTIPageToArray(currentPage)
                        allPages.add(PageData(pageNumber, subPage, pageData))
                        currentPage.clear()
                    }

                    // Parse new page number
                    val pn = trimmed.substring(3).trim()
                    pageNumber = if (pn.length >= 3) pn.substring(0, 3) else pn
                    subPage = if (pn.length > 3) pn.substring(3) else "1"
                }

                trimmed.startsWith("OL,") -> {
                    val firstComma = 3
                    val secondComma = trimmed.indexOf(',', firstComma)

                    if (secondComma != -1) {
                        val rowStr = trimmed.substring(firstComma, secondComma)
                        val row = rowStr.trim().toIntOrNull() ?: continue
                        val content = if (secondComma + 1 < trimmed.length) {
                            trimmed.substring(secondComma + 1)
                        } else ""

                        currentPage[row] = content
                    }
                }
            }
        }

        // Save last page
        if (currentPage.isNotEmpty()) {
            val pageData = convertTTIPageToArray(currentPage)
            allPages.add(PageData(pageNumber, subPage, pageData))
        }
    }

    private fun convertTTIPageToArray(pageMap: Map<Int, String>): Array<IntArray> {
        val pageData = Array(25) { IntArray(40) { 0x20 } }

        for ((row, content) in pageMap) {
            if (row !in 0..24) continue

            var col = 0
            var i = 0
            while (i < content.length && col < 40) {
                var charCode = content[i].code

                if (charCode == 0x1B && i + 1 < content.length) {
                    i++
                    charCode = content[i].code - 0x40
                }

                pageData[row][col] = charCode and 0x7F
                col++
                i++
            }
        }

        return pageData
    }

    private fun loadT42Pages(data: ByteArray) {
        allPages.clear()

        // Use the new parseMultiPage method
        val pages = T42Parser.parseMultiPage(data)

        for ((index, pageData) in pages.withIndex()) {
            val pageNum = extractPageNumber(pageData)
            val subPage = (index + 1).toString() // Sequential subpage numbering
            allPages.add(PageData(pageNum, subPage, pageData))
        }
    }

    private fun loadTTXPages(data: ByteArray) {
        allPages.clear()
        var offset = 0

        while (offset + 42 <= data.size) {
            val pageData =
                TTXParser.parse(data.sliceArray(offset until minOf(offset + 1050, data.size)))
            if (pageData != null) {
                val pageNum = extractPageNumber(pageData)
                allPages.add(PageData(pageNum, "1", pageData))
                offset += 1050
            } else {
                break
            }
        }
    }

    private fun displayCurrentPage() {
        if (currentPageIndex < 0 || currentPageIndex >= allPages.size) return

        val page = allPages[currentPageIndex]
        currentPageNumber = page.pageNumber
        currentSubPage = page.subPage

        teletextView.pageData = page.data

        updateStatusBar()
        updatePageDisplay()
    }

    private fun saveFileAs() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = when (currentFormat) {
                FileFormat.TTI -> "text/plain"
                else -> "application/octet-stream"
            }
            putExtra(Intent.EXTRA_TITLE, "page.${getExtension(currentFormat)}")
        }
        saveFileLauncher.launch(intent)
    }

    private fun saveFile(uri: Uri) {
        try {
            val data = when (currentFormat) {
                FileFormat.TTI -> {
                    val page = TTIParser.TTIPage(
                        currentPageNumber,
                        currentDescription,
                        "English",
                        teletextView.pageData
                    )
                    TTIParser.serialize(page).toByteArray()
                }

                FileFormat.EP1 -> EP1Parser.serialize(teletextView.pageData)
                FileFormat.T42 -> T42Parser.serialize(teletextView.pageData)
                FileFormat.TTX -> TTXParser.serialize(teletextView.pageData)
                FileFormat.BIN -> BinaryParser.serialize(teletextView.pageData)
            }

            val outputStream = contentResolver.openOutputStream(uri)
            outputStream?.write(data)
            outputStream?.close()

            currentUri = uri
            Toast.makeText(this, "File saved successfully", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            showError("Error saving file: ${e.message}")
        }
    }

    private fun exportPng() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/png"
            putExtra(Intent.EXTRA_TITLE, "teletext_page.png")
        }
        exportPngLauncher.launch(intent)
    }

    private fun exportToPng(uri: Uri) {
        try {
            // Temporarily disable visual elements
            val wasShowingGrid = teletextView.showGrid
            val wasShowingCodes = teletextView.showControlCodes
            val wasCursorVisible = teletextView.cursorVisible

            teletextView.showGrid = false
            teletextView.showControlCodes = false
            teletextView.cursorVisible = false
            teletextView.invalidate()

            // Wait for view to redraw
            teletextView.post {
                // Create bitmap from view
                val bitmap = createBitmapFromView()

                val outputStream = contentResolver.openOutputStream(uri)
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                outputStream?.close()

                // Restore visual elements
                teletextView.showGrid = wasShowingGrid
                teletextView.showControlCodes = wasShowingCodes
                teletextView.cursorVisible = wasCursorVisible
                teletextView.invalidate()

                Toast.makeText(this, "PNG exported successfully", Toast.LENGTH_SHORT).show()
            }

        } catch (e: Exception) {
            showError("Error exporting PNG: ${e.message}")
        }
    }

    private fun createBitmapFromView(): Bitmap {
        val scale = 3
        val width = TeletextView.COLS * 24 * scale
        val height = TeletextView.ROWS * 40 * scale

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)

        canvas.scale(scale.toFloat(), scale.toFloat())
        teletextView.draw(canvas)

        return bitmap
    }

    private fun clearPage() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Clear Page")
            .setMessage("Are you sure you want to clear the entire page?")
            .setPositiveButton("Clear") { _, _ ->
                teletextView.clearPage()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun insertControlCode(code: Int) {
        teletextView.setCharAt(teletextView.cursorX, teletextView.cursorY, code)
        if (teletextView.cursorX < TeletextView.COLS - 1) {
            teletextView.cursorX++
        }
        updateStatusBar()
    }

    private fun getFileName(uri: Uri): String {
        var result = ""
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val displayNameIndex =
                    cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (displayNameIndex >= 0) {
                    result = cursor.getString(displayNameIndex)
                }
            }
        }
        return result.ifEmpty { uri.lastPathSegment ?: "unknown" }
    }

    private fun getExtension(format: FileFormat): String {
        return when (format) {
            FileFormat.TTI -> "tti"
            FileFormat.EP1 -> "ep1"
            FileFormat.T42 -> "t42"
            FileFormat.TTX -> "ttx"
            FileFormat.BIN -> "bin"
        }
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun setupColorButtons() {
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnColorRed)
            .setOnClickListener { insertControlCode(0x01) }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnColorGreen)
            .setOnClickListener { insertControlCode(0x02) }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnColorYellow)
            .setOnClickListener { insertControlCode(0x03) }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnColorBlue)
            .setOnClickListener { insertControlCode(0x04) }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnColorMagenta)
            .setOnClickListener { insertControlCode(0x05) }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnColorCyan)
            .setOnClickListener { insertControlCode(0x06) }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnColorWhite)
            .setOnClickListener { insertControlCode(0x07) }

        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnMosBlack)
            .setOnClickListener { insertControlCode(0x10) }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnMosRed)
            .setOnClickListener { insertControlCode(0x11) }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnMosGreen)
            .setOnClickListener { insertControlCode(0x12) }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnMosYellow)
            .setOnClickListener { insertControlCode(0x13) }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnMosBlue)
            .setOnClickListener { insertControlCode(0x14) }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnMosMagenta)
            .setOnClickListener { insertControlCode(0x15) }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnMosCyan)
            .setOnClickListener { insertControlCode(0x16) }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnMosWhite)
            .setOnClickListener { insertControlCode(0x17) }
    }

    private fun setupControlCodeEditor() {
        val etControlCode =
            findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etControlCode)
        val btnSet =
            findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSetControlCode)

        // Update field when cursor moves
        teletextView.onCursorChanged = { x, y ->
            val ch = teletextView.getCharAt(x, y)
            etControlCode.setText(String.format("0x%02X", ch))
            updateStatusBar()
        }

        // Set button handler
        btnSet.setOnClickListener {
            val text = etControlCode.text.toString().trim()
            try {
                val code = if (text.startsWith("0x", ignoreCase = true)) {
                    text.substring(2).toInt(16)
                } else {
                    text.toInt(16)
                }

                if (code in 0x00..0x7F) {
                    insertControlCode(code)
                    Toast.makeText(
                        this,
                        "Control code 0x${String.format("%02X", code)} set",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(this, "Code must be between 0x00 and 0x7F", Toast.LENGTH_SHORT)
                        .show()
                }
            } catch (e: NumberFormatException) {
                Toast.makeText(this, "Invalid hex code format", Toast.LENGTH_SHORT).show()
            }
        }

        // Quick control buttons
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnFlash)
            .setOnClickListener { insertControlCode(0x08) }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDoubleHeight)
            .setOnClickListener { insertControlCode(0x0D) }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnNormalHeight)
            .setOnClickListener { insertControlCode(0x0C) }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnNewBg)
            .setOnClickListener { insertControlCode(0x1D) }
    }

    private fun updateStatusBar() {
        val x = teletextView.cursorX
        val y = teletextView.cursorY
        val ch = teletextView.getCharAt(x, y)

        // Update status bar fields
        findViewById<TextView>(R.id.tvStatusPage).text = "P$currentPageNumber"
        findViewById<TextView>(R.id.tvStatusPos).text = "($x,$y)"
        findViewById<TextView>(R.id.tvStatusChar).text = String.format("0x%02X", ch)

        // Calculate CRC
        val crc = CRCCalculator.calculateCRC(teletextView.pageData)
        val crcText = findViewById<TextView>(R.id.tvStatusCRC)
        crcText.text = CRCCalculator.formatCRC(crc)
        crcText.setTextColor(getColor(android.R.color.holo_green_dark))
    }

    private fun updatePageDisplay() {
        // Update address bar dropdowns
        updateAddressBar()

        // Enable/disable navigation buttons
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPrevPage).isEnabled =
            currentPageIndex > 0
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnNextPage).isEnabled =
            currentPageIndex < allPages.size - 1
    }

    private fun updateAddressBar() {
        if (allPages.isEmpty()) return

        // Extract unique magazines, pages, subpages
        val magazines = allPages.map { it.pageNumber.take(1) }.distinct().sorted()
        val pages = allPages.filter { it.pageNumber.take(1) == currentPageNumber.take(1) }
            .map { it.pageNumber.takeLast(2) }.distinct().sorted()
        val subpages = allPages.filter { it.pageNumber == currentPageNumber }
            .map { it.subPage }.distinct().sorted()

        // Setup magazine spinner
        val magazineSpinner = findViewById<AutoCompleteTextView>(R.id.spinnerMagazine)
        val magazineAdapter =
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, magazines)
        magazineSpinner.setAdapter(magazineAdapter)
        magazineSpinner.setText(currentPageNumber.take(1), false)
        magazineSpinner.setOnItemClickListener { _, _, position, _ ->
            val selectedMag = magazines[position]
            navigateToAddress(selectedMag, null, null)
        }

        // Setup page spinner
        val pageSpinner = findViewById<AutoCompleteTextView>(R.id.spinnerPage)
        val pageAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, pages)
        pageSpinner.setAdapter(pageAdapter)
        pageSpinner.setText(currentPageNumber.takeLast(2), false)
        pageSpinner.setOnItemClickListener { _, _, position, _ ->
            val selectedPage = pages[position]
            navigateToAddress(currentPageNumber.take(1), selectedPage, null)
        }

        // Setup subpage spinner
        val subpageSpinner = findViewById<AutoCompleteTextView>(R.id.spinnerSubpage)
        val subpageAdapter =
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, subpages)
        subpageSpinner.setAdapter(subpageAdapter)
        subpageSpinner.setText(currentSubPage, false)
        subpageSpinner.setOnItemClickListener { _, _, position, _ ->
            val selectedSubpage = subpages[position]
            navigateToAddress(null, null, selectedSubpage)
        }
    }

    private fun navigateToAddress(magazine: String?, page: String?, subpage: String?) {
        val targetMag = magazine ?: currentPageNumber.take(1)
        val targetPage = page ?: currentPageNumber.takeLast(2)
        val targetSubpage = subpage ?: currentSubPage
        val targetPageNumber = targetMag + targetPage

        // Find matching page
        val index = allPages.indexOfFirst {
            it.pageNumber == targetPageNumber && it.subPage == targetSubpage
        }

        if (index != -1) {
            currentPageIndex = index
            displayCurrentPage()
        } else {
            Toast.makeText(
                this,
                "Page $targetPageNumber/$targetSubpage not found",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun deleteCurrentPage() {
        if (allPages.isEmpty()) return

        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Page")
            .setMessage("Delete page $currentPageNumber/$currentSubPage?")
            .setPositiveButton("Delete") { _, _ ->
                allPages.removeAt(currentPageIndex)

                if (allPages.isEmpty()) {
                    // No pages left, create empty page
                    allPages.add(PageData("100", "1", Array(25) { IntArray(40) { 0x20 } }))
                    currentPageIndex = 0
                } else if (currentPageIndex >= allPages.size) {
                    currentPageIndex = allPages.size - 1
                }

                displayCurrentPage()
                Toast.makeText(this, "Page deleted", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupPageNavigation() {
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPrevPage).setOnClickListener {
            if (currentPageIndex > 0) {
                currentPageIndex--
                displayCurrentPage()
            }
        }

        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnNextPage).setOnClickListener {
            if (currentPageIndex < allPages.size - 1) {
                currentPageIndex++
                displayCurrentPage()
            }
        }
    }

    private fun extractPageNumber(pageData: Array<IntArray>): String {
        // Try to extract page number from header row (row 0, columns 0-2)
        try {
            val pageChars = StringBuilder()

            // Read first 3 characters from row 0 (magazine and page number)
            for (col in 0..2) {
                val ch = pageData[0][col]
                if (ch in 0x30..0x39) { // '0'-'9'
                    pageChars.append((ch - 0x30))
                } else if (ch in 0x20..0x7F) {
                    // Try to use the character as-is if it's printable
                    val char = ch.toChar()
                    if (char.isDigit()) {
                        pageChars.append(char)
                    }
                }
            }

            if (pageChars.length >= 3) {
                // Format as magazine + page (e.g., "124" or "800")
                return pageChars.toString()
            }
        } catch (e: Exception) {
            // Ignore errors
        }

        return "100" // Default fallback
    }

    private fun setupCharsetSelector() {
        val charsetSpinner = findViewById<AutoCompleteTextView>(R.id.charsetSpinner)
        val charsetNames = CharsetManager.getCharsetNames()

        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, charsetNames)
        charsetSpinner.setAdapter(adapter)

        // Set default
        charsetSpinner.setText(charsetNames[0], false)

        // Handle selection
        charsetSpinner.setOnItemClickListener { _, _, position, _ ->
            teletextView.currentCharset = position
            Toast.makeText(
                this,
                "Charset changed to ${CharsetManager.CHARSETS[position].name}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
