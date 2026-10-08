package com.example.myapp

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.BufferedOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ImgEntry(val docId: String, val name: String, val key: Long)

class ProgressBarView(ctx: Context) : View(ctx) {
    private var fraction: Float = 0f
    private val bgPaint: Paint = Paint()
    private val fgPaint: Paint = Paint()

    init {
        bgPaint.color = Color.parseColor("#34495e")
        fgPaint.color = Color.parseColor("#27ae60")
    }

    fun setFraction(f: Float) {
        fraction = if (f < 0f) 0f else if (f > 1f) 1f else f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        canvas.drawRect(0f, 0f, width.toFloat() * fraction, height.toFloat(), fgPaint)
    }
}

class MainActivity : Activity() {

    companion object {
        const val REQ_IN = 101
        const val REQ_OUT = 102
        val IMAGE_EXTS: Set<String> = setOf("jpg", "jpeg", "png", "bmp", "tiff", "tif", "gif", "webp")
        const val MAX_BYTES: Long = 700L * 1024L * 1024L
        const val BIG_BYTES: Long = 120L * 1024L * 1024L
    }

    // ---- colors (same palette as the Python app) ----
    private val cBg: Int = Color.parseColor("#2c3e50")
    private val cPanel: Int = Color.parseColor("#34495e")
    private val cLight: Int = Color.parseColor("#ecf0f1")
    private val cBlue: Int = Color.parseColor("#3498db")
    private val cGreen: Int = Color.parseColor("#27ae60")
    private val cGreenLight: Int = Color.parseColor("#2ecc71")
    private val cRed: Int = Color.parseColor("#e74c3c")
    private val cOrange: Int = Color.parseColor("#f39c12")
    private val cOrangeDark: Int = Color.parseColor("#e67e22")
    private val cGrey: Int = Color.parseColor("#95a5a6")
    private val cGrey2: Int = Color.parseColor("#bdc3c7")

    private lateinit var prefs: SharedPreferences

    private var inputTree: Uri? = null
    private var folderName: String = ""

    @Volatile
    private var images: List<ImgEntry> = emptyList()

    private var batchSize: Int = 6
    private var perColumn: Int = 2
    private var breakWidth: Int = 20

    @Volatile
    private var scanGen: Int = 0

    @Volatile
    private var processing: Boolean = false

    @Volatile
    private var cancelFlag: Boolean = false

    private lateinit var batchEdit: EditText
    private lateinit var colEdit: EditText
    private lateinit var widthEdit: EditText
    private lateinit var layoutLabel: TextView
    private lateinit var folderLabel: TextView
    private lateinit var infoLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var detailLabel: TextView
    private lateinit var startBtn: Button
    private lateinit var progressView: ProgressBarView

    // =====================================================================
    // UI
    // =====================================================================

    private fun dp(v: Int): Int {
        return (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun params(w: Int, h: Int, topDp: Int): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(w, h)
        p.setMargins(0, dp(topDp), 0, 0)
        return p
    }

    private fun tv(text: String, sp: Float, color: Int, bold: Boolean): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = sp
        t.setTextColor(color)
        if (bold) t.setTypeface(null, Typeface.BOLD)
        t.gravity = Gravity.CENTER
        return t
    }

    private fun makeButton(text: String, color: Int, sp: Float, vPad: Int): Button {
        val b = Button(this)
        b.text = text
        b.setTextColor(Color.WHITE)
        b.setBackgroundColor(color)
        b.textSize = sp
        b.isAllCaps = false
        b.setTypeface(null, Typeface.BOLD)
        b.isFocusable = false
        b.isFocusableInTouchMode = false
        b.setPadding(dp(24), dp(vPad), dp(24), dp(vPad))
        return b
    }

    private fun makeEdit(initial: String, maxLen: Int, onChange: () -> Unit): EditText {
        val e = EditText(this)
        e.setText(initial)
        e.inputType = InputType.TYPE_CLASS_NUMBER
        e.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(maxLen))
        e.setTextColor(Color.BLACK)
        e.setBackgroundColor(Color.WHITE)
        e.gravity = Gravity.CENTER
        e.textSize = 16f
        e.setPadding(dp(8), dp(6), dp(8), dp(6))
        e.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                onChange()
            }

            override fun afterTextChanged(s: Editable?) {}
        })
        return e
    }

    private fun addRow(parent: LinearLayout, label: String, edit: EditText, suffix: String?) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        row.addView(tv(label, 15f, Color.WHITE, true))
        val p = LinearLayout.LayoutParams(dp(100), ViewGroup.LayoutParams.WRAP_CONTENT)
        p.setMargins(dp(8), 0, dp(8), 0)
        row.addView(edit, p)
        if (suffix != null) row.addView(tv(suffix, 15f, Color.WHITE, false))
        parent.addView(row, params(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 8))
    }

    private fun makePanel(): LinearLayout {
        val p = LinearLayout(this)
        p.orientation = LinearLayout.VERTICAL
        p.gravity = Gravity.CENTER
        p.setBackgroundColor(cPanel)
        p.setPadding(dp(12), dp(10), dp(12), dp(10))
        return p
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("merger_prefs", Context.MODE_PRIVATE)
        batchSize = prefs.getInt("batch", 6)
        perColumn = prefs.getInt("percol", 2)
        breakWidth = prefs.getInt("breakw", 20)
        if (batchSize <= 0) batchSize = 6
        if (perColumn <= 0) perColumn = 2
        if (breakWidth < 0) breakWidth = 20

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(cBg)
        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.gravity = Gravity.CENTER_HORIZONTAL
        content.setPadding(dp(16), dp(20), dp(16), dp(24))
        scroll.addView(
            content,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val match = ViewGroup.LayoutParams.MATCH_PARENT

        content.addView(tv("Universal Batch Image Merger", 22f, Color.WHITE, true), params(match, wrap, 0))
        content.addView(
            tv(
                "Select a folder with images. Configure batch size and images per column.\nColumns will be created automatically based on your settings.",
                13f, cLight, false
            ),
            params(match, wrap, 12)
        )

        val selectBtn = makeButton("\uD83D\uDCC1 Select Image Folder", cBlue, 16f, 12)
        selectBtn.setOnClickListener { selectFolder() }
        content.addView(selectBtn, params(match, wrap, 18))

        val settings = LinearLayout(this)
        settings.orientation = LinearLayout.VERTICAL
        settings.gravity = Gravity.CENTER
        batchEdit = makeEdit(batchSize.toString(), 7) { onSettingsChanged() }
        colEdit = makeEdit(perColumn.toString(), 7) { onSettingsChanged() }
        widthEdit = makeEdit(breakWidth.toString(), 5) { onWidthChanged() }
        addRow(settings, "Images per batch:", batchEdit, null)
        addRow(settings, "Images per column:", colEdit, null)
        addRow(settings, "Page break width:", widthEdit, "pixels")
        content.addView(settings, params(match, wrap, 12))

        val layoutPanel = makePanel()
        layoutLabel = tv("", 14f, cBlue, true)
        layoutPanel.addView(layoutLabel)
        content.addView(layoutPanel, params(match, wrap, 14))

        val infoPanel = makePanel()
        folderLabel = tv("No folder selected", 13f, cLight, false)
        infoLabel = tv("", 13f, cGrey, false)
        infoPanel.addView(folderLabel)
        infoPanel.addView(infoLabel, params(match, wrap, 6))
        content.addView(infoPanel, params(match, wrap, 12))

        startBtn = makeButton("\uD83D\uDE80 Start Batch Merging", cGreen, 18f, 14)
        startBtn.setOnClickListener { onStartClicked() }
        content.addView(startBtn, params(match, wrap, 20))
        setStartEnabled(false)

        progressView = ProgressBarView(this)
        content.addView(progressView, params(match, dp(30), 16))

        statusLabel = tv("Ready to select folder...", 14f, cGrey, true)
        content.addView(statusLabel, params(match, wrap, 12))
        detailLabel = tv("", 12f, cGrey2, false)
        content.addView(detailLabel, params(match, wrap, 6))

        setContentView(scroll)
        updateLayoutPreview()

        val saved = prefs.getString("input_tree", null)
        if (saved != null) {
            val u = Uri.parse(saved)
            var has = false
            for (perm in contentResolver.persistedUriPermissions) {
                if (perm.uri == u && perm.isReadPermission) has = true
            }
            if (has) {
                inputTree = u
                scanImages(u)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        val ed = prefs.edit()
        ed.putInt("batch", batchSize)
        ed.putInt("percol", perColumn)
        ed.putInt("breakw", breakWidth)
        val t = inputTree
        if (t != null) ed.putString("input_tree", t.toString())
        ed.apply()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && event.isCtrlPressed) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_O -> {
                    selectFolder()
                    return true
                }
                KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_ENTER -> {
                    onStartClicked()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun setStartEnabled(enabled: Boolean) {
        startBtn.isEnabled = enabled
        startBtn.alpha = if (enabled) 1f else 0.5f
    }

    private fun msg(title: String, text: String) {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(text)
            .setPositiveButton("OK", null)
            .show()
    }

    // =====================================================================
    // Settings / layout preview / info
    // =====================================================================

    private fun onSettingsChanged() {
        val b = batchEdit.text.toString().trim().toIntOrNull()
        val c = colEdit.text.toString().trim().toIntOrNull()
        if (b != null && c != null && b > 0 && c > 0) {
            batchSize = b
            perColumn = c
            updateLayoutPreview()
            updateBatchInfo()
        }
    }

    private fun onWidthChanged() {
        val w = widthEdit.text.toString().trim().toIntOrNull()
        if (w != null && w >= 0) breakWidth = w
    }

    private fun updateLayoutPreview() {
        val bs: Long = batchSize.toLong()
        val pc: Long = perColumn.toLong()
        val n: Long = (bs + pc - 1L) / pc
        val rem: Long = bs % pc
        val icon = "\uD83D\uDCCA"
        val text: String
        if (n == 1L) {
            text = "$icon Layout: 1 column with $bs images"
        } else if (rem == 0L) {
            text = "$icon Layout: $n columns with $pc images each"
        } else if (n <= 60L) {
            val parts = ArrayList<String>()
            var remaining = bs
            var col = 0L
            while (col < n) {
                val k = if (pc < remaining) pc else remaining
                parts.add(k.toString())
                remaining -= k
                col++
            }
            text = "$icon Layout: $n columns with [" + parts.joinToString(", ") + "] images respectively"
        } else {
            text = "$icon Layout: $n columns with $pc images each (last column: $rem)"
        }
        layoutLabel.text = text
    }

    private fun updateFolderInfo() {
        if (inputTree != null && images.isNotEmpty()) {
            folderLabel.text = "\uD83D\uDCC1 Selected: $folderName"
            folderLabel.setTextColor(cGreenLight)
            setStartEnabled(!processing)
        } else {
            folderLabel.text = "No valid images found in selected folder"
            folderLabel.setTextColor(cRed)
            setStartEnabled(false)
        }
    }

    private fun updateBatchInfo() {
        val list = images
        if (list.isEmpty()) {
            infoLabel.text = ""
            detailLabel.text = ""
            return
        }
        val total = list.size
        val numBatches = (total + batchSize - 1) / batchSize
        infoLabel.text =
            "\uD83D\uDCCA Found $total images \u2192 Will create $numBatches batches of up to $batchSize images each"

        val parts = ArrayList<String>()
        val shown = if (numBatches < 3) numBatches else 3
        for (i in 0 until shown) {
            val start = i * batchSize
            val end = if (start + batchSize < total) start + batchSize else total
            val firstName = list[start].name.substringBeforeLast('.')
            val lastName = list[end - 1].name.substringBeforeLast('.')
            val actual = end - start
            val cols = (actual + perColumn - 1) / perColumn
            parts.add("Batch ${i + 1}: $firstName-$lastName ($actual imgs, $cols cols)")
        }
        if (numBatches > 3) parts.add("... and ${numBatches - 3} more batches")
        detailLabel.text = parts.joinToString(" | ")
        detailLabel.setTextColor(cGrey2)
    }

    // =====================================================================
    // Folder selection + scanning
    // =====================================================================

    private fun selectFolder() {
        if (processing) return
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        i.putExtra("android.provider.extra.PROMPT", "Select Folder with Images")
        i.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        )
        startActivityForResult(i, REQ_IN)
    }

    private fun persist(uri: Uri, write: Boolean) {
        try {
            var f = Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (write) f = f or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, f)
        } catch (e: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        val uri = data.data ?: return
        if (requestCode == REQ_IN) {
            persist(uri, false)
            inputTree = uri
            prefs.edit().putString("input_tree", uri.toString()).apply()
            scanImages(uri)
        } else if (requestCode == REQ_OUT) {
            persist(uri, true)
            prefs.edit().putString("out_tree", uri.toString()).apply()
            runBatches(uri)
        }
    }

    private fun sortKey(name: String): Long {
        val n = name.length
        var i = 0
        while (i < n && !name[i].isDigit()) i++
        if (i >= n) return Long.MAX_VALUE
        var j = i
        while (j < n && name[j].isDigit()) j++
        val s = name.substring(i, j)
        return s.toLongOrNull() ?: (Long.MAX_VALUE - 1L)
    }

    private fun queryDisplayName(tree: Uri, docId: String): String {
        try {
            val u = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
            val c = contentResolver.query(
                u, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
            )
            if (c != null) {
                c.use {
                    if (it.moveToFirst()) {
                        val s = it.getString(0)
                        if (s != null) return s
                    }
                }
            }
        } catch (e: Exception) {
        }
        return ""
    }

    private fun scanImages(tree: Uri) {
        scanGen = scanGen + 1
        val gen = scanGen
        folderLabel.text = "Scanning folder..."
        folderLabel.setTextColor(cOrange)
        infoLabel.text = ""
        detailLabel.text = ""
        setStartEnabled(false)
        Thread {
            val found = ArrayList<ImgEntry>()
            var name = ""
            var error: String? = null
            try {
                val rootId = DocumentsContract.getTreeDocumentId(tree)
                name = queryDisplayName(tree, rootId)
                if (name.isBlank()) name = rootId.substringAfterLast('/').substringAfterLast(':')
                if (name.isBlank()) name = "images"
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId)
                val cursor = contentResolver.query(
                    children,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                    ),
                    null, null, null
                )
                if (cursor != null) {
                    cursor.use { c ->
                        while (c.moveToNext()) {
                            val id = c.getString(0)
                            val n = c.getString(1)
                            val mime = c.getString(2)
                            if (id == null || n == null) continue
                            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                            val ext = n.substringAfterLast('.', "").lowercase(Locale.ROOT)
                            if (IMAGE_EXTS.contains(ext)) found.add(ImgEntry(id, n, sortKey(n)))
                        }
                    }
                }
                found.sortWith(compareBy<ImgEntry>({ it.key }, { it.name }))
            } catch (e: Exception) {
                error = e.message
            }
            val err = error
            val finalName = name
            runOnUiThread {
                if (gen == scanGen) {
                    images = found
                    folderName = finalName
                    updateFolderInfo()
                    updateBatchInfo()
                    if (err != null) msg("Error", "Failed to scan folder: $err")
                    statusLabel.text = "Ready to select folder..."
                    statusLabel.setTextColor(cGrey)
                    progressView.setFraction(0f)
                }
            }
        }.start()
    }

    // =====================================================================
    // Processing
    // =====================================================================

    private fun onStartClicked() {
        if (processing) {
            cancelFlag = true
            startBtn.text = "Stopping..."
            return
        }
        if (images.isEmpty()) {
            msg("Warning", "No images found to process")
            return
        }
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        i.putExtra("android.provider.extra.PROMPT", "Select Output Directory for Merged Images")
        i.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        )
        val last = prefs.getString("out_tree", null)
        if (last != null) {
            try {
                i.putExtra("android.provider.extra.INITIAL_URI", Uri.parse(last))
            } catch (e: Exception) {
            }
        }
        startActivityForResult(i, REQ_OUT)
    }

    private fun waitFor(ref: AtomicReference<Future<*>?>) {
        val f = ref.get()
        if (f != null) {
            try {
                f.get()
            } catch (e: Exception) {
            }
        }
    }

    private fun listNames(tree: Uri): HashMap<String, String> {
        val map = HashMap<String, String>()
        try {
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            val u = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId)
            val c = contentResolver.query(
                u,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
                null, null, null
            )
            if (c != null) {
                c.use {
                    while (it.moveToNext()) {
                        val id = it.getString(0)
                        val n = it.getString(1)
                        if (id != null && n != null) map[n] = id
                    }
                }
            }
        } catch (e: Exception) {
        }
        return map
    }

    private fun readBounds(tree: Uri, docId: String): IntArray? {
        try {
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            val s = contentResolver.openInputStream(uri) ?: return null
            s.use { BitmapFactory.decodeStream(it, null, o) }
            if (o.outWidth > 0 && o.outHeight > 0) return intArrayOf(o.outWidth, o.outHeight)
        } catch (t: Throwable) {
        }
        return null
    }

    private fun decodeFull(tree: Uri, docId: String): Bitmap? {
        try {
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
            val o = BitmapFactory.Options()
            o.inPreferredConfig = Bitmap.Config.ARGB_8888
            val s = contentResolver.openInputStream(uri) ?: return null
            return s.use { BitmapFactory.decodeStream(it, null, o) }
        } catch (t: Throwable) {
        }
        return null
    }

    /**
     * Builds one merged bitmap for a batch (same layout logic as the Python version).
     * Only the merged bitmap + one decoded image are in memory at any moment.
     */
    private fun composeBatch(
        slice: List<ImgEntry>,
        tree: Uri,
        pc: Int,
        bw: Int,
        waitBig: () -> Unit
    ): Bitmap? {
        val valid = ArrayList<ImgEntry>()
        val ws = ArrayList<Int>()
        val hs = ArrayList<Int>()
        for (entry in slice) {
            val d = readBounds(tree, entry.docId)
            if (d != null) {
                valid.add(entry)
                ws.add(d[0])
                hs.add(d[1])
            }
        }
        if (valid.isEmpty()) return null
        val count = valid.size
        val numCols = (count + pc - 1) / pc

        val colW = IntArray(numCols)
        val colH = LongArray(numCols)
        for (c in 0 until numCols) {
            val s = c * pc
            val e = if (s + pc < count) s + pc else count
            var w = 0
            var h = 0L
            for (i in s until e) {
                if (ws[i] > w) w = ws[i]
                h += hs[i].toLong()
            }
            colW[c] = w
            colH[c] = h
        }

        var totalW = 0L
        for (w in colW) totalW += w.toLong()
        if (bw > 0 && numCols > 1) totalW += (numCols - 1).toLong() * bw.toLong()
        var maxH = 0L
        for (h in colH) if (h > maxH) maxH = h
        if (totalW <= 0L || maxH <= 0L) return null
        val bytes = totalW * maxH * 4L
        if (bytes > MAX_BYTES) return null
        if (bytes > BIG_BYTES) waitBig()

        val merged: Bitmap
        try {
            merged = Bitmap.createBitmap(totalW.toInt(), maxH.toInt(), Bitmap.Config.ARGB_8888)
        } catch (t: Throwable) {
            return null
        }
        merged.eraseColor(Color.WHITE)
        val canvas = Canvas(merged)
        val breakPaint = Paint()
        breakPaint.color = Color.BLACK
        breakPaint.style = Paint.Style.FILL

        var curX = 0
        for (c in 0 until numCols) {
            val s = c * pc
            val e = if (s + pc < count) s + pc else count
            var curY = ((maxH - colH[c]) / 2L).toInt()
            for (i in s until e) {
                val bmp = decodeFull(tree, valid[i].docId)
                val xPos = curX + (colW[c] - ws[i]) / 2
                if (bmp != null) {
                    canvas.drawBitmap(bmp, xPos.toFloat(), curY.toFloat(), null)
                    bmp.recycle()
                }
                curY += hs[i]
            }
            curX += colW[c]
            if (c < numCols - 1 && bw > 0) {
                canvas.drawRect(
                    curX.toFloat(), 0f, (curX + bw + 1).toFloat(), maxH.toFloat(), breakPaint
                )
                curX += bw
            }
        }
        return merged
    }

    private fun saveBitmap(
        bmp: Bitmap,
        outTree: Uri,
        outDir: Uri,
        name: String,
        existing: HashMap<String, String>
    ): Boolean {
        try {
            val old = existing[name]
            if (old != null) {
                try {
                    DocumentsContract.deleteDocument(
                        contentResolver, DocumentsContract.buildDocumentUriUsingTree(outTree, old)
                    )
                } catch (e: Exception) {
                }
                existing.remove(name)
            }
            val doc = DocumentsContract.createDocument(contentResolver, outDir, "image/png", name)
                ?: return false
            val os = contentResolver.openOutputStream(doc) ?: return false
            val buffered = BufferedOutputStream(os, 1 shl 16)
            val ok = bmp.compress(Bitmap.CompressFormat.PNG, 100, buffered)
            buffered.flush()
            buffered.close()
            return ok
        } catch (t: Throwable) {
            return false
        } finally {
            bmp.recycle()
        }
    }

    private fun runBatches(outTree: Uri) {
        val list = images
        val tree = inputTree ?: return
        val bs = batchSize
        val pc = perColumn
        val bw = breakWidth
        val folder = folderName.replace('/', '_')
        val total = list.size
        if (total == 0) return
        val numBatches = (total + bs - 1) / bs

        processing = true
        cancelFlag = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        startBtn.text = "\u26D4 Stop"
        statusLabel.text = "Processing $numBatches batches..."
        statusLabel.setTextColor(cOrange)
        detailLabel.text = ""
        progressView.setFraction(0f)

        Thread {
            val saver = Executors.newSingleThreadExecutor()
            val pendingRef = AtomicReference<Future<*>?>(null)
            val okCount = AtomicInteger(0)
            val badCount = AtomicInteger(0)
            var lastUi = 0L
            var stopped = false
            var outLabel = ""
            try {
                val outRoot = DocumentsContract.getTreeDocumentId(outTree)
                outLabel = queryDisplayName(outTree, outRoot)
                val outDir = DocumentsContract.buildDocumentUriUsingTree(outTree, outRoot)
                val existing = listNames(outTree)
                var maxNum = 0L
                for (k in existing.keys) {
                    val num = k.substringBeforeLast('.').toLongOrNull()
                    if (num != null && num > maxNum) maxNum = num
                }
                var nextNum = maxNum + 1L

                for (b in 0 until numBatches) {
                    if (cancelFlag) {
                        stopped = true
                        break
                    }
                    val start = b * bs
                    val end = if (start + bs < total) start + bs else total
                    val slice = list.subList(start, end)

                    val now = System.currentTimeMillis()
                    if (now - lastUi > 120L) {
                        lastUi = now
                        val first = slice[0].name.substringBeforeLast('.')
                        val last = slice[slice.size - 1].name.substringBeforeLast('.')
                        val frac = b.toFloat() / numBatches.toFloat()
                        runOnUiThread {
                            statusLabel.text = "Processing batch ${b + 1}/$numBatches ($first to $last)..."
                            statusLabel.setTextColor(cBlue)
                            progressView.setFraction(frac)
                        }
                    }

                    var bmp: Bitmap? = null
                    try {
                        bmp = composeBatch(slice, tree, pc, bw) { waitFor(pendingRef) }
                    } catch (t: Throwable) {
                        bmp = null
                    }
                    if (bmp == null) {
                        badCount.incrementAndGet()
                        continue
                    }
                    // at most one save in flight (keeps memory bounded)
                    waitFor(pendingRef)
                    val outName = String.format(Locale.US, "%d.png", nextNum)
                    nextNum += 1L
                    val toSave: Bitmap = bmp
                    pendingRef.set(
                        saver.submit(Runnable {
                            if (saveBitmap(toSave, outTree, outDir, outName, existing)) {
                                okCount.incrementAndGet()
                            } else {
                                badCount.incrementAndGet()
                            }
                        })
                    )
                }
            } catch (t: Throwable) {
                badCount.incrementAndGet()
            }
            waitFor(pendingRef)
            saver.shutdown()

            val okN = okCount.get()
            val badN = badCount.get()
            val wasStopped = stopped
            val label = outLabel
            runOnUiThread { finishProcessing(okN, badN, numBatches, wasStopped, label) }
        }.start()
    }

    private fun finishProcessing(ok: Int, bad: Int, num: Int, stopped: Boolean, outLabel: String) {
        processing = false
        cancelFlag = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        startBtn.text = "\uD83D\uDE80 Start Batch Merging"
        setStartEnabled(images.isNotEmpty())

        if (stopped) {
            statusLabel.text = "\u23F9 Stopped. $ok batches merged"
            statusLabel.setTextColor(cOrange)
            if (bad > 0) {
                detailLabel.text = "\u26A0\uFE0F $bad batches failed"
                detailLabel.setTextColor(cOrangeDark)
            } else {
                detailLabel.text = ""
            }
            return
        }

        progressView.setFraction(1f)
        if (ok > 0) {
            statusLabel.text = "\u2705 Completed! $ok batches merged successfully"
            statusLabel.setTextColor(cGreen)
            if (bad > 0) {
                detailLabel.text = "\u26A0\uFE0F $bad batches failed"
                detailLabel.setTextColor(cOrangeDark)
            } else {
                detailLabel.text = "All batches processed successfully! \uD83C\uDF89"
                detailLabel.setTextColor(cGrey2)
            }
            msg(
                "Batch Processing Complete",
                "Successfully processed $ok/$num batches!\n\nMerged images saved to:\n$outLabel"
            )
        } else {
            statusLabel.text = "\u274C All batches failed"
            statusLabel.setTextColor(cRed)
            detailLabel.text = "Images could not be decoded or the merged image is too large"
            detailLabel.setTextColor(cGrey2)
            msg("Error", "No batches could be processed successfully")
        }
    }
}
