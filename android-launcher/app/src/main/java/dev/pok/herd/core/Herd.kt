package dev.pok.herd.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * The "server": owns every workspace, tab, pane and shell process — herdr's model. It lives as
 * long as the app process, which [HerdService] keeps alive in the foreground, so closing the UI
 * (detach) leaves work running and opening it again reattaches. Layout is saved to disk and
 * restored after the process dies; like herdr, the processes themselves don't survive that.
 *
 *   workspace (w1)  — a project container; its sidebar state rolls up from its panes
 *     tab (w1:t1)   — a layout inside a workspace
 *       pane (w1:p1) — a real shell
 *
 * Everything here runs on the main thread.
 */
class Herd private constructor(val ctx: Context) {

    class Tab(val num: Int, var label: String?, var root: Node, var focus: Int, var zoomed: Boolean = false)

    class Workspace(val num: Int, var label: String, var cwd: String) {
        val tabs = ArrayList<Tab>()
        var activeTab = 0
        var nextTab = 1
        var nextPane = 1
        val ref get() = "w$num"
        val tab get() = tabs[activeTab]
        fun tabRef(t: Tab) = "w$num:t${t.num}"
        fun panes() = tabs.flatMap { it.root.leaves() }
    }

    /** Where a pane lives. */
    data class Loc(val ws: Int, val tab: Int)

    interface Listener {
        /** Workspaces, tabs, splits, focus or zoom changed. */
        fun onLayoutChanged()
        /** A pane's title or state changed. */
        fun onPaneChanged(pane: Pane)
        fun onDetachRequested() {}
    }

    private class Waiter(val target: Int, val states: Set<PaneState>, val deadline: Long, val done: (PaneState?) -> Unit)

    val main = Handler(Looper.getMainLooper())
    val io = Executors.newCachedThreadPool()
    val home: File = File(ctx.filesDir, "home").apply { mkdirs() }
    private val stateFile = File(ctx.filesDir, "session.json")

    val workspaces = ArrayList<Workspace>()
    var active = 0; private set
    private var nextWs = 1
    val panes = LinkedHashMap<Int, Pane>()
    private var nextPaneId = 1
    val aliases = LinkedHashMap<String, String>()
    private val waiters = ArrayList<Waiter>()
    private val listeners = ArrayList<Listener>()

    /** True while the activity is in the foreground. */
    var uiVisible = false

    val apps = Apps(ctx)
    val commands = Commands(this)
    val api = HerdApi(this)

    // ---- listeners ---------------------------------------------------------------------------

    fun addListener(l: Listener) { if (l !in listeners) listeners += l }
    fun removeListener(l: Listener) { listeners -= l }
    private fun layoutChanged() { listeners.toList().forEach { it.onLayoutChanged() }; scheduleSave() }

    // ---- queries -----------------------------------------------------------------------------

    val ws: Workspace get() = workspaces[active]
    val tab: Tab get() = ws.tab
    fun focused(): Pane = panes.getValue(tab.focus)

    fun locate(paneId: Int): Loc? {
        workspaces.forEachIndexed { wi, w ->
            w.tabs.forEachIndexed { ti, t -> if (t.root.contains(paneId)) return Loc(wi, ti) }
        }
        return null
    }

    fun workspaceOf(p: Pane) = locate(p.id)?.let { workspaces[it.ws] }
    fun tabOf(p: Pane) = locate(p.id)?.let { workspaces[it.ws].tabs[it.tab] }
    fun isFocused(p: Pane) = workspaces.isNotEmpty() && tab.focus == p.id

    /** Resolves `w1:p2`, `p2` (current workspace), or an agent/pane name. */
    fun findPane(target: String): Pane? {
        panes.values.firstOrNull { it.ref == target }?.let { return it }
        if (target.matches(Regex("p\\d+"))) panes.values.firstOrNull { it.ref == "${ws.ref}:$target" }?.let { return it }
        return panes.values.firstOrNull { it.name == target }
    }

    fun findWorkspace(target: String): Int? {
        workspaces.indexOfFirst { it.ref == target || it.label == target }.takeIf { it >= 0 }?.let { return it }
        return target.toIntOrNull()?.minus(1)?.takeIf { it in workspaces.indices }
    }

    fun findTab(target: String): Loc? {
        workspaces.forEachIndexed { wi, w ->
            w.tabs.forEachIndexed { ti, t -> if (w.tabRef(t) == target) return Loc(wi, ti) }
        }
        if (target.matches(Regex("t\\d+"))) ws.tabs.indexOfFirst { "t${it.num}" == target }.takeIf { it >= 0 }?.let { return Loc(active, it) }
        return null
    }

    /** The shell reports /data/data/<pkg>/… while Context gives /data/user/0/<pkg>/…; treat both as ~. */
    private val homeAliases = listOf(home.path, home.path.replace("/data/user/0/", "/data/data/")).distinct()

    fun shortPath(path: String): String {
        for (h in homeAliases) {
            if (path == h) return "~"
            if (path.startsWith("$h/")) return "~" + path.substring(h.length)
        }
        return path
    }

    private fun worst(ids: List<Int>) = ids.mapNotNull { panes[it]?.state }.maxByOrNull { it.rank } ?: PaneState.IDLE
    fun tabState(t: Tab) = worst(t.root.leaves())
    fun wsState(w: Workspace) = worst(w.panes())

    /** Environment for a pane's shell: herdr's ids plus how to reach the API. */
    fun paneEnv(p: Pane): Map<String, String> {
        val w = workspaceOf(p)
        val t = tabOf(p)
        return buildMap {
            put("HERDR_PANE_ID", p.ref)
            if (w != null) put("HERDR_WORKSPACE_ID", w.ref)
            if (w != null && t != null) put("HERDR_TAB_ID", w.tabRef(t))
            put("HERDR_ENV", "1")
            putAll(api.env())
            put("HERDR_SHIM", api.shim.path)
        }
    }

    // ---- mutations ---------------------------------------------------------------------------

    private fun newPane(w: Workspace, cwd: String): Pane {
        val p = Pane(nextPaneId++, "${w.ref}:p${w.nextPane++}", null, cwd, this)
        panes[p.id] = p
        return p
    }

    private fun currentCwd() = workspaces.getOrNull(active)?.let { panes[it.tab.focus]?.cwd } ?: home.path

    fun newWorkspace(label: String? = null, cwd: String? = null, focus: Boolean = true): Workspace {
        val dir = cwd ?: currentCwd()
        val num = nextWs++
        val w = Workspace(num, label ?: File(dir).name.takeIf { dir != home.path && it.isNotEmpty() } ?: "workspace $num", dir)
        workspaces += w
        addTab(w, null, dir)
        if (focus || workspaces.size == 1) active = workspaces.size - 1
        layoutChanged()
        return w
    }

    private fun addTab(w: Workspace, label: String?, cwd: String): Tab {
        val p = newPane(w, cwd)
        val t = Tab(w.nextTab++, label, Node.Leaf(p.id), p.id)
        w.tabs += t
        return t
    }

    fun newTab(wsIndex: Int = active, label: String? = null, cwd: String? = null, focus: Boolean = true): Tab {
        val w = workspaces[wsIndex]
        val t = addTab(w, label, cwd ?: (if (wsIndex == active) currentCwd() else w.cwd))
        if (focus) { active = wsIndex; w.activeTab = w.tabs.size - 1 }
        layoutChanged()
        return t
    }

    /** Splits [target]; the new pane gets focus when [focus] (the TUI always focuses). */
    fun split(target: Int, down: Boolean, ratio: Float = 0.5f, cwd: String? = null, focus: Boolean = true): Pane? {
        val loc = locate(target) ?: return null
        val w = workspaces[loc.ws]
        val t = w.tabs[loc.tab]
        val p = newPane(w, cwd ?: panes[target]?.cwd ?: w.cwd)
        t.root = t.root.replaceLeaf(target) { Node.Split(down, ratio.coerceIn(0.1f, 0.9f), it, Node.Leaf(p.id)) }
        t.zoomed = false
        if (focus) { active = loc.ws; w.activeTab = loc.tab; t.focus = p.id }
        layoutChanged()
        return p
    }

    fun closePane(id: Int = tab.focus) {
        val loc = locate(id) ?: return
        val w = workspaces[loc.ws]
        val t = w.tabs[loc.tab]
        panes.remove(id)?.kill()
        failWaiters(id)
        val root = t.root.removeLeaf(id)
        if (root == null) { closeTab(loc.ws, loc.tab, killPanes = false); return }
        t.root = root
        if (t.focus == id) t.focus = root.leaves().first()
        t.zoomed = false
        layoutChanged()
    }

    /** Closing a workspace's last tab closes the workspace, as in herdr. */
    fun closeTab(wsIndex: Int = active, tabIndex: Int = ws.activeTab, killPanes: Boolean = true) {
        val w = workspaces.getOrNull(wsIndex) ?: return
        val t = w.tabs.getOrNull(tabIndex) ?: return
        if (killPanes) t.root.leaves().forEach { panes.remove(it)?.kill(); failWaiters(it) }
        w.tabs.removeAt(tabIndex)
        if (w.tabs.isEmpty()) { closeWorkspace(wsIndex, killPanes = false); return }
        if (w.activeTab >= w.tabs.size || w.activeTab > tabIndex) w.activeTab = maxOf(0, w.activeTab - 1)
        layoutChanged()
    }

    fun closeWorkspace(index: Int, killPanes: Boolean = true) {
        val w = workspaces.getOrNull(index) ?: return
        if (killPanes) w.panes().forEach { panes.remove(it)?.kill(); failWaiters(it) }
        workspaces.removeAt(index)
        if (workspaces.isEmpty()) { active = 0; newWorkspace(cwd = home.path); return }
        if (active >= workspaces.size || active > index) active = maxOf(0, active - 1)
        markSeen(focused())
        layoutChanged()
    }

    fun focusPane(id: Int) {
        val loc = locate(id) ?: return
        active = loc.ws
        val w = workspaces[loc.ws]
        w.activeTab = loc.tab
        w.tab.focus = id
        panes[id]?.let { markSeen(it) }
        layoutChanged()
    }

    private fun markSeen(p: Pane) {
        if (!p.unseen) return
        p.unseen = false
        refreshState(p)
    }

    fun moveFocus(dir: Dir) { tab.root.neighbor(tab.focus, dir)?.let { focusPane(it) } }

    fun swapPane(dir: Dir) {
        val other = tab.root.neighbor(tab.focus, dir) ?: return
        tab.root = tab.root.swapLeaves(tab.focus, other)
        layoutChanged()
    }

    fun resizePane(dir: Dir, amount: Float = 0.05f, id: Int = tab.focus) {
        val t = locate(id)?.let { workspaces[it.ws].tabs[it.tab] } ?: return
        val s = t.root.splitFor(id, vertical = dir == Dir.UP || dir == Dir.DOWN) ?: return
        val d = if (dir == Dir.LEFT || dir == Dir.UP) -amount else amount
        s.ratio = (s.ratio + d).coerceIn(0.1f, 0.9f)
        layoutChanged()
    }

    fun cyclePane(delta: Int = 1) {
        val leaves = tab.root.leaves()
        val i = leaves.indexOf(tab.focus)
        focusPane(leaves[(i + delta + leaves.size) % leaves.size])
    }

    fun selectTab(index: Int, wsIndex: Int = active) {
        val w = workspaces.getOrNull(wsIndex) ?: return
        if (index !in w.tabs.indices) return
        active = wsIndex
        w.activeTab = index
        markSeen(focused())
        layoutChanged()
    }

    fun cycleTab(delta: Int) = selectTab((ws.activeTab + delta + ws.tabs.size) % ws.tabs.size)

    fun selectWorkspace(index: Int) {
        if (index !in workspaces.indices) return
        active = index
        markSeen(focused())
        layoutChanged()
    }

    fun cycleWorkspace(delta: Int) = selectWorkspace((active + delta + workspaces.size) % workspaces.size)

    fun setZoom(on: Boolean, id: Int = tab.focus) {
        val t = locate(id)?.let { workspaces[it.ws].tabs[it.tab] } ?: return
        t.focus = id
        t.zoomed = on
        layoutChanged()
    }

    fun toggleZoom() = setZoom(!tab.zoomed)

    fun renameWorkspace(index: Int, label: String) { workspaces.getOrNull(index)?.label = label; layoutChanged() }

    fun renameTab(loc: Loc, label: String?) {
        workspaces.getOrNull(loc.ws)?.tabs?.getOrNull(loc.tab)?.label = label?.ifBlank { null }; layoutChanged()
    }

    fun renamePane(id: Int, name: String?) {
        panes[id]?.let { it.name = name?.ifBlank { null }; onPaneChanged(it); scheduleSave() }
    }

    fun requestDetach() = listeners.toList().forEach { it.onDetachRequested() }

    /** `herdr server stop`: kill everything and start over with one empty workspace. */
    fun stopServer() {
        panes.values.forEach { it.kill() }
        panes.keys.toList().forEach { failWaiters(it) }
        panes.clear(); workspaces.clear()
        active = 0
        newWorkspace(cwd = home.path)
        HerdService.refresh(ctx)
    }

    // ---- input -------------------------------------------------------------------------------

    /** A line typed into the input bar for the focused pane. */
    fun submit(line: String) = submitTo(focused(), line)

    fun submitTo(pane: Pane, line: String) {
        if (pane.running) { pane.sendInput(line); return }
        pane.echoPrompt(line)
        if (line.isBlank()) return
        if (pane.history.lastOrNull() != line) pane.history += line
        if (pane.history.size > 500) pane.history.removeAt(0)
        commands.run(pane, line)
        scheduleSave()
    }

    // ---- state tracking ----------------------------------------------------------------------

    fun onPaneOutput(p: Pane) = refreshState(p)

    fun onPaneChanged(p: Pane) = refreshState(p, force = true)

    fun onCommandFinished(p: Pane) {
        if (!(uiVisible && isFocused(p))) p.unseen = true
        refreshState(p, force = true)
        scheduleSave()
    }

    /** Calls [done] with the state once pane [target] is in one of [states], or null on timeout/close. */
    fun waitFor(target: Int, states: Set<PaneState>, timeoutMs: Long, done: (PaneState?) -> Unit) {
        val p = panes[target] ?: return done(null)
        if (p.state in states) return done(p.state)
        val deadline = if (timeoutMs > 0) SystemClock.uptimeMillis() + timeoutMs else Long.MAX_VALUE
        waiters += Waiter(target, states, deadline, done)
    }

    private fun failWaiters(id: Int) {
        waiters.filter { it.target == id }.forEach { waiters -= it; it.done(null) }
    }

    private fun refreshState(p: Pane, force: Boolean = false) {
        val old = p.state
        val new = p.computeState(SystemClock.uptimeMillis())
        if (new == old && !force) return
        p.state = new
        listeners.toList().forEach { it.onPaneChanged(p) }
        if (new == old) return

        waiters.filter { it.target == p.id && new in it.states }.forEach { waiters -= it; it.done(new) }
        // In the foreground the badges say it; a system notification is for when you're away.
        if ((new == PaneState.BLOCKED || new == PaneState.DONE) && !uiVisible) {
            HerdService.notifyAttention(ctx, p, new)
        }
        HerdService.refresh(ctx)
    }

    private val ticker = object : Runnable {
        override fun run() {
            // working → blocked/unknown depends on how long a pane has been quiet: re-evaluate on a timer.
            panes.values.toList().forEach { if (it.running || it.state != PaneState.IDLE) refreshState(it) }
            val now = SystemClock.uptimeMillis()
            waiters.filter { now > it.deadline }.forEach { waiters -= it; it.done(null) }
            main.postDelayed(this, 500)
        }
    }

    // ---- persistence -------------------------------------------------------------------------

    private val saver = Runnable { save() }
    fun scheduleSave() { main.removeCallbacks(saver); main.postDelayed(saver, 600) }

    private fun save() {
        val json = JSONObject()
            .put("version", 2)
            .put("nextPaneId", nextPaneId)
            .put("nextWs", nextWs)
            .put("active", active)
            .put("aliases", JSONObject(aliases as Map<*, *>))
            .put("workspaces", JSONArray().apply {
                workspaces.forEach { w ->
                    put(JSONObject().put("num", w.num).put("label", w.label).put("cwd", w.cwd)
                        .put("activeTab", w.activeTab).put("nextTab", w.nextTab).put("nextPane", w.nextPane)
                        .put("tabs", JSONArray().apply {
                            w.tabs.forEach { t ->
                                put(JSONObject().put("num", t.num).put("label", t.label ?: JSONObject.NULL)
                                    .put("focus", t.focus).put("zoomed", t.zoomed).put("root", t.root.toJson()))
                            }
                        }))
                }
            })
            .put("panes", JSONArray().apply {
                panes.values.forEach { p ->
                    put(JSONObject().put("id", p.id).put("ref", p.ref).put("name", p.name ?: JSONObject.NULL).put("cwd", p.cwd)
                        .put("history", JSONArray(p.history.takeLast(200))))
                }
            })
            .toString()
        io.execute {
            try {
                val tmp = File(stateFile.path + ".tmp")
                tmp.writeText(json)
                tmp.renameTo(stateFile)
            } catch (_: Exception) {}
        }
    }

    private fun JSONObject.optStr(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }

    private fun restore() {
        try {
            if (stateFile.exists()) {
                val o = JSONObject(stateFile.readText())
                if (o.optInt("version") != 2) throw IllegalStateException("old format")
                nextPaneId = o.getInt("nextPaneId")
                nextWs = o.getInt("nextWs")
                o.optJSONObject("aliases")?.let { a -> a.keys().forEach { aliases[it] = a.getString(it) } }
                val pa = o.getJSONArray("panes")
                for (i in 0 until pa.length()) {
                    val po = pa.getJSONObject(i)
                    val p = Pane(po.getInt("id"), po.getString("ref"), po.optStr("name"), po.getString("cwd"), this)
                    po.optJSONArray("history")?.let { h -> for (j in 0 until h.length()) p.history += h.getString(j) }
                    panes[p.id] = p
                }
                val wa = o.getJSONArray("workspaces")
                for (i in 0 until wa.length()) {
                    val wo = wa.getJSONObject(i)
                    val w = Workspace(wo.getInt("num"), wo.getString("label"), wo.getString("cwd"))
                    w.nextTab = wo.getInt("nextTab"); w.nextPane = wo.getInt("nextPane")
                    val ta = wo.getJSONArray("tabs")
                    for (j in 0 until ta.length()) {
                        val to = ta.getJSONObject(j)
                        val root = Node.fromJson(to.getJSONObject("root"))
                        if (root.leaves().any { it !in panes }) continue
                        val focus = to.getInt("focus").takeIf { root.contains(it) } ?: root.leaves().first()
                        w.tabs += Tab(to.getInt("num"), to.optStr("label"), root, focus, to.optBoolean("zoomed"))
                    }
                    if (w.tabs.isEmpty()) continue
                    w.activeTab = wo.optInt("activeTab").coerceIn(0, w.tabs.size - 1)
                    workspaces += w
                }
                val used = workspaces.flatMap { it.panes() }.toSet()
                panes.keys.retainAll(used)
                active = o.optInt("active").coerceIn(0, maxOf(0, workspaces.size - 1))
                panes.values.forEach { it.print("[layout restored — earlier processes did not survive a restart]", Palette.DIM) }
            }
        } catch (_: Exception) {
            panes.clear(); workspaces.clear(); nextPaneId = 1; nextWs = 1
        }
        if (workspaces.isEmpty()) {
            newWorkspace("main", home.path)
            focused().print(WELCOME, Palette.DIM)
        }
        api.start()
        main.post(ticker)
    }

    companion object {
        @Volatile private var instance: Herd? = null

        fun get(context: Context): Herd {
            instance?.let { return it }
            check(Looper.myLooper() == Looper.getMainLooper()) { "Herd.get must be called on the main thread" }
            return instance ?: Herd(context.applicationContext).also { instance = it; it.restore() }
        }

        private val WELCOME = """
            herd — an Android launcher that runs like herdr
              open <app>      launch an app (fuzzy)
              apps [filter]   list apps
              ctrl+b v / -    split right / down
              ctrl+b c        new tab
              ctrl+b g        go to any pane
              ctrl+b q        detach (panes keep running)
              ctrl+b ?        all keys
              herdr status    every pane and its state
            anything else runs in this pane's shell.
            no keyboard? use the ⌃B button below.
        """.trimIndent()
    }
}
