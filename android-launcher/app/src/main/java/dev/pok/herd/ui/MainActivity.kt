package dev.pok.herd.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.pok.herd.core.Commands
import dev.pok.herd.core.Dir
import dev.pok.herd.core.Herd
import dev.pok.herd.core.HerdService
import dev.pok.herd.core.Palette
import dev.pok.herd.core.Pane
import dev.pok.herd.core.PaneState

/**
 * The client. Everything it shows lives in [Herd]; closing this activity (detach, `ctrl+b q`)
 * leaves every pane running, and opening it again reattaches to the same state.
 *
 * Layout follows herdr: a sidebar of workspaces with rolled-up agent state, a tab bar for the
 * active workspace, and the active tab's split panes.
 */
class MainActivity : Activity(), Herd.Listener {

    private lateinit var herd: Herd
    private lateinit var wsButton: TextView
    private lateinit var tabs: LinearLayout
    private lateinit var status: TextView
    private lateinit var sidebar: LinearLayout
    private lateinit var sidebarScroll: ScrollView
    private lateinit var split: SplitLayout
    private lateinit var prompt: TextView
    private lateinit var input: EditText

    private val paneViews = HashMap<Int, PaneView>()
    private var prefixArmed = false
    private var resizeMode = false
    private var historyIndex = -1
    private var draft = ""

    // ---- lifecycle ---------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        herd = Herd.get(this)
        HerdService.start(this)
        setContentView(buildUi())
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        val id = i?.getIntExtra(HerdService.EXTRA_PANE, -1) ?: -1
        if (id > 0) herd.focusPane(id)
    }

    override fun onResume() {
        super.onResume()
        herd.uiVisible = true
        herd.addListener(this)
        HerdService.start(this)
        herd.apps.invalidate()
        herd.panes.keys.forEach { HerdService.clearAttention(this, it) }
        herd.focusPane(herd.tab.focus) // marks the focused pane seen and renders
        input.requestFocus()
    }

    override fun onPause() {
        herd.uiVisible = false
        herd.removeListener(this)
        super.onPause()
    }

    @Deprecated("Launcher: back never leaves the home screen")
    override fun onBackPressed() {
        when {
            prefixArmed || resizeMode -> { prefixArmed = false; resizeMode = false; renderPrompt() }
            herd.tab.zoomed -> herd.toggleZoom()
            sidebarScroll.visibility == View.VISIBLE && !wide() -> toggleSidebar()
        }
    }

    // ---- Herd.Listener -----------------------------------------------------------------------

    override fun onLayoutChanged() = render()

    override fun onPaneChanged(pane: Pane) {
        paneViews[pane.id]?.refresh()
        renderChrome()
        if (herd.isFocused(pane)) renderPrompt()
    }

    override fun onDetachRequested() {
        val n = herd.panes.values.count { it.running }
        Toast.makeText(this, "detached — $n running pane${if (n == 1) "" else "s"} kept alive", Toast.LENGTH_SHORT).show()
        finish()
    }

    // ---- UI construction ---------------------------------------------------------------------

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()
    private fun wide() = resources.configuration.screenWidthDp >= 600

    private fun mono(size: Float, color: Int = Palette.FG) = TextView(this).apply {
        typeface = Typeface.MONOSPACE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
        }

        // Top bar: sidebar toggle with the workspace name, the workspace's tabs, global state.
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Palette.SURFACE)
            gravity = Gravity.CENTER_VERTICAL
        }
        wsButton = mono(12f).apply {
            setPadding(dp(10f), dp(7f), dp(8f), dp(7f))
            setOnClickListener { toggleSidebar() }
            setOnLongClickListener { showWorkspaceNav(); true }
        }
        top.addView(wsButton)
        tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabs)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        status = mono(12f).apply {
            setPadding(dp(10f), dp(7f), dp(10f), dp(7f))
            setOnClickListener { showGoto() }
        }
        top.addView(status)
        root.addView(top, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // Body: sidebar + panes.
        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sidebar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4f), 0, dp(4f))
        }
        sidebarScroll = ScrollView(this).apply {
            setBackgroundColor(Palette.SURFACE)
            addView(sidebar)
            visibility = if (wide()) View.VISIBLE else View.GONE
        }
        body.addView(sidebarScroll, LinearLayout.LayoutParams(dp(190f), MATCH_PARENT))
        split = SplitLayout(this).apply {
            viewFor = { id -> paneViews[id] }
            onResized = { herd.scheduleSave() }
        }
        body.addView(split, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        root.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // Input line, bound to the focused pane.
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Palette.SURFACE)
            setPadding(dp(8f), 0, dp(4f), 0)
        }
        prompt = mono(13f, Palette.ACCENT)
        inputRow.addView(prompt)
        input = EditText(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Palette.FG)
            setHintTextColor(Palette.IDLE)
            background = null
            setSingleLine()
            setPadding(dp(6f), dp(10f), dp(6f), dp(10f))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            setOnEditorActionListener { _, actionId, event ->
                val isEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER
                if (actionId == EditorInfo.IME_ACTION_SEND || (isEnter && event?.action == KeyEvent.ACTION_DOWN)) submit()
                actionId == EditorInfo.IME_ACTION_SEND || isEnter
            }
        }
        inputRow.addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(inputRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        root.addView(buildKeyBar(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return root
    }

    /** Touch equivalents of the prefix keys, like Termux's extra-keys row. */
    private fun buildKeyBar(): View {
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val keys = listOf<Pair<String, () -> Unit>>(
            "⌃B" to { showPrefixMenu() },
            "⌃C" to { herd.focused().interrupt() },
            "TAB" to { complete() },
            "↑" to { history(-1) },
            "↓" to { history(1) },
            "▏▕" to { herd.split(herd.tab.focus, down = false) },
            "⊟" to { herd.split(herd.tab.focus, down = true) },
            "⇥" to { herd.cyclePane(1) },
            "⤢" to { herd.toggleZoom() },
            "+tab" to { herd.newTab() },
            "goto" to { showGoto() },
            "☰" to { toggleSidebar() },
            "✕" to { herd.closePane() },
            "⏏" to { herd.requestDetach() },
        )
        keys.forEach { (label, action) ->
            bar.addView(mono(13f).apply {
                text = label
                gravity = Gravity.CENTER
                minWidth = dp(44f)
                setPadding(dp(10f), dp(9f), dp(10f), dp(9f))
                setOnClickListener { action(); input.requestFocus() }
            })
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Palette.BG)
            addView(bar)
        }
    }

    // ---- rendering ---------------------------------------------------------------------------

    private fun viewFor(id: Int): PaneView =
        paneViews.getOrPut(id) { PaneView(this, herd.panes.getValue(id)) { p -> if (!herd.isFocused(p)) herd.focusPane(p.id) } }

    private fun render() {
        // A pane object can be replaced under the same id after `herdr server stop`.
        paneViews.entries.removeAll { (id, v) -> herd.panes[id] !== v.pane }
        val t = herd.tab
        val views = t.root.leaves().map { viewFor(it) }
        split.show(t.root, if (t.zoomed) t.focus else null, views)
        views.forEach { it.setFocusedPane(it.pane.id == t.focus); it.refresh() }
        renderChrome()
        renderPrompt()
    }

    private fun span(sb: SpannableStringBuilder, text: String, color: Int, bold: Boolean = false) {
        val start = sb.length
        sb.append(text, ForegroundColorSpan(color), 0)
        if (bold) sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, 0)
    }

    private fun renderChrome() {
        renderTabs()
        renderSidebar()
        renderStatus()
    }

    private fun renderTabs() {
        val w = herd.ws
        wsButton.text = SpannableStringBuilder().also {
            span(it, "☰ ", Palette.DIM)
            span(it, w.label.take(14), Palette.FG, bold = true)
            val s = herd.wsState(w)
            if (s != PaneState.IDLE) span(it, " ●", s.color)
        }
        tabs.removeAllViews()
        w.tabs.forEachIndexed { i, t ->
            val active = i == w.activeTab
            val st = herd.tabState(t)
            val sb = SpannableStringBuilder()
            span(sb, "${i + 1}", Palette.DIM)
            t.label?.let { span(sb, " $it", if (active) Palette.FG else Palette.DIM) }
            if (st != PaneState.IDLE) span(sb, " ●", st.color)
            tabs.addView(mono(12f).apply {
                text = sb
                setPadding(dp(10f), dp(7f), dp(10f), dp(7f))
                background = if (active) GradientDrawable().apply { setColor(Palette.SURFACE_FOCUS) } else null
                setOnClickListener { herd.selectTab(i) }
                setOnLongClickListener { showTabMenu(i); true }
            })
        }
        tabs.addView(mono(12f, Palette.DIM).apply {
            text = " + "
            setPadding(dp(8f), dp(7f), dp(8f), dp(7f))
            setOnClickListener { herd.newTab() }
        })
    }

    /** herdr's sidebar: workspaces with their rolled-up state, and the panes inside each. */
    private fun renderSidebar() {
        if (sidebarScroll.visibility != View.VISIBLE) return
        sidebar.removeAllViews()
        herd.workspaces.forEachIndexed { wi, w ->
            val active = wi == herd.active
            sidebar.addView(mono(12f).apply {
                text = SpannableStringBuilder().also {
                    span(it, "● ", herd.wsState(w).color)
                    span(it, w.label, if (active) Palette.FG else Palette.DIM, bold = active)
                }
                setPadding(dp(10f), dp(6f), dp(8f), dp(3f))
                if (active) setBackgroundColor(Palette.SURFACE_FOCUS)
                setOnClickListener { herd.selectWorkspace(wi) }
                setOnLongClickListener { showWorkspaceMenu(wi); true }
            })
            w.tabs.forEach { t ->
                t.root.leaves().mapNotNull { herd.panes[it] }.forEach { p ->
                    sidebar.addView(mono(11f).apply {
                        text = SpannableStringBuilder().also {
                            span(it, "  ● ", p.state.color)
                            span(it, p.title.take(16), if (herd.isFocused(p)) Palette.FG else Palette.DIM)
                            if (p.state != PaneState.IDLE) span(it, " ${p.state.label}", p.state.color)
                        }
                        setPadding(dp(10f), dp(2f), dp(8f), dp(2f))
                        setOnClickListener { herd.focusPane(p.id) }
                    })
                }
            }
        }
        sidebar.addView(mono(11f, Palette.DIM).apply {
            text = "+ workspace"
            setPadding(dp(10f), dp(8f), dp(8f), dp(6f))
            setOnClickListener { herd.newWorkspace() }
        })
    }

    private fun toggleSidebar() {
        sidebarScroll.visibility = if (sidebarScroll.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        renderSidebar()
    }

    private fun renderStatus() {
        val counts = herd.panes.values.groupingBy { it.state }.eachCount()
        val sb = SpannableStringBuilder()
        listOf(PaneState.BLOCKED, PaneState.DONE, PaneState.WORKING, PaneState.UNKNOWN).forEach { s ->
            val n = counts[s] ?: 0
            if (n > 0) span(sb, "●$n ", s.color)
        }
        if (sb.isEmpty()) span(sb, "● idle", Palette.IDLE)
        status.text = sb
    }

    private fun renderPrompt() {
        val p = herd.focused()
        val sb = SpannableStringBuilder()
        when {
            resizeMode -> span(sb, "RESIZE hjkl/arrows · esc ", Palette.WORKING, bold = true)
            prefixArmed -> span(sb, "⌃B ", Palette.WORKING, bold = true)
        }
        if (p.running) {
            span(sb, "${p.ref} ${p.title} ", Palette.DIM)
            span(sb, "⇢", p.state.color)
            input.hint = "input to ${p.title}"
        } else {
            span(sb, "${p.ref} ", Palette.DIM)
            span(sb, herd.shortPath(p.cwd).let { if (it.length > 18) "…" + it.takeLast(17) else it }, Palette.CWD)
            span(sb, " ❯", Palette.ACCENT)
            input.hint = "command, app, or herdr …"
        }
        prompt.text = sb
    }

    // ---- input -------------------------------------------------------------------------------

    private fun submit() {
        val line = input.text.toString()
        input.setText("")
        historyIndex = -1
        herd.submit(line)
    }

    private fun complete() {
        val c = herd.commands.complete(input.text.toString()) ?: return
        input.setText(c)
        input.setSelection(c.length)
    }

    private fun history(delta: Int) {
        val h = herd.focused().history
        if (h.isEmpty()) return
        if (historyIndex == -1) {
            if (delta > 0) return
            draft = input.text.toString()
            historyIndex = h.size
        }
        historyIndex += delta
        val text = when {
            historyIndex < 0 -> { historyIndex = 0; h[0] }
            historyIndex >= h.size -> { historyIndex = -1; draft }
            else -> h[historyIndex]
        }
        input.setText(text)
        input.setSelection(text.length)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e)
        if (KeyEvent.isModifierKey(e.keyCode)) return super.dispatchKeyEvent(e)
        if (resizeMode) { resizeKey(e); return true }
        if (prefixArmed) {
            prefixArmed = false
            prefixKey(e)
            renderPrompt()
            return true
        }
        if (e.isCtrlPressed) when (e.keyCode) {
            KeyEvent.KEYCODE_B -> { prefixArmed = true; renderPrompt(); return true }
            KeyEvent.KEYCODE_C -> { herd.focused().interrupt(); return true }
            KeyEvent.KEYCODE_L -> { herd.focused().clear(); return true }
        }
        when (e.keyCode) {
            KeyEvent.KEYCODE_TAB -> { complete(); return true }
            KeyEvent.KEYCODE_DPAD_UP -> if (!e.isAltPressed) { history(-1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (!e.isAltPressed) { history(1); return true }
        }
        return super.dispatchKeyEvent(e)
    }

    private fun arrowDir(e: KeyEvent): Dir? = when (e.keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT -> Dir.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> Dir.RIGHT
        KeyEvent.KEYCODE_DPAD_UP -> Dir.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> Dir.DOWN
        else -> when (e.getUnicodeChar(0).toChar()) {
            'h' -> Dir.LEFT; 'l' -> Dir.RIGHT; 'k' -> Dir.UP; 'j' -> Dir.DOWN
            else -> null
        }
    }

    /** herdr's resize mode: move the focused pane's divider until esc/enter/q. */
    private fun resizeKey(e: KeyEvent) {
        val d = arrowDir(e)
        if (d != null) herd.resizePane(d) else { resizeMode = false; renderPrompt() }
    }

    /** herdr's default prefix bindings. */
    private fun prefixKey(e: KeyEvent) {
        if (e.keyCode in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT) { arrowDir(e)?.let { herd.moveFocus(it) }; return }
        val c = e.getUnicodeChar(e.metaState).toChar()
        when (c) {
            'c' -> herd.newTab()
            'v' -> herd.split(herd.tab.focus, down = false)
            '-' -> herd.split(herd.tab.focus, down = true)
            'h' -> herd.moveFocus(Dir.LEFT)
            'j' -> herd.moveFocus(Dir.DOWN)
            'k' -> herd.moveFocus(Dir.UP)
            'l' -> herd.moveFocus(Dir.RIGHT)
            'H' -> herd.swapPane(Dir.LEFT)
            'J' -> herd.swapPane(Dir.DOWN)
            'K' -> herd.swapPane(Dir.UP)
            'L' -> herd.swapPane(Dir.RIGHT)
            'z' -> herd.toggleZoom()
            'x' -> herd.closePane()
            'r' -> { resizeMode = true; renderPrompt() }
            '[' -> showCopyMode()
            'n' -> herd.cycleTab(1)
            'p' -> herd.cycleTab(-1)
            'T' -> renameTab(herd.active, herd.ws.activeTab)
            'X' -> herd.closeTab()
            'N' -> herd.newWorkspace()
            'W' -> renameWorkspace(herd.active)
            'D' -> herd.closeWorkspace(herd.active)
            'w' -> showWorkspaceNav()
            'g' -> showGoto()
            'b' -> toggleSidebar()
            'q' -> herd.requestDetach()
            '?' -> showHelp()
            in '1'..'9' -> herd.selectTab(c - '1')
        }
    }

    // ---- dialogs -----------------------------------------------------------------------------

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)

    private fun showPrefixMenu() {
        val actions = listOf<Pair<String, () -> Unit>>(
            "c  new tab" to { herd.newTab() },
            "v  split right" to { herd.split(herd.tab.focus, down = false) },
            "-  split down" to { herd.split(herd.tab.focus, down = true) },
            "z  zoom pane" to { herd.toggleZoom() },
            "x  close pane" to { herd.closePane() },
            "r  resize mode (keyboard) — or drag a divider" to { resizeMode = true; renderPrompt() },
            "[  copy mode" to { showCopyMode() },
            "n  next tab" to { herd.cycleTab(1) },
            "p  previous tab" to { herd.cycleTab(-1) },
            "T  rename tab" to { renameTab(herd.active, herd.ws.activeTab) },
            "X  close tab" to { herd.closeTab() },
            "N  new workspace" to { herd.newWorkspace() },
            "W  rename workspace" to { renameWorkspace(herd.active) },
            "D  close workspace" to { herd.closeWorkspace(herd.active) },
            "w  workspace navigation" to { showWorkspaceNav() },
            "g  go to…" to { showGoto() },
            "b  toggle sidebar" to { toggleSidebar() },
            "q  detach" to { herd.requestDetach() },
            "?  keys" to { showHelp() },
        )
        dialog().setTitle("ctrl+b")
            .setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }
            .show()
    }

    private fun monoBlock(text: CharSequence) = ScrollView(this).apply {
        addView(mono(11f).apply {
            this.text = text
            setPadding(dp(20f), dp(12f), dp(20f), dp(12f))
            setTextIsSelectable(true)
        })
    }

    private fun showHelp() {
        dialog().setTitle("keys").setView(monoBlock(Commands.KEYS)).setPositiveButton("ok", null).show()
    }

    /** herdr's copy mode, phone-style: selectable scrollback plus a copy-all button. */
    private fun showCopyMode() {
        val p = herd.focused()
        val text = p.tail(1000)
        val block = monoBlock(text)
        dialog().setTitle("copy · ${p.ref}")
            .setView(block)
            .setPositiveButton("copy all") { _, _ ->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(p.ref, text))
            }
            .setNegativeButton("close", null)
            .show()
        block.post { block.fullScroll(View.FOCUS_DOWN) }
    }

    /** herdr's workspace navigation (prefix+w). */
    private fun showWorkspaceNav() {
        val items = herd.workspaces.mapIndexed { i, w ->
            SpannableStringBuilder().also {
                span(it, "● ", herd.wsState(w).color)
                span(it, "${w.ref} ${w.label}", if (i == herd.active) Palette.FG else Palette.DIM, bold = i == herd.active)
                span(it, "  ${w.tabs.size} tab${if (w.tabs.size == 1) "" else "s"} · ${herd.wsState(w).label}", Palette.DIM)
            } as CharSequence
        } + listOf<CharSequence>("+ new workspace")
        dialog().setTitle("workspaces")
            .setItems(items.toTypedArray()) { _, i -> if (i < herd.workspaces.size) herd.selectWorkspace(i) else herd.newWorkspace() }
            .show()
    }

    /**
     * herdr's Go To picker (prefix+g): every pane on its own row, grouped by workspace, with a
     * search box and blocked/working/idle/done filters.
     */
    private fun showGoto() {
        var filter: Set<PaneState>? = null
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val search = EditText(this).apply {
            hint = "search names, tabs, workspaces, paths"
            setSingleLine()
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(8f), dp(16f), 0)
            addView(search)
            addView(HorizontalScrollView(this@MainActivity).apply { addView(chips) })
            addView(ScrollView(this@MainActivity).apply { addView(list) }, LinearLayout.LayoutParams(MATCH_PARENT, dp(360f)))
        }
        val dlg = dialog().setTitle("go to").setView(content).setNegativeButton("close", null).create()

        fun rebuild() {
            list.removeAllViews()
            val q = search.text.toString().trim().lowercase()
            herd.workspaces.forEach { w ->
                val rows = w.tabs.flatMap { t -> t.root.leaves().mapNotNull { herd.panes[it] }.map { t to it } }.filter { (t, p) ->
                    (filter == null || p.state in filter!!) &&
                        (q.isEmpty() || listOf(p.title, p.ref, p.name ?: "", p.agentKind ?: "", t.label ?: "", w.label, p.cwd, p.state.label)
                            .any { it.lowercase().contains(q) })
                }
                if (rows.isEmpty()) return@forEach
                list.addView(mono(11f, Palette.DIM).apply { text = "${w.ref} ${w.label}"; setPadding(0, dp(8f), 0, dp(2f)) })
                rows.forEach { (t, p) ->
                    list.addView(mono(13f).apply {
                        text = SpannableStringBuilder().also {
                            span(it, "● ", p.state.color)
                            span(it, p.title, Palette.FG)
                            span(it, "  ${p.state.label} · ${w.tabRef(t)} · ${herd.shortPath(p.cwd)}", Palette.DIM)
                        }
                        setPadding(dp(4f), dp(8f), 0, dp(8f))
                        setOnClickListener { herd.focusPane(p.id); dlg.dismiss() }
                    })
                }
            }
            if (list.childCount == 0) list.addView(mono(12f, Palette.DIM).apply { text = "nothing matches"; setPadding(0, dp(12f), 0, 0) })
        }

        listOf<Pair<String, Set<PaneState>?>>(
            "all" to null,
            "blocked" to setOf(PaneState.BLOCKED),
            "working" to setOf(PaneState.WORKING),
            "idle" to setOf(PaneState.IDLE),
            "done" to setOf(PaneState.DONE),
        ).forEach { (label, set) ->
            chips.addView(mono(12f, set?.first()?.color ?: Palette.FG).apply {
                text = label
                setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
                setOnClickListener { filter = set; rebuild() }
            })
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = rebuild()
        })
        rebuild()
        dlg.show()
    }

    private fun showTabMenu(i: Int) {
        dialog().setTitle(herd.ws.tabRef(herd.ws.tabs[i]))
            .setItems(arrayOf("rename", "close (kills its panes)")) { _, which ->
                if (which == 0) renameTab(herd.active, i) else herd.closeTab(herd.active, i)
            }.show()
    }

    private fun showWorkspaceMenu(i: Int) {
        dialog().setTitle(herd.workspaces[i].label)
            .setItems(arrayOf("new tab", "rename", "close (kills its panes)")) { _, which ->
                when (which) {
                    0 -> herd.newTab(i)
                    1 -> renameWorkspace(i)
                    else -> herd.closeWorkspace(i)
                }
            }.show()
    }

    private fun ask(title: String, initial: String, onOk: (String) -> Unit) {
        val field = EditText(this).apply {
            setText(initial)
            setSingleLine()
            selectAll()
        }
        dialog().setTitle(title)
            .setView(field)
            .setPositiveButton("ok") { _, _ -> onOk(field.text.toString().trim()) }
            .setNegativeButton("cancel", null)
            .show()
    }

    private fun renameWorkspace(i: Int) =
        ask("rename workspace", herd.workspaces[i].label) { if (it.isNotEmpty()) herd.renameWorkspace(i, it) }

    private fun renameTab(wi: Int, ti: Int) =
        ask("rename tab", herd.workspaces[wi].tabs[ti].label ?: "") { herd.renameTab(Herd.Loc(wi, ti), it) }
}
