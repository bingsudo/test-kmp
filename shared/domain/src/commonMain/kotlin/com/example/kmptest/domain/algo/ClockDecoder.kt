package com.example.kmptest.domain.algo

import com.example.kmptest.domain.model.Box
import com.example.kmptest.domain.model.ClockError
import com.example.kmptest.domain.model.ClockNotFoundException
import com.example.kmptest.domain.model.YuvFrame
import kotlin.math.abs

/** Glyph images -> small integer ids (one per distinct digit shape), learned on the fly. */
class GlyphClusterer(private val threshold: Float = 25f) {
    private val templates = ArrayList<FloatArray>()
    private val counts = ArrayList<Int>()

    fun idOf(cell: FloatArray): Int {
        var best = Float.MAX_VALUE
        var bi = -1
        for (i in templates.indices) {
            val t = templates[i]
            var d = 0f
            for (j in cell.indices) d += abs(t[j] - cell[j])
            d /= cell.size
            if (d < best) {
                best = d
                bi = i
            }
        }
        if (bi < 0 || best > threshold) {
            templates.add(cell)
            counts.add(0)
            bi = templates.size - 1
        }
        counts[bi] = counts[bi] + 1
        return bi
    }

    /** Ids seen fewer than [min] times are compression glitches, not digits. */
    fun reliable(min: Int = 3): Set<Int> = counts.indices.filter { counts[it] >= min }.toSet()

    companion object {
        const val CELL_W = 14
        const val CELL_H = 18

        /** Area-average resize of [box] in the luma plane to CELL_W x CELL_H. */
        fun cell(frame: YuvFrame, box: Box): FloatArray {
            val out = FloatArray(CELL_W * CELL_H)
            val bw = box.width
            val bh = box.height
            for (j in 0 until CELL_H) {
                val ys = box.y0 + j * bh / CELL_H
                val ye = maxOf(box.y0 + (j + 1) * bh / CELL_H, ys + 1)
                for (i in 0 until CELL_W) {
                    val xs = box.x0 + i * bw / CELL_W
                    val xe = maxOf(box.x0 + (i + 1) * bw / CELL_W, xs + 1)
                    var s = 0
                    for (y in ys until ye) for (x in xs until xe) s += frame.data[y * frame.width + x].toInt() and 0xFF
                    out[j * CELL_W + i] = s.toFloat() / ((ye - ys) * (xe - xs))
                }
            }
            return out
        }
    }
}

class DecodedClock(val seconds: IntArray, val usedTens: Boolean)

/**
 * Turns per-frame glyph ids of the seconds digits into elapsed seconds. The digit order
 * (0,1,2,...) is learned from how the glyphs succeed each other, so no font is needed. The tens
 * cell gives mod-60 resolution (gaps up to ~50 s); without it only mod-10 is available.
 * Ids of -1 mean "unreadable".
 */
object ClockDecoder {
    fun decode(onesIds: IntArray, tensIds: IntArray?): DecodedClock {
        val cycO = learnCycle(onesIds, 10, null) ?: throw ClockNotFoundException(ClockError.NO_CYCLE)
        val po = positions(cycO, onesIds)
        val n = onesIds.size
        val o = IntArray(n) { i -> onesIds[i].let { if (it >= 0 && it < po.size) po[it] else -1 } }
        // tens digit changes are rare: learn their order only from clean one-second steps
        val step1 = BooleanArray(n) { i -> i > 0 && o[i] >= 0 && o[i - 1] >= 0 && (o[i] - o[i - 1] + 10) % 10 == 1 }
        val order = tensIds?.let { learnTensOrder(it, step1) }
        val secs = IntArray(n) { -1 }
        val cyclic: Boolean
        val maxGap: Int
        if (order != null && tensIds != null) {
            val pt = positions(order.symbols, tensIds)
            val t = IntArray(n) { i -> tensIds[i].let { if (it >= 0 && it < pt.size) pt[it] else -1 } }
            // align: when the tens glyph changes, the ones digit is a true 0
            val votes = IntArray(10)
            for (i in 1 until n) if (t[i] >= 0 && t[i - 1] >= 0 && t[i] != t[i - 1] && o[i] >= 0) votes[o[i]]++
            val a = votes.indices.maxByOrNull { votes[it] } ?: 0
            for (i in 0 until n) if (o[i] >= 0 && t[i] >= 0) {
                val v = t[i] * 10 + floorMod(o[i] - a, 10)
                secs[i] = if (order.cyclic) v % 60 else v
            }
            cyclic = order.cyclic
            maxGap = 50
        } else {
            for (i in 0 until n) if (o[i] >= 0) secs[i] = o[i]
            cyclic = true
            maxGap = 8
        }
        val mod = if (order != null) 60 else 10
        val out = IntArray(n)
        var cur = 0
        var last = -1
        for (i in 0 until n) {
            val s = secs[i]
            if (s >= 0) {
                if (last < 0) {
                    last = s
                } else {
                    // a clip that never shows all six tens glyphs has no wrap-around: plain difference
                    val d = if (cyclic) floorMod(s - last, mod) else s - last
                    if (d in 0 until maxGap) {
                        cur += d
                        last = s
                    }
                }
            }
            out[i] = cur
        }
        return DecodedClock(out, order != null)
    }

    class TensOrder(val symbols: List<Int>, val cyclic: Boolean)

    /**
     * Order of the tens glyphs 0..5. With all six seen it is the cyclic order (mod 60). A shorter clip
     * shows fewer; their best linear order is used, and the clock never wraps inside such a clip.
     */
    private fun learnTensOrder(ids: IntArray, step1: BooleanArray): TensOrder? {
        val syms = ids.filter { it >= 0 }.toSortedSet().toList()
        if (syms.size == 6) return learnCycle(ids, 6, step1)?.let { TensOrder(it, true) }
        if (syms.size !in 2..5) return null
        val succ = HashMap<Pair<Int, Int>, Int>()
        for (i in 1 until ids.size) {
            val a = ids[i - 1]
            val b = ids[i]
            if (step1[i] && a >= 0 && b >= 0 && a != b) succ.merge(a to b, 1, Int::plus)
        }
        var best: List<Int>? = null
        var bestScore = -1
        permutations(syms) { perm ->
            var score = 0
            for (k in 0 until perm.size - 1) score += succ[perm[k] to perm[k + 1]] ?: 0
            if (score > bestScore) {
                bestScore = score
                best = perm
            }
        }
        return best?.takeIf { bestScore > 0 }?.let { TensOrder(it, false) }
    }

    private fun positions(cycle: List<Int>, ids: IntArray): IntArray {
        val size = maxOf(ids.maxOrNull() ?: 0, cycle.max()) + 1
        val p = IntArray(size) { -1 }
        cycle.forEachIndexed { i, c -> p[c] = i }
        return p
    }

    /** Most-voted successor cycle of exactly [size] symbols, or null. */
    fun learnCycle(ids: IntArray, size: Int, only: BooleanArray?): List<Int>? {
        val succ = HashMap<Int, HashMap<Int, Int>>()
        for (i in 1 until ids.size) {
            val a = ids[i - 1]
            val b = ids[i]
            if (only != null && !only[i]) continue
            if (a >= 0 && b >= 0 && a != b) succ.getOrPut(a) { HashMap() }.merge(b, 1, Int::plus)
        }
        if (succ.isEmpty()) return null
        if (size <= 7) {
            val syms = (succ.keys + succ.values.flatMap { it.keys }).toSortedSet().toList()
            if (syms.size != size) return null
            var best: List<Int>? = null
            var bestScore = -1
            permutations(syms.drop(1)) { perm ->
                val cyc = listOf(syms[0]) + perm
                var score = 0
                for (k in 0 until size) score += succ[cyc[k]]?.get(cyc[(k + 1) % size]) ?: 0
                if (score > bestScore) {
                    bestScore = score
                    best = cyc
                }
            }
            return best
        }
        val next = succ.mapValues { (_, m) -> m.maxByOrNull { it.value }!!.key }
        for (start in next.keys) {
            val cyc = arrayListOf(start)
            var cur = next[start]
            while (cur != null && cur != start && cur !in cyc && cyc.size <= size) {
                cyc.add(cur)
                cur = next[cur]
            }
            if (cur == start && cyc.size == size) return cyc
        }
        return null
    }

    private fun permutations(items: List<Int>, emit: (List<Int>) -> Unit) {
        fun rec(prefix: List<Int>, rest: List<Int>) {
            if (rest.isEmpty()) {
                emit(prefix)
                return
            }
            for (i in rest.indices) rec(prefix + rest[i], rest.filterIndexed { j, _ -> j != i })
        }
        rec(emptyList(), items)
    }
}

/** Non-negative remainder (kotlin.math has none in common code). */
internal fun floorMod(a: Int, m: Int): Int = ((a % m) + m) % m
