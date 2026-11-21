package dev.pok.herd.core

import android.graphics.RectF
import org.json.JSONObject

/**
 * A workspace's pane layout: a binary split tree, like tmux/herdr.
 * [Split.vertical] = true stacks [Split.a] on top of [Split.b] ("split-down");
 * false puts them side by side ("split-right").
 */
sealed class Node {
    class Leaf(val pane: Int) : Node()
    class Split(val vertical: Boolean, var ratio: Float, var a: Node, var b: Node) : Node()

    fun leaves(): List<Int> = when (this) {
        is Leaf -> listOf(pane)
        is Split -> a.leaves() + b.leaves()
    }

    fun contains(id: Int): Boolean = when (this) {
        is Leaf -> pane == id
        is Split -> a.contains(id) || b.contains(id)
    }

    /** Replaces the leaf holding [id] with whatever [f] returns. Returns the (possibly new) root. */
    fun replaceLeaf(id: Int, f: (Leaf) -> Node): Node = when (this) {
        is Leaf -> if (pane == id) f(this) else this
        is Split -> {
            a = a.replaceLeaf(id, f); b = b.replaceLeaf(id, f); this
        }
    }

    /** Removes the leaf holding [id]; its sibling takes the parent's place. Null when the tree is empty. */
    fun removeLeaf(id: Int): Node? = when (this) {
        is Leaf -> if (pane == id) null else this
        is Split -> {
            val na = a.removeLeaf(id)
            val nb = b.removeLeaf(id)
            when {
                na == null -> nb
                nb == null -> na
                else -> { a = na; b = nb; this }
            }
        }
    }

    /** Exchanges the positions of two panes. */
    fun swapLeaves(x: Int, y: Int): Node =
        replaceLeaf(x) { Leaf(Int.MIN_VALUE) }.replaceLeaf(y) { Leaf(x) }.replaceLeaf(Int.MIN_VALUE) { Leaf(y) }

    /** Deepest split of the given orientation that contains [id] — the divider a resize moves. */
    fun splitFor(id: Int, vertical: Boolean): Split? {
        if (this !is Split) return null
        val inner = (if (a.contains(id)) a else if (b.contains(id)) b else return null).splitFor(id, vertical)
        return inner ?: if (this.vertical == vertical) this else null
    }

    /** Normalised rectangles (unit square) for each pane, used for directional focus. */
    fun rects(l: Float = 0f, t: Float = 0f, r: Float = 1f, b: Float = 1f, out: MutableMap<Int, RectF> = HashMap()): Map<Int, RectF> {
        when (this) {
            is Leaf -> out[pane] = RectF(l, t, r, b)
            is Split -> if (vertical) {
                val mid = t + (b - t) * ratio
                a.rects(l, t, r, mid, out); this.b.rects(l, mid, r, b, out)
            } else {
                val mid = l + (r - l) * ratio
                a.rects(l, t, mid, b, out); this.b.rects(mid, t, r, b, out)
            }
        }
        return out
    }

    fun toJson(): JSONObject = when (this) {
        is Leaf -> JSONObject().put("pane", pane)
        is Split -> JSONObject()
            .put("dir", if (vertical) "down" else "right")
            .put("ratio", ratio.toDouble())
            .put("a", a.toJson())
            .put("b", b.toJson())
    }

    companion object {
        fun fromJson(o: JSONObject): Node =
            if (o.has("pane")) Leaf(o.getInt("pane"))
            else Split(
                o.getString("dir") == "down",
                o.optDouble("ratio", 0.5).toFloat(),
                fromJson(o.getJSONObject("a")),
                fromJson(o.getJSONObject("b")),
            )
    }
}

enum class Dir { LEFT, RIGHT, UP, DOWN }

/** Finds the pane adjacent to [from] in direction [dir], preferring the one with the most overlap. */
fun Node.neighbor(from: Int, dir: Dir): Int? {
    val rects = rects()
    val r = rects[from] ?: return null
    val eps = 0.001f
    var best: Int? = null
    var bestScore = Float.MAX_VALUE
    for ((id, c) in rects) {
        if (id == from) continue
        val (gap, overlap) = when (dir) {
            Dir.LEFT -> (r.left - c.right) to (minOf(r.bottom, c.bottom) - maxOf(r.top, c.top))
            Dir.RIGHT -> (c.left - r.right) to (minOf(r.bottom, c.bottom) - maxOf(r.top, c.top))
            Dir.UP -> (r.top - c.bottom) to (minOf(r.right, c.right) - maxOf(r.left, c.left))
            Dir.DOWN -> (c.top - r.bottom) to (minOf(r.right, c.right) - maxOf(r.left, c.left))
        }
        if (gap < -eps || overlap <= eps) continue
        val score = gap * 10 - overlap
        if (score < bestScore) { bestScore = score; best = id }
    }
    return best
}
