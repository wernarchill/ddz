package com.ddz.core

/**
 * 规则判定：比大小、重复牌校验、排序、枚举与提示。
 */
object Rules {

    /** 同 rank 的比较器：rank 降序，同 rank 按 suit 升序 */
    private val DESC: Comparator<Card> = Comparator { a, b ->
        if (a.rank != b.rank) b.rank - a.rank else a.suit - b.suit
    }

    /**
     * [candidate] 能否压过 [target]。
     * [target] 为 null 或 PASS 表示自由出牌，任何合法牌型都可以（但 PASS 本身不行）。
     */
    fun canBeat(candidate: Combo, target: Combo?): Boolean {
        if (!candidate.isValid) return false
        if (candidate.type == ComboType.PASS) return false
        if (target == null || target.type == ComboType.PASS || !target.isValid) return true

        // 王炸最大（一局只有一副王，理论上 target 不可能是 ROCKET）
        if (candidate.type == ComboType.ROCKET) return true
        if (target.type == ComboType.ROCKET) return false

        if (candidate.type == ComboType.BOMB) {
            return if (target.type == ComboType.BOMB) candidate.mainRank > target.mainRank else true
        }
        if (target.type == ComboType.BOMB) return false

        if (candidate.type != target.type) return false
        if (candidate.length != target.length) return false
        return candidate.mainRank > target.mainRank
    }

    /** hand 是否真的包含 cards（按 rank+suit 多重集判断，支持重复牌） */
    fun contains(hand: List<Card>, cards: List<Card>): Boolean {
        if (cards.isEmpty()) return true
        if (hand.size < cards.size) return false
        val pool = HashMap<Card, Int>(hand.size * 2)
        for (c in hand) {
            val old = pool[c]
            pool[c] = if (old == null) 1 else old + 1
        }
        for (c in cards) {
            val left = pool[c] ?: return false
            if (left <= 0) return false
            pool[c] = left - 1
        }
        return true
    }

    /** 从 hand 中移除 cards（每个元素移除一次），返回新列表，不改原列表 */
    fun remove(hand: List<Card>, cards: List<Card>): List<Card> {
        val left = ArrayList<Card>(hand)
        for (c in cards) {
            if (!left.remove(c)) return ArrayList(hand)
        }
        return left
    }

    /** 手牌排序：rank 从大到小；同 rank 按 suit 升序 */
    fun sortDesc(hand: List<Card>): List<Card> {
        val out = ArrayList(hand)
        out.sortWith(DESC)
        return out
    }

    // ------------------------------------------------------------------
    // 枚举
    // ------------------------------------------------------------------

    /**
     * 枚举手牌中所有合法牌型（去重：相同 rank 组合只保留一种）。
     *
     * 复杂度：先按 rank 分组 O(N)。
     *  - 顺子：起点 O(15) × 终点 O(15) = O(225)
     *  - 连对：O(225)
     *  - 飞机：串数 O(15×6) × 翅膀组合 C(15, n)（n<=6，即每串最多 C(15,6)=5005）
     *  - 四带二 / 三带 / 单双三：O(15) 级别
     * 故单次调用约 O(10^4) 量级、几十毫秒内完成；翅膀只取「最小最省牌」的组合，
     * 因此不会出现全排列笛卡尔积爆炸。
     */
    fun enumerateAll(hand: List<Card>): List<List<Card>> {
        val byRank = groupByRank(hand)
        val result = LinkedHashSet<List<Card>>()

        // 单张 / 对子 / 三张（含王；四张同点会被拆成 1+3 或 2+2，也一并产出）
        for (r in CardConst.R3..CardConst.JOKER_BIG) {
            val g = byRank[r] ?: continue
            if (g.size >= 1) result.add(ArrayList(g.subList(0, 1)))
            if (g.size >= 2) result.add(ArrayList(g.subList(0, 2)))
            if (g.size >= 3) result.add(ArrayList(g.subList(0, 3)))
        }

        // 炸弹（整个四张）
        for (r in CardConst.R3..CardConst.MAX_RANK) {
            val g = byRank[r] ?: continue
            if (g.size == 4) result.add(ArrayList(g))
        }

        // 顺子：长度 5..12，起点 s、终点 s+len-1（<= A）
        for (s in CardConst.R3..CardConst.MAX_CHAIN_RANK) {
            for (len in 5..12) {
                val end = s + len - 1
                if (end > CardConst.MAX_CHAIN_RANK) break
                result.add(chain(byRank, s, len, 1) ?: break)
            }
        }

        // 连对：对数 3..10
        for (s in CardConst.R3..CardConst.MAX_CHAIN_RANK) {
            for (n in 3..10) {
                val end = s + n - 1
                if (end > CardConst.MAX_CHAIN_RANK) break
                result.add(chain(byRank, s, n, 2) ?: break)
            }
        }

        // 三带一 / 三带二
        for (r in CardConst.R3..CardConst.MAX_RANK) {
            val g = byRank[r] ?: continue
            if (g.size < 3) continue
            val main = ArrayList(g.subList(0, 3))
            result.add(ArrayList(main))
            smallestWing(byRank, setOf(r), 1, 1)?.let { w ->
                val c = ArrayList(main); c.addAll(w); result.add(c)
            }
            smallestWing(byRank, setOf(r), 1, 2)?.let { w ->
                val c = ArrayList(main); c.addAll(w); result.add(c)
            }
        }

        // 四带两单 / 四带两对
        for (r in CardConst.R3..CardConst.MAX_RANK) {
            val g = byRank[r] ?: continue
            if (g.size < 4) continue
            val main = ArrayList(g.subList(0, 4))
            smallestWing(byRank, setOf(r), 2, 1)?.let { w ->
                val c = ArrayList(main); c.addAll(w); result.add(c)
            }
            smallestWing(byRank, setOf(r), 2, 2)?.let { w ->
                val c = ArrayList(main); c.addAll(w); result.add(c)
            }
        }

        // 飞机：组数 n=2..6，含纯飞机 / 带单 / 带对
        for (n in 2..6) {
            for (s in CardConst.R3..CardConst.MAX_CHAIN_RANK) {
                val end = s + n - 1
                if (end > CardConst.MAX_CHAIN_RANK) break
                val main = chain(byRank, s, n, 3) ?: break
                val banned = HashSet<Int>()
                for (k in 0 until n) banned.add(s + k)
                result.add(ArrayList(main))
                smallestWing(byRank, banned, n, 1)?.let { w ->
                    val c = ArrayList(main); c.addAll(w); result.add(c)
                }
                smallestWing(byRank, banned, n, 2)?.let { w ->
                    val c = ArrayList(main); c.addAll(w); result.add(c)
                }
            }
        }

        // 王炸
        val small = byRank[CardConst.JOKER_SMALL]
        val big = byRank[CardConst.JOKER_BIG]
        if (small != null && small.isNotEmpty() && big != null && big.isNotEmpty()) {
            val c = ArrayList<Card>(2)
            c.add(small[0]); c.add(big[0]); result.add(c)
        }

        val out = ArrayList<List<Card>>(result.size)
        for (c in result) out.add(sortDesc(c))
        return out
    }

    /** 取连续 [len] 个 rank、每个 rank [per] 张的牌，缺牌返回 null */
    private fun chain(byRank: Map<Int, List<Card>>, start: Int, len: Int, per: Int): List<Card>? {
        val out = ArrayList<Card>(len * per)
        for (k in 0 until len) {
            val g = byRank[start + k] ?: return null
            if (g.size < per) return null
            out.addAll(g.subList(0, per))
        }
        return out
    }

    private fun groupByRank(hand: List<Card>): Map<Int, List<Card>> {
        val map = LinkedHashMap<Int, MutableList<Card>>()
        for (c in hand) map.getOrPut(c.rank) { ArrayList() }.add(c)
        return map
    }

    // ------------------------------------------------------------------
    // 提示
    // ------------------------------------------------------------------

    /**
     * 找一手能压过 [target] 的最小牌；找不到返回 null。
     * 顺序：非炸弹最小可压牌 -> 炸弹 -> 王炸。
     * [target] 为 null/PASS 时（自由出牌）：整手牌合法就打完，否则出最小的单张。
     */
    fun findHint(hand: List<Card>, target: Combo?): List<Card>? {
        if (hand.isEmpty()) return null

        // 自由出牌
        if (target == null || target.type == ComboType.PASS || !target.isValid) {
            // 整手牌能一次打完就直接打完（能赢的提示就是最好的提示）
            if (hand.size <= 20) {
                val whole = ComboParser.parse(hand)
                if (whole.isValid && whole.type != ComboType.PASS) return sortDesc(hand)
            }
            // 否则出最小的单张
            var bestR = 0
            for (c in hand) if (bestR == 0 || c.rank < bestR) bestR = c.rank
            for (c in hand) if (c.rank == bestR) return arrayListOf(c)
            return null
        }

        val byRank = groupByRank(hand)

        // 1) 非炸弹：同型同长、mainRank 更大
        sameTypeCopy(byRank, target)?.let { return sortDesc(it) }

        // 2) 炸弹
        if (target.type == ComboType.BOMB) {
            // 只能压更大的炸弹
            var r = target.mainRank + 1
            while (r <= CardConst.MAX_RANK) {
                val g = byRank[r]
                if (g != null && g.size == 4) return sortDesc(ArrayList(g))
                r++
            }
        } else {
            var r = CardConst.R3
            while (r <= CardConst.MAX_RANK) {
                val g = byRank[r]
                if (g != null && g.size == 4) return sortDesc(ArrayList(g))
                r++
            }
        }

        // 3) 王炸
        val small = byRank[CardConst.JOKER_SMALL]
        val big = byRank[CardConst.JOKER_BIG]
        if (target.type != ComboType.ROCKET && small != null && small.isNotEmpty() && big != null && big.isNotEmpty()) {
            val c = ArrayList<Card>(2)
            c.add(small[0]); c.add(big[0])
            return sortDesc(c)
        }
        return null
    }

    /** 构造一手与 [target] 同型同长、mainRank 恰好更大的牌；没有则 null */
    private fun sameTypeCopy(byRank: Map<Int, List<Card>>, target: Combo): List<Card>? {
        when (target.type) {
            ComboType.SINGLE -> {
                var r = target.mainRank + 1
                while (r <= CardConst.JOKER_BIG) {
                    val g = byRank[r]
                    if (g != null && g.isNotEmpty()) return arrayListOf(g[0])
                    r++
                }
                return null
            }
            ComboType.PAIR -> {
                var r = target.mainRank + 1
                while (r <= CardConst.JOKER_BIG) {
                    val g = byRank[r]
                    if (g != null && g.size >= 2) return ArrayList(g.subList(0, 2))
                    r++
                }
                return null
            }
            ComboType.TRIPLE -> {
                var r = target.mainRank + 1
                while (r <= CardConst.MAX_RANK) {
                    val g = byRank[r]
                    if (g != null && g.size >= 3) return ArrayList(g.subList(0, 3))
                    r++
                }
                return null
            }
            ComboType.TRIPLE_ONE, ComboType.TRIPLE_TWO -> {
                val w = if (target.type == ComboType.TRIPLE_ONE) 1 else 2
                var r = target.mainRank + 1
                while (r <= CardConst.MAX_RANK) {
                    val g = byRank[r]
                    if (g != null && g.size >= 3) {
                        val wing = smallestWing(byRank, setOf(r), 1, w)
                        if (wing != null) {
                            val out = ArrayList(g.subList(0, 3)); out.addAll(wing); return out
                        }
                    }
                    r++
                }
                return null
            }
            ComboType.FOUR_TWO_SINGLE, ComboType.FOUR_TWO_PAIR -> {
                val w = if (target.type == ComboType.FOUR_TWO_SINGLE) 1 else 2
                var r = target.mainRank + 1
                while (r <= CardConst.MAX_RANK) {
                    val g = byRank[r]
                    if (g != null && g.size >= 4) {
                        val wing = smallestWing(byRank, setOf(r), 2, w)
                        if (wing != null) {
                            val out = ArrayList(g.subList(0, 4)); out.addAll(wing); return out
                        }
                    }
                    r++
                }
                return null
            }
            ComboType.STRAIGHT, ComboType.STRAIGHT_PAIR -> {
                val per = if (target.type == ComboType.STRAIGHT) 1 else 2
                val len = target.length
                // 只允许同长度，因此 mainRank 越小越难压；从 mainRank+1 开始逐个尝试，
                // 第一个成功的必然是最小的可压牌（不能只往「更长的顺子」方向找）
                var maxEnd = target.mainRank + 1
                while (maxEnd <= CardConst.MAX_CHAIN_RANK) {
                    val s = maxEnd - len + 1
                    if (s >= CardConst.R3) {
                        val c = chain(byRank, s, len, per)
                        if (c != null) return c
                    }
                    maxEnd++
                }
                return null
            }
            ComboType.PLANE, ComboType.PLANE_ONE, ComboType.PLANE_TWO -> {
                val n = target.length
                val mode = when (target.type) {
                    ComboType.PLANE -> 0
                    ComboType.PLANE_ONE -> 1
                    else -> 2
                }
                var maxEnd = target.mainRank + 1
                while (maxEnd <= CardConst.MAX_CHAIN_RANK) {
                    val s = maxEnd - n + 1
                    if (s >= CardConst.R3) {
                        val main = chain(byRank, s, n, 3)
                        if (main != null) {
                            if (mode == 0) return main
                            val banned = HashSet<Int>()
                            for (k in 0 until n) banned.add(s + k)
                            val wing = smallestWing(byRank, banned, n, mode)
                            if (wing != null) {
                                val out = ArrayList(main); out.addAll(wing); return out
                            }
                        }
                    }
                    maxEnd++
                }
                return null
            }
            else -> return null
        }
    }

    /**
     * 取最省牌的翅膀：[count] 个 rank，每个 rank 取 [per] 张，rank 尽量小。
     * [per] == 1 时优先取只有 1 张的 rank（不拆对子/三张）；[per] == 2 时只取恰好成对的 rank。
     */
    private fun smallestWing(
        byRank: Map<Int, List<Card>>,
        banned: Set<Int>,
        count: Int,
        per: Int
    ): List<Card>? {
        val chosen = ArrayList<List<Card>>(count)
        if (per == 1) {
            val ones = ArrayList<List<Card>>()
            val twos = ArrayList<List<Card>>()
            val threes = ArrayList<List<Card>>()
            for (r in CardConst.R3..CardConst.JOKER_BIG) {
                if (banned.contains(r)) continue
                val g = byRank[r] ?: continue
                when (g.size) {
                    1 -> ones.add(g)
                    2 -> twos.add(g)
                    3, 4 -> threes.add(g) // 三张/炸弹都可以拆一张出来当单牌
                }
            }
            for (g in ones) { if (chosen.size >= count) break; chosen.add(g.subList(0, 1)) }
            for (g in twos) { if (chosen.size >= count) break; chosen.add(g.subList(0, 1)) }
            for (g in threes) { if (chosen.size >= count) break; chosen.add(g.subList(0, 1)) }
        } else {
            for (r in CardConst.R3..CardConst.JOKER_BIG) {
                if (banned.contains(r)) continue
                val g = byRank[r] ?: continue
                if (g.size == 2) {
                    chosen.add(g)
                    if (chosen.size >= count) break
                }
            }
        }
        if (chosen.size < count) return null
        val out = ArrayList<Card>(count * per)
        for (g in chosen) out.addAll(g)
        return out
    }
}
