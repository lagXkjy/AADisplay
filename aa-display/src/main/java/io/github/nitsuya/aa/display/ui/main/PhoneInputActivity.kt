package io.github.nitsuya.aa.display.ui.main

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import io.github.nitsuya.aa.display.CoreApi

/**
 * Stable phone-side text bridge for AAD pane editors.
 *
 * It intentionally does not attempt to attach the system IME to an AAD VirtualDisplay.
 * The phone's normal IME edits this local EditText; pressing Send pastes the Unicode text
 * into whichever AAD pane was focused when the car editor requested the keyboard.
 */
class PhoneInputActivity : Activity() {
    private lateinit var editor: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(20))
            setBackgroundColor(Color.rgb(18, 18, 18))
        }
        root.addView(TextView(this).apply {
            text = "AADisplay 手机输入"
            textSize = 20f
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(TextView(this).apply {
            text = "在手机输入文字，点“发送到车机”后会粘贴到当前分屏的输入框。"
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(8), 0, dp(12))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        editor = EditText(this).apply {
            hint = "请输入…"
            textSize = 18f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setSingleLine(false)
            minLines = 2
            maxLines = 5
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendText()
                    true
                } else {
                    false
                }
            }
        }
        root.addView(editor, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        actions.addView(Button(this).apply {
            text = "取消"
            setOnClickListener { finish() }
        })
        actions.addView(Button(this).apply {
            text = "发送到车机"
            setOnClickListener { sendText() }
        })
        root.addView(actions, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        setContentView(root)
        showPhoneIme()
    }

    override fun onResume() {
        super.onResume()
        showPhoneIme()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            editor.postDelayed({ showPhoneIme() }, 60L)
        }
    }

    private fun showPhoneIme() {
        if (!::editor.isInitialized) return
        editor.requestFocus()
        editor.post {
            runCatching {
                window.insetsController?.show(WindowInsets.Type.ime())
            }
            getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun sendText() {
        val text = editor.text?.toString().orEmpty()
        if (text.isNotEmpty()) {
            CoreApi.commitTextToFocusedPane(text)
        }
        finish()
    }
}
