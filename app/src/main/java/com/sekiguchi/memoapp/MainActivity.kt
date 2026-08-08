package com.sekiguchi.memoapp

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    // ---------- 画面1(テキストページ) ----------
    private lateinit var screen1Root: LinearLayout
    private lateinit var editor: EditText
    private lateinit var fileLabel: TextView
    private var currentUri: Uri? = null
    private var currentName: String = "無題"
    private var dirty = false
    private var printWebView: WebView? = null

    private val MAX_CHARS = 2_000_000

    private val REQ_OPEN = 1
    private val REQ_SAVE_AS = 2
    private val REQ_OPEN_IMG = 3
    private val REQ_SAVE_IMG = 4
    private val REQ_VOICE_DIR = 5
    private val REQ_MD_OPEN = 6
    private val REQ_MD_SAVE = 7
    private val REQ_PERM = 100

    // ---------- 保持スロット(テキスト) ----------
    private val SLOTS = 10
    private val ST_EMPTY = 0
    private val ST_HELD = 1
    private val ST_SECRET = 2

    class Slot(var state: Int = 0, var text: String = "")

    private val slots = Array(SLOTS) { Slot() }
    private var pwHash: String? = null

    // ---------- 画面2(保持ページ) ----------
    private var screen2Root: LinearLayout? = null
    private val rowChecks = arrayOfNulls<CheckBox>(SLOTS)
    private val rowBtns = arrayOfNulls<Button>(SLOTS)
    private val rowEdits = arrayOfNulls<EditText>(SLOTS)
    private var deleteMode2 = false
    private var deleteBtn2: Button? = null

    // ---------- 画面3(機密情報ページ) ----------
    private var deleteMode3 = false
    private var revealed = -1
    private val checks3 = HashMap<Int, CheckBox>()

    // ---------- 画面4(手書きページ) ----------
    private var screen4Root: LinearLayout? = null
    private lateinit var drawView: DrawView
    private var drawUri: Uri? = null
    private val DRAW_SLOTS = 5

    // ---------- 画面6(ボイスページ) ----------
    private var voiceStatusText: TextView? = null
    private var pendingMinutes = -1
    private val uiHandler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (currentScreen == 6) {
                updateVoiceStatus()
                uiHandler.postDelayed(this, 500)
            }
        }
    }
    private val recordReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (currentScreen == 6) showScreen(6)
        }
    }

    // ---------- 画面7/8/9(AI: プロジェクト管理) ----------
    private val PROJECTS = 30
    private val PLAN_MAX = 1000

    // MDファイル種別: 0=オントロジー 1=使い方
    private val MD_ONTO = 0
    private val MD_USAGE = 1
    private val MD_LABELS = arrayOf("オントロジー", "使い方")

    // ステータス: 0=未着手 1=推進 2=テスト中 3=完了
    private val STATUS_NAMES = arrayOf("未着手", "推進", "テスト中", "完了")
    private val STATUS_COLORS = intArrayOf(
        Color.parseColor("#9E9E9E"),
        Color.parseColor("#1565C0"),
        Color.parseColor("#EF6C00"),
        Color.parseColor("#2E7D32")
    )

    class Project(
        var name: String = "",
        var policy: String = "",
        var plan: String = "",
        var status: Int = 0,
        var ontoName: String = "",
        var usageName: String = ""
    )

    private var projectFilter = -1

    private val projects = Array(PROJECTS) { Project() }
    private var editIndex = -1
    private var viewIndex = -1
    private var pendingMdProject = -1
    private var pendingMdKind = -1
    private var mdEditIndex = -1
    private var ontoRowHolder: LinearLayout? = null
    private var usageRowHolder: LinearLayout? = null

    private var currentScreen = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadSlots()
        loadAi()
        buildScreen1()
        showScreen(1)

        val filter = IntentFilter(RecordService.BROADCAST)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(recordReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(recordReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(recordReceiver) } catch (_: Exception) { }
    }

    // ================= 画面1 =================
    private fun buildScreen1() {
        screen1Root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        // ヘッダー: ファイル名(左) + 手書き/ボイス(右・色付き)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fileLabel = TextView(this).apply {
            text = currentName
            setTypeface(null, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(8, 4, 8, 12)
        }
        header.addView(fileLabel, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        header.addView(makeColorButton("AI", Color.parseColor("#6A1B9A")) { showScreen(7) })
        header.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(24, 1)
        })
        header.addView(makeColorButton("手書き", Color.parseColor("#1565C0")) { showScreen(4) })
        header.addView(makeColorButton("ボイス", Color.parseColor("#C62828")) { openVoice() })
        screen1Root.addView(header)

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(makeButton("開く") { confirmIfDirty { pickOpen() } })
        row1.addView(makeButton("保存") { saveMenu() })
        row1.addView(makeButton("印刷") { printMemo() })
        screen1Root.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(makeButton("保持") { showScreen(2) })
        row2.addView(makeButton("クリア") { clearConfirm() })
        screen1Root.addView(row2)

        editor = EditText(this).apply {
            gravity = Gravity.TOP or Gravity.START
            setBackgroundColor(Color.parseColor("#FFFDF5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(24, 24, 24, 24)
            hint = "ここに文書を貼り付けて編集できます"
            inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = false
            setHorizontallyScrolling(false)
            minLines = 1
            maxLines = Integer.MAX_VALUE
            filters = arrayOf(InputFilter.LengthFilter(MAX_CHARS))
            isVerticalScrollBarEnabled = true
            isVerticalFadingEdgeEnabled = false
        }
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { dirty = true; updateLabel() }
        })
        screen1Root.addView(editor, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        updateLabel()
    }

    private fun makeButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
    }

    private fun makeColorButton(label: String, bg: Int, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.WHITE)
            setBackgroundColor(bg)
            setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            lp.marginStart = 8
            layoutParams = lp
            minWidth = 0
            minimumWidth = 0
            setPadding(18, 8, 18, 8)
        }
    }

    private fun updateLabel() {
        val text = editor.text
        var lines = 1
        for (i in 0 until text.length) if (text[i] == '\n') lines++
        val mark = if (dirty) " *" else ""
        fileLabel.text = "$currentName$mark   ${lines}行 / ${text.length}文字"
    }

    private fun clearConfirm() {
        AlertDialog.Builder(this)
            .setTitle("クリア")
            .setMessage("画面の内容をすべて消去します。よろしいですか?")
            .setPositiveButton("クリア") { _, _ ->
                editor.setText("")
                currentUri = null
                currentName = "無題"
                dirty = false
                updateLabel()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun saveMenu() {
        AlertDialog.Builder(this)
            .setTitle("保存方法を選択")
            .setItems(arrayOf("上書き保存", "名前をつけて保存")) { _, which ->
                if (which == 0) save() else pickSaveAs()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun pickOpen() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "text/*"))
        }
        startActivityForResult(intent, REQ_OPEN)
    }

    private fun save() {
        val uri = currentUri
        if (uri == null) { pickSaveAs(); return }
        try {
            contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(editor.text.toString().toByteArray(Charsets.UTF_8))
            }
            dirty = false
            updateLabel()
            Toast.makeText(this, "上書き保存しました", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "保存に失敗: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun pickSaveAs() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, if (currentName == "無題") "memo.txt" else currentName)
        }
        startActivityForResult(intent, REQ_SAVE_AS)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        if (requestCode == REQ_VOICE_DIR) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) { }
            getSharedPreferences(RecordService.PREFS, MODE_PRIVATE).edit()
                .putString(RecordService.KEY_DIR, uri.toString()).apply()
            Toast.makeText(this, "保存先を設定しました", Toast.LENGTH_SHORT).show()
            if (pendingMinutes >= 0) {
                val m = pendingMinutes
                pendingMinutes = -1
                actuallyStart(m)
            }
            return
        }

        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) { }

        when (requestCode) {
            REQ_OPEN -> {
                try {
                    val text = contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    } ?: ""
                    if (text.length > MAX_CHARS) {
                        Toast.makeText(this, "ファイルが大きすぎます(${MAX_CHARS}文字まで)", Toast.LENGTH_LONG).show()
                        return
                    }
                    editor.setText(text)
                    currentUri = uri
                    currentName = queryName(uri)
                    dirty = false
                    updateLabel()
                } catch (e: Exception) {
                    Toast.makeText(this, "開けませんでした: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            REQ_SAVE_AS -> {
                currentUri = uri
                currentName = queryName(uri)
                save()
            }
            REQ_OPEN_IMG -> {
                try {
                    val bmp = contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it)
                    }
                    if (bmp != null) {
                        drawView.setImage(bmp)
                        drawUri = uri
                        Toast.makeText(this, "画像を読み込みました。上から手書きできます", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "画像を読み込めませんでした", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "開けませんでした: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            REQ_SAVE_IMG -> {
                drawUri = uri
                writeDrawing(uri)
            }
            REQ_MD_OPEN -> {
                val prj = pendingMdProject
                val kind = pendingMdKind
                pendingMdProject = -1
                pendingMdKind = -1
                if (prj < 0 || kind < 0) return
                try {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes == null) {
                        Toast.makeText(this, "ファイルを読み込めませんでした", Toast.LENGTH_LONG).show()
                        return
                    }
                    mdFile(prj, kind).outputStream().use { it.write(bytes) }
                    val nm = queryName(uri)
                    if (kind == MD_ONTO) projects[prj].ontoName = nm else projects[prj].usageName = nm
                    saveAi()
                    refreshMdRow(kind)
                    Toast.makeText(this, "${MD_LABELS[kind]}を保存しました", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "保存に失敗: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            REQ_MD_SAVE -> {
                val prj = pendingMdProject
                val kind = pendingMdKind
                pendingMdProject = -1
                pendingMdKind = -1
                if (prj < 0 || kind < 0) return
                try {
                    val f = mdFile(prj, kind)
                    if (!f.exists()) return
                    contentResolver.openOutputStream(uri, "wt")?.use { out ->
                        f.inputStream().use { input -> input.copyTo(out) }
                    }
                    Toast.makeText(this, "ダウンロードしました", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "ダウンロードに失敗: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun queryName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) return c.getString(i)
        }
        return uri.lastPathSegment ?: "無題"
    }

    private fun printMemo() {
        val text = editor.text.toString()
        if (text.isBlank()) {
            Toast.makeText(this, "文書が空です", Toast.LENGTH_SHORT).show()
            return
        }
        val escaped = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        val html = """
            <html><head><meta charset="utf-8"></head>
            <body style="margin:24px;">
            <pre style="white-space:pre-wrap;word-wrap:break-word;
                        font-family:sans-serif;font-size:13pt;line-height:1.6;">$escaped</pre>
            </body></html>
        """.trimIndent()
        printHtml(html, currentName.removeSuffix(".txt"))
    }

    private fun printHtml(html: String, jobName: String) {
        val webView = WebView(this)
        printWebView = webView
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                val pm = getSystemService(PRINT_SERVICE) as PrintManager
                pm.print(
                    jobName,
                    view.createPrintDocumentAdapter(jobName),
                    PrintAttributes.Builder()
                        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                        .build()
                )
                printWebView = null
            }
        }
        webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    private fun confirmIfDirty(action: () -> Unit) {
        if (!dirty || editor.text.isBlank()) { action(); return }
        AlertDialog.Builder(this)
            .setMessage("未保存の変更があります。破棄しますか?")
            .setPositiveButton("破棄") { _, _ -> action() }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    // ================= 画面2(保持ページ) =================
    private fun buildScreen2(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        val title = TextView(this).apply {
            text = "保持ページ"
            setTypeface(null, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(8, 4, 8, 12)
        }
        root.addView(title)

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(makeButton("保持") { holdRows() })
        deleteBtn2 = makeButton("削除") { deleteAction2() }
        btnRow.addView(deleteBtn2)
        btnRow.addView(makeButton("機密") { secretAction() })
        btnRow.addView(makeButton("戻る") { showScreen(1) })
        root.addView(btnRow)

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 12, 0, 12)
        }

        for (i in 0 until SLOTS) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 6, 0, 6)
            }

            val cb = CheckBox(this).apply { visibility = View.GONE }
            rowChecks[i] = cb
            row.addView(cb)

            val btn = Button(this).apply {
                visibility = View.GONE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                minWidth = 0
                minimumWidth = 0
                setPadding(20, 0, 20, 0)
            }
            rowBtns[i] = btn
            row.addView(btn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

            val label = TextView(this).apply {
                text = "保持${i + 1}"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(8, 0, 8, 0)
            }
            row.addView(label)

            val edit = EditText(this).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                inputType = InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                isSingleLine = false
                maxLines = 3
                filters = arrayOf(InputFilter.LengthFilter(MAX_CHARS))
                hint = "ここに貼り付け"
            }
            rowEdits[i] = edit
            row.addView(edit, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

            list.addView(row)
        }

        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        return root
    }

    private fun refreshRows2() {
        for (i in 0 until SLOTS) {
            val slot = slots[i]
            val cb = rowChecks[i]!!
            val btn = rowBtns[i]!!
            val edit = rowEdits[i]!!

            if (deleteMode2 && slot.state == ST_HELD) {
                cb.visibility = View.VISIBLE
            } else {
                cb.visibility = View.GONE
                cb.isChecked = false
            }

            when (slot.state) {
                ST_EMPTY -> {
                    btn.visibility = View.GONE
                    edit.isEnabled = true
                    edit.isFocusable = true
                    edit.isFocusableInTouchMode = true
                    edit.setTextColor(Color.BLACK)
                }
                ST_HELD -> {
                    btn.visibility = View.VISIBLE
                    btn.text = "コピー"
                    btn.setOnClickListener { copyToClipboard(slot.text) }
                    if (edit.text.toString() != slot.text) edit.setText(slot.text)
                    lockEdit(edit)
                }
                ST_SECRET -> {
                    btn.visibility = View.VISIBLE
                    btn.text = "機密情報"
                    btn.setOnClickListener { secretInfoAction(i) }
                    edit.setText("●●●●●●")
                    lockEdit(edit)
                }
            }
        }
        deleteBtn2?.text = if (deleteMode2) "削除実行" else "削除"
    }

    private fun lockEdit(edit: EditText) {
        edit.isEnabled = false
        edit.isFocusable = false
        edit.setTextColor(Color.parseColor("#37474F"))
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("memo", text))
        Toast.makeText(this, "コピーしました", Toast.LENGTH_SHORT).show()
    }

    private fun holdRows() {
        var count = 0
        for (i in 0 until SLOTS) {
            if (slots[i].state == ST_EMPTY) {
                val t = rowEdits[i]!!.text.toString()
                if (t.isNotBlank()) {
                    slots[i].state = ST_HELD
                    slots[i].text = t
                    count++
                }
            }
        }
        if (count == 0) {
            Toast.makeText(this, "保持する入力がありません", Toast.LENGTH_SHORT).show()
        } else {
            saveSlots()
            Toast.makeText(this, "${count}行を保持しました", Toast.LENGTH_SHORT).show()
        }
        refreshRows2()
    }

    private fun deleteAction2() {
        if (!deleteMode2) {
            if (slots.none { it.state == ST_HELD }) {
                Toast.makeText(this, "削除できる保持行がありません", Toast.LENGTH_SHORT).show()
                return
            }
            deleteMode2 = true
            Toast.makeText(this, "削除する行を選択して、もう一度削除を押してください", Toast.LENGTH_LONG).show()
        } else {
            var count = 0
            for (i in 0 until SLOTS) {
                if (slots[i].state == ST_HELD && rowChecks[i]!!.isChecked) {
                    resetSlot(i)
                    count++
                }
            }
            deleteMode2 = false
            if (count > 0) {
                saveSlots()
                Toast.makeText(this, "${count}行を初期化しました", Toast.LENGTH_SHORT).show()
            }
        }
        refreshRows2()
    }

    private fun resetSlot(i: Int) {
        slots[i].state = ST_EMPTY
        slots[i].text = ""
        rowEdits[i]?.setText("")
        if (slots.none { it.state == ST_SECRET }) pwHash = null
    }

    private fun secretAction() {
        val candidates = (0 until SLOTS).filter {
            slots[it].state == ST_EMPTY && rowEdits[it]!!.text.toString().isNotBlank()
        }
        when {
            candidates.isEmpty() -> {
                Toast.makeText(this, "機密登録する入力がありません", Toast.LENGTH_SHORT).show()
            }
            candidates.size >= 2 -> {
                AlertDialog.Builder(this)
                    .setTitle("エラー")
                    .setMessage("機密登録は1行ずつ行ってください。入力を1行のみにしてください。")
                    .setPositiveButton("OK", null)
                    .show()
            }
            else -> {
                val i = candidates[0]
                if (pwHash == null) {
                    inputDialog(
                        title = "パスワード設定",
                        message = "4桁の数字を入力してください",
                        numeric = true,
                        positive = "保存"
                    ) { input, d ->
                        if (!Regex("^\\d{4}$").matches(input)) {
                            Toast.makeText(this, "4桁の数字を入力してください", Toast.LENGTH_SHORT).show()
                        } else {
                            pwHash = sha(input)
                            registerSecret(i)
                            d.dismiss()
                        }
                    }
                } else {
                    inputDialog(
                        title = "パスワード確認",
                        message = "登録済みの4桁パスワードを入力してください",
                        numeric = true,
                        positive = "OK"
                    ) { input, d ->
                        if (sha(input) == pwHash) {
                            registerSecret(i)
                            d.dismiss()
                        } else {
                            Toast.makeText(this, "パスワードが違います", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    private fun registerSecret(i: Int) {
        slots[i].text = rowEdits[i]!!.text.toString()
        slots[i].state = ST_SECRET
        saveSlots()
        refreshRows2()
        Toast.makeText(this, "保持${i + 1}を機密登録しました", Toast.LENGTH_SHORT).show()
    }

    private fun secretInfoAction(i: Int) {
        inputDialog(
            title = "パスワード入力",
            message = "4桁のパスワードを入力してください",
            numeric = true,
            positive = "OK",
            neutral = "完全削除",
            onNeutral = { hardDeleteConfirm(i) }
        ) { input, d ->
            if (sha(input) == pwHash) {
                d.dismiss()
                revealed = i
                showScreen(3)
            } else {
                Toast.makeText(this, "パスワードが違います", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun hardDeleteConfirm(i: Int) {
        inputDialog(
            title = "完全削除の確認",
            message = "パスワードなしで保持${i + 1}を初期化します。\n確認のため Delete と入力してください",
            numeric = false,
            positive = "OK"
        ) { input, d ->
            if (input == "Delete") {
                resetSlot(i)
                saveSlots()
                refreshRows2()
                d.dismiss()
                Toast.makeText(this, "保持${i + 1}を完全削除しました", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "「Delete」と正確に入力してください", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ================= 画面3(機密情報ページ) =================
    private fun buildScreen3(): LinearLayout {
        deleteMode3 = false
        checks3.clear()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        val title = TextView(this).apply {
            text = "機密情報ページ"
            setTypeface(null, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(8, 4, 8, 12)
        }
        root.addView(title)

        lateinit var delBtn: Button
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        delBtn = makeButton("削除") { }
        btnRow.addView(delBtn)
        btnRow.addView(makeButton("戻る") { showScreen(1) })
        root.addView(btnRow)

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 12, 0, 12)
        }

        val secretRows = (0 until SLOTS).filter { slots[it].state == ST_SECRET }
        if (secretRows.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "機密情報はありません"
                setPadding(8, 16, 8, 16)
            })
        }
        for (i in secretRows) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
            }
            val shown = (i == revealed)

            val cb = CheckBox(this).apply { visibility = View.GONE }
            if (shown) checks3[i] = cb
            row.addView(cb)

            if (shown) {
                val copyBtn = Button(this).apply {
                    text = "コピー"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    minWidth = 0
                    minimumWidth = 0
                    setPadding(20, 0, 20, 0)
                    setOnClickListener { copyToClipboard(slots[i].text) }
                }
                row.addView(copyBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            }

            val tv = TextView(this).apply {
                text = if (shown) "保持${i + 1}: ${slots[i].text}"
                       else "保持${i + 1}: ●●●●（ロック中）"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(12, 0, 8, 0)
            }
            row.addView(tv, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

            list.addView(row)
        }

        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        delBtn.setOnClickListener {
            if (!deleteMode3) {
                if (checks3.isEmpty()) {
                    Toast.makeText(this, "削除できるのは文字列が表示されている行のみです", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                deleteMode3 = true
                delBtn.text = "削除実行"
                checks3.values.forEach { it.visibility = View.VISIBLE }
                Toast.makeText(this, "削除する行を選択して、もう一度削除を押してください", Toast.LENGTH_LONG).show()
            } else {
                var count = 0
                for ((idx, cb) in checks3) {
                    if (cb.isChecked) {
                        resetSlot(idx)
                        count++
                    }
                }
                if (count > 0) {
                    saveSlots()
                    Toast.makeText(this, "${count}行を初期化しました", Toast.LENGTH_SHORT).show()
                }
                showScreen(3)
            }
        }

        return root
    }

    // ================= 画面4(手書きページ) =================
    private fun buildScreen4(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        val title = TextView(this).apply {
            text = "手書きページ"
            setTypeface(null, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(8, 4, 8, 12)
        }
        root.addView(title)

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(makeButton("開く") { pickOpenImage() })
        row1.addView(makeButton("保存") { drawSaveMenu() })
        row1.addView(makeButton("テキスト") { showScreen(1) })
        root.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(makeButton("印刷") { printDrawing() })
        row2.addView(makeButton("保持") { holdDrawing() })
        row2.addView(makeButton("初期化") { clearDrawingConfirm() })
        root.addView(row2)

        drawView = DrawView(this)
        root.addView(drawView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        return root
    }

    private fun pickOpenImage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        startActivityForResult(intent, REQ_OPEN_IMG)
    }

    private fun drawSaveMenu() {
        if (!drawView.hasContent) {
            Toast.makeText(this, "手書きスペースが空です", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("保存方法を選択")
            .setItems(arrayOf("上書き保存", "名前をつけて保存")) { _, which ->
                if (which == 0) {
                    val uri = drawUri
                    if (uri == null) pickSaveImage() else writeDrawing(uri)
                } else {
                    pickSaveImage()
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun pickSaveImage() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/png"
            putExtra(Intent.EXTRA_TITLE, "tegaki.png")
        }
        startActivityForResult(intent, REQ_SAVE_IMG)
    }

    private fun writeDrawing(uri: Uri) {
        try {
            val bmp = drawView.getBitmap() ?: return
            contentResolver.openOutputStream(uri, "wt")?.use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            Toast.makeText(this, "保存しました", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "保存に失敗: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun printDrawing() {
        val bmp = drawView.getBitmap()
        if (bmp == null || !drawView.hasContent) {
            Toast.makeText(this, "手書きスペースが空です", Toast.LENGTH_SHORT).show()
            return
        }
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
        val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        val html = """
            <html><body style="margin:0;">
            <img style="width:100%;" src="data:image/png;base64,$b64"/>
            </body></html>
        """.trimIndent()
        printHtml(html, "手書き")
    }

    private fun clearDrawingConfirm() {
        if (!drawView.hasContent) return
        AlertDialog.Builder(this)
            .setMessage("手書きの内容を消去します。よろしいですか?")
            .setPositiveButton("初期化") { _, _ ->
                drawView.clear()
                drawUri = null
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun drawHoldFile(i: Int) = File(filesDir, "draw_hold_$i.png")

    private fun holdDrawing() {
        if (!drawView.hasContent) {
            Toast.makeText(this, "手書きスペースが空です", Toast.LENGTH_SHORT).show()
            return
        }
        val free = (0 until DRAW_SLOTS).firstOrNull { !drawHoldFile(it).exists() }
        if (free == null) {
            AlertDialog.Builder(this)
                .setTitle("エラー")
                .setMessage("手書きの保持は5つまでです。保持画面で不要なものを削除してください。")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        try {
            val bmp = drawView.getBitmap() ?: return
            drawHoldFile(free).outputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            Toast.makeText(this, "保持${free + 1}に保存しました", Toast.LENGTH_SHORT).show()
            showScreen(5)
        } catch (e: Exception) {
            Toast.makeText(this, "保持に失敗: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ================= 画面5(手書き保持ページ) =================
    private fun buildScreen5(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        val title = TextView(this).apply {
            text = "手書き保持ページ"
            setTypeface(null, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(8, 4, 8, 12)
        }
        root.addView(title)

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(makeButton("戻る") { showScreen(4) })
        root.addView(btnRow)

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 12, 0, 12)
        }

        for (i in 0 until DRAW_SLOTS) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
            }

            val label = TextView(this).apply {
                text = "保持${i + 1}"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(4, 0, 8, 0)
            }
            row.addView(label)

            val f = drawHoldFile(i)
            if (f.exists()) {
                val img = ImageView(this).apply {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    setImageBitmap(BitmapFactory.decodeFile(f.absolutePath, opts))
                    setBackgroundColor(Color.parseColor("#EEEEEE"))
                    adjustViewBounds = true
                }
                row.addView(img, LinearLayout.LayoutParams(0, 300, 1f))

                val delBtn = Button(this).apply {
                    text = "削除"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    minWidth = 0; minimumWidth = 0
                    setPadding(20, 0, 20, 0)
                    setOnClickListener {
                        f.delete()
                        showScreen(5)
                        Toast.makeText(this@MainActivity, "保持${i + 1}を削除しました", Toast.LENGTH_SHORT).show()
                    }
                }
                row.addView(delBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

                val moveBtn = Button(this).apply {
                    text = "移す"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    minWidth = 0; minimumWidth = 0
                    setPadding(20, 0, 20, 0)
                    setOnClickListener {
                        val bmp = BitmapFactory.decodeFile(f.absolutePath)
                        if (bmp != null) {
                            drawView.setImage(bmp)
                            showScreen(4)
                            Toast.makeText(this@MainActivity, "保持${i + 1}を手書き画面に移しました", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                row.addView(moveBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            } else {
                val empty = TextView(this).apply {
                    text = "（空き）"
                    setTextColor(Color.parseColor("#9E9E9E"))
                    setPadding(8, 24, 8, 24)
                }
                row.addView(empty, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }

            list.addView(row)
        }

        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        return root
    }

    // ================= 画面6(ボイスページ) =================
    private fun openVoice() {
        if (!hasMicPermission()) { requestVoicePerms(); return }
        showScreen(6)
    }

    private fun hasMicPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestVoicePerms() {
        val perms = if (Build.VERSION.SDK_INT >= 33)
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        else
            arrayOf(Manifest.permission.RECORD_AUDIO)
        requestPermissions(perms, REQ_PERM)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERM) {
            if (hasMicPermission()) {
                showScreen(6)
            } else {
                Toast.makeText(this, "録音にはマイク権限が必要です", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun buildScreen6(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "ボイスレコード"
            setTypeface(null, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(8, 4, 8, 8)
        }
        header.addView(title, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val backBtn = Button(this).apply {
            text = "戻る"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setOnClickListener { showScreen(1) }
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        }
        header.addView(backBtn)
        root.addView(header)

        voiceStatusText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(null, Typeface.BOLD)
            setPadding(8, 12, 8, 16)
        }
        root.addView(voiceStatusText)

        val ctrlRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ctrlRow.addView(makeColorButton("スタート", Color.parseColor("#2E7D32")) { startRec(60) })
        ctrlRow.addView(makeColorButton("ストップ", Color.parseColor("#C62828")) { stopRec() })
        root.addView(ctrlRow)

        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 4)
        }
        timeRow.addView(makeButton("1分") { startRec(1) })
        timeRow.addView(makeButton("5分") { startRec(5) })
        timeRow.addView(makeButton("15分") { startRec(15) })
        timeRow.addView(makeButton("60分") { startRec(60) })
        root.addView(timeRow)

        val histTitle = TextView(this).apply {
            text = "保存履歴（1日で自動クリア）"
            setTypeface(null, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(8, 20, 8, 8)
        }
        root.addView(histTitle)

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 4, 0, 4)
        }

        val entries = loadHistoryPruned()
        if (entries.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "履歴はありません"
                setTextColor(Color.parseColor("#9E9E9E"))
                setPadding(8, 12, 8, 12)
            })
        }
        val fmt = SimpleDateFormat("MM/dd HH:mm", Locale.JAPAN)
        for (e in entries) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 6, 0, 6)
            }
            val tv = TextView(this).apply {
                text = "${fmt.format(Date(e.getLong("t")))}  ${e.getString("n")}"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(8, 0, 8, 0)
            }
            row.addView(tv, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

            val playBtn = Button(this).apply {
                text = "再生"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                minWidth = 0; minimumWidth = 0
                setPadding(20, 0, 20, 0)
                setOnClickListener {
                    try {
                        val play = Intent(Intent.ACTION_VIEW)
                        play.setDataAndType(Uri.parse(e.getString("u")), "audio/mp4")
                        play.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        startActivity(play)
                    } catch (ex: Exception) {
                        Toast.makeText(this@MainActivity, "再生アプリが見つかりません", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            row.addView(playBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

            list.addView(row)
        }

        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        updateVoiceStatus()
        return root
    }

    private fun updateVoiceStatus() {
        val tv = voiceStatusText ?: return
        if (RecordService.isRecording) {
            val sec = ((System.currentTimeMillis() - RecordService.startTime) / 1000).toInt()
            val mm = sec / 60
            val ss = sec % 60
            tv.text = "● 録音中  %02d:%02d".format(mm, ss)
            tv.setTextColor(Color.parseColor("#C62828"))
        } else {
            tv.text = "停止中"
            tv.setTextColor(Color.parseColor("#37474F"))
        }
    }

    private fun startRec(minutes: Int) {
        if (RecordService.isRecording) {
            Toast.makeText(this, "すでに録音中です", Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasMicPermission()) { requestVoicePerms(); return }
        val dir = getSharedPreferences(RecordService.PREFS, MODE_PRIVATE)
            .getString(RecordService.KEY_DIR, null)
        if (dir == null) {
            pendingMinutes = minutes
            Toast.makeText(this, "録音の保存先フォルダを選んでください", Toast.LENGTH_LONG).show()
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_VOICE_DIR)
            return
        }
        actuallyStart(minutes)
    }

    private fun actuallyStart(minutes: Int) {
        val i = Intent(this, RecordService::class.java).apply {
            action = RecordService.ACTION_START
            putExtra(RecordService.EXTRA_MINUTES, minutes)
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        Toast.makeText(this, "録音を開始しました（最大${minutes}分）", Toast.LENGTH_SHORT).show()
        uiHandler.postDelayed({ updateVoiceStatus() }, 300)
    }

    private fun stopRec() {
        if (!RecordService.isRecording) {
            Toast.makeText(this, "録音していません", Toast.LENGTH_SHORT).show()
            return
        }
        val i = Intent(this, RecordService::class.java).apply { action = RecordService.ACTION_STOP }
        startService(i)
        Toast.makeText(this, "停止して保存します", Toast.LENGTH_SHORT).show()
    }

    // 履歴を読み込み、1日以上前の項目を除去して書き戻す
    private fun loadHistoryPruned(): List<JSONObject> {
        val sp = getSharedPreferences(RecordService.PREFS, MODE_PRIVATE)
        val raw = sp.getString(RecordService.KEY_HIST, "[]")
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        val kept = JSONArray()
        val out = ArrayList<JSONObject>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.getLong("t") >= cutoff) {
                    kept.put(o)
                    out.add(o)
                }
            }
        } catch (_: Exception) { }
        sp.edit().putString(RecordService.KEY_HIST, kept.toString()).apply()
        out.sortByDescending { it.getLong("t") }
        return out
    }

    // ================= 画面7(AI: プロジェクト) =================
    private fun mdFile(prj: Int, kind: Int) =
        File(filesDir, if (kind == MD_ONTO) "proj_${prj}_onto.md" else "proj_${prj}_usage.md")

    private fun buildScreen7(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "AI"
            setTypeface(null, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(8, 4, 8, 8)
        }
        header.addView(title, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val backBtn = Button(this).apply {
            text = "戻る"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setOnClickListener { showScreen(1) }
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        }
        header.addView(backBtn)
        root.addView(header)

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 4, 0, 8)
        }

        list.addView(sectionLabel("プロジェクト"))
        list.addView(filterRow())

        val registered = (0 until PROJECTS).filter { projects[it].name.isNotBlank() }
        val shown =
            if (projectFilter < 0) registered
            else registered.filter { projects[it].status == projectFilter }

        if (shown.isEmpty()) {
            list.addView(TextView(this).apply {
                text = if (registered.isEmpty()) "登録されたプロジェクトはありません"
                       else "この状態のプロジェクトはありません"
                setTextColor(Color.parseColor("#9E9E9E"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(8, 12, 8, 12)
            })
        }
        for (i in shown) list.addView(projectRow(i))

        val free = (0 until PROJECTS).firstOrNull { projects[it].name.isBlank() }
        val freeCount = (0 until PROJECTS).count { projects[it].name.isBlank() }
        val addRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 10, 0, 4)
        }
        val addInfo = TextView(this).apply {
            text = "登録済み ${registered.size} / $PROJECTS 件"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.parseColor("#616161"))
            setPadding(8, 0, 8, 0)
        }
        addRow.addView(addInfo, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (free != null) {
            addRow.addView(smallButton("新規登録") { editIndex = free; showScreen(8) })
        } else {
            addRow.addView(TextView(this).apply {
                text = "空き枠なし"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Color.parseColor("#9E9E9E"))
                setPadding(8, 0, 8, 0)
            })
        }
        list.addView(addRow)

        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        return root
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        setTypeface(null, Typeface.BOLD)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(Color.parseColor("#37474F"))
        setPadding(8, 20, 8, 8)
    }

    private fun smallButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        minWidth = 0; minimumWidth = 0
        setPadding(20, 0, 20, 0)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
    }

    private fun filterRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 4)
        }
        fun chip(label: String, value: Int) {
            val selected = (projectFilter == value)
            val b = Button(this).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                minWidth = 0; minimumWidth = 0
                setPadding(4, 0, 4, 0)
                if (selected) {
                    setBackgroundColor(
                        if (value < 0) Color.parseColor("#37474F") else STATUS_COLORS[value]
                    )
                    setTextColor(Color.WHITE)
                }
                setOnClickListener {
                    projectFilter = value
                    showScreen(7)
                }
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            }
            row.addView(b)
        }
        chip("すべて", -1)
        for (s in 0 until STATUS_NAMES.size) chip(STATUS_NAMES[s], s)
        return row
    }

    private fun projectRow(i: Int): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 6, 0, 6)
        }
        val p = projects[i]

        val statusBtn = Button(this).apply {
            text = STATUS_NAMES[p.status]
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(Color.WHITE)
            setBackgroundColor(STATUS_COLORS[p.status])
            minWidth = 0; minimumWidth = 0
            setPadding(12, 0, 12, 0)
            setOnClickListener { pickStatus(i) }
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        }
        row.addView(statusBtn)

        val tv = TextView(this).apply {
            text = p.name
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(8, 0, 8, 0)
        }
        row.addView(tv, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        row.addView(smallButton("閲覧") { viewIndex = i; showScreen(9) })
        row.addView(smallButton("編集") { editIndex = i; showScreen(8) })
        return row
    }

    private fun pickStatus(i: Int) {
        AlertDialog.Builder(this)
            .setTitle("状態を選択")
            .setItems(STATUS_NAMES) { _, which ->
                projects[i].status = which
                saveAi()
                showScreen(7)
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    // ---------- MDファイル行(オントロジー / 使い方) ----------
    private fun mdHolder(kind: Int): LinearLayout? =
        if (kind == MD_ONTO) ontoRowHolder else usageRowHolder

    private fun mdName(prj: Int, kind: Int): String =
        if (kind == MD_ONTO) projects[prj].ontoName else projects[prj].usageName

    private fun refreshMdRow(kind: Int) {
        if (currentScreen == 8) fillMdRow(kind)
    }

    private fun fillMdRow(kind: Int) {
        val holder = mdHolder(kind) ?: return
        val prj = mdEditIndex
        holder.removeAllViews()
        if (prj !in 0 until PROJECTS) return

        val name = mdName(prj, kind)
        val exists = name.isNotBlank() && mdFile(prj, kind).exists()

        val tv = TextView(this).apply {
            text = if (exists) name else "未アップロード"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(if (exists) Color.parseColor("#37474F") else Color.parseColor("#9E9E9E"))
            setPadding(8, 0, 8, 0)
        }
        holder.addView(tv, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        if (exists) {
            holder.addView(smallButton("DL") { downloadMd(prj, kind) })
            holder.addView(smallButton("削除") { deleteMdConfirm(prj, kind) })
        } else {
            holder.addView(smallButton("アップロード") { pickMd(prj, kind) })
        }
    }

    private fun pickMd(prj: Int, kind: Int) {
        pendingMdProject = prj
        pendingMdKind = kind
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/markdown", "text/plain", "text/*"))
        }
        startActivityForResult(intent, REQ_MD_OPEN)
    }

    private fun downloadMd(prj: Int, kind: Int) {
        if (!mdFile(prj, kind).exists()) {
            Toast.makeText(this, "ファイルがありません", Toast.LENGTH_SHORT).show()
            return
        }
        pendingMdProject = prj
        pendingMdKind = kind
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/markdown"
            putExtra(Intent.EXTRA_TITLE, mdName(prj, kind).ifBlank { "${MD_LABELS[kind]}.md" })
        }
        startActivityForResult(intent, REQ_MD_SAVE)
    }

    private fun deleteMdConfirm(prj: Int, kind: Int) {
        AlertDialog.Builder(this)
            .setTitle("削除の確認")
            .setMessage("${MD_LABELS[kind]}「${mdName(prj, kind)}」を削除します。よろしいですか?")
            .setPositiveButton("削除") { _, _ ->
                mdFile(prj, kind).delete()
                if (kind == MD_ONTO) projects[prj].ontoName = "" else projects[prj].usageName = ""
                saveAi()
                fillMdRow(kind)
                Toast.makeText(this, "${MD_LABELS[kind]}を削除しました", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    // ================= 画面8(プロジェクト登録・編集) =================
    private fun buildScreen8(): LinearLayout {
        val i = editIndex
        val p = if (i in 0 until PROJECTS) projects[i] else Project()
        val registered = p.name.isNotBlank()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        val title = TextView(this).apply {
            text = if (registered) "プロジェクト編集" else "プロジェクト登録"
            setTypeface(null, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(8, 4, 8, 12)
        }
        root.addView(title)

        val nameEdit = EditText(this).apply {
            setText(p.name)
            hint = "プロジェクト名"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val policyEdit = EditText(this).apply {
            setText(p.policy)
            hint = "方針（文字数制限なし）"
            gravity = Gravity.TOP or Gravity.START
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setBackgroundColor(Color.parseColor("#FFFDF5"))
            setPadding(16, 16, 16, 16)
            inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = false
            setHorizontallyScrolling(false)
            minLines = 4
            maxLines = Integer.MAX_VALUE
        }
        val planLabel = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.parseColor("#616161"))
            setPadding(8, 12, 8, 4)
        }
        val planEdit = EditText(this).apply {
            setText(p.plan)
            hint = "進捗（1000文字まで）"
            gravity = Gravity.TOP or Gravity.START
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setBackgroundColor(Color.parseColor("#FFFDF5"))
            setPadding(16, 16, 16, 16)
            inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = false
            setHorizontallyScrolling(false)
            minLines = 4
            maxLines = Integer.MAX_VALUE
            filters = arrayOf(InputFilter.LengthFilter(PLAN_MAX))
        }
        planLabel.text = "進捗  ${planEdit.text.length} / $PLAN_MAX 文字"
        planEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                planLabel.text = "進捗  ${s?.length ?: 0} / $PLAN_MAX 文字"
            }
        })

        val statusHolder = intArrayOf(p.status)
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 0)
        }
        val statusBtns = arrayOfNulls<Button>(STATUS_NAMES.size)
        fun paintStatus() {
            for (s in STATUS_NAMES.indices) {
                val b = statusBtns[s] ?: continue
                if (statusHolder[0] == s) {
                    b.setBackgroundColor(STATUS_COLORS[s])
                    b.setTextColor(Color.WHITE)
                } else {
                    b.setBackgroundColor(Color.parseColor("#E0E0E0"))
                    b.setTextColor(Color.parseColor("#37474F"))
                }
            }
        }
        for (s in STATUS_NAMES.indices) {
            val b = Button(this).apply {
                text = STATUS_NAMES[s]
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                minWidth = 0; minimumWidth = 0
                setPadding(4, 0, 4, 0)
                setOnClickListener { statusHolder[0] = s; paintStatus() }
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            }
            statusBtns[s] = b
            statusRow.addView(b)
        }
        paintStatus()

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(makeButton("保存") {
            val n = nameEdit.text.toString().trim()
            if (i !in 0 until PROJECTS) {
                showScreen(7)
            } else if (n.isBlank()) {
                Toast.makeText(this, "プロジェクト名を入力してください", Toast.LENGTH_SHORT).show()
            } else {
                projects[i].name = n
                projects[i].policy = policyEdit.text.toString()
                projects[i].plan = planEdit.text.toString()
                projects[i].status = statusHolder[0]
                saveAi()
                showScreen(7)
                Toast.makeText(this, "保存しました", Toast.LENGTH_SHORT).show()
            }
        })
        btnRow.addView(makeButton("戻る") { showScreen(7) })
        if (registered) {
            btnRow.addView(makeButton("削除") { deleteProjectConfirm(i) })
        }
        root.addView(btnRow)

        val scroll = ScrollView(this)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 12, 0, 12)
        }
        form.addView(TextView(this).apply {
            text = "プロジェクト名"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.parseColor("#616161"))
            setPadding(8, 0, 8, 4)
        })
        form.addView(nameEdit)
        form.addView(TextView(this).apply {
            text = "状態"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.parseColor("#616161"))
            setPadding(8, 12, 8, 4)
        })
        form.addView(statusRow)
        form.addView(TextView(this).apply {
            text = "方針"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.parseColor("#616161"))
            setPadding(8, 12, 8, 4)
        })
        form.addView(policyEdit)
        form.addView(planLabel)
        form.addView(planEdit)

        for (kind in MD_LABELS.indices) {
            form.addView(TextView(this).apply {
                text = MD_LABELS[kind]
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Color.parseColor("#616161"))
                setPadding(8, 16, 8, 4)
            })
            val holder = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            if (kind == MD_ONTO) ontoRowHolder = holder else usageRowHolder = holder
            form.addView(holder)
        }
        mdEditIndex = i
        fillMdRow(MD_ONTO)
        fillMdRow(MD_USAGE)

        scroll.addView(form)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        return root
    }

    private fun deleteProjectConfirm(i: Int) {
        AlertDialog.Builder(this)
            .setTitle("削除の確認")
            .setMessage("プロジェクト「${projects[i].name}」を削除します。よろしいですか?")
            .setPositiveButton("削除") { _, _ ->
                mdFile(i, MD_ONTO).delete()
                mdFile(i, MD_USAGE).delete()
                projects[i].name = ""
                projects[i].policy = ""
                projects[i].plan = ""
                projects[i].ontoName = ""
                projects[i].usageName = ""
                saveAi()
                showScreen(7)
                Toast.makeText(this, "削除しました", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    // ================= 画面9(プロジェクト閲覧) =================
    private fun buildScreen9(): LinearLayout {
        val i = viewIndex
        val p = if (i in 0 until PROJECTS) projects[i] else Project()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = p.name
            setTypeface(null, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(8, 4, 8, 8)
        }
        header.addView(title, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        header.addView(TextView(this).apply {
            text = STATUS_NAMES[p.status]
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.WHITE)
            setBackgroundColor(STATUS_COLORS[p.status])
            setPadding(16, 6, 16, 6)
        })
        val backBtn = Button(this).apply {
            text = "戻る"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setOnClickListener { showScreen(7) }
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        }
        header.addView(backBtn)
        root.addView(header)

        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 12)
        }
        body.addView(TextView(this).apply {
            text = "方針"
            setTypeface(null, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.parseColor("#616161"))
            setPadding(8, 8, 8, 4)
        })
        body.addView(TextView(this).apply {
            text = p.policy.ifBlank { "（未記入）" }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextIsSelectable(true)
            setBackgroundColor(Color.parseColor("#FFFDF5"))
            setPadding(16, 16, 16, 16)
        })
        body.addView(TextView(this).apply {
            text = "進捗"
            setTypeface(null, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.parseColor("#616161"))
            setPadding(8, 16, 8, 4)
        })
        body.addView(TextView(this).apply {
            text = p.plan.ifBlank { "（未記入）" }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextIsSelectable(true)
            setBackgroundColor(Color.parseColor("#FFFDF5"))
            setPadding(16, 16, 16, 16)
        })
        for (kind in MD_LABELS.indices) {
            body.addView(TextView(this).apply {
                text = MD_LABELS[kind]
                setTypeface(null, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(Color.parseColor("#616161"))
                setPadding(8, 16, 8, 4)
            })
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val has = i in 0 until PROJECTS &&
                    mdName(i, kind).isNotBlank() && mdFile(i, kind).exists()
            row.addView(TextView(this).apply {
                text = if (has) mdName(i, kind) else "未アップロード"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(if (has) Color.parseColor("#37474F") else Color.parseColor("#9E9E9E"))
                setPadding(8, 0, 8, 0)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (has) row.addView(smallButton("DL") { downloadMd(i, kind) })
            body.addView(row)
        }

        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        return root
    }

    // ---------- AI関連の永続化 ----------
    private fun saveAi() {
        val pArr = JSONArray()
        projects.forEach {
            pArr.put(
                JSONObject().put("n", it.name).put("po", it.policy)
                    .put("pl", it.plan).put("st", it.status)
                    .put("on", it.ontoName).put("us", it.usageName)
            )
        }
        getSharedPreferences("ai", MODE_PRIVATE).edit()
            .putString("projects", pArr.toString())
            .apply()
    }

    private fun loadAi() {
        val sp = getSharedPreferences("ai", MODE_PRIVATE)
        try {
            val raw = sp.getString("projects", null)
            if (raw != null) {
                val arr = JSONArray(raw)
                for (i in 0 until minOf(arr.length(), PROJECTS)) {
                    val o = arr.getJSONObject(i)
                    projects[i].name = o.optString("n", "")
                    projects[i].policy = o.optString("po", "")
                    projects[i].plan = o.optString("pl", "")
                    projects[i].status = o.optInt("st", 0).coerceIn(0, STATUS_NAMES.size - 1)
                    projects[i].ontoName = o.optString("on", "")
                    projects[i].usageName = o.optString("us", "")
                }
            }
        } catch (_: Exception) { }
    }

    // ================= 画面切替・共通 =================
    private fun showScreen(n: Int) {
        currentScreen = n
        when (n) {
            1 -> setContentView(screen1Root)
            2 -> {
                if (screen2Root == null) screen2Root = buildScreen2()
                refreshRows2()
                setContentView(screen2Root)
            }
            3 -> setContentView(buildScreen3())
            4 -> {
                if (screen4Root == null) screen4Root = buildScreen4()
                setContentView(screen4Root)
            }
            5 -> setContentView(buildScreen5())
            6 -> {
                setContentView(buildScreen6())
                uiHandler.removeCallbacks(tick)
                uiHandler.post(tick)
            }
            7 -> setContentView(buildScreen7())
            8 -> setContentView(buildScreen8())
            9 -> setContentView(buildScreen9())
        }
    }

    override fun onBackPressed() {
        when (currentScreen) {
            2, 3, 4, 6, 7 -> showScreen(1)
            5 -> showScreen(4)
            8, 9 -> showScreen(7)
            else -> confirmIfDirty { super.onBackPressed() }
        }
    }

    private fun inputDialog(
        title: String,
        message: String,
        numeric: Boolean,
        positive: String,
        neutral: String? = null,
        onNeutral: (() -> Unit)? = null,
        onOk: (String, AlertDialog) -> Unit
    ) {
        val edit = EditText(this).apply {
            inputType = if (numeric)
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else
                InputType.TYPE_CLASS_TEXT
        }
        val container = FrameLayout(this).apply {
            setPadding(48, 16, 48, 0)
            addView(edit)
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setView(container)
            .setPositiveButton(positive, null)
            .setNegativeButton("キャンセル", null)
        if (neutral != null) builder.setNeutralButton(neutral, null)
        val d = builder.create()
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            onOk(edit.text.toString(), d)
        }
        if (neutral != null) {
            d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                d.dismiss()
                onNeutral?.invoke()
            }
        }
    }

    private fun sha(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun saveSlots() {
        val arr = JSONArray()
        slots.forEach { arr.put(JSONObject().put("s", it.state).put("t", it.text)) }
        getSharedPreferences("hold", MODE_PRIVATE).edit()
            .putString("slots", arr.toString())
            .putString("pw", pwHash ?: "")
            .apply()
    }

    private fun loadSlots() {
        val sp = getSharedPreferences("hold", MODE_PRIVATE)
        pwHash = sp.getString("pw", "")!!.ifEmpty { null }
        val raw = sp.getString("slots", null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until minOf(arr.length(), SLOTS)) {
                val o = arr.getJSONObject(i)
                slots[i].state = o.getInt("s")
                slots[i].text = o.getString("t")
            }
        } catch (_: Exception) { }
    }

    // ================= 手書きビュー =================
    class DrawView(context: Context) : View(context) {
        private var bmp: Bitmap? = null
        private var bmpCanvas: Canvas? = null
        private var pending: Bitmap? = null
        private val path = Path()
        private var lastX = 0f
        private var lastY = 0f
        var hasContent = false
            private set

        private val strokePaint = Paint().apply {
            color = Color.BLACK
            strokeWidth = 6f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
        }
        private val bmpPaint = Paint(Paint.DITHER_FLAG)

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w <= 0 || h <= 0) return
            if (bmp == null) {
                bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmpCanvas = Canvas(bmp!!)
                bmpCanvas!!.drawColor(Color.WHITE)
            }
            applyPending()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            bmp?.let { canvas.drawBitmap(it, 0f, 0f, bmpPaint) }
            canvas.drawPath(path, strokePaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val x = event.x
            val y = event.y
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    path.moveTo(x, y)
                    lastX = x; lastY = y
                }
                MotionEvent.ACTION_MOVE -> {
                    path.quadTo(lastX, lastY, (x + lastX) / 2, (y + lastY) / 2)
                    lastX = x; lastY = y
                }
                MotionEvent.ACTION_UP -> {
                    path.lineTo(x, y)
                    bmpCanvas?.drawPath(path, strokePaint)
                    path.reset()
                    hasContent = true
                }
                else -> return false
            }
            invalidate()
            return true
        }

        fun clear() {
            path.reset()
            bmpCanvas?.drawColor(Color.WHITE)
            hasContent = false
            invalidate()
        }

        fun getBitmap(): Bitmap? = bmp

        fun setImage(src: Bitmap) {
            pending = src
            if (bmpCanvas != null) applyPending() else invalidate()
        }

        private fun applyPending() {
            val p = pending ?: return
            val c = bmpCanvas ?: return
            pending = null
            c.drawColor(Color.WHITE)
            val scale = minOf(width.toFloat() / p.width, height.toFloat() / p.height)
            val w = p.width * scale
            val h = p.height * scale
            val left = (width - w) / 2f
            val top = (height - h) / 2f
            c.drawBitmap(p, null, RectF(left, top, left + w, top + h), null)
            hasContent = true
            invalidate()
        }
    }
}
