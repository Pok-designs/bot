package dev.pok.herd.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import dev.pok.herd.core.Node
import dev.pok.herd.core.Palette

/**
 * Lays out pane views according to a workspace's split tree. Dividers can be dragged to resize
 * (mouse/touch first-class, like herdr); when zoomed only the focused pane is shown.
 */
class SplitLayout(ctx: Context) : ViewGroup(ctx) {

    private class Divider(val split: Node.Split, val bar: Rect, val area: Rect)

    private var root: Node? = null
    private var zoomed: Int? = null
    private val rects = HashMap<View, Rect>()
    private val dividers = ArrayList<Divider>()
    private var dragging: Divider? = null
    private val divPx = (3 * resources.displayMetrics.density).toInt().coerceAtLeast(2)
    private val grabPx = (14 * resources.displayMetrics.density).toInt()

    /** Called after a divider drag finishes, so the new ratio can be saved. */
    var onResized: (() -> Unit)? = null
    var viewFor: (Int) -> View? = { null }

    init {
        setBackgroundColor(Palette.DIVIDER)
    }

    fun show(root: Node, zoomedPane: Int?, views: List<View>) {
        this.root = root
        this.zoomed = zoomedPane
        // Re-parent only what changed to keep scroll positions stable.
        val wanted = views.toSet()
        for (i in childCount - 1 downTo 0) if (getChildAt(i) !in wanted) removeViewAt(i)
        views.forEach { v ->
            if (v.parent !== this) {
                (v.parent as? ViewGroup)?.removeView(v)
                addView(v)
            }
        }
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        rects.clear(); dividers.clear()
        val r = root ?: return
        val z = zoomed
        if (z != null) viewFor(z)?.let { rects[it] = Rect(0, 0, w, h) }
        else calc(r, 0, 0, w, h)
        for ((v, rect) in rects) {
            v.measure(MeasureSpec.makeMeasureSpec(rect.width(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(rect.height(), MeasureSpec.EXACTLY))
        }
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c !in rects) c.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY))
        }
    }

    private fun calc(n: Node, l: Int, t: Int, r: Int, b: Int) {
        when (n) {
            is Node.Leaf -> viewFor(n.pane)?.let { rects[it] = Rect(l, t, r, b) }
            is Node.Split -> if (n.vertical) {
                val mid = t + ((b - t - divPx) * n.ratio).toInt()
                calc(n.a, l, t, r, mid)
                calc(n.b, l, mid + divPx, r, b)
                dividers += Divider(n, Rect(l, mid, r, mid + divPx), Rect(l, t, r, b))
            } else {
                val mid = l + ((r - l - divPx) * n.ratio).toInt()
                calc(n.a, l, t, mid, b)
                calc(n.b, mid + divPx, t, r, b)
                dividers += Divider(n, Rect(mid, t, mid + divPx, b), Rect(l, t, r, b))
            }
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val rect = rects[c]
            if (rect == null) { c.layout(0, 0, 0, 0); c.visibility = INVISIBLE; continue }
            c.visibility = VISIBLE
            c.layout(rect.left, rect.top, rect.right, rect.bottom)
        }
    }

    private fun hit(x: Int, y: Int): Divider? = dividers.firstOrNull { d ->
        val g = Rect(d.bar)
        if (d.split.vertical) g.inset(0, -grabPx) else g.inset(-grabPx, 0)
        g.contains(x, y)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            dragging = hit(ev.x.toInt(), ev.y.toInt())
            if (dragging != null) parent?.requestDisallowInterceptTouchEvent(true)
        }
        return dragging != null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val d = dragging ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val ratio = if (d.split.vertical) (ev.y - d.area.top) / d.area.height().toFloat()
                else (ev.x - d.area.left) / d.area.width().toFloat()
                d.split.ratio = ratio.coerceIn(0.1f, 0.9f)
                requestLayout()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = null
                onResized?.invoke()
            }
        }
        return true
    }
}
