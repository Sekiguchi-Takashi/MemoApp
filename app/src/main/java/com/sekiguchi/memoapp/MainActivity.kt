package com.sekiguchi.memoapp

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

class MainActivity : Activity() {

    // ---------- 画面1(エディタ) ----------
    private lateinit var screen1Root: LinearLayout
    private lateinit var editor: EditText
    private lateinit var fileLabel: TextView
    private var currentUri: Uri? = null
    private var currentName: String = "無題"
    private var dirty = false
    private var printWebView: WebView? = null

    private val REQ_OPEN = 1
    private val REQ_SAVE_AS = 2

    // ---------- 保持スロット ----------
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

    private var currentScreen = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadSlots()
        buildScreen1()
        showScreen(1)
    }

    // ================= 画面1 =================
    private fun buildScreen1() {
        screen1Root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        fileLabel = TextView(this).apply {
            text = currentName
            setTypeface(null, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(8, 4, 8, 12)
        }
        screen1Root.addView(fileLabel)

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(makeButton("開く") { confirmIfDirty { pickOpen() } })
        row1.addView(makeButton("上書き保存") { save() })
        row1.addView(makeButton("名前をつけて保存") { pickSaveAs() })
        screen1Root.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(makeButton("印刷") { printMemo() })
        row2.addView(makeButton("保持") { showScreen(2) })
        row2.addView(makeButton("閉じる") { confirmIfDirty { finish() } })
        screen1Root.addView(row2)

        editor = EditText(this).apply {
            gravity = Gravity.TOP or Gravity.START
            setBackgroundColor(Color.parseColor("#FFFDF5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(24, 24, 24, 24)
            hint = "ここに文書を貼り付けて編集できます"
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

    private fun updateLabel() {
        fileLabel.text = if (dirty) "$currentName *" else currentName
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

        val webView = WebView(this)
        printWebView = webView
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                val pm = getSystemService(PRINT_SERVICE) as PrintManager
                val jobName = currentName.removeSuffix(".txt")
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
                maxLines = 3
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

            // チェックボックス: 削除モード中の「保持」行のみ(機密行は画面2から削除不可)
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

    // --- 保持ボタン: 入力済みの空欄行を固定化 ---
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

    // --- 削除ボタン(画面2) ---
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
        // 機密行がすべて無くなったらパスワードもリセット(次回は新規設定)
        if (slots.none { it.state == ST_SECRET }) pwHash = null
    }

    // --- 機密ボタン: 1行のみ機密登録 ---
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
                    // 1回目: パスワード設定(保存/キャンセル)
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
                    // 2回目以降: パスワード確認
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

    // --- 機密情報ボタン: 画面3への入口 ---
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

    // --- 完全削除: パスワード不要、Delete入力で初期化 ---
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
                showScreen(3) // 再構築
            }
        }

        return root
    }

    // ================= 画面切替・保存 =================
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
        }
    }

    override fun onBackPressed() {
        when (currentScreen) {
            2, 3 -> showScreen(1)
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
}
