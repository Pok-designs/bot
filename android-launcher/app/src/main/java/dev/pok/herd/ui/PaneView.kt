package dev.pok.herd.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.util.TypedValue
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.pok.herd.core.Palette
import dev.pok.herd.core.Pane

/** One pane on screen: a header (state dot, id, title, cwd) over its scrollback. */
@SuppressLint("ViewConstructor")
class PaneView(ctx: Context, val pane: Pane, private val onTap: (Pane) -> Unit) : LinearLayout(ctx) {

    private val header = TextView(ctx)
    private val scroll = ScrollView(ctx)
    private val output = TextView(ctx)
    private var stickToBottom = true
    private var isFocusedPane = false
    private val border = Paint().apply { style = Paint.Style.STROKE; strokeWidth = dp(1.5f) }

    init {
        orientation = VERTICAL
        setBackgroundColor(Palette.BG)
        setWillNotDraw(false)

        header.apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(8f).toInt(), dp(3f).toInt(), dp(8f).toInt(), dp(3f).toInt())
            maxLines = 1
            setSingleLine()
        }
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        output.apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Palette.FG)
            setPadding(dp(8f).toInt(), dp(4f).toInt(), dp(8f).toInt(), dp(6f).toInt())
            setHorizontallyScrolling(false)
            // Share the pane's buffer instead of copying it: appends show up without re-setting text.
            setEditableFactory(object : Editable.Factory() {
                override fun newEditable(source: CharSequence): Editable =
                    if (source is SpannableStringBuilder) source else super.newEditable(source)
            })
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) { if (stickToBottom) scrollToEnd() }
            })
            setOnLongClickListener { copyOutput(); true }
        }
        scroll.isFillViewport = true
        scroll.isVerticalScrollBarEnabled = true
        scroll.addView(output)
        scroll.setOnScrollChangeListener { _, _, y, _, _ ->
            stickToBottom = y + scroll.height >= output.height - dp(24f)
        }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        refresh()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        output.setText(pane.buffer, TextView.BufferType.EDITABLE)
        stickToBottom = true
        scrollToEnd()
    }

    override fun onDetachedFromWindow() {
        // Unhook the TextView's watchers from the shared buffer.
        output.text = ""
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Zoom, splits and the keyboard resize panes; stay pinned to the newest output.
        if (stickToBottom) scrollToEnd()
    }

    private fun scrollToEnd() = scroll.post { scroll.scrollTo(0, output.height) }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) onTap(pane)
        return super.dispatchTouchEvent(ev)
    }

    fun setFocusedPane(f: Boolean) {
        if (isFocusedPane == f) return
        isFocusedPane = f
        refresh()
    }

    /** Re-renders the header for the pane's current state/title. */
    fun refresh() {
        val s = pane.state
        val sb = SpannableStringBuilder()
        sb.append("● ", android.text.style.ForegroundColorSpan(s.color), 0)
        sb.append("${pane.ref} ", android.text.style.ForegroundColorSpan(Palette.DIM), 0)
        sb.append(pane.title, android.text.style.ForegroundColorSpan(if (isFocusedPane) Palette.FG else Palette.DIM), 0)
        if (s != dev.pok.herd.core.PaneState.IDLE) sb.append("  ${s.label}", android.text.style.ForegroundColorSpan(s.color), 0)
        sb.append("  ${pane.cwd.substringAfterLast('/')}", android.text.style.ForegroundColorSpan(Palette.IDLE), 0)
        header.text = sb
        header.setBackgroundColor(if (isFocusedPane) Palette.SURFACE_FOCUS else Palette.SURFACE)
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (isFocusedPane) {
            border.color = Palette.ACCENT
            val h = border.strokeWidth / 2
            canvas.drawRect(h, h, width - h, height - h, border)
        }
    }

    private fun copyOutput() {
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText(pane.ref, pane.tail(200)))
        Toast.makeText(context, "copied last 200 lines of ${pane.ref}", Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
