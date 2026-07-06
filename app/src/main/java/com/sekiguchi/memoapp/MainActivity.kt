package com.sekiguchi.memoapp

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var editor: EditText
    private lateinit var fileLabel: TextView
    private var currentUri: Uri? = null      // 上書き保存先
    private var currentName: String = "無題"
    private var dirty = false                // 未保存の変更があるか
    private var printWebView: WebView? = null // 印刷中のGC防止用

    private val REQ_OPEN = 1
    private val REQ_SAVE_AS = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        // ファイル名表示
        fileLabel = TextView(this).apply {
            text = currentName
            setTypeface(null, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(8, 4, 8, 12)
        }
        root.addView(fileLabel)

        // ボタン行1: 開く / 上書き保存 / 名前をつけて保存
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(makeButton("開く") { confirmIfDirty { pickOpen() } })
        row1.addView(makeButton("上書き保存") { save() })
        row1.addView(makeButton("名前をつけて保存") { pickSaveAs() })
        root.addView(row1)

        // ボタン行2: 印刷 / 閉じる
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(makeButton("印刷") { printMemo() })
        row2.addView(makeButton("閉じる") { confirmIfDirty { finish() } })
        root.addView(row2)

        // 編集エリア(開くと即編集ページ)
        editor = EditText(this).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            setBackgroundColor(Color.parseColor("#FFFDF5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(24, 24, 24, 24)
            hint = "ここに文書を貼り付けて編集できます"
        }
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { dirty = true }
        })
        root.addView(editor, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        setContentView(root)
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

    // ------- 開く -------
    private fun pickOpen() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "text/*"))
        }
        startActivityForResult(intent, REQ_OPEN)
    }

    // ------- 上書き保存 -------
    private fun save() {
        val uri = currentUri
        if (uri == null) {
            pickSaveAs() // まだ保存先がなければ「名前をつけて保存」へ
            return
        }
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

    // ------- 名前をつけて保存 -------
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
        } catch (_: Exception) { /* 権限が取れない場合もそのまま続行 */ }

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

    // ------- 印刷(PDF保存対応) -------
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

    // ------- 未保存確認 -------
    private fun confirmIfDirty(action: () -> Unit) {
        if (!dirty || editor.text.isBlank()) { action(); return }
        AlertDialog.Builder(this)
            .setMessage("未保存の変更があります。破棄しますか?")
            .setPositiveButton("破棄") { _, _ -> action() }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    override fun onBackPressed() {
        confirmIfDirty { super.onBackPressed() }
    }
}
