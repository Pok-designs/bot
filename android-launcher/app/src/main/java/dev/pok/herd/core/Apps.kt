package dev.pok.herd.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings

data class AppEntry(val label: String, val pkg: String, val activity: String)

/** Installed launchable apps, fuzzy matching, and launch counts for ranking. */
class Apps(private val ctx: Context) {
    private var cache: List<AppEntry> = emptyList()
    private var cachedAt = 0L
    private val prefs = ctx.getSharedPreferences("launches", Context.MODE_PRIVATE)

    fun all(): List<AppEntry> {
        val now = SystemClock.uptimeMillis()
        if (cache.isEmpty() || now - cachedAt > 15_000) {
            val pm = ctx.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            cache = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                .filter { it.activityInfo.packageName != ctx.packageName }
                .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.activityInfo.name) }
                .distinctBy { it.pkg + "/" + it.activity }
                .sortedBy { it.label.lowercase() }
            cachedAt = now
        }
        return cache
    }

    fun invalidate() { cachedAt = 0 }

    /** Best matches for [query], ranked by match quality then by how often you launch them. */
    fun find(query: String): List<AppEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return all()
        return all().mapNotNull { a ->
            val s = score(a, q)
            if (s > 0) a to s + minOf(prefs.getInt(a.pkg, 0), 20) else null
        }.sortedByDescending { it.second }.map { it.first }
    }

    private fun score(a: AppEntry, q: String): Int {
        val label = a.label.lowercase()
        return when {
            label == q -> 1000
            label.startsWith(q) -> 800
            label.split(' ', '-', '_', '.').any { it.startsWith(q) } -> 600
            label.contains(q) -> 400
            a.pkg.lowercase().contains(q) -> 200
            isSubsequence(q, label) -> 100
            else -> 0
        }
    }

    private fun isSubsequence(q: String, s: String): Boolean {
        var i = 0
        for (c in s) if (i < q.length && c == q[i]) i++
        return i == q.length
    }

    fun launch(a: AppEntry): Boolean = start(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(a.pkg, a.activity))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    ).also { if (it) prefs.edit().putInt(a.pkg, prefs.getInt(a.pkg, 0) + 1).apply() }

    fun appInfo(a: AppEntry) = start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}")))

    fun uninstall(a: AppEntry) = start(Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.pkg}")))

    fun start(intent: Intent): Boolean = try {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (e: Exception) {
        false
    }
}
