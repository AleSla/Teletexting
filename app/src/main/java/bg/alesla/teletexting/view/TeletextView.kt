package bg.alesla.teletexting.view

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import bg.alesla.teletexting.utils.CharsetManager


/**
 * Custom View for rendering Teletext Level 1 pages with full attribute support
 */
class TeletextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val COLS = 40
        const val ROWS = 25
        private const val CELL_WIDTH = 24f
        private const val CELL_HEIGHT = 40f
    }

    // Teletext colors (Level 1)
    private val colors = intArrayOf(
        Color.BLACK,        // 0
        Color.RED,          // 1
        Color.GREEN,        // 2
        Color.YELLOW,       // 3
        Color.BLUE,         // 4
        Color.MAGENTA,      // 5
        Color.CYAN,         // 6
        Color.WHITE         // 7
    )

    // Page data: 25 rows x 40 columns
    var pageData = Array(ROWS) { IntArray(COLS) { 0x20 } }
        set(value) {
            field = value
            recomputeAttributes()
            invalidate()
        }

    // Computed attributes per cell
    private var fgColors = Array(ROWS) { IntArray(COLS) { 7 } }
    private var bgColors = Array(ROWS) { IntArray(COLS) { 0 } }
    private var textMode = Array(ROWS) { BooleanArray(COLS) { true } }
    private var doubleHeight = Array(ROWS) { BooleanArray(COLS) { false } }
    private var flashState = Array(ROWS) { BooleanArray(COLS) { false } }
    private var separatedGraphics = Array(ROWS) { BooleanArray(COLS) { false } }

    // Display options
    var showGrid = false
        set(value) {
            field = value
            invalidate()
        }

    var showControlCodes = false
        set(value) {
            field = value
            invalidate()
        }

    var cursorVisible = true
        set(value) {
            field = value
            invalidate()
        }

    var currentCharset = 0
        set(value) {
            field = value
            invalidate()
        }

    var blackWhiteMode = false
        set(value) {
            field = value
            invalidate()
        }

    // Undo/Redo history
    private data class HistoryState(
        val pageData: Array<IntArray>,
        val cursorX: Int,
        val cursorY: Int
    ) {
        fun copy(): HistoryState {
            return HistoryState(
                pageData.map { it.clone() }.toTypedArray(),
                cursorX,
                cursorY
            )
        }
    }

    private val history = mutableListOf<HistoryState>()
    private var historyIndex = -1
    private val maxHistorySize = 200

    var onHistoryChanged: (() -> Unit)? = null

    private var _cursorX = 0
    private var _cursorY = 0

    var cursorX: Int
        get() = _cursorX
        set(value) {
            _cursorX = value.coerceIn(0, COLS - 1)
            android.util.Log.d("TeletextView", "cursorX set to: $_cursorX (input was: $value)")
            onCursorChanged?.invoke(_cursorX, _cursorY)
            invalidate()
        }

    var cursorY: Int
        get() = _cursorY
        set(value) {
            _cursorY = value.coerceIn(0, ROWS - 1)
            android.util.Log.d("TeletextView", "cursorY set to: $_cursorY (input was: $value)")
            onCursorChanged?.invoke(_cursorX, _cursorY)
            invalidate()
        }

    // Flash animation
    private var flashVisible = true
    private val flashRunnable = object : Runnable {
        override fun run() {
            flashVisible = !flashVisible
            invalidate()
            postDelayed(this, 500)
        }
    }

    // Paints
    private val textPaint = Paint().apply {
        textAlign = Paint.Align.LEFT
        isAntiAlias = false // Pixelated effect
        isFilterBitmap = false
        isDither = false
    }

    private val textPaintDouble = Paint().apply {
        textAlign = Paint.Align.LEFT
        isAntiAlias = false
        isFilterBitmap = false
        isDither = false
    }

    init {
        // Load custom fonts if available, fallback to monospace
        try {
            val normalFont = Typeface.createFromAsset(context.assets, "fonts/teletext.ttf")
            textPaint.typeface = normalFont
            textPaint.textSize = CELL_HEIGHT * 0.85f
        } catch (e: Exception) {
            textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textPaint.textSize = CELL_HEIGHT * 0.72f
        }

        try {
            val doubleFont = Typeface.createFromAsset(context.assets, "fonts/teletext_double.ttf")
            textPaintDouble.typeface = doubleFont
            textPaintDouble.textSize = CELL_HEIGHT * 1.7f
        } catch (e: Exception) {
            textPaintDouble.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textPaintDouble.textSize = CELL_HEIGHT * 1.44f
        }

        // Make view focusable for keyboard input
        isFocusable = true
        isFocusableInTouchMode = true

        // Initialize history with empty state
        post {
            pushHistory()
        }

        post(flashRunnable)
    }

    private val bgPaint = Paint()
    private val gridPaint = Paint().apply {
        color = Color.DKGRAY
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private val cursorPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val controlCodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY
        textSize = CELL_HEIGHT * 0.5f
        textAlign = Paint.Align.CENTER
    }

    // Callbacks
    var onCellClicked: ((x: Int, y: Int) -> Unit)? = null
    var onCursorChanged: ((x: Int, y: Int) -> Unit)? = null

    // Gesture detection
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            // e.x and e.y are already scaled by onTouchEvent
            val col = (e.x / CELL_WIDTH).toInt().coerceIn(0, COLS - 1)
            val row = (e.y / CELL_HEIGHT).toInt().coerceIn(0, ROWS - 1)

            // Debug logging
            android.util.Log.d("TeletextView", "Touch at: (${e.x}, ${e.y}) -> Cell: ($col, $row)")

            cursorX = col
            cursorY = row

            onCellClicked?.invoke(col, row)
            return true
        }
    })

    init {
        post(flashRunnable)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(flashRunnable)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = (COLS * CELL_WIDTH).toInt()
        val height = (ROWS * CELL_HEIGHT).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Log all touch events
        android.util.Log.d("TeletextView", "onTouchEvent: action=${event.action}, x=${event.x}, y=${event.y}")

        // Request parent to not intercept touch events
        parent?.requestDisallowInterceptTouchEvent(true)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Calculate cell position directly
                val scaleX = width.toFloat() / (COLS * CELL_WIDTH)
                val scaleY = height.toFloat() / (ROWS * CELL_HEIGHT)

                val canvasX = event.x / scaleX
                val canvasY = event.y / scaleY

                val col = (canvasX / CELL_WIDTH).toInt().coerceIn(0, COLS - 1)
                val row = (canvasY / CELL_HEIGHT).toInt().coerceIn(0, ROWS - 1)

                android.util.Log.d("TeletextView", "Touch: canvas=($canvasX, $canvasY) -> cell=($col, $row)")

                cursorX = col
                cursorY = row

                // Request focus and show keyboard
                requestFocus()
                showKeyboard()

                performClick()
                onCellClicked?.invoke(col, row)

                return true
            }
        }

        return super.onTouchEvent(event)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE
        return TeletextInputConnection(this, true)
    }

    private fun showKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                // Save state before deletion
                pushHistory()

                // Backspace - delete current cell and move back
                if (_cursorX > 0) {
                    _cursorX--
                    pageData[_cursorY][_cursorX] = 0x20
                } else if (_cursorY > 0) {
                    _cursorY--
                    _cursorX = COLS - 1
                    pageData[_cursorY][_cursorX] = 0x20
                } else {
                    // At position (0,0) - wrap to end of page
                    _cursorY = ROWS - 1
                    _cursorX = COLS - 1
                    pageData[_cursorY][_cursorX] = 0x20
                }
                onCursorChanged?.invoke(_cursorX, _cursorY)
                recomputeAttributes()
                invalidate()
                return true
            }
            KeyEvent.KEYCODE_ENTER -> {
                // Move to start of next row
                _cursorY++
                _cursorX = 0

                // Wrap to top if at bottom
                if (_cursorY >= ROWS) {
                    _cursorY = 0
                }
                onCursorChanged?.invoke(_cursorX, _cursorY)
                invalidate()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (_cursorX > 0) {
                    _cursorX--
                } else {
                    _cursorX = COLS - 1
                    if (_cursorY > 0) {
                        _cursorY--
                    } else {
                        _cursorY = ROWS - 1
                    }
                }
                onCursorChanged?.invoke(_cursorX, _cursorY)
                invalidate()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (_cursorX < COLS - 1) {
                    _cursorX++
                } else {
                    _cursorX = 0
                    if (_cursorY < ROWS - 1) {
                        _cursorY++
                    } else {
                        _cursorY = 0
                    }
                }
                onCursorChanged?.invoke(_cursorX, _cursorY)
                invalidate()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (_cursorY > 0) {
                    _cursorY--
                } else {
                    _cursorY = ROWS - 1
                }
                onCursorChanged?.invoke(_cursorX, _cursorY)
                invalidate()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (_cursorY < ROWS - 1) {
                    _cursorY++
                } else {
                    _cursorY = 0
                }
                onCursorChanged?.invoke(_cursorX, _cursorY)
                invalidate()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    fun insertCharacter(char: Char) {
        val charCode = char.code

        android.util.Log.d("TeletextView", "insertCharacter: '$char' at ($_cursorX, $_cursorY)")

        // Save state before modification
        pushHistory()

        // Insert character at cursor
        pageData[_cursorY][_cursorX] = charCode and 0x7F

        // Move cursor to next position
        _cursorX++

        // Check if we've reached end of row
        if (_cursorX >= COLS) {
            _cursorX = 0
            _cursorY++

            // Check if we've reached end of page - wrap to top
            if (_cursorY >= ROWS) {
                _cursorY = 0
            }
        }

        android.util.Log.d("TeletextView", "Cursor now at: ($_cursorX, $_cursorY)")

        onCursorChanged?.invoke(_cursorX, _cursorY)
        recomputeAttributes()
        invalidate()
    }

    // Custom InputConnection for handling text input
    private inner class TeletextInputConnection(
        targetView: View,
        fullEditor: Boolean
    ) : BaseInputConnection(targetView, fullEditor) {

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            text?.forEach { char ->
                insertCharacter(char)
            }
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            if (beforeLength > 0) {
                repeat(beforeLength) {
                    if (cursorX > 0) {
                        cursorX--
                    } else if (cursorY > 0) {
                        cursorY--
                        cursorX = COLS - 1
                    }
                    pageData[cursorY][cursorX] = 0x20
                }
                recomputeAttributes()
                invalidate()
            }
            return true
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /**
     * Recompute display attributes based on control codes
     */
    private fun recomputeAttributes() {
        for (row in 0 until ROWS) {
            var currentFg = 7
            var currentBg = 0
            var currentText = true
            var currentDouble = false
            var currentFlash = false
            var currentSeparated = false

            for (col in 0 until COLS) {
                val ch = pageData[row][col]

                when {
                    ch in 0x00..0x07 -> { // Text color
                        currentFg = ch
                        currentText = true
                    }
                    ch == 0x09 -> currentFlash = false // Steady
                    ch == 0x08 -> currentFlash = true  // Flash
                    ch == 0x0C -> currentDouble = false // Normal height
                    ch == 0x0D -> currentDouble = true  // Double height
                    ch in 0x10..0x17 -> { // Graphics color
                        currentFg = ch - 0x10
                        currentText = false
                    }
                    ch == 0x19 -> currentSeparated = false // Contiguous graphics
                    ch == 0x1A -> currentSeparated = true  // Separated graphics
                    ch == 0x1C -> currentBg = 0 // Black background
                    ch == 0x1D -> currentBg = currentFg // New background
                }

                fgColors[row][col] = currentFg
                bgColors[row][col] = currentBg
                textMode[row][col] = currentText
                doubleHeight[row][col] = currentDouble
                flashState[row][col] = currentFlash
                separatedGraphics[row][col] = currentSeparated
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw backgrounds
        drawBackgrounds(canvas)

        // Draw characters and graphics
        for (row in 0 until ROWS) {
            for (col in 0 until COLS) {
                drawCell(canvas, row, col)
            }
        }

        // Draw grid
        if (showGrid) {
            for (row in 0..ROWS) {
                canvas.drawLine(0f, row * CELL_HEIGHT, COLS * CELL_WIDTH, row * CELL_HEIGHT, gridPaint)
            }
            for (col in 0..COLS) {
                canvas.drawLine(col * CELL_WIDTH, 0f, col * CELL_WIDTH, ROWS * CELL_HEIGHT, gridPaint)
            }
        }

        // Draw cursor
        if (cursorVisible) {
            val x = _cursorX * CELL_WIDTH
            val y = _cursorY * CELL_HEIGHT
            canvas.drawRect(x + 2, y + 2, x + CELL_WIDTH - 2, y + CELL_HEIGHT - 2, cursorPaint)
        }
    }

    private fun drawBackgrounds(canvas: Canvas) {
        for (row in 0 until ROWS) {
            for (col in 0 until COLS) {
                // Skip bottom half of double height
                if (row > 0 && doubleHeight[row - 1][col]) continue

                val x = col * CELL_WIDTH
                val y = row * CELL_HEIGHT
                val bgColor = if (blackWhiteMode) Color.BLACK else colors[bgColors[row][col]]

                bgPaint.color = bgColor

                if (doubleHeight[row][col]) {
                    canvas.drawRect(x, y, x + CELL_WIDTH, y + CELL_HEIGHT * 2, bgPaint)
                } else {
                    canvas.drawRect(x, y, x + CELL_WIDTH, y + CELL_HEIGHT, bgPaint)
                }
            }
        }
    }

    private fun drawCell(canvas: Canvas, row: Int, col: Int) {
        var ch = pageData[row][col]
        var fgIdx = fgColors[row][col]
        var bgIdx = bgColors[row][col]
        var isText = textMode[row][col]
        var dbl = 0

        // Handle double height - check if we're in bottom half of double height
        if (row > 0 && doubleHeight[row - 1][col]) {
            // This row is the bottom half of previous row's double height
            return // Skip rendering - already drawn by top half
        } else if (doubleHeight[row][col]) {
            dbl = 1 // This is top half of double height
        }

        val x = col * CELL_WIDTH
        val y = row * CELL_HEIGHT

        // Check flash visibility
        val isFlashing = flashState[row][col]
        if (isFlashing && !flashVisible) return // Hide flashing content
        val displayChar = CharsetManager.mapChar(ch, currentCharset)
        val fgColor = if (blackWhiteMode) Color.WHITE else colors[fgIdx]

        if (ch < 0x20) {
            // Control code
            if (showControlCodes) {
                controlCodePaint.color = Color.GRAY
                val label = String.format("%02X", ch)
                canvas.drawText(label, x + CELL_WIDTH / 2, y + CELL_HEIGHT / 2, controlCodePaint)
            }
        } else if (isText) {
            // Text character
            if (dbl == 1) {
                // Double height text - spans two rows
                textPaintDouble.color = fgColor
                canvas.save()
                // Clip to both current and next row
                canvas.clipRect(x, y, x + CELL_WIDTH, y + CELL_HEIGHT * 2)
                // Draw character at double size, baseline in middle of two-row span
                val baseline = y + CELL_HEIGHT * 2f

                canvas.drawText(displayChar, x + 2, baseline, textPaintDouble)
                canvas.restore()
            } else {
                // Normal height text
                textPaint.color = fgColor
                val baseline = y + CELL_HEIGHT * 1f
                canvas.drawText(displayChar, x + 2, baseline, textPaint)
            }
        } else {
            // Graphics mosaic
            drawMosaic(canvas, ch, x, y, fgColor, dbl, separatedGraphics[row][col])
        }
    }

    private fun drawMosaic(canvas: Canvas, ch: Int, x: Float, y: Float, color: Int, dbl: Int, separated: Boolean) {
        val code = ch and 0x7F
        val sixW = CELL_WIDTH / 2
        val sixH = when (dbl) {
            1 -> CELL_HEIGHT / 2
            2 -> CELL_HEIGHT
            else -> CELL_HEIGHT / 3
        }

        bgPaint.color = color

        // Add spacing for separated graphics
        val spacing = if (separated) 2f else 0f

        when (dbl) {
            1 -> { // Top half
                if (code and 0x01 != 0) canvas.drawRect(x + spacing, y + spacing, x + sixW - spacing, y + sixH - spacing, bgPaint)
                if (code and 0x02 != 0) canvas.drawRect(x + sixW + spacing, y + spacing, x + CELL_WIDTH - spacing, y + sixH - spacing, bgPaint)
                if (code and 0x04 != 0) canvas.drawRect(x + spacing, y + sixH + spacing, x + sixW - spacing, y + CELL_HEIGHT - spacing, bgPaint)
                if (code and 0x08 != 0) canvas.drawRect(x + sixW + spacing, y + sixH + spacing, x + CELL_WIDTH - spacing, y + CELL_HEIGHT - spacing, bgPaint)
            }
            2 -> { // Bottom half
                if (code and 0x10 != 0) canvas.drawRect(x + spacing, y + spacing, x + sixW - spacing, y + CELL_HEIGHT - spacing, bgPaint)
                if (code and 0x40 != 0) canvas.drawRect(x + sixW + spacing, y + spacing, x + CELL_WIDTH - spacing, y + CELL_HEIGHT - spacing, bgPaint)
            }
            else -> { // Normal height - 6 sixels
                if (code and 0x01 != 0) canvas.drawRect(x + spacing, y + spacing, x + sixW - spacing, y + sixH - spacing, bgPaint)
                if (code and 0x02 != 0) canvas.drawRect(x + sixW + spacing, y + spacing, x + CELL_WIDTH - spacing, y + sixH - spacing, bgPaint)
                if (code and 0x04 != 0) canvas.drawRect(x + spacing, y + sixH + spacing, x + sixW - spacing, y + sixH * 2 - spacing, bgPaint)
                if (code and 0x08 != 0) canvas.drawRect(x + sixW + spacing, y + sixH + spacing, x + CELL_WIDTH - spacing, y + sixH * 2 - spacing, bgPaint)
                if (code and 0x10 != 0) canvas.drawRect(x + spacing, y + sixH * 2 + spacing, x + sixW - spacing, y + CELL_HEIGHT - spacing, bgPaint)
                if (code and 0x40 != 0) canvas.drawRect(x + sixW + spacing, y + sixH * 2 + spacing, x + CELL_WIDTH - spacing, y + CELL_HEIGHT - spacing, bgPaint)
            }
        }
    }

    fun setCharAt(x: Int, y: Int, charCode: Int) {
        if (x in 0 until COLS && y in 0 until ROWS) {
            pageData[y][x] = charCode and 0x7F
            recomputeAttributes()
            invalidate()
        }
    }

    fun getCharAt(x: Int, y: Int): Int {
        return if (x in 0 until COLS && y in 0 until ROWS) {
            pageData[y][x]
        } else 0x20
    }

    fun clearPage() {
        pushHistory() // Save before clearing
        pageData = Array(ROWS) { IntArray(COLS) { 0x20 } }
        recomputeAttributes()
        invalidate()
    }

    /**
     * Push current state to history
     */
    private fun pushHistory() {
        // Remove any redo states after current position
        if (historyIndex < history.size - 1) {
            history.subList(historyIndex + 1, history.size).clear()
        }

        // Create snapshot of current state
        val state = HistoryState(
            pageData.map { it.clone() }.toTypedArray(),
            _cursorX,
            _cursorY
        )

        history.add(state)

        // Limit history size
        if (history.size > maxHistorySize) {
            history.removeAt(0)
        } else {
            historyIndex++
        }

        if (history.size > maxHistorySize) {
            historyIndex = maxHistorySize - 1
        } else {
            historyIndex = history.size - 1
        }

        android.util.Log.d("TeletextView", "pushHistory: size=${history.size}, index=$historyIndex, canUndo=${canUndo()}, canRedo=${canRedo()}")

        onHistoryChanged?.invoke()
    }

    /**
     * Undo last action
     */
    fun undo(): Boolean {
        android.util.Log.d("TeletextView", "undo() called: historyIndex=$historyIndex, size=${history.size}")

        if (historyIndex <= 0) {
            android.util.Log.d("TeletextView", "undo() blocked: no history")
            return false
        }

        historyIndex--
        restoreState(history[historyIndex])
        onHistoryChanged?.invoke()

        android.util.Log.d("TeletextView", "undo() success: new index=$historyIndex")
        return true
    }

    /**
     * Redo last undone action
     */
    fun redo(): Boolean {
        android.util.Log.d("TeletextView", "redo() called: historyIndex=$historyIndex, size=${history.size}")

        if (historyIndex >= history.size - 1) {
            android.util.Log.d("TeletextView", "redo() blocked: at end of history")
            return false
        }

        historyIndex++
        restoreState(history[historyIndex])
        onHistoryChanged?.invoke()

        android.util.Log.d("TeletextView", "redo() success: new index=$historyIndex")
        return true
    }

    /**
     * Check if undo is available
     */
    fun canUndo(): Boolean = historyIndex > 0

    /**
     * Check if redo is available
     */
    fun canRedo(): Boolean = historyIndex < history.size - 1

    /**
     * Restore state from history
     */
    private fun restoreState(state: HistoryState) {
        // Restore page data
        for (row in 0 until ROWS) {
            for (col in 0 until COLS) {
                pageData[row][col] = state.pageData[row][col]
            }
        }

        // Restore cursor position using backing fields
        _cursorX = state.cursorX
        _cursorY = state.cursorY

        onCursorChanged?.invoke(_cursorX, _cursorY)
        recomputeAttributes()
        invalidate()
    }

}
