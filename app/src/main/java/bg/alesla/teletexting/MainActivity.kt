package bg.alesla.teletexting

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.Manifest
import android.annotation.SuppressLint
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
import java.io.Serializable
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import bg.alesla.teletexting.parsers.BinaryParser
import bg.alesla.teletexting.parsers.EP1Parser
import bg.alesla.teletexting.parsers.T42Parser
import bg.alesla.teletexting.parsers.TTIParser
import bg.alesla.teletexting.parsers.TTXParser
import bg.alesla.teletexting.parsers.TTIxParser
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
    ) : Serializable

    enum class FileFormat {
        TTI, TTIX, EP1, T42, BIN, TTX
    }

    private val openFileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                try {
                    // Persist permissions so we can save after app restart
                    val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(uri, takeFlags)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                loadFile(uri)
            }
        }
    }

    private val saveFileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                try {
                    val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(uri, takeFlags)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
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

        teletextView.onSelectionChanged = { start, end ->
            invalidateOptionsMenu() // Update menu items
            updateStatusBar() // Show selection info
        }
        restoreAppState()
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

        val hasSelection = teletextView.selectionStartRow != null
        val hasClipboard = teletextView.hasClipboard()

        menu.findItem(R.id.action_extend_selection)?.isEnabled = hasSelection
        menu.findItem(R.id.action_clear_selection)?.isEnabled = hasSelection
        menu.findItem(R.id.action_copy)?.isEnabled = true // Can always copy current row
        menu.findItem(R.id.action_cut)?.isEnabled = true
        menu.findItem(R.id.action_paste)?.isEnabled = hasClipboard
        menu.findItem(R.id.action_insert)?.isEnabled = hasClipboard

        return super.onPrepareOptionsMenu(menu)
    }

    private fun updateUndoRedoButtons(menu: Menu) {
        menu.findItem(R.id.action_undo)?.isEnabled = teletextView.canUndo()
        menu.findItem(R.id.action_redo)?.isEnabled = teletextView.canRedo()
    }

    private fun createDropdownAdapter(items: List<String>): ArrayAdapter<String> {
        return object : ArrayAdapter<String>(this, android.R.layout.simple_dropdown_item_1line, items) {
            override fun getFilter(): android.widget.Filter {
                return object : android.widget.Filter() {
                    override fun performFiltering(constraint: CharSequence?): FilterResults {
                        return FilterResults().apply {
                            values = items
                            count = items.size
                        }
                    }
                    @Suppress("UNCHECKED_CAST")
                    override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                        notifyDataSetChanged()
                    }
                }
            }
        }
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

            R.id.action_close_file -> {
                showCloseFileDialog()
                true
            }

            R.id.action_export -> {
                showExportDialog()
                true
            }

            R.id.action_clear -> {
                clearPage()
                true
            }

            R.id.action_renumber -> {
                showRenumberDialog()
                true
            }
            R.id.action_spellcheck -> {
                showSpellcheckDialog()
                true
            }

            R.id.action_start_selection -> {
                teletextView.startSelection()
                Toast.makeText(this, "Selection started at row ${teletextView.cursorY}", Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_extend_selection -> {
                teletextView.extendSelection()
                val start = teletextView.selectionStartRow
                val end = teletextView.selectionEndRow
                Toast.makeText(this, "Selection: rows $start-$end", Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_clear_selection -> {
                teletextView.clearSelection()
                Toast.makeText(this, "Selection cleared", Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_copy -> {
                if (teletextView.copySelection()) {
                    val info = teletextView.getClipboardInfo()
                    Toast.makeText(this, "Copied: $info", Toast.LENGTH_SHORT).show()
                }
                true
            }
            R.id.action_cut -> {
                if (teletextView.cutSelection()) {
                    val info = teletextView.getClipboardInfo()
                    Toast.makeText(this, "Cut: $info", Toast.LENGTH_SHORT).show()
                }
                true
            }
            R.id.action_paste -> {
                if (teletextView.pasteAtCursor()) {
                    Toast.makeText(this, "Pasted at row ${teletextView.cursorY}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Nothing to paste", Toast.LENGTH_SHORT).show()
                }
                true
            }
            R.id.action_insert -> {
                if (teletextView.insertAtCursor()) {
                    Toast.makeText(this, "Inserted at row ${teletextView.cursorY}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Nothing to insert", Toast.LENGTH_SHORT).show()
                }
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

    @SuppressLint("NewApi")
    private fun loadFile(uri: Uri) {
        Toast.makeText(this, "Loading file, please wait...", Toast.LENGTH_SHORT).show()

        // Launch in background thread to prevent UI freezing on huge files
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val data = inputStream?.readBytes()
                inputStream?.close()

                if (data == null) {
                    withContext(Dispatchers.Main) { showError("Failed to read file") }
                    return@launch
                }

                val filename = getFileName(uri)
                val ext = filename.substringAfterLast('.', "").lowercase()
                var newFormat = currentFormat
                val tempPages = mutableListOf<PageData>()

                when (ext) {
                    "tti", "txt" -> {
                        newFormat = FileFormat.TTI
                        val content = String(data, Charsets.UTF_8)
                        // Note: For full isolation, you might want to adjust loadTTIPages to return a list
                        // rather than mutating allPages directly. For now, we'll sync it carefully.
                        withContext(Dispatchers.Main) { loadTTIPages(content) }
                    }
                    "ttix" -> {
                        newFormat = FileFormat.TTIX
                        val content = String(data, Charsets.UTF_8)
                        val parsedPages = TTIxParser.parseMultiPage(content)
                        for (page in parsedPages) {
                            tempPages.add(PageData(page.pageNumber, page.subPage, page.data))
                        }
                    }
                    "ep1" -> {
                        newFormat = FileFormat.EP1
                        EP1Parser.parse(data)?.let {
                            tempPages.add(PageData(extractPageNumber(it), "0000", it))
                        }
                    }
                    "t42" -> {
                        newFormat = FileFormat.T42
                        val parsedPages = T42Parser.parseMultiPage(data)
                        val subpageCounters = mutableMapOf<String, Int>()
                        for (pageData in parsedPages) {
                            val pageNum = extractPageNumber(pageData)
                            val currentSub = subpageCounters.getOrDefault(pageNum, 0)
                            subpageCounters[pageNum] = currentSub + 1
                            tempPages.add(PageData(pageNum, String.format("%04X", currentSub), pageData))
                        }
                    }
                    "ttx" -> {
                        newFormat = FileFormat.TTX
                        var offset = 0
                        val subpageCounters = mutableMapOf<String, Int>()
                        while (offset + 42 <= data.size) {
                            val pageData = TTXParser.parse(data.sliceArray(offset until minOf(offset + 1050, data.size)))
                            if (pageData != null) {
                                val pageNum = extractPageNumber(pageData)
                                val currentSub = subpageCounters.getOrDefault(pageNum, 0)
                                subpageCounters[pageNum] = currentSub + 1
                                tempPages.add(PageData(pageNum, String.format("%04X", currentSub), pageData))
                                offset += 1050
                            } else {
                                break
                            }
                        }
                    }
                    else -> {
                        newFormat = FileFormat.BIN
                        BinaryParser.parse(data)?.let {
                            tempPages.add(PageData(extractPageNumber(it), "0000", it))
                        }
                    }
                }

                // Switch back to Main Thread to update UI
                withContext(Dispatchers.Main) {
                    if (ext != "tti" && ext != "txt") {
                        allPages.clear()
                        allPages.addAll(tempPages)
                    }

                    if (allPages.isNotEmpty()) {
                        currentUri = uri
                        currentFileName = filename
                        currentFormat = newFormat
                        currentPageIndex = 0

                        displayCurrentPage()
                        supportActionBar?.subtitle = currentFileName
                        Toast.makeText(this@MainActivity, "Loaded ${allPages.size} pages", Toast.LENGTH_SHORT).show()
                    } else {
                        showError("Failed to parse file")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { showError("Error loading file: ${e.message}") }
            }
        }
    }

    private fun syncCurrentPageData() {
        if (allPages.isNotEmpty() && currentPageIndex in allPages.indices) {
            // Clone the array to prevent reference issues
            val clonedData = teletextView.pageData.map { it.clone() }.toTypedArray()
            allPages[currentPageIndex] = allPages[currentPageIndex].copy(data = clonedData)
        }
    }

    private fun loadTTIPages(content: String) {
        allPages.clear()
        val lines = content.split("\n")
        var currentPage: MutableMap<Int, String> = mutableMapOf()
        var pageNumber = "100"
        var subPage = "0000"

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

    @SuppressLint("NewApi")
    private fun loadT42Pages(data: ByteArray) {
        allPages.clear()
        val pages = T42Parser.parseMultiPage(data)

        val subpageCounters = mutableMapOf<String, Int>()
        for (pageData in pages) {
            val pageNum = extractPageNumber(pageData)
            val currentSub = subpageCounters.getOrDefault(pageNum, 0)
            subpageCounters[pageNum] = currentSub + 1

            // Format subpage as 4-digit hex (e.g., 0000, 0001)
            val subPage = String.format("%04X", currentSub)
            allPages.add(PageData(pageNum, subPage, pageData))
        }
    }

    @SuppressLint("NewApi")
    private fun loadTTXPages(data: ByteArray) {
        allPages.clear()
        var offset = 0
        val subpageCounters = mutableMapOf<String, Int>()

        while (offset + 42 <= data.size) {
            val pageData = TTXParser.parse(data.sliceArray(offset until minOf(offset + 1050, data.size)))
            if (pageData != null) {
                val pageNum = extractPageNumber(pageData)
                val currentSub = subpageCounters.getOrDefault(pageNum, 0)
                subpageCounters[pageNum] = currentSub + 1

                val subPage = String.format("%04X", currentSub)
                allPages.add(PageData(pageNum, subPage, pageData))
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

        // Clone the array so edits don't instantly overwrite the memory array
        teletextView.pageData = page.data.map { it.clone() }.toTypedArray()

        // Reset the undo/redo stack for this specific page
        teletextView.clearHistory()

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
        Toast.makeText(this, "Saving...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val data = when (currentFormat) {
                    FileFormat.TTI -> {
                        val page = TTIParser.TTIPage(
                            currentPageNumber, currentDescription, "English", teletextView.pageData
                        )
                        TTIParser.serialize(page).toByteArray()
                    }
                    FileFormat.TTIX -> {
                        val ttixPages = allPages.map {
                            TTIxParser.TTIxPage(it.pageNumber, it.subPage, currentDescription, it.data)
                        }
                        TTIxParser.serializeMultiPage(ttixPages)
                    }
                    FileFormat.EP1 -> EP1Parser.serialize(teletextView.pageData)
                    FileFormat.T42 -> T42Parser.serializeMultiPage(allPages.map { it.data })
                    FileFormat.TTX -> TTXParser.serialize(teletextView.pageData)
                    FileFormat.BIN -> BinaryParser.serialize(teletextView.pageData)
                }

                val outputStream = try {
                    contentResolver.openOutputStream(uri, "rwt")
                } catch (e: Exception) {
                    contentResolver.openOutputStream(uri)
                }

                outputStream?.write(data)
                outputStream?.close()

                withContext(Dispatchers.Main) {
                    currentUri = uri
                    Toast.makeText(this@MainActivity, "File saved successfully", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { showError("Error saving file: ${e.message}") }
            }
        }
    }

    private fun showExportDialog() {
        val exportFormats = arrayOf("PNG Image", "TTI", "TTIx", "T42", "EP1", "TTX", "Binary", "HTML Pages")

        MaterialAlertDialogBuilder(this)
            .setTitle("Export")
            .setItems(exportFormats) { _, which ->
                when (which) {
                    0 -> exportPng()
                    1 -> exportToFormat("TTI", "text/plain", "tti")
                    2 -> exportToFormat("TTIx", "application/octet-stream", "ttix")
                    3 -> exportToFormat("T42", "application/octet-stream", "t42")
                    4 -> exportToFormat("EP1", "application/octet-stream", "ep1")
                    5 -> exportToFormat("TTX", "application/octet-stream", "ttx")
                    6 -> exportToFormat("Binary", "application/octet-stream", "bin")
                    7 -> exportHtmlDirLauncher.launch(null) // Launch Folder Picker
                }
            }
            .show()
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

                // Fix: Safely handle the nullable OutputStream
                contentResolver.openOutputStream(uri)?.let { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                    outputStream.close()
                    Toast.makeText(this, "PNG exported successfully", Toast.LENGTH_SHORT).show()
                } ?: run {
                    showError("Failed to open file for export")
                }

                // Restore visual elements
                teletextView.showGrid = wasShowingGrid
                teletextView.showControlCodes = wasShowingCodes
                teletextView.cursorVisible = wasCursorVisible
                teletextView.invalidate()
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
    
    private val exportTTILauncher = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult()
) { result ->
    if (result.resultCode == RESULT_OK) {
        result.data?.data?.let { uri ->
            exportTTIToUri(uri)
        }
    }
}
    private val exportTTIxLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                try {
                    val page = TTIxParser.TTIxPage(currentPageNumber, currentSubPage, currentDescription, teletextView.pageData)
                    val data = TTIxParser.serialize(page).toByteArray()
                    contentResolver.openOutputStream(uri)?.use { it.write(data) }
                    Toast.makeText(this, "Exported as TTIx", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    showError("Error exporting TTIx: ${e.message}")
                }
            }
        }
    }

private val exportT42Launcher = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult()
) { result ->
    if (result.resultCode == RESULT_OK) {
        result.data?.data?.let { uri ->
            exportT42ToUri(uri)
        }
    }
}

private val exportEP1Launcher = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult()
) { result ->
    if (result.resultCode == RESULT_OK) {
        result.data?.data?.let { uri ->
            exportEP1ToUri(uri)
        }
    }
}

private val exportTTXLauncher = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult()
) { result ->
    if (result.resultCode == RESULT_OK) {
        result.data?.data?.let { uri ->
            exportTTXToUri(uri)
        }
    }
}

private val exportBinaryLauncher = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult()
) { result ->
    if (result.resultCode == RESULT_OK) {
        result.data?.data?.let { uri ->
            exportBinaryToUri(uri)
        }
    }
}

    private val exportHtmlDirLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            exportToHtmlDirectory(uri)
        }
    }

// ADD export helper function:
private fun exportToFormat(formatName: String, mimeType: String, extension: String) {
    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = mimeType
        putExtra(Intent.EXTRA_TITLE, "page.$extension")
    }
    
    when (extension) {
        "tti" -> exportTTILauncher.launch(intent)
        "ttix" -> exportTTIxLauncher.launch(intent)
        "t42" -> exportT42Launcher.launch(intent)
        "ep1" -> exportEP1Launcher.launch(intent)
        "ttx" -> exportTTXLauncher.launch(intent)
        "bin" -> exportBinaryLauncher.launch(intent)
    }
}

// ADD export implementation functions:
private fun exportTTIToUri(uri: Uri) {
    try {
        val page = TTIParser.TTIPage(
            currentPageNumber,
            currentDescription,
            "English",
            teletextView.pageData
        )
        val data = TTIParser.serialize(page).toByteArray()
        
        val outputStream = contentResolver.openOutputStream(uri)
        outputStream?.write(data)
        outputStream?.close()
        
        Toast.makeText(this, "Exported as TTI", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        showError("Error exporting TTI: ${e.message}")
    }
}

private fun exportT42ToUri(uri: Uri) {
    try {
        val data = T42Parser.serialize(teletextView.pageData)
        
        val outputStream = contentResolver.openOutputStream(uri)
        outputStream?.write(data)
        outputStream?.close()
        
        Toast.makeText(this, "Exported as T42", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        showError("Error exporting T42: ${e.message}")
    }
}

private fun exportEP1ToUri(uri: Uri) {
    try {
        val data = EP1Parser.serialize(teletextView.pageData)
        
        val outputStream = contentResolver.openOutputStream(uri)
        outputStream?.write(data)
        outputStream?.close()
        
        Toast.makeText(this, "Exported as EP1", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        showError("Error exporting EP1: ${e.message}")
    }
}

private fun exportTTXToUri(uri: Uri) {
    try {
        val data = TTXParser.serialize(teletextView.pageData)
        
        val outputStream = contentResolver.openOutputStream(uri)
        outputStream?.write(data)
        outputStream?.close()
        
        Toast.makeText(this, "Exported as TTX", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        showError("Error exporting TTX: ${e.message}")
    }
}

private fun exportBinaryToUri(uri: Uri) {
    try {
        val data = BinaryParser.serialize(teletextView.pageData)
        
        val outputStream = contentResolver.openOutputStream(uri)
        outputStream?.write(data)
        outputStream?.close()
        
        Toast.makeText(this, "Exported as Binary", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        showError("Error exporting Binary: ${e.message}")
    }
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

    // =========================================
    // FEATURE 1: CLOSE FILE
    // =========================================
    private fun showCloseFileDialog() {
        if (allPages.isEmpty() || currentUri == null) {
            performCloseFile()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Close File")
            .setMessage("Do you want to save your changes before closing?")
            .setPositiveButton("Save") { _, _ ->
                saveFile(currentUri!!)
                performCloseFile() // Close after saving
            }
            .setNegativeButton("Discard") { _, _ ->
                performCloseFile()
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun performCloseFile() {
        allPages.clear()
        // Load a single blank default page
        allPages.add(PageData("100", "0000", Array(25) { IntArray(40) { 0x20 } }))
        currentPageIndex = 0
        currentUri = null
        currentFileName = "No file opened"
        supportActionBar?.subtitle = currentFileName

        displayCurrentPage()
        teletextView.clearHistory()
        Toast.makeText(this, "File closed", Toast.LENGTH_SHORT).show()
    }

    // =========================================
    // FEATURE 2: RENUMBER PAGE
    // =========================================
    private fun showRenumberDialog() {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        // 1. Magazine Spinner (1-8)
        val magSpinner = android.widget.Spinner(this)
        val magAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, (1..8).map { it.toString() })
        magSpinner.adapter = magAdapter

        // 2. Page & Subpage Inputs
        val etPage = com.google.android.material.textfield.TextInputEditText(this).apply { hint = "Page (e.g. 00)" }
        val etSubpage = com.google.android.material.textfield.TextInputEditText(this).apply { hint = "Subpage (e.g. 0000)" }

        // Pre-fill with current page info
        val curMag = currentPageNumber.take(1).toIntOrNull() ?: 1
        magSpinner.setSelection(if (curMag == 8) 7 else curMag - 1)
        etPage.setText(currentPageNumber.substring(1))
        etSubpage.setText(currentSubPage)

        layout.addView(TextView(this).apply { text = "Magazine (1-8):" })
        layout.addView(magSpinner)
        layout.addView(TextView(this).apply { text = "Page (Hex):"; setPadding(0, 20, 0, 0) })
        layout.addView(etPage)
        layout.addView(TextView(this).apply { text = "Subpage (Hex):"; setPadding(0, 20, 0, 0) })
        layout.addView(etSubpage)

        MaterialAlertDialogBuilder(this)
            .setTitle("Renumber Current Page")
            .setView(layout)
            .setPositiveButton("OK") { _, _ ->
                val newMag = magSpinner.selectedItem.toString()
                val newPage = etPage.text.toString().uppercase().padStart(2, '0')
                val newSubpage = etSubpage.text.toString().uppercase().padStart(4, '0')
                val newPageNum = newMag + newPage

                if (allPages.isNotEmpty() && currentPageIndex in allPages.indices) {
                    allPages[currentPageIndex] = allPages[currentPageIndex].copy(
                        pageNumber = newPageNum,
                        subPage = newSubpage
                    )
                    currentPageNumber = newPageNum
                    currentSubPage = newSubpage
                    updateAddressBar()
                    updateStatusBar()
                    Toast.makeText(this, "Page renumbered to $newPageNum/$newSubpage", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // =========================================
    // FEATURE 3: SPELLCHECKING
    // =========================================
    data class SpellCorrection(val original: String, val suggestion: String, val row: Int, val startCol: Int)

    private fun showSpellcheckDialog() {
        lifecycleScope.launch(Dispatchers.IO) {
            val charsetName = CharsetManager.CHARSETS[teletextView.currentCharset].name
            val dictSet = mutableSetOf<String>()

            // 1. Load the dictionary from assets (e.g., "English.dic")
            try {
                assets.open("dicts/$charsetName.dic").bufferedReader().useLines { lines ->
                    lines.forEach { dictSet.add(it.trim().lowercase()) }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showError("Dictionary '$charsetName.dic' not found in assets folder!")
                }
                return@launch
            }

            // 2. Scan the current page for misspelled words
            val suggestions = mutableListOf<SpellCorrection>()
            val pageData = teletextView.pageData

            for (row in 0 until 25) {
                var col = 0
                while (col < 40) {
                    val ch = pageData[row][col] and 0x7F
                    val charStr = CharsetManager.mapChar(ch, teletextView.currentCharset)

                    // If we find the start of a word
                    if (charStr.length == 1 && charStr[0].isLetter()) {
                        val startCol = col
                        val wordBuilder = StringBuilder()

                        while (col < 40) {
                            val c = pageData[row][col] and 0x7F
                            val s = CharsetManager.mapChar(c, teletextView.currentCharset)
                            if (s.length == 1 && s[0].isLetter()) {
                                wordBuilder.append(s)
                                col++
                            } else {
                                break
                            }
                        }

                        val word = wordBuilder.toString()
                        if (word.length > 1 && !dictSet.contains(word.lowercase())) {
                            val bestMatch = findBestMatch(word, dictSet)
                            if (bestMatch != null && bestMatch != word) {
                                suggestions.add(SpellCorrection(word, bestMatch, row, startCol))
                            }
                        }
                    } else {
                        col++
                    }
                }
            }

            // 3. Display the dialog with checkboxes
            withContext(Dispatchers.Main) {
                if (suggestions.isEmpty()) {
                    Toast.makeText(this@MainActivity, "No spelling errors found!", Toast.LENGTH_SHORT).show()
                } else {
                    val items = suggestions.map { "Change '${it.original}' to '${it.suggestion}'" }.toTypedArray()
                    val checkedItems = BooleanArray(items.size) { true } // Checked by default

                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("Spellcheck ($charsetName)")
                        .setMultiChoiceItems(items, checkedItems) { _, which, isChecked ->
                            checkedItems[which] = isChecked
                        }
                        .setPositiveButton("OK") { _, _ ->
                            applySpellCorrections(suggestions, checkedItems)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }
    }

    private fun findBestMatch(word: String, dict: Set<String>): String? {
        var minDist = Int.MAX_VALUE
        var bestWord: String? = null
        val target = word.lowercase()

        for (dictWord in dict) {
            if (Math.abs(dictWord.length - target.length) > 2) continue // Optimize search speed

            val dist = calculateLevenshteinDistance(target, dictWord)
            if (dist < minDist) {
                minDist = dist
                bestWord = dictWord
                if (minDist == 1) break // Good enough match
            }
        }

        // Match original capitalization
        return bestWord?.let {
            if (word.isNotEmpty() && word[0].isUpperCase()) {
                it.replaceFirstChar { char -> char.uppercase() }
            } else it
        }
    }

    private fun calculateLevenshteinDistance(lhs: CharSequence, rhs: CharSequence): Int {
        var cost = IntArray(lhs.length + 1) { it }
        var newCost = IntArray(lhs.length + 1)

        for (i in 1..rhs.length) {
            newCost[0] = i
            for (j in 1..lhs.length) {
                val match = if (lhs[j - 1] == rhs[i - 1]) 0 else 1
                val costReplace = cost[j - 1] + match
                val costInsert = cost[j] + 1
                val costDelete = newCost[j - 1] + 1
                newCost[j] = minOf(minOf(costInsert, costDelete), costReplace)
            }
            val swap = cost
            cost = newCost
            newCost = swap
        }
        return cost[lhs.length]
    }

    private fun applySpellCorrections(suggestions: List<SpellCorrection>, checked: BooleanArray) {
        teletextView.pushHistory() // Save state so the user can 'Undo' the spellcheck!
        val pageData = teletextView.pageData

        for (i in suggestions.indices) {
            if (checked[i]) {
                val corr = suggestions[i]
                val replacement = corr.suggestion

                // Write new word
                for (c in replacement.indices) {
                    if (corr.startCol + c < 40) { // Stay within screen bounds
                        val charStr = replacement[c].toString()
                        val code = reverseMapChar(charStr, teletextView.currentCharset)
                        pageData[corr.row][corr.startCol + c] = code
                    }
                }

                // If original word was longer, fill remainder with spaces
                if (corr.original.length > replacement.length) {
                    for (c in replacement.length until corr.original.length) {
                        if (corr.startCol + c < 40) {
                            pageData[corr.row][corr.startCol + c] = 0x20
                        }
                    }
                }
            }
        }

        syncCurrentPageData()
        teletextView.invalidate() // Redraw screen
        Toast.makeText(this, "Spelling corrections applied!", Toast.LENGTH_SHORT).show()
    }

    private fun reverseMapChar(charStr: String, charsetId: Int): Int {
        val charset = CharsetManager.CHARSETS.find { it.id == charsetId } ?: CharsetManager.CHARSETS[0]

        // Check charset-specific substitutions first
        for ((code, str) in charset.substitutions) {
            if (str == charStr) return code
        }

        // Fallback to standard ASCII
        if (charStr.isNotEmpty()) {
            val code = charStr[0].code
            if (code in 0x20..0x7F) return code
        }
        return 0x20 // Space
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
            FileFormat.TTIX -> "ttix"
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
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnColorBlack)
            .setOnClickListener { insertControlCode(0x00) }
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

        // Show selection info if active
        val posText = if (teletextView.selectionStartRow != null && teletextView.selectionEndRow != null) {
            val start = minOf(teletextView.selectionStartRow!!, teletextView.selectionEndRow!!)
            val end = maxOf(teletextView.selectionStartRow!!, teletextView.selectionEndRow!!)
            "Sel: $start-$end"
        } else {
            "($x,$y)"
        }
        findViewById<TextView>(R.id.tvStatusPos).text = posText

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

        val magazines = allPages.map { it.pageNumber.take(1) }.distinct().sorted()
        // Use substring(1) instead of takeLast(2) to properly handle Hex
        val pages = allPages.filter { it.pageNumber.take(1) == currentPageNumber.take(1) }
            .map { it.pageNumber.substring(1) }.distinct().sorted()
        val subpages = allPages.filter { it.pageNumber == currentPageNumber }
            .map { it.subPage }.distinct().sorted()

        val magazineSpinner = findViewById<AutoCompleteTextView>(R.id.spinnerMagazine)
        magazineSpinner.setAdapter(createDropdownAdapter(magazines))
        magazineSpinner.setText(currentPageNumber.take(1), false)
        magazineSpinner.setOnItemClickListener { _, _, position, _ ->
            navigateToAddress(magazines[position], null, null)
        }

        val pageSpinner = findViewById<AutoCompleteTextView>(R.id.spinnerPage)
        pageSpinner.setAdapter(createDropdownAdapter(pages))
        pageSpinner.setText(currentPageNumber.substring(1), false)
        pageSpinner.setOnItemClickListener { _, _, position, _ ->
            navigateToAddress(currentPageNumber.take(1), pages[position], null)
        }

        val subpageSpinner = findViewById<AutoCompleteTextView>(R.id.spinnerSubpage)
        subpageSpinner.setAdapter(createDropdownAdapter(subpages))
        subpageSpinner.setText(currentSubPage, false)
        subpageSpinner.setOnItemClickListener { _, _, position, _ ->
            navigateToAddress(null, null, subpages[position])
        }
    }

    private fun navigateToAddress(magazine: String?, page: String?, subpage: String?) {
        val targetMag = magazine ?: currentPageNumber.take(1)
        val targetPage = page ?: currentPageNumber.substring(1)
        val targetPageNumber = targetMag + targetPage

        // Smart searching: if subpage is null, find the FIRST instance of the new page
        val index = if (subpage != null) {
            allPages.indexOfFirst { it.pageNumber == targetPageNumber && it.subPage == subpage }
        } else if (magazine != null || page != null) {
            allPages.indexOfFirst { it.pageNumber == targetPageNumber }
        } else {
            allPages.indexOfFirst { it.pageNumber == targetPageNumber && it.subPage == currentSubPage }
        }

        if (index != -1) {
            syncCurrentPageData()
            currentPageIndex = index
            displayCurrentPage()
        } else {
            val subDisplay = subpage ?: "0000"
            Toast.makeText(this, "Page $targetPageNumber/$subDisplay not found", Toast.LENGTH_SHORT).show()
        }
    }

    // =========================================
    // ADVANCED DELETE PAGE LOGIC
    // =========================================
    private fun deleteCurrentPage() {
        if (allPages.isEmpty()) return

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        val radioGroup = android.widget.RadioGroup(this)

        // FIX: We must generate unique IDs so the RadioGroup knows how to uncheck them!
        val rbCurrent = android.widget.RadioButton(this).apply {
            id = android.view.View.generateViewId()
            text = "Delete Current Page ($currentPageNumber/$currentSubPage)"
            isChecked = true
        }
        val rbRange = android.widget.RadioButton(this).apply {
            id = android.view.View.generateViewId()
            text = "Delete Range of Pages"
        }

        radioGroup.addView(rbCurrent)
        radioGroup.addView(rbRange)
        layout.addView(radioGroup)

        // Range Inputs Container
        val rangeContainer = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            visibility = android.view.View.GONE
            setPadding(0, 20, 0, 0)
        }

        val etStart = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = "Start Address (e.g. 1320003)"
            setText(currentPageNumber + currentSubPage)
        }
        val etEnd = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = "End Address (e.g. 132004F)"
        }

        rangeContainer.addView(TextView(this).apply { text = "Start Address (Mag+Page+Subpage):" })
        rangeContainer.addView(etStart)
        rangeContainer.addView(TextView(this).apply { text = "End Address (Mag+Page+Subpage):"; setPadding(0, 20, 0, 0) })
        rangeContainer.addView(etEnd)
        layout.addView(rangeContainer)

        // Toggle Range Inputs visibility
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            rangeContainer.visibility = if (checkedId == rbRange.id) android.view.View.VISIBLE else android.view.View.GONE
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Pages")
            .setView(layout)
            .setPositiveButton("Delete") { _, _ ->
                // FIX: Check the RadioGroup's active ID instead of the button property
                if (radioGroup.checkedRadioButtonId == rbCurrent.id) {
                    allPages.removeAt(currentPageIndex)
                    finalizeDeletion()
                } else {
                    val start = etStart.text.toString().trim().uppercase()
                    val end = etEnd.text.toString().trim().uppercase()

                    if (start.length != 7 || end.length != 7) {
                        Toast.makeText(this, "Addresses must be exactly 7 characters (Mag 1 + Page 2 + Sub 4)", Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }

                    // Save the key of the page we are currently looking at
                    val currentViewedKey = currentPageNumber + currentSubPage

                    // Perform bulk delete by lexicographical comparison
                    val initialSize = allPages.size
                    allPages.removeAll { page ->
                        val key = page.pageNumber + page.subPage
                        key in start..end
                    }
                    val deletedCount = initialSize - allPages.size
                    Toast.makeText(this, "Deleted $deletedCount pages.", Toast.LENGTH_SHORT).show()

                    // FIX: Re-find our current page index so the screen doesn't skip forward!
                    val newIndex = allPages.indexOfFirst { (it.pageNumber + it.subPage) == currentViewedKey }
                    if (newIndex != -1) {
                        currentPageIndex = newIndex
                    }

                    finalizeDeletion()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun finalizeDeletion() {
        if (allPages.isEmpty()) {
            // No pages left, create an empty one so the app doesn't crash
            allPages.add(PageData("100", "0000", Array(25) { IntArray(40) { 0x20 } }))
            currentPageIndex = 0
        } else if (currentPageIndex >= allPages.size) {
            currentPageIndex = allPages.size - 1
        }

        displayCurrentPage()
    }

    private fun setupPageNavigation() {
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPrevPage).setOnClickListener {
            if (currentPageIndex > 0) {
                syncCurrentPageData()
                currentPageIndex--
                displayCurrentPage()
            }
        }

        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnNextPage).setOnClickListener {
            if (currentPageIndex < allPages.size - 1) {
                syncCurrentPageData()
                currentPageIndex++
                displayCurrentPage()
            }
        }
    }

    private fun extractPageNumber(pageData: Array<IntArray>): String {
        try {
            val mag = if (pageData[0][0] in 0x30..0x38) pageData[0][0] - 0x30 else 1
            val actualMag = if (mag == 0) 8 else mag

            val tens = if (pageData[0][1] >= 0x30) pageData[0][1] - 0x30 else 0
            val units = if (pageData[0][2] >= 0x30) pageData[0][2] - 0x30 else 0

            // Format as proper Hex (e.g., 100, 13F, 8AA)
            return String.format("%d%X%X", actualMag, tens, units)
        } catch (e: Exception) {
            return "100" // Default fallback
        }
    }

    // =========================================
    // HTML EXPORT LOGIC
    // =========================================
    private fun exportToHtmlDirectory(treeUri: Uri) {
        Toast.makeText(this, "Exporting HTML pages...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dir = androidx.documentfile.provider.DocumentFile.fromTreeUri(this@MainActivity, treeUri)
                if (dir == null || !dir.canWrite()) {
                    withContext(Dispatchers.Main) { showError("Cannot write to selected directory") }
                    return@launch
                }

                // 1. Copy Asset Files (CSS & Fonts) to the directory
                val assetsToCopy = listOf("teletext.css", "teletext-noscanlines.css", "teletext2.ttf", "teletext4.ttf")
                for (asset in assetsToCopy) {
                    val file = dir.createFile("*/*", asset)
                    if (file != null) {
                        contentResolver.openOutputStream(file.uri)?.use { outStream ->
                            assets.open(asset).use { inStream ->
                                inStream.copyTo(outStream)
                            }
                        }
                    }
                }

                // 2. Group pages by Magazine+Page (ignoring subpage for now to cluster them)
                val pagesGrouped = allPages.groupBy { it.pageNumber }

                // 3. Generate HTML for each Page
                for ((pageNum, subpages) in pagesGrouped) {
                    val htmlContent = generateHtmlForPage(pageNum, subpages)
                    val htmlFile = dir.createFile("text/html", "$pageNum.html")
                    if (htmlFile != null) {
                        contentResolver.openOutputStream(htmlFile.uri)?.use { out ->
                            out.write(htmlContent.toByteArray(Charsets.UTF_8))
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "HTML Export Complete!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { showError("Export failed: ${e.message}") }
            }
        }
    }

    private fun generateHtmlForPage(pageNum: String, subpages: List<PageData>): String {
        val sb = StringBuilder()
        sb.append("""
            <html>
                <head>
                    <meta http-equiv="Content-Type" content="text/html; charset=utf-8">
                    <title>Page $pageNum</title>
                    <link rel="stylesheet" type="text/css" href="teletext.css" title="Default Style"/>
                    <link rel="alternative stylesheet" type="text/css" href="teletext-noscanlines.css" title="No Scanlines"/>
                    <script type="text/javascript" src="cssswitch.js"></script>
                </head>
                <body onload="set_style_from_cookie()">
        """.trimIndent())

        for (subpage in subpages) {
            sb.append("<div class=\"subpage\" id=\"${subpage.subPage}\">")
            val pageData = subpage.data

            var prevRowDhActive = BooleanArray(40)
            var prevRowDhFg = IntArray(40)
            var prevRowDhBg = IntArray(40)

            for (row in 0 until 25) {
                val nextRowDhActive = BooleanArray(40)
                val nextRowDhFg = IntArray(40)
                val nextRowDhBg = IntArray(40)

                sb.append("<span class=\"row\">")

                var spanFg = -1
                var spanBg = -1
                var spanHeight = ""
                var spanText = StringBuilder()

                var currentFg = 7
                var currentBg = 0
                var currentHeight = "nx"
                var isMosaic = false
                var isSeparated = false

                val flushSpan = {
                    if (spanText.isNotEmpty()) {
                        val text = spanText.toString()
                            .replace("&", "&amp;")
                            .replace("<", "&lt;")
                            .replace(">", "&gt;")
                        sb.append("<span class=\"f$spanFg b$spanBg $spanHeight\">$text</span>")
                        spanText.clear()
                    }
                }

                for (col in 0 until 40) {
                    val ch = pageData[row][col] and 0x7F
                    var displayChar = " "

                    var newFg = currentFg
                    var newBg = currentBg
                    var newHeight = currentHeight

                    if (ch < 0x20) {
                        displayChar = " "
                        when (ch) {
                            in 0x00..0x07 -> { newFg = ch; isMosaic = false }
                            in 0x10..0x17 -> { newFg = ch - 0x10; isMosaic = true }
                            0x0C -> newHeight = "nx"
                            0x0D -> newHeight = "dh"
                            0x19 -> isSeparated = false
                            0x1A -> isSeparated = true
                            0x1C -> newBg = 0
                            0x1D -> newBg = currentFg
                        }
                    } else {
                        if (isMosaic && (ch in 0x20..0x3F || ch in 0x60..0x7F)) {
                            // Fix: 0xEE80 correctly points to Separated Level 1 Mosaics.
                            val puaBase = if (isSeparated) 0xEE80 else 0xEE00
                            displayChar = (puaBase + ch).toChar().toString()
                        } else {
                            displayChar = CharsetManager.mapChar(ch, teletextView.currentCharset)
                        }
                    }

                    if (newHeight == "dh") {
                        nextRowDhActive[col] = true
                        nextRowDhFg[col] = newFg
                        nextRowDhBg[col] = newBg
                    }

                    val visualFg: Int
                    val visualBg: Int
                    val visualHeight: String
                    val visualChar: String

                    if (prevRowDhActive[col]) {
                        // Fix: The `.dh` class on the top row scales the font to 200%, overflowing downwards.
                        // By blanking out the bottom row with empty spaces, we prevent duplicate text and clashing!
                        visualFg = prevRowDhFg[col]
                        visualBg = prevRowDhBg[col]
                        visualHeight = "nx"
                        visualChar = " "
                        nextRowDhActive[col] = false
                    } else {
                        visualFg = newFg
                        visualBg = newBg
                        visualHeight = newHeight
                        visualChar = displayChar
                    }

                    if (visualFg != spanFg || visualBg != spanBg || visualHeight != spanHeight) {
                        flushSpan()
                        spanFg = visualFg
                        spanBg = visualBg
                        spanHeight = visualHeight
                    }

                    spanText.append(visualChar)

                    currentFg = newFg
                    currentBg = newBg
                    currentHeight = newHeight
                }
                flushSpan()
                sb.append("</span>")

                prevRowDhActive = nextRowDhActive
                prevRowDhFg = nextRowDhFg
                prevRowDhBg = nextRowDhBg
            }
            sb.append("</div>")
        }
        sb.append("</body></html>")
        return sb.toString()
    }
    private fun setupCharsetSelector() {
        val charsetSpinner = findViewById<AutoCompleteTextView>(R.id.charsetSpinner)
        val charsetNames = CharsetManager.getCharsetNames()

        // Use our non-filtering adapter
        val adapter = createDropdownAdapter(charsetNames)
        charsetSpinner.setAdapter(adapter)

        // Set default ONLY if it's currently empty (preserves state on resume)
        if (charsetSpinner.text.isEmpty()) {
            charsetSpinner.setText(charsetNames[0], false)
        }

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

    override fun onStop() {
        super.onStop()
        saveAppState()
    }

    private fun saveAppState() {
        syncCurrentPageData()

        val prefs = getSharedPreferences("TeletextState", MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean("has_state", true)
            putString("currentUri", currentUri?.toString())
            putString("currentFormat", currentFormat.name)
            putString("currentFileName", currentFileName)
            putInt("currentPageIndex", currentPageIndex)
            putInt("cursorX", teletextView.cursorX)
            putInt("cursorY", teletextView.cursorY)
            putInt("currentCharset", teletextView.currentCharset)
            putBoolean("showGrid", teletextView.showGrid)
            putBoolean("showControlCodes", teletextView.showControlCodes)
            putBoolean("blackWhiteMode", teletextView.blackWhiteMode)
            apply()
        }

        // Run cache saving in background. If file is massive, skip cache to avoid OOM
        if (allPages.size < 5000) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val file = java.io.File(cacheDir, "pages_cache.bin")
                    java.io.ObjectOutputStream(java.io.FileOutputStream(file)).use {
                        it.writeObject(allPages)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun restoreAppState() {
        val prefs = getSharedPreferences("TeletextState", MODE_PRIVATE)
        if (!prefs.getBoolean("has_state", false)) return

        // 1. Restore the page data array from the cache file
        try {
            val file = java.io.File(cacheDir, "pages_cache.bin")
            if (file.exists()) {
                java.io.ObjectInputStream(java.io.FileInputStream(file)).use {
                    @Suppress("UNCHECKED_CAST")
                    val cachedPages = it.readObject() as? List<PageData>
                    if (cachedPages != null) {
                        allPages.clear()
                        allPages.addAll(cachedPages)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Restore file metadata
        val uriStr = prefs.getString("currentUri", null)
        currentUri = if (uriStr != null) android.net.Uri.parse(uriStr) else null
        currentFormat = FileFormat.valueOf(prefs.getString("currentFormat", FileFormat.TTI.name)!!)
        currentFileName = prefs.getString("currentFileName", "No file opened")!!
        currentPageIndex = prefs.getInt("currentPageIndex", 0)

        // 3. Reload the screen
        if (allPages.isNotEmpty()) {
            if (currentPageIndex >= allPages.size) currentPageIndex = 0
            displayCurrentPage()
            supportActionBar?.subtitle = currentFileName
        }

        // 4. Restore View options and formatting
        teletextView.currentCharset = prefs.getInt("currentCharset", 0)
        teletextView.showGrid = prefs.getBoolean("showGrid", false)
        teletextView.showControlCodes = prefs.getBoolean("showControlCodes", false)
        teletextView.blackWhiteMode = prefs.getBoolean("blackWhiteMode", false)

        // Update the visual Charset spinner text to match the restored state
        findViewById<android.widget.AutoCompleteTextView>(R.id.charsetSpinner)
            ?.setText(CharsetManager.getCharsetNames()[teletextView.currentCharset], false)

        // Restore cursor position (Post ensures the view has finished measuring)
        teletextView.post {
            teletextView.cursorX = prefs.getInt("cursorX", 0)
            teletextView.cursorY = prefs.getInt("cursorY", 0)
        }
    }
}
