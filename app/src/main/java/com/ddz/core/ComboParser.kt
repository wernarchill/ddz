package com.ddz.core

/**
 * 牌型识别。
 *
 * 判定优先级**严格**如下（先命中者胜出），这是整个引擎最容易写错的地方：
 *  1. 空列表 -> PASS
 *  2. ROCKET：恰好 16 + 17 两张王
 *  3. BOMB：4 张且 rank 全同
 *  4. STRAIGHT：>=5 张，rank 全不同、连续、最大 rank <= 14（A），length = 张数
 *  5. STRAIGHT_PAIR：>=3 对、每 rank 恰好 2 张、连续、最大 rank <= 14，length = 对数
 *  6. PLANE：n>=2 个连续三张，恰好 3n 张（**必须先于下面两条判定**，见 parsePlane 的说明）
 *  7. PLANE_TWO：n>=2 个连续三张（最大 rank <= 14）+ n 个对子，总张数 5n
 *  8. PLANE_ONE：n>=2 个连续三张（最大 rank <= 14）+ n 张单牌，总张数 4n
 *  9. FOUR_TWO_PAIR：四张 + 两个不同 rank 的对子（8 张）
 * 10. FOUR_TWO_SINGLE：四张 + 其余任意 2 张（6 张）
 * 11. TRIPLE_TWO：三张 + 一个对子（5 张）
 * 12. TRIPLE_ONE：三张 + 一张单牌（4 张）
 * 13. TRIPLE / 14. PAIR / 15. SINGLE
 * 16. 其它 -> INVALID
 *
 * 歧义消解说明（按上面的顺序自然得到）：
 *  - 3333 -> BOMB（张数 4，不走 FOUR_TWO_SINGLE 的 6 张分支）
 *  - 333444 -> PLANE(n=2)；33344455 -> PLANE_ONE(n=2)；333444555 -> PLANE(n=3)
 *  - 333444555666 -> PLANE(n=4)，**不是** PLANE_ONE(n=3, 翅膀=666)：整手都是连续三张时按纯飞机算
 *  - 333444555 + 6,6,7 -> PLANE_ONE(n=3, 翅膀={6,6,7})，此时 Combo.cards 恰好 12 张
 *  - 33344455566 -> INVALID（{3,4} 组数 2 需要 8/10 张，{3,4,5} 组数 3 需要 12/15 张，都不匹配）
 *  - 333355（6 张）-> FOUR_TWO_SINGLE，mainRank=3
 *  - 33344 -> TRIPLE_TWO（翅膀同 rank 优先当对子）
 *  - 王不可能是 BOMB：16/17 各只有 1 张，4 张同 rank 永远不含王
 */
object ComboParser {

    /** 入口：任意牌列表 -> Combo */
    fun parse(cards: List<Card>): Combo {
        if (cards.isEmpty()) return Combo.PASS
        return analyse(cards)
    }

    /** 按优先级逐条尝试，第一个成功的结果即为最终牌型 */
    private fun analyse(cards: List<Card>): Combo {
        val size = cards.size
        val count = IntArray(CardConst.RANK_TABLE)
        val byRank = HashMap<Int, MutableList<Card>>()
        for (c in cards) {
            if (c.rank in 0 until CardConst.RANK_TABLE) count[c.rank]++
            byRank.getOrPut(c.rank) { ArrayList() }.add(c)
        }

        // 2. ROCKET
        if (size == 2 && count[CardConst.JOKER_SMALL] == 1 && count[CardConst.JOKER_BIG] == 1) {
            return Combo(ComboType.ROCKET, CardConst.JOKER_BIG, 2, ArrayList(cards))
        }

        // 3. BOMB
        if (size == 4) {
            val r = singleRankOf(count)
            if (r > 0) return Combo(ComboType.BOMB, r, 4, ArrayList(cards))
        }

        // 4. STRAIGHT
        if (size >= 5) {
            var allDistinct = true
            var minR = CardConst.RANK_TABLE
            var maxR = 0
            for (r in CardConst.R3..CardConst.JOKER_BIG) {
                if (count[r] > 1) { allDistinct = false; break }
                if (count[r] == 1) {
                    if (r < minR) minR = r
                    if (r > maxR) maxR = r
                }
            }
            if (allDistinct && maxR <= CardConst.MAX_CHAIN_RANK && maxR - minR + 1 == size) {
                return Combo(ComboType.STRAIGHT, maxR, size, ArrayList(cards))
            }
        }

        // 5. STRAIGHT_PAIR
        if (size >= 6 && size % 2 == 0) {
            var ok = true
            var pairs = 0
            var minR = CardConst.RANK_TABLE
            var maxR = 0
            for (r in CardConst.R3..CardConst.JOKER_BIG) {
                val c = count[r]
                if (c == 0) continue
                if (c != 2) { ok = false; break }
                pairs++
                if (r < minR) minR = r
                if (r > maxR) maxR = r
            }
            if (ok && pairs >= 3 && maxR <= CardConst.MAX_CHAIN_RANK && maxR - minR + 1 == pairs) {
                return Combo(ComboType.STRAIGHT_PAIR, maxR, pairs, ArrayList(cards))
            }
        }

        // 6/7/8. PLANE 系列（内部按 PLANE_TWO -> PLANE_ONE -> PLANE 顺序、组数 n 从小到大尝试）
        val plane = parsePlane(size, count, cards, byRank)
        if (plane != null) return plane

        // 9. FOUR_TWO_PAIR
        if (size == 8) {
            for (r in CardConst.R3..CardConst.MAX_RANK) {
                if (count[r] != 4) continue
                // 剩余 4 张必须正好是两个不同 rank 的对子
                var pairs = 0
                var ok = true
                for (o in CardConst.R3..CardConst.MAX_RANK) {
                    if (o == r || count[o] == 0) continue
                    if (count[o] != 2) { ok = false; break }
                    pairs++
                }
                if (ok && pairs == 2) {
                    return Combo(ComboType.FOUR_TWO_PAIR, r, 8, ArrayList(cards))
                }
            }
        }

        // 10. FOUR_TWO_SINGLE
        if (size == 6) {
            for (r in CardConst.R3..CardConst.MAX_RANK) {
                if (count[r] == 4) return Combo(ComboType.FOUR_TWO_SINGLE, r, 6, ArrayList(cards))
            }
        }

        // 11. TRIPLE_TWO
        if (size == 5) {
            for (r in CardConst.R3..CardConst.MAX_RANK) {
                if (count[r] != 3) continue
                for (o in CardConst.R3..CardConst.MAX_RANK) {
                    if (o != r && count[o] == 2) {
                        return Combo(ComboType.TRIPLE_TWO, r, 5, ArrayList(cards))
                    }
                }
            }
        }

        // 12. TRIPLE_ONE
        if (size == 4) {
            for (r in CardConst.R3..CardConst.MAX_RANK) {
                if (count[r] == 3) return Combo(ComboType.TRIPLE_ONE, r, 4, ArrayList(cards))
            }
        }

        // 13. TRIPLE
        if (size == 3) {
            val r = singleRankOf(count)
            if (r > 0 && r <= CardConst.MAX_RANK) return Combo(ComboType.TRIPLE, r, 3, ArrayList(cards))
        }

        // 14. PAIR
        if (size == 2 && count[CardConst.JOKER_SMALL] == 0 && count[CardConst.JOKER_BIG] == 0) {
            val r = singleRankOf(count)
            if (r > 0) return Combo(ComboType.PAIR, r, 2, ArrayList(cards))
        }

        // 15. SINGLE（单张王也走这里）
        if (size == 1) return Combo(ComboType.SINGLE, cards[0].rank, 1, ArrayList(cards))

        // 16. INVALID
        return Combo.invalid()
    }

    /** 若所有牌同 rank 返回该 rank，否则返回 0 */
    private fun singleRankOf(count: IntArray): Int {
        var found = 0
        for (r in CardConst.R3..CardConst.JOKER_BIG) {
            if (count[r] > 0) {
                if (found != 0) return 0
                found = r
            }
        }
        return found
    }

    /**
     * 飞机系列。枚举所有可能的「连续三张串」：起点 [s]，长度 [n]（2..6）。
     *
     * **注意歧义的消解顺序**：先单独跑一遍「纯飞机」，再跑带翅膀的。
     * 原因是 12 张连续三张（如 333444555666）既满足 PLANE(n=4)，也满足 PLANE_ONE(n=3, 翅膀=666)，
     * 按标准规则应取前者（整串都是三张）。如果不先跑纯飞机，PLANE_ONE 会把它抢走，
     * 导致同一手牌在两家眼里牌型不同，比大小时出错。
     */
    private fun parsePlane(
        size: Int,
        count: IntArray,
        cards: List<Card>,
        byRank: Map<Int, List<Card>>
    ): Combo? {
        // 第一遍：纯飞机（整手牌恰好是 n 个连续三张）
        for (s in CardConst.R3..CardConst.MAX_CHAIN_RANK) {
            var maxRun = 0
            var t = s
            while (t <= CardConst.MAX_CHAIN_RANK && count[t] >= 3) {
                maxRun++
                t++
            }
            if (maxRun < 2) continue
            val limit = if (maxRun > 6) 6 else maxRun
            for (n in 2..limit) {
                if (size != 3 * n) continue
                var ok = true
                for (r in CardConst.R3..CardConst.JOKER_BIG) {
                    val expect = if (r in s..(s + n - 1)) 3 else 0
                    if (count[r] != expect) {
                        ok = false
                        break
                    }
                }
                if (ok) return Combo(ComboType.PLANE, s + n - 1, n, ArrayList(cards))
            }
        }

        // 第二遍：带翅膀的飞机
        for (s in CardConst.R3..CardConst.MAX_CHAIN_RANK) {
            var maxRun = 0
            var t = s
            while (t <= CardConst.MAX_CHAIN_RANK && count[t] >= 3) {
                maxRun++
                t++
            }
            if (maxRun < 2) continue
            val limit = if (maxRun > 6) 6 else maxRun
            var n = 2
            while (n <= limit) {
                val used = IntArray(CardConst.RANK_TABLE)
                for (k in 0 until n) used[s + k] = 3

                // 方案一：三张 + n 个对子
                if (size == 5 * n) {
                    val att = IntArray(CardConst.RANK_TABLE)
                    val at = attachStats(count, used, att)
                    if (at && canFormPairs(att, n)) {
                        return Combo(ComboType.PLANE_TWO, s + n - 1, n, buildPlaneCards(byRank, s, n, att))
                    }
                }

                // 方案二：三张 + n 张单牌（翅膀里允许把对子拆成两张单牌）
                if (size == 4 * n) {
                    val att = IntArray(CardConst.RANK_TABLE)
                    val at = attachStats(count, used, att)
                    if (at && totalCount(att) == n) {
                        return Combo(ComboType.PLANE_ONE, s + n - 1, n, buildPlaneCards(byRank, s, n, att))
                    }
                }
                n++
            }
        }
        return null
    }

    /**
     * 计算翅膀：att[r] = 该 rank 在翅膀里用掉几张。
     * 返回 false 表示该串不可能成立，调用方直接换下一个 n / s：
     *  - 某 rank 在串内却用了 4 张以上（串本身只取 3 张）
     *  - 某 rank 既不在串内又是 4 张（四张只能整个当炸弹，不能拆进飞机翅膀）
     *  - 有 rank 一张都没用上（说明该 rank 应该属于串，会导致串更长）
     */
    private fun attachStats(count: IntArray, used: IntArray, att: IntArray): Boolean {
        for (r in CardConst.R3..CardConst.JOKER_BIG) {
            val left = count[r] - used[r]
            if (left < 0) return false
            if (left > 3) return false
            if (used[r] > 0 && left == 3) return false
            if (used[r] == 0 && left == 4) return false
            att[r] = left
        }
        return true
    }

    /** 翅膀能否拆成 [n] 个对子（2n 张，且每个 rank 恰好 2 张） */
    private fun canFormPairs(att: IntArray, n: Int): Boolean {
        var total = 0
        var pairs = 0
        for (r in CardConst.R3..CardConst.JOKER_BIG) {
            val c = att[r]
            if (c == 0) continue
            if (c != 2) return false
            pairs++
            total += c
        }
        return total == 2 * n && pairs == n
    }

    private fun totalCount(count: IntArray): Int {
        var sum = 0
        for (r in CardConst.R3..CardConst.JOKER_BIG) sum += count[r]
        return sum
    }

    /**
     * 取出实际牌：主串每个 rank 取 3 张，翅膀每个 rank 取 att[r] 张。
     *
     * 这里必须用 att[r]（而不是固定张数）：带单的翅膀里，一个对子会被拆成两张单牌
     * （如 333444555 + 6,6,7 中的 6），固定取 1 张会让 Combo.cards 少一张，
     * 导致「提示」功能选错牌。
     */
    private fun buildPlaneCards(
        byRank: Map<Int, List<Card>>,
        start: Int,
        n: Int,
        att: IntArray
    ): List<Card> {
        val out = ArrayList<Card>(n * 5)
        for (k in 0 until n) {
            out.addAll(pick(byRank[start + k], 3))
        }
        for (r in CardConst.R3..CardConst.JOKER_BIG) {
            if (att[r] > 0) out.addAll(pick(byRank[r], att[r]))
        }
        return out
    }

    /** 安全取前 [n] 张（不足时全给） */
    private fun pick(list: List<Card>?, n: Int): List<Card> {
        if (list == null) return emptyList()
        return if (list.size <= n) ArrayList(list) else ArrayList(list.subList(0, n))
    }
}
