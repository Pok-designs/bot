package dev.pok.herd.core

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

/**
 * Commands typed into a pane. Launcher built-ins (open, apps, …) are checked first, after alias
 * expansion; `herdr …` is the multiplexer CLI, mirroring herdr's own subcommands so the same habits
 * and scripts work; anything else goes to the pane's shell.
 *
 * Typed `herdr` commands print human-readable output (add `--json` for herdr's `{"result": …}`
 * shape); calls arriving through the API from scripts get JSON by default, like herdr.
 */
class Commands(private val herd: Herd) {

    private class Builtin(val usage: String, val help: String, val run: (Pane, List<String>) -> Unit)

    private class CliError(val code: String, message: String) : Exception(message)

    /** One `herdr` invocation. [done] must be called exactly once when output is complete. */
    private class Ctx(val caller: Pane, val out: Out, val json: Boolean, val done: () -> Unit)

    private class Args(raw: List<String>) {
        val pos = ArrayList<String>()
        private val opts = HashMap<String, MutableList<String>>()

        init {
            var i = 0
            while (i < raw.size) {
                val a = raw[i]
                when {
                    a == "--" -> { pos += raw.drop(i + 1); break }
                    a.startsWith("--") && a.length > 2 -> {
                        val k = a.substring(2)
                        when {
                            '=' in k -> opts.getOrPut(k.substringBefore('=')) { ArrayList() } += k.substringAfter('=')
                            k in FLAGS -> opts.getOrPut(k) { ArrayList() } += ""
                            else -> { opts.getOrPut(k) { ArrayList() } += raw.getOrElse(i + 1) { "" }; i++ }
                        }
                    }
                    else -> pos += a
                }
                i++
            }
        }

        fun has(k: String) = k in opts
        operator fun get(k: String) = opts[k]?.lastOrNull()
        fun all(k: String): List<String> = opts[k] ?: emptyList()
        fun rest(from: Int) = pos.drop(from).joinToString(" ")

        companion object {
            val FLAGS = setOf("focus", "no-focus", "json", "wait", "toggle", "on", "off", "clear", "ansi", "raw", "current", "takeover", "help")
        }
    }

    private val builtins = LinkedHashMap<String, Builtin>()

    init {
        def("help", "help", "show this help") { p, _ -> help(p) }
        def("herdr", "herdr <status|workspace|tab|pane|agent|…>", "multiplexer CLI (herdr help)") { p, a ->
            herdr(p, a.filter { it != "--json" }, p, json = "--json" in a) {}
        }
        def("open", "open <app>", "launch the best-matching app") { p, a -> open(p, a.joinToString(" ")) }
        def("o", "o <app>", "same as open") { p, a -> open(p, a.joinToString(" ")) }
        def("apps", "apps [filter]", "list installed apps") { p, a -> apps(p, a.joinToString(" ")) }
        def("appinfo", "appinfo <app>", "open an app's system settings page") { p, a -> withApp(p, a) { herd.apps.appInfo(it) } }
        def("uninstall", "uninstall <app>", "ask Android to uninstall an app") { p, a -> withApp(p, a) { herd.apps.uninstall(it) } }
        def("search", "search <query>", "web search") { p, a ->
            intent(p, Intent(Intent.ACTION_WEB_SEARCH).putExtra("query", a.joinToString(" ")))
        }
        def("url", "url <address>", "open a web page") { p, a ->
            val u = a.firstOrNull() ?: return@def p.print("usage: url <address>", Palette.DIM)
            intent(p, Intent(Intent.ACTION_VIEW, Uri.parse(if ("://" in u) u else "https://$u")))
        }
        def("call", "call <number>", "open the dialer") { p, a -> intent(p, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + a.joinToString("")))) }
        def("settings", "settings [wifi|bluetooth|apps|display|battery|sound|home]", "open system settings") { p, a -> settings(p, a.firstOrNull()) }
        def("alias", "alias [name=command]", "list or define aliases") { p, a -> alias(p, a.joinToString(" ")) }
        def("unalias", "unalias <name>", "remove an alias") { _, a -> a.forEach { herd.aliases.remove(it) }; herd.scheduleSave() }
        def("history", "history", "this pane's command history") { p, _ ->
            p.print(p.history.withIndex().joinToString("\n") { (i, c) -> "%4d  %s".format(i + 1, c) })
        }
        def("clear", "clear", "clear this pane") { p, _ -> p.clear() }
        def("restart", "restart", "restart this pane's shell") { p, _ -> p.restart() }
    }

    private fun def(name: String, usage: String, help: String, run: (Pane, List<String>) -> Unit) {
        builtins[name] = Builtin(usage, help, run)
    }

    fun run(pane: Pane, line: String) {
        val expanded = expandAlias(line)
        val args = tokenize(expanded)
        val b = args.firstOrNull()?.let { builtins[it] }
        if (b != null) b.run(pane, args.drop(1)) else pane.exec(expanded)
    }

    private fun expandAlias(line: String): String {
        val first = line.trim().substringBefore(' ')
        val a = herd.aliases[first] ?: return line
        return a + line.trim().removePrefix(first)
    }

    // ---- launcher ----------------------------------------------------------------------------

    private fun open(p: Pane, q: String) {
        if (q.isBlank()) return p.print("usage: open <app>", Palette.DIM)
        val hit = herd.apps.find(q).firstOrNull() ?: return p.print("no app matches \"$q\"", Palette.BLOCKED)
        if (herd.apps.launch(hit)) p.print("→ ${hit.label}", Palette.DONE) else p.print("couldn't launch ${hit.label}", Palette.BLOCKED)
    }

    private fun apps(p: Pane, filter: String) {
        val list = herd.apps.find(filter)
        if (list.isEmpty()) return p.print("no apps match \"$filter\"", Palette.DIM)
        val width = list.maxOf { it.label.length }.coerceAtMost(28)
        p.print(list.joinToString("\n") { it.label.take(28).padEnd(width) + "  " + it.pkg })
        p.print("${list.size} apps", Palette.DIM)
    }

    private fun withApp(p: Pane, a: List<String>, f: (AppEntry) -> Unit) {
        val q = a.joinToString(" ")
        val hit = herd.apps.find(q).firstOrNull().takeIf { q.isNotBlank() }
            ?: return p.print("no app matches \"$q\"", Palette.BLOCKED)
        f(hit)
    }

    private fun intent(p: Pane, i: Intent) {
        if (!herd.apps.start(i)) p.print("nothing on this device can handle that", Palette.BLOCKED)
    }

    private fun settings(p: Pane, what: String?) {
        val action = when (what?.lowercase()) {
            null, "" -> Settings.ACTION_SETTINGS
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bt", "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "home", "launcher" -> Settings.ACTION_HOME_SETTINGS
            "notifications" -> Settings.ACTION_APP_NOTIFICATION_SETTINGS
            else -> return p.print("unknown settings page: $what", Palette.BLOCKED)
        }
        val i = Intent(action)
        if (action == Settings.ACTION_APP_NOTIFICATION_SETTINGS) i.putExtra(Settings.EXTRA_APP_PACKAGE, herd.ctx.packageName)
        intent(p, i)
    }

    private fun alias(p: Pane, def: String) {
        if (def.isBlank()) {
            if (herd.aliases.isEmpty()) p.print("no aliases", Palette.DIM)
            else p.print(herd.aliases.entries.joinToString("\n") { "${it.key}='${it.value}'" })
            return
        }
        val name = def.substringBefore('=').trim()
        val value = def.substringAfter('=', "").trim().removeSurrounding("'").removeSurrounding("\"")
        if (name.isEmpty() || value.isEmpty()) return p.print("usage: alias name=command", Palette.DIM)
        herd.aliases[name] = value
        herd.scheduleSave()
    }

    private fun help(p: Pane) {
        val w = builtins.values.maxOf { it.usage.length }.coerceAtMost(40)
        p.print(builtins.values.joinToString("\n") { it.usage.padEnd(w) + "  " + it.help })
        p.print(KEYS, Palette.DIM)
    }

    // ---- herdr CLI ---------------------------------------------------------------------------

    fun herdr(caller: Pane, raw: List<String>, out: Out, json: Boolean, done: () -> Unit) {
        val c = Ctx(caller, out, json, done)
        val async = try {
            dispatch(c, raw)
        } catch (e: CliError) {
            if (json) out.print(JSONObject().put("error", JSONObject().put("code", e.code).put("message", e.message)).toString(2))
            else out.print("herdr: ${e.message}", Palette.BLOCKED)
            false
        }
        if (!async) done()
    }

    /** Returns true when the command finishes later (and calls [Ctx.done] itself). */
    private fun dispatch(c: Ctx, raw: List<String>): Boolean {
        val group = raw.getOrNull(0)
        val a = Args(raw.drop(if (group == null) 0 else 2))
        val sub = raw.getOrNull(1)
        when (group) {
            null, "status" -> status(c)
            "help", "-h", "--help" -> c.out.print(HERDR_HELP)
            "workspace" -> workspace(c, sub, a)
            "tab" -> tab(c, sub, a)
            "pane" -> pane(c, sub, a)
            "agent" -> return agent(c, sub, a)
            "notification" -> notification(c, sub, Args(raw.drop(2)))
            "session" -> when (sub) {
                "list" -> result(c, JSONObject().put("sessions", JSONArray().put(JSONObject().put("name", "default").put("running", true))), "default (running)")
                else -> throw usage("herdr session list (herd has one session: the launcher)")
            }
            "server" -> when (sub) {
                "stop" -> { herd.stopServer(); result(c, JSONObject().put("stopped", true), "server stopped; started a fresh workspace") }
                null -> status(c)
                else -> throw usage("herdr server [stop]")
            }
            "detach" -> herd.requestDetach()
            else -> throw CliError("unknown_command", "unknown command `$group` — try `herdr help`")
        }
        return false
    }

    private fun usage(text: String) = CliError("usage", "usage: $text")

    private fun result(c: Ctx, obj: JSONObject, human: String) {
        if (c.json) c.out.print(JSONObject().put("result", obj).toString(2)) else c.out.print(human)
    }

    private fun focusFlag(a: Args) = a.has("focus") && !a.has("no-focus")

    private fun needPane(t: String?): Pane = t?.let { herd.findPane(it) } ?: throw CliError("pane_not_found", "no pane `${t ?: ""}`")

    private fun target(c: Ctx, a: Args, idx: Int = 0): Pane = when {
        a["pane"] != null -> needPane(a["pane"])
        a.has("current") -> c.caller
        a.pos.size > idx -> needPane(a.pos[idx])
        else -> c.caller
    }

    private fun dir(a: Args): Dir = when (a["direction"]) {
        "left" -> Dir.LEFT; "right" -> Dir.RIGHT; "up" -> Dir.UP; "down" -> Dir.DOWN
        else -> throw usage("--direction left|right|up|down")
    }

    // JSON views ------------------------------------------------------------------------------

    private fun paneJson(p: Pane): JSONObject {
        val w = herd.workspaceOf(p)
        val t = herd.tabOf(p)
        return JSONObject()
            .put("pane_id", p.ref)
            .put("workspace_id", w?.ref)
            .put("tab_id", if (w != null && t != null) w.tabRef(t) else null)
            .put("label", p.name ?: JSONObject.NULL)
            .put("title", p.title)
            .put("cwd", p.cwd)
            .put("focused", herd.isFocused(p))
            .put("agent", p.agentKind ?: JSONObject.NULL)
            .put("agent_status", p.state.label)
            .put("running", p.running)
            .put("command", p.command ?: JSONObject.NULL)
            .put("last_exit", p.lastExit ?: JSONObject.NULL)
    }

    private fun tabJson(w: Herd.Workspace, t: Herd.Tab) = JSONObject()
        .put("tab_id", w.tabRef(t)).put("workspace_id", w.ref).put("label", t.label ?: JSONObject.NULL)
        .put("focused", herd.ws === w && w.tab === t).put("zoomed", t.zoomed)
        .put("agent_status", herd.tabState(t).label)
        .put("panes", JSONArray(t.root.leaves().mapNotNull { herd.panes[it]?.ref }))

    private fun wsJson(w: Herd.Workspace) = JSONObject()
        .put("workspace_id", w.ref).put("label", w.label).put("cwd", w.cwd)
        .put("focused", herd.ws === w).put("agent_status", herd.wsState(w).label)
        .put("tabs", JSONArray(w.tabs.map { w.tabRef(it) }))

    private fun paneLine(p: Pane): String {
        val mark = if (herd.isFocused(p)) "*" else " "
        return "$mark %-7s %-8s %-14s %s".format(p.ref, p.state.label, p.title.take(14), herd.shortPath(p.cwd))
    }

    // status ----------------------------------------------------------------------------------

    private fun status(c: Ctx) {
        val all = herd.workspaces.flatMap { w -> w.panes().mapNotNull { herd.panes[it] } }
        val counts = all.groupingBy { it.state }.eachCount()
        if (c.json) return result(c, JSONObject().put("server", "running").put("workspaces", herd.workspaces.size)
            .put("panes", all.size).put("states", JSONObject(counts.mapKeys { it.key.label } as Map<*, *>)), "")
        val sb = StringBuilder()
        herd.workspaces.forEach { w ->
            sb.append("${w.ref}  ${w.label}  [${herd.wsState(w).label}]\n")
            w.tabs.forEach { t ->
                sb.append("  ${w.tabRef(t)}  ${t.label ?: ""}\n")
                t.root.leaves().mapNotNull { herd.panes[it] }.forEach { sb.append("  ").append(paneLine(it)).append('\n') }
            }
        }
        c.out.print(sb.toString().trimEnd())
        c.out.print(PaneState.entries.reversed().filter { (counts[it] ?: 0) > 0 }.joinToString(" · ") { "${counts[it]} ${it.label}" }, Palette.DIM)
    }

    // workspace -------------------------------------------------------------------------------

    private fun ws(t: String?): Int = t?.let { herd.findWorkspace(it) } ?: throw CliError("workspace_not_found", "no workspace `${t ?: ""}`")

    private fun workspace(c: Ctx, sub: String?, a: Args) {
        when (sub) {
            null, "list" -> result(c, JSONObject().put("workspaces", JSONArray(herd.workspaces.map { wsJson(it) })),
                herd.workspaces.joinToString("\n") { w ->
                    "${if (herd.ws === w) "*" else " "} %-4s %-16s %-8s tabs %d".format(w.ref, w.label.take(16), herd.wsState(w).label, w.tabs.size)
                })
            "create" -> {
                val w = herd.newWorkspace(a["label"], a["cwd"]?.let { expand(it) }, focusFlag(a))
                result(c, JSONObject().put("workspace", wsJson(w)).put("tab", tabJson(w, w.tabs[0]))
                    .put("root_pane", paneJson(herd.panes.getValue(w.tabs[0].focus))),
                    "created ${w.ref} ${w.label}" + if (focusFlag(a)) "" else "  (add --focus to switch to it)")
            }
            "get" -> result(c, JSONObject().put("workspace", wsJson(herd.workspaces[ws(a.pos.getOrNull(0))])), herd.workspaces[ws(a.pos.getOrNull(0))].let { "${it.ref} ${it.label}" })
            "focus" -> { herd.selectWorkspace(ws(a.pos.getOrNull(0))); result(c, wsJson(herd.ws), "focused ${herd.ws.ref}") }
            "rename" -> {
                val i = ws(a.pos.getOrNull(0))
                val label = a.rest(1).ifBlank { throw usage("herdr workspace rename <workspace_id> <label>") }
                herd.renameWorkspace(i, label)
                result(c, wsJson(herd.workspaces[i]), "renamed ${herd.workspaces[i].ref} → $label")
            }
            "close" -> { val i = ws(a.pos.getOrNull(0)); val ref = herd.workspaces[i].ref; herd.closeWorkspace(i); result(c, JSONObject().put("closed", ref), "closed $ref") }
            else -> throw usage("herdr workspace list|create|get|focus|rename|close")
        }
    }

    private fun expand(path: String) = if (path == "~" || path.startsWith("~/")) herd.home.path + path.substring(1) else path

    // tab -------------------------------------------------------------------------------------

    private fun tabLoc(t: String?): Herd.Loc = t?.let { herd.findTab(it) } ?: throw CliError("tab_not_found", "no tab `${t ?: ""}`")

    private fun tab(c: Ctx, sub: String?, a: Args) {
        when (sub) {
            null, "list" -> {
                val list = a["workspace"]?.let { listOf(herd.workspaces[ws(it)]) } ?: listOf(herd.ws)
                val tabs = list.flatMap { w -> w.tabs.map { w to it } }
                result(c, JSONObject().put("tabs", JSONArray(tabs.map { (w, t) -> tabJson(w, t) })),
                    tabs.joinToString("\n") { (w, t) ->
                        "${if (herd.ws === w && w.tab === t) "*" else " "} %-7s %-14s %-8s panes %d".format(w.tabRef(t), (t.label ?: "").take(14), herd.tabState(t).label, t.root.leaves().size)
                    })
            }
            "create" -> {
                val wi = a["workspace"]?.let { ws(it) } ?: herd.active
                val t = herd.newTab(wi, a["label"], a["cwd"]?.let { expand(it) }, focusFlag(a))
                val w = herd.workspaces[wi]
                result(c, JSONObject().put("tab", tabJson(w, t)).put("root_pane", paneJson(herd.panes.getValue(t.focus))),
                    "created ${w.tabRef(t)}" + if (focusFlag(a)) "" else "  (add --focus to switch to it)")
            }
            "get" -> tabLoc(a.pos.getOrNull(0)).let { l -> val w = herd.workspaces[l.ws]; result(c, JSONObject().put("tab", tabJson(w, w.tabs[l.tab])), w.tabRef(w.tabs[l.tab])) }
            "focus" -> tabLoc(a.pos.getOrNull(0)).let { l -> herd.selectTab(l.tab, l.ws); result(c, tabJson(herd.ws, herd.tab), "focused ${herd.ws.tabRef(herd.tab)}") }
            "rename" -> {
                val l = tabLoc(a.pos.getOrNull(0))
                val label = a.rest(1).ifBlank { throw usage("herdr tab rename <tab_id> <label>") }
                herd.renameTab(l, label)
                result(c, tabJson(herd.workspaces[l.ws], herd.workspaces[l.ws].tabs[l.tab]), "renamed → $label")
            }
            "close" -> tabLoc(a.pos.getOrNull(0)).let { l ->
                val ref = herd.workspaces[l.ws].let { it.tabRef(it.tabs[l.tab]) }
                herd.closeTab(l.ws, l.tab); result(c, JSONObject().put("closed", ref), "closed $ref")
            }
            else -> throw usage("herdr tab list|create|get|focus|rename|close")
        }
    }

    // pane ------------------------------------------------------------------------------------

    private fun pane(c: Ctx, sub: String?, a: Args) {
        when (sub) {
            null, "list" -> {
                val list = (a["workspace"]?.let { listOf(herd.workspaces[ws(it)]) } ?: herd.workspaces)
                    .flatMap { it.panes() }.mapNotNull { herd.panes[it] }
                result(c, JSONObject().put("panes", JSONArray(list.map { paneJson(it) })), list.joinToString("\n") { paneLine(it) })
            }
            "current", "get" -> target(c, a).let { result(c, JSONObject().put("pane", paneJson(it)), paneLine(it)) }
            "split" -> {
                val src = target(c, a)
                val down = when (a["direction"]) { "down" -> true; "right" -> false; else -> throw usage("herdr pane split [<pane_id>] --direction right|down") }
                val np = herd.split(src.id, down, a["ratio"]?.toFloatOrNull() ?: 0.5f, a["cwd"]?.let { expand(it) }, focusFlag(a))
                    ?: throw CliError("pane_not_found", "can't split ${src.ref}")
                result(c, JSONObject().put("pane", paneJson(np)), "created ${np.ref}")
            }
            "neighbor" -> {
                val p = target(c, a, 99)
                val id = herd.tabOf(p)?.root?.neighbor(p.id, dir(a))
                val n = id?.let { herd.panes[it] }
                result(c, JSONObject().put("pane", n?.let { paneJson(it) } ?: JSONObject.NULL), n?.ref ?: "none")
            }
            "focus" -> {
                if (a["direction"] != null) {
                    val p = target(c, a, 99)
                    herd.focusPane(p.id); herd.moveFocus(dir(a))
                } else herd.focusPane(needPane(a.pos.getOrNull(0)).id)
                result(c, JSONObject().put("pane", paneJson(herd.focused())), "focused ${herd.focused().ref}")
            }
            "resize" -> {
                val p = target(c, a, 99)
                herd.resizePane(dir(a), a["amount"]?.toFloatOrNull() ?: 0.05f, p.id)
                result(c, JSONObject().put("pane", paneJson(p)), "resized ${p.ref}")
            }
            "zoom" -> {
                val p = target(c, a)
                val t = herd.tabOf(p) ?: throw CliError("pane_not_found", "no pane")
                val on = when { a.has("on") -> true; a.has("off") -> false; else -> !(t.zoomed && t.focus == p.id) }
                herd.setZoom(on, p.id)
                result(c, JSONObject().put("pane", paneJson(p)).put("zoomed", on), if (on) "zoomed ${p.ref}" else "unzoomed")
            }
            "rename" -> {
                val p = needPane(a.pos.getOrNull(0))
                val label = if (a.has("clear")) null else a.rest(1).ifBlank { throw usage("herdr pane rename <pane_id> <label>|--clear") }
                herd.renamePane(p.id, label)
                result(c, JSONObject().put("pane", paneJson(p)), "renamed ${p.ref}")
            }
            "swap" -> {
                if (a["source-pane"] != null) {
                    val s = needPane(a["source-pane"]); val t = needPane(a["target-pane"])
                    val tab = herd.tabOf(s)
                    if (tab == null || herd.tabOf(t) !== tab) throw CliError("unsupported", "herd can only swap panes within one tab")
                    tab.root = tab.root.swapLeaves(s.id, t.id); herd.focusPane(herd.tab.focus)
                } else {
                    val p = target(c, a, 99); herd.focusPane(p.id); herd.swapPane(dir(a))
                }
                result(c, JSONObject().put("swapped", true), "swapped")
            }
            "close" -> { val p = needPane(a.pos.getOrNull(0)); herd.closePane(p.id); result(c, JSONObject().put("closed", p.ref), "closed ${p.ref}") }
            "read" -> {
                val p = needPane(a.pos.getOrNull(0))
                val text = p.tail(a["lines"]?.toIntOrNull() ?: 80)
                // herdr prints pane text directly, even in JSON mode.
                c.out.print(text)
            }
            "send-text" -> { val p = needPane(a.pos.getOrNull(0)); p.type(a.rest(1)); result(c, JSONObject().put("sent", true), "") }
            "send-keys" -> { val p = needPane(a.pos.getOrNull(0)); sendKeys(p, a.pos.drop(1)); result(c, JSONObject().put("sent", true), "") }
            "run" -> {
                val p = needPane(a.pos.getOrNull(0))
                p.type(a.rest(1)); p.enter()
                result(c, JSONObject().put("pane", paneJson(p)), "")
            }
            else -> throw usage("herdr pane list|current|get|split|neighbor|focus|resize|zoom|rename|swap|close|read|send-text|send-keys|run")
        }
    }

    private fun sendKeys(p: Pane, keys: List<String>) {
        for (k in keys) when (k.lowercase()) {
            "enter", "return" -> p.enter()
            "ctrl+c" -> p.interrupt()
            "tab" -> p.type("\t")
            "space" -> p.type(" ")
            "esc", "escape" -> p.type("\u001b")
            else -> if (k.length == 1) p.type(k) else throw CliError("invalid_key", "unsupported key `$k`")
        }
    }

    // agent -----------------------------------------------------------------------------------

    private fun untilStates(a: Args): Set<PaneState> {
        val until = a.all("until").flatMap { it.split(',') }.filter { it.isNotBlank() }
        if (until.isEmpty()) return setOf(PaneState.IDLE, PaneState.DONE, PaneState.BLOCKED)
        return until.map { PaneState.parse(it) ?: throw usage("--until blocked|working|done|idle|unknown") }.toSet()
    }

    private fun agent(c: Ctx, sub: String?, a: Args): Boolean {
        when (sub) {
            null, "list" -> {
                // On a phone few panes host a recognised agent, so every pane is listed; `agent` says which kind.
                val all = herd.workspaces.flatMap { it.panes() }.mapNotNull { herd.panes[it] }
                result(c, JSONObject().put("agents", JSONArray(all.map { paneJson(it) })),
                    all.joinToString("\n") { "${paneLine(it)}  ${it.agentKind ?: ""}" })
            }
            "get" -> needPane(a.pos.getOrNull(0)).let { result(c, JSONObject().put("agent", paneJson(it)), paneLine(it)) }
            "read" -> c.out.print(needPane(a.pos.getOrNull(0)).tail(a["lines"]?.toIntOrNull() ?: 80))
            "send-keys" -> { sendKeys(needPane(a.pos.getOrNull(0)), a.pos.drop(1)); result(c, JSONObject().put("sent", true), "") }
            "focus" -> { val p = needPane(a.pos.getOrNull(0)); herd.focusPane(p.id); result(c, JSONObject().put("agent", paneJson(p)), "focused ${p.ref}") }
            "rename" -> {
                val p = needPane(a.pos.getOrNull(0))
                val name = if (a.has("clear")) null else a.pos.getOrNull(1)?.takeIf { it.matches(Regex("[a-z][a-z0-9_-]{0,31}")) }
                    ?: throw usage("herdr agent rename <target> <name>|--clear  (name: [a-z][a-z0-9_-]{0,31})")
                herd.renamePane(p.id, name)
                result(c, JSONObject().put("agent", paneJson(p)), "renamed ${p.ref}")
            }
            "prompt" -> {
                val p = needPane(a.pos.getOrNull(0))
                val text = a.rest(1).ifBlank { throw usage("herdr agent prompt <target> <text> [--wait] [--until STATUS]… [--timeout MS]") }
                if (a.all("until").isNotEmpty() && !a.has("wait")) throw usage("--until requires --wait")
                if (p.state == PaneState.BLOCKED) throw CliError("agent_blocked", "${p.ref} is blocked; answer it first (herdr agent read ${p.ref})")
                val states = untilStates(a)
                p.type(text); p.enter()
                if (!a.has("wait")) { result(c, JSONObject().put("agent", paneJson(p)), "sent to ${p.ref}"); return false }
                val timeout = a["timeout"]?.toLongOrNull() ?: 0L
                // Like herdr: activity must be observed within 5 s, then wait for a settled state.
                herd.waitFor(p.id, setOf(PaneState.WORKING, PaneState.BLOCKED), 5000) { seen ->
                    if (seen == null) { err(c, "agent_prompt_stalled", "${p.ref} showed no activity after the prompt"); c.done(); return@waitFor }
                    herd.waitFor(p.id, states, timeout) { s -> finishWait(c, p, s) }
                }
                return true
            }
            "wait" -> {
                val p = needPane(a.pos.getOrNull(0))
                herd.waitFor(p.id, untilStates(a), a["timeout"]?.toLongOrNull() ?: 0L) { s -> finishWait(c, p, s) }
                return true
            }
            else -> throw usage("herdr agent list|get|read|send-keys|prompt|wait|focus|rename")
        }
        return false
    }

    private fun finishWait(c: Ctx, p: Pane, s: PaneState?) {
        if (s == null) err(c, "timeout", "gave up waiting for ${p.ref}")
        else result(c, JSONObject().put("agent", paneJson(p)).put("status", s.label), "${p.ref} (${p.title}) is ${s.label}")
        c.done()
    }

    private fun err(c: Ctx, code: String, msg: String) {
        if (c.json) c.out.print(JSONObject().put("error", JSONObject().put("code", code).put("message", msg)).toString(2))
        else c.out.print("herdr: $msg", Palette.BLOCKED)
    }

    private fun notification(c: Ctx, sub: String?, a: Args) {
        if (sub != "show") throw usage("herdr notification show <title> [--body TEXT]")
        val title = a.pos.joinToString(" ").ifBlank { throw usage("herdr notification show <title> [--body TEXT]") }
        HerdService.notifyCustom(herd.ctx, title, a["body"], c.caller.id)
        result(c, JSONObject().put("shown", true), "")
    }

    // ---- completion --------------------------------------------------------------------------

    /** Tab completion: built-ins, app names after open/appinfo/uninstall, herdr subcommands, pane ids. */
    fun complete(line: String): String? {
        val words = line.split(' ')
        val last = words.last()
        if (words.size == 1) {
            val c = (builtins.keys + herd.aliases.keys).filter { it.startsWith(last) }
            return c.singleOrNull()?.let { "$it " } ?: commonPrefix(c)?.takeIf { it.length > last.length }
        }
        val head = words.first()
        if (head in setOf("open", "o", "appinfo", "uninstall")) {
            return herd.apps.find(line.substringAfter(' ')).firstOrNull()?.let { "$head ${it.label}" }
        }
        if (head != "herdr") return null
        val opts: List<String> = when (words.size) {
            2 -> SUBCOMMANDS.keys.toList()
            3 -> SUBCOMMANDS[words[1]] ?: emptyList()
            else -> herd.panes.values.map { it.ref } + listOf("--direction", "--focus", "--wait", "--until", "--lines", "right", "down", "left", "up")
        }
        val c = opts.filter { it.startsWith(last) }
        val pick = c.singleOrNull()?.let { "$it " } ?: commonPrefix(c)?.takeIf { it.length > last.length } ?: return null
        return line.dropLast(last.length) + pick
    }

    private fun commonPrefix(c: List<String>): String? {
        if (c.isEmpty()) return null
        var pre = c.first()
        for (s in c) while (!s.startsWith(pre)) pre = pre.dropLast(1)
        return pre
    }

    private fun tokenize(s: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        var any = false
        for (c in s) {
            when {
                quote != null -> if (c == quote) quote = null else cur.append(c)
                c == '"' || c == '\'' -> { quote = c; any = true }
                c.isWhitespace() -> { if (cur.isNotEmpty() || any) { out += cur.toString(); cur.setLength(0); any = false } }
                else -> cur.append(c)
            }
        }
        if (cur.isNotEmpty() || any) out += cur.toString()
        return out
    }

    companion object {
        private val SUBCOMMANDS = linkedMapOf(
            "status" to emptyList(),
            "workspace" to listOf("list", "create", "get", "focus", "rename", "close"),
            "tab" to listOf("list", "create", "get", "focus", "rename", "close"),
            "pane" to listOf("list", "current", "get", "split", "neighbor", "focus", "resize", "zoom", "rename", "swap", "close", "read", "send-text", "send-keys", "run"),
            "agent" to listOf("list", "get", "read", "send-keys", "prompt", "wait", "focus", "rename"),
            "notification" to listOf("show"),
            "session" to listOf("list"),
            "server" to listOf("stop"),
            "help" to emptyList(),
        )

        val KEYS = """
            prefix ctrl+b (or the ⌃B button), then:
              c     new tab              v  split right        -  split down
              h j k l / arrows  focus    H J K L  swap panes   z  zoom      x  close pane
              r     resize mode          [  copy mode          n p  next/prev tab   1-9  tab
              T     rename tab           X  close tab
              N     new workspace        W  rename workspace   D  close workspace
              w     workspace navigation g  go to any pane     b  toggle sidebar
              q     detach (everything keeps running)          ?  this help
            input bar: tab completes · ↑/↓ history · ctrl+c interrupts (twice restarts the shell)
        """.trimIndent()

        private val HERDR_HELP = """
            herdr status                                   every workspace, tab and pane
            herdr workspace list|create [--label T] [--cwd P] [--focus]|get|focus|rename|close <id>
            herdr tab list [--workspace W]|create [--workspace W] [--label T] [--focus]|get|focus|rename|close <id>
            herdr pane list [--workspace W] | current | get <id>
            herdr pane split [<id>] --direction right|down [--ratio F] [--cwd P] [--focus]
            herdr pane focus <id> | focus --direction left|right|up|down
            herdr pane resize --direction D [--amount F] | swap --direction D | zoom [<id>] [--on|--off]
            herdr pane rename <id> <label>|--clear | close <id>
            herdr pane read <id> [--lines N] | send-text <id> <text> | send-keys <id> <key>… | run <id> <command>
            herdr agent list | get | read | focus | rename <target> <name>
            herdr agent prompt <target> <text> [--wait] [--until STATUS]… [--timeout MS]
            herdr agent wait <target> [--until STATUS]… [--timeout MS]
            herdr notification show <title> [--body TEXT]
            herdr server stop                              kill every pane, start fresh
            ids: w1 (workspace) · w1:t1 (tab) · w1:p1 (pane); p1 = pane in this workspace.
            new workspaces/tabs/splits keep focus where it is unless --focus, as in herdr.
            from scripts inside a pane: herdr …  (or sh "${'$'}HERDR_SHIM" … in a child script)
        """.trimIndent()
    }
}
