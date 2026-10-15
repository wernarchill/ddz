package com.ddz.core

import kotlin.random.Random

/**
 * 纯 JVM 自测入口，零依赖、只用 Kotlin 标准库：
 *
 *   kotlinc 编译后  java -cp <classes> com.ddz.core.RulesSelfTest
 *
 * 覆盖：16 种牌型识别与误判、顺子/连对/飞机的边界（2 与王不参与连续序列）、
 * 比大小、重复牌校验、排序、枚举手牌去重、提示优先级、计分倍数。
 */
object RulesSelfTest {

    private var passed = 0
    private var failed = 0

    @JvmStatic
    fun main(args: Array<String>) {
        println("==== 斗地主规则引擎自测 ====")

        testCardAndDeck()
        testAllComboTypes()
        testSequenceBoundary()
        testPlaneEdgeCases()
        testCanBeat()
        testContainsRemoveSort()
        testEnumerate()
        testFindHint()
        testScorer()

        println("----")
        println("通过: $passed, 失败: $failed, 合计: ${passed + failed}")
        println(if (failed > 0) "结果: FAILED" else "结果: ALL PASSED")
    }

    // ------------------------------------------------------------------
    // 断言工具
    // ------------------------------------------------------------------

    private fun check(condition: Boolean, message: String) {
        if (condition) {
            passed++
            println("[PASS] $message")
        } else {
            failed++
            println("[FAIL] $message")
        }
    }

    private fun eqInt(expected: Int, actual: Int, message: String) =
        check(expected == actual, "$message (expected=$expected, actual=$actual)")

    private fun eqType(expected: ComboType, actual: ComboType, message: String) =
        check(expected == actual, "$message (expected=$expected, actual=$actual)")

    private fun eqBool(expected: Boolean, actual: Boolean, message: String) =
        check(expected == actual, "$message (expected=$expected, actual=$actual)")

    private fun eqList(expected: List<Int>, actual: List<Int>, message: String) =
        check(expected == actual, "$message (expected=$expected, actual=$actual)")

    // ------------------------------------------------------------------
    // 造牌工具
    // ------------------------------------------------------------------

    /** 按 rank 构造牌，同一 rank 的第 n 张自动用第 n 个花色 */
    private fun hand(vararg ranks: Int): List<Card> {
        val used = HashMap<Int, Int>()
        val out = ArrayList<Card>(ranks.size)
        for (r in ranks) {
            val n = used[r] ?: 0
            used[r] = n + 1
            out.add(Card(r, if (r >= CardConst.JOKER_SMALL) CardConst.JOKER else n))
        }
        return out
    }

    /**
     * **注意：这个辅助函数会 `.sorted()`，把顺序信息销毁掉。**
     * 它适合做"集合相等"类断言（比较有哪些牌），
     * 但凡是**验证顺序**的断言（例如 sortDesc 是否真的降序）必须改用 [ranksInOrder]，
     * 否则实际值永远是有序的，断言永远比不出问题 —— 曾经有两条断言就是这么写错的。
     */
    private fun toRanks(cards: List<Card>): List<Int> = cards.map { it.rank }.sorted()

    /** 保序取出 rank（**不排序**），用于验证顺序类断言 */
    private fun ranksInOrder(cards: List<Card>): List<Int> = cards.map { it.rank }

    private fun keyOf(c: Card): String = c.rank.toString() + "_" + c.suit

    // ------------------------------------------------------------------
    // 1. 牌与牌堆
    // ------------------------------------------------------------------

    private fun testCardAndDeck() {
        println("\n[1] Card / Deck")
        eqBool(true, Card(CardConst.R3, CardConst.SPADE).label == "3", "label 3")
        eqBool(true, Card(CardConst.R10, CardConst.HEART).label == "10", "label 10")
        eqBool(true, Card(CardConst.RJ, CardConst.CLUB).label == "J", "label J")
        eqBool(true, Card(CardConst.RK, CardConst.DIAMOND).label == "K", "label K")
        eqBool(true, Card(CardConst.R2, CardConst.SPADE).label == "2", "label 2")
        eqBool(true, Card(CardConst.JOKER_SMALL, CardConst.JOKER).label == "小王", "label 小王")
        eqBool(true, Card(CardConst.JOKER_BIG, CardConst.JOKER).label == "大王", "label 大王")
        eqBool(true, Card(CardConst.R10, CardConst.SPADE).textureName == "card_s_10", "texture 黑桃10")
        // 贴图后缀必须小写：Android 资源名不允许大写字母，
        // 小写才能让同一份文件既放 assets/ 也放 res/drawable-nodpi/
        eqBool(true, Card(CardConst.RJ, CardConst.HEART).textureName == "card_h_j", "texture 红桃j（小写）")
        eqBool(true, Card(CardConst.R3, CardConst.CLUB).textureName == "card_c_3", "texture 梅花3")
        eqBool(true, Card(CardConst.RA, CardConst.DIAMOND).textureName == "card_d_a", "texture 方块a（小写）")
        eqBool(true, Card(CardConst.RQ, CardConst.SPADE).textureName == "card_s_q", "texture 黑桃q（小写）")
        eqBool(true, Card(CardConst.RK, CardConst.HEART).textureName == "card_h_k", "texture 红桃k（小写）")
        eqBool(true, Card(CardConst.R2, CardConst.SPADE).textureName == "card_s_2", "texture 黑桃2")
        // 但给人看的 label 仍是大写
        eqBool(true, Card(CardConst.RJ, CardConst.HEART).label == "J", "label 仍为大写 J")
        eqBool(true, Card(CardConst.JOKER_SMALL, CardConst.JOKER).textureName == "card_joker_small", "texture 小王")
        eqBool(true, Card(CardConst.JOKER_BIG, CardConst.JOKER).textureName == "card_joker_big", "texture 大王")
        eqBool(false, Card(CardConst.R2, CardConst.SPADE).isJoker, "2 不是王")
        eqBool(true, Card(CardConst.JOKER_BIG, CardConst.JOKER).isJoker, "大王 isJoker")

        val full = Deck.full()
        eqInt(54, full.size, "full() 张数 54")
        eqInt(54, full.toSet().size, "full() 无重复")
        eqInt(CardConst.R3, full[0].rank, "full() 首张 rank=3")
        eqInt(CardConst.SPADE, full[0].suit, "full() 首张 suit=黑桃")
        eqInt(CardConst.R2, full[12].rank, "full() 第 13 张 rank=2")
        eqInt(CardConst.JOKER_SMALL, full[52].rank, "full() 第 53 张是小王")
        eqInt(CardConst.JOKER_BIG, full[53].rank, "full() 第 54 张是大王")

        val shuffled = Deck.shuffled(Random(20240517))
        eqInt(54, shuffled.size, "shuffled() 张数 54")
        eqInt(54, shuffled.toSet().size, "shuffled() 无重复无丢失")
        check(shuffled != full, "shuffled() 与 full() 顺序不同")
        eqBool(true, shuffled.toSet() == full.toSet(), "shuffled() 与 full() 是同一副牌")

        val deal = Deck.deal(shuffled)
        eqInt(3, deal.hands.size, "deal() 三家")
        eqInt(17, deal.hands[0].size, "deal() 手牌 17 张")
        eqInt(3, deal.bottom.size, "deal() 底牌 3 张")
        val all = ArrayList<Card>()
        for (h in deal.hands) all.addAll(h)
        all.addAll(deal.bottom)
        eqInt(54, all.toSet().size, "发牌覆盖 54 张不重不漏")
    }

    // ------------------------------------------------------------------
    // 2. 十六种牌型识别
    // ------------------------------------------------------------------

    private fun testAllComboTypes() {
        println("\n[2] 牌型识别")
        val pass = ComboParser.parse(emptyList())
        eqType(ComboType.PASS, pass.type, "空列表 -> PASS")
        eqInt(0, pass.mainRank, "PASS mainRank=0")
        eqInt(0, pass.length, "PASS length=0")
        eqBool(false, pass.isValid, "PASS 不合法（不可主动出）")

        val single = ComboParser.parse(hand(CardConst.R5))
        eqType(ComboType.SINGLE, single.type, "单张 -> SINGLE")
        eqInt(CardConst.R5, single.mainRank, "SINGLE mainRank=5")
        eqInt(1, single.length, "SINGLE length=1")

        val pair = ComboParser.parse(hand(CardConst.R5, CardConst.R5))
        eqType(ComboType.PAIR, pair.type, "两张同点 -> PAIR")
        eqInt(CardConst.R5, pair.mainRank, "PAIR mainRank=5")
        eqInt(2, pair.length, "PAIR length=2")

        val triple = ComboParser.parse(hand(CardConst.R7, CardConst.R7, CardConst.R7))
        eqType(ComboType.TRIPLE, triple.type, "三张同点 -> TRIPLE")
        eqInt(CardConst.R7, triple.mainRank, "TRIPLE mainRank=7")
        eqInt(3, triple.length, "TRIPLE length=3")

        val t1 = ComboParser.parse(hand(CardConst.R7, CardConst.R7, CardConst.R7, CardConst.R9))
        eqType(ComboType.TRIPLE_ONE, t1.type, "三带一 -> TRIPLE_ONE")
        eqInt(CardConst.R7, t1.mainRank, "TRIPLE_ONE mainRank=7")
        eqInt(4, t1.length, "TRIPLE_ONE length=4")

        val t2 = ComboParser.parse(hand(CardConst.R7, CardConst.R7, CardConst.R7, CardConst.R9, CardConst.R9))
        eqType(ComboType.TRIPLE_TWO, t2.type, "三带一对 -> TRIPLE_TWO")
        eqInt(CardConst.R7, t2.mainRank, "TRIPLE_TWO mainRank=7")
        eqInt(5, t2.length, "TRIPLE_TWO length=5")
        eqType(ComboType.TRIPLE_TWO, ComboParser.parse(hand(3, 3, 3, 4, 4)).type, "33344（翅膀同点）-> TRIPLE_TWO")

        val straight = ComboParser.parse(hand(3, 4, 5, 6, 7))
        eqType(ComboType.STRAIGHT, straight.type, "34567 -> STRAIGHT")
        eqInt(7, straight.mainRank, "STRAIGHT mainRank=7")
        eqInt(5, straight.length, "STRAIGHT length=5")

        val straight12 = ComboParser.parse(hand(3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14))
        eqType(ComboType.STRAIGHT, straight12.type, "3~A 十二张 -> STRAIGHT")
        eqInt(14, straight12.mainRank, "12 张顺 mainRank=A")
        eqInt(12, straight12.length, "12 张顺 length=12")

        val sp = ComboParser.parse(hand(3, 3, 4, 4, 5, 5))
        eqType(ComboType.STRAIGHT_PAIR, sp.type, "334455 -> STRAIGHT_PAIR")
        eqInt(5, sp.mainRank, "连对 mainRank=5")
        eqInt(3, sp.length, "连对 length=对数 3")

        val sp10 = ComboParser.parse(hand(3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12))
        eqType(ComboType.STRAIGHT_PAIR, sp10.type, "3~Q 十连对 -> STRAIGHT_PAIR")
        eqInt(12, sp10.mainRank, "10 连对 mainRank=Q")
        eqInt(10, sp10.length, "10 连对 length=10")

        val plane = ComboParser.parse(hand(3, 3, 3, 4, 4, 4))
        eqType(ComboType.PLANE, plane.type, "333444 -> PLANE")
        eqInt(4, plane.mainRank, "PLANE(n=2) mainRank=4")
        eqInt(2, plane.length, "PLANE(n=2) length=2")

        val plane3 = ComboParser.parse(hand(3, 3, 3, 4, 4, 4, 5, 5, 5))
        eqType(ComboType.PLANE, plane3.type, "333444555 -> PLANE(n=3)")
        eqInt(5, plane3.mainRank, "PLANE(n=3) mainRank=5")
        eqInt(3, plane3.length, "PLANE(n=3) length=3")

        val po = ComboParser.parse(hand(3, 3, 3, 4, 4, 4, 5, 5))
        eqType(ComboType.PLANE_ONE, po.type, "33344455 -> PLANE_ONE(n=2)")
        eqInt(4, po.mainRank, "PLANE_ONE(n=2) mainRank=4")
        eqInt(2, po.length, "PLANE_ONE(n=2) length=2")

        val po2 = ComboParser.parse(hand(3, 3, 3, 4, 4, 4, 5, 7))
        eqType(ComboType.PLANE_ONE, po2.type, "33344457 -> PLANE_ONE(n=2)")
        eqBool(true, Rules.contains(po2.cards, hand(5)), "PLANE_ONE 翅膀含单张 5")

        // 回归用例 1：飞机带单必须恰好 4n 张。7 张（两副三张 + 1 张）不是合法牌型。
        // 原自测这里写成 hand(3,3,3,4,4,4,5) 却断言 PLANE_ONE，是断言本身写错了。
        eqType(
            ComboType.INVALID,
            ComboParser.parse(hand(3, 3, 3, 4, 4, 4, 5)).type,
            "3334445（7 张）不是飞机带单"
        )

        // 回归用例 2：翅膀里出现对子（55）时，Combo.cards 必须把两张 5 都取出来。
        // 早期 buildPlaneCards 固定每个翅膀 rank 只取 1 张，会导致 cards 比输入少一张，
        // 进而让「提示」功能选中错误的牌。
        eqInt(8, po.cards.size, "PLANE_ONE 的 cards 张数必须与输入一致")
        eqBool(true, Rules.contains(po.cards, hand(5, 5)), "PLANE_ONE 翅膀是对子时两张都要在")

        val pt = ComboParser.parse(hand(3, 3, 3, 4, 4, 4, 5, 5, 6, 6))
        eqType(ComboType.PLANE_TWO, pt.type, "3334445566 -> PLANE_TWO(n=2)")
        eqInt(4, pt.mainRank, "PLANE_TWO(n=2) mainRank=4")
        eqInt(2, pt.length, "PLANE_TWO(n=2) length=2")

        val f2s = ComboParser.parse(hand(3, 3, 3, 3, 5, 5))
        eqType(ComboType.FOUR_TWO_SINGLE, f2s.type, "333355 -> FOUR_TWO_SINGLE")
        eqInt(3, f2s.mainRank, "FOUR_TWO_SINGLE mainRank=3")
        eqInt(6, f2s.length, "FOUR_TWO_SINGLE length=6")
        eqType(ComboType.FOUR_TWO_SINGLE, ComboParser.parse(hand(3, 3, 3, 3, 5, 7)).type, "333357 -> FOUR_TWO_SINGLE")

        val f2p = ComboParser.parse(hand(3, 3, 3, 3, 5, 5, 6, 6))
        eqType(ComboType.FOUR_TWO_PAIR, f2p.type, "33335566 -> FOUR_TWO_PAIR")
        eqInt(3, f2p.mainRank, "FOUR_TWO_PAIR mainRank=3")
        eqInt(8, f2p.length, "FOUR_TWO_PAIR length=8")

        val bomb = ComboParser.parse(hand(9, 9, 9, 9))
        eqType(ComboType.BOMB, bomb.type, "9999 -> BOMB")
        eqInt(9, bomb.mainRank, "BOMB mainRank=9")
        eqInt(4, bomb.length, "BOMB length=4")
        eqType(ComboType.BOMB, ComboParser.parse(hand(15, 15, 15, 15)).type, "2222 -> BOMB")

        val rocket = ComboParser.parse(hand(CardConst.JOKER_SMALL, CardConst.JOKER_BIG))
        eqType(ComboType.ROCKET, rocket.type, "双王 -> ROCKET")
        eqInt(CardConst.JOKER_BIG, rocket.mainRank, "ROCKET mainRank=JOKER_BIG")
        eqInt(2, rocket.length, "ROCKET length=2")
        // 王不可能凑成 BOMB：4 张同 rank 的牌永远不含王
        // 222 + 小王：三带一的翅膀可以是任意单张，**包括王**（888+大王 是经典打法）。
        // 原断言写的是 INVALID，属于把规则记错了。
        eqType(ComboType.TRIPLE_ONE, ComboParser.parse(hand(CardConst.JOKER_SMALL, 15, 15, 15)).type, "222+小王 -> TRIPLE_ONE（翅膀可以是王）")
        eqType(ComboType.TRIPLE_ONE, ComboParser.parse(hand(15, 15, 15, 14)).type, "222+A -> TRIPLE_ONE")

        // 需求里点名的歧义项
        eqType(ComboType.BOMB, ComboParser.parse(hand(3, 3, 3, 3)).type, "3333 -> BOMB（不是四带二）")
        eqType(ComboType.INVALID, ComboParser.parse(hand(3, 3, 3, 3, 4)).type, "33334 张数不对 -> INVALID")
        eqType(ComboType.INVALID, ComboParser.parse(hand(3, 3, 3, 3, 4, 4, 4)).type, "3333444 -> INVALID")
        eqType(ComboType.INVALID, ComboParser.parse(hand(3, 3, 3, 4, 4, 4, 5, 5, 5, 6, 6)).type, "33344455566 -> INVALID")

        // 基础误判检查
        eqType(ComboType.INVALID, ComboParser.parse(hand(3, 4)).type, "3,4 -> INVALID")
        eqType(ComboType.INVALID, ComboParser.parse(hand(3, 3, 4)).type, "334 -> INVALID")
        eqType(ComboType.TRIPLE_ONE, ComboParser.parse(hand(3, 3, 3, 4)).type, "3334 -> TRIPLE_ONE")
        eqType(ComboType.SINGLE, ComboParser.parse(hand(CardConst.JOKER_SMALL)).type, "单张小王 -> SINGLE")
        eqType(ComboType.SINGLE, ComboParser.parse(hand(CardConst.JOKER_BIG)).type, "单张大王 -> SINGLE")
        eqBool(true, ComboParser.parse(hand(CardConst.JOKER_SMALL)).mainRank == CardConst.JOKER_SMALL, "单张小王 mainRank=16")
        eqType(ComboType.INVALID, ComboParser.parse(hand(CardConst.JOKER_SMALL, CardConst.JOKER_BIG, 3)).type, "双王+3 -> INVALID")
        // 8 张 = 四张 + 两组对子，按优先级归 FOUR_TWO_PAIR（不是飞机/炸弹）
        // 33334444（8 张）：读作「333+444 带 3、4 两张单牌」= 飞机带单，合法。
        // 它**不是**四带两对：四带两对要求两个**不同 rank** 的对子，而这里 4 出现了 4 张。
        // 原断言写的是 FOUR_TWO_PAIR，属于把规则记错了。
        eqType(ComboType.PLANE_ONE, ComboParser.parse(hand(3, 3, 3, 3, 4, 4, 4, 4)).type, "33334444 -> PLANE_ONE（333+444 带 3、4）")
        // 333344（6 张）= 四张 + 任意两张，那两张恰好成对也算「四带二」，合法。
        // 原断言写的是 INVALID，属于把规则记错了。
        eqType(ComboType.FOUR_TWO_SINGLE, ComboParser.parse(hand(3, 3, 3, 3, 4, 4)).type, "333344 -> FOUR_TWO_SINGLE（四带二，带的对子算两张单牌）")
    }

    // ------------------------------------------------------------------
    // 3. 连续序列边界（2 与王不参与）
    // ------------------------------------------------------------------

    private fun testSequenceBoundary() {
        println("\n[3] 顺子 / 连对边界")
        eqType(ComboType.INVALID, ComboParser.parse(hand(3, 4, 5, 6, 8)).type, "3,4,5,6,8 不连续 -> INVALID")
        eqType(ComboType.INVALID, ComboParser.parse(hand(11, 12, 13, 14, 15)).type, "J,Q,K,A,2 含 2 -> INVALID")
        eqType(ComboType.INVALID, ComboParser.parse(hand(12, 13, 14, 15, 3)).type, "Q,K,A,2,3 -> INVALID")
        eqType(ComboType.INVALID, ComboParser.parse(hand(14, 15, 3, 4, 5)).type, "A,2,3,4,5 -> INVALID")
        eqType(ComboType.INVALID, ComboParser.parse(hand(14, 15)).type, "A,2 -> INVALID")
        eqType(ComboType.INVALID, ComboParser.parse(hand(15, 16, 17)).type, "2+双王 -> INVALID")
        eqType(ComboType.STRAIGHT, ComboParser.parse(hand(10, 11, 12, 13, 14)).type, "10,J,Q,K,A -> STRAIGHT")
        // rank 13..17 数值上连续，2 与王并不在 13..15，因此这手牌字面上仍是「连续单牌」，但最大 rank>14，故不可能是顺子
        eqType(ComboType.INVALID, ComboParser.parse(hand(13, 14, 15, 16, 17)).type, "K,A,2,小王,大王 最大 rank>14 -> INVALID")

        eqType(ComboType.INVALID, ComboParser.parse(hand(15, 15, 3, 3, 4, 4)).type, "连对含 2 -> INVALID")
        eqType(ComboType.INVALID, ComboParser.parse(hand(14, 14, 15, 15, 3, 3)).type, "AA2233 -> INVALID")
        eqType(ComboType.STRAIGHT_PAIR, ComboParser.parse(hand(12, 12, 13, 13, 14, 14)).type, "QQKKAA -> STRAIGHT_PAIR")
        eqType(ComboType.INVALID, ComboParser.parse(hand(3, 3, 4, 4)).type, "3344 只有两对 -> INVALID")
    }

    // ------------------------------------------------------------------
    // 4. 飞机边界
    // ------------------------------------------------------------------

    private fun testPlaneEdgeCases() {
        println("\n[4] 飞机边界")
        eqType(ComboType.PLANE, ComboParser.parse(hand(13, 13, 13, 14, 14, 14)).type, "KKKAAA -> PLANE(n=2)")
        eqType(ComboType.INVALID, ComboParser.parse(hand(13, 13, 13, 14, 14, 14, 15, 15, 15)).type, "KKKAAA222 -> INVALID（2 不参与连续序列）")
        eqType(ComboType.PLANE_ONE, ComboParser.parse(hand(13, 13, 13, 14, 14, 14, 15, 15)).type, "KKKAAA22 -> PLANE_ONE(n=2，翅膀 22)")
        eqInt(14, ComboParser.parse(hand(13, 13, 13, 14, 14, 14, 15, 15)).mainRank, "KKKAAA22 mainRank=A")
        eqType(ComboType.INVALID, ComboParser.parse(hand(13, 13, 13, 14, 14, 14, 15)).type, "KKKAAA2 -> INVALID（翅膀只有 1 张）")
        // KKK+AAA 是合法飞机（K、A 连续），翅膀 33 与 K/A 是否连续**无关** —— 翅膀可以是任意牌。
        // 原断言写的是 INVALID，属于把"连续的必须是三张组、不是翅膀"记反了。
        eqType(ComboType.PLANE_ONE, ComboParser.parse(hand(13, 13, 13, 14, 14, 14, 3, 3)).type, "KKKAAA33 -> PLANE_ONE（翅膀 33 无需与 K/A 连续）")

        eqType(ComboType.INVALID, ComboParser.parse(hand(3, 3, 3, 5, 5, 5)).type, "333555 三张不连续 -> INVALID")
        eqType(ComboType.INVALID, ComboParser.parse(hand(3, 3, 3, 4, 4, 5, 5, 5)).type, "33344555 -> INVALID")
        eqType(ComboType.INVALID, ComboParser.parse(hand(3, 3, 3, 4, 4, 5, 6, 7)).type, "33344+5+6+7 翅膀要多 1 张 -> INVALID")
        eqType(ComboType.PLANE_ONE, ComboParser.parse(hand(3, 3, 3, 4, 4, 4, 5, 6)).type, "33344456 -> PLANE_ONE(n=2)")
        eqType(ComboType.INVALID, ComboParser.parse(hand(3, 3, 3, 3, 4, 4, 4)).type, "3333444 -> INVALID（333 或 444 必剩单张）")
        eqType(ComboType.PLANE, ComboParser.parse(hand(3, 3, 3, 4, 4, 4, 5, 5, 5, 6, 6, 6)).type, "3~6 飞机(n=4)")
        eqType(ComboType.PLANE, ComboParser.parse(hand(3, 3, 3, 4, 4, 4, 5, 5, 5, 6, 6, 6, 7, 7, 7, 8, 8, 8)).type, "3~8 飞机(n=6)")
        eqType(ComboType.INVALID, ComboParser.parse(hand(3, 3, 3, 4, 4, 4, 5, 5, 5, 6, 6, 6, 7, 7, 7, 8, 8, 8, 9, 9, 9)).type, "n=7 超出 6 组 -> INVALID")
        eqType(ComboType.PLANE_ONE, ComboParser.parse(hand(3, 3, 3, 4, 4, 4, 5, 5, 5, 6, 6, 6, 7, 7, 8, 9)).type, "3~6 飞机 + 4 张翅膀 -> PLANE_ONE(n=4)")

        val f2s = ComboParser.parse(hand(3, 3, 3, 3, 16, 17))
        eqType(ComboType.FOUR_TWO_SINGLE, f2s.type, "3333 + 双王 -> FOUR_TWO_SINGLE（翅膀任意两张）")
        eqInt(3, f2s.mainRank, "3333+双王 mainRank=3")
    }

    // ------------------------------------------------------------------
    // 5. 比大小
    // ------------------------------------------------------------------

    private fun testCanBeat() {
        println("\n[5] canBeat")
        val s3 = ComboParser.parse(hand(3))
        val s5 = ComboParser.parse(hand(5))
        eqBool(true, Rules.canBeat(s5, s3), "单 5 压单 3")
        eqBool(false, Rules.canBeat(s3, s5), "单 3 不压单 5")
        eqBool(false, Rules.canBeat(s5, s5), "同点单张不可压")
        eqBool(true, Rules.canBeat(s5, null), "自由出牌：任意合法牌型")
        eqBool(true, Rules.canBeat(s5, Combo.PASS), "自由出牌（target=PASS）")
        eqBool(false, Rules.canBeat(Combo.PASS, null), "自由出牌也不能出 PASS")
        eqBool(false, Rules.canBeat(Combo.invalid(), null), "自由出牌也不能出非法牌型")
        eqBool(false, Rules.canBeat(Combo.PASS, s3), "PASS 不能压任何牌")
        eqBool(true, Rules.canBeat(ComboParser.parse(hand(16)), null), "单张小王可自由出")
        eqBool(true, Rules.canBeat(ComboParser.parse(hand(16)), s3), "小王压单 3")

        val p3 = ComboParser.parse(hand(3, 3))
        val p4 = ComboParser.parse(hand(4, 4))
        eqBool(true, Rules.canBeat(p4, p3), "对 4 压对 3")
        eqBool(false, Rules.canBeat(s5, p3), "单张不能压对子")
        eqBool(false, Rules.canBeat(p4, s3), "对子不能压单张")

        val t7 = ComboParser.parse(hand(7, 7, 7))
        val t7one = ComboParser.parse(hand(7, 7, 7, 9))
        val t8one = ComboParser.parse(hand(8, 8, 8, 3))
        val t7two = ComboParser.parse(hand(7, 7, 7, 9, 9))
        eqBool(false, Rules.canBeat(t7, t7one), "三张不能压三带一（长度不同）")
        eqBool(true, Rules.canBeat(t8one, t7one), "888+3 压 777+9")
        eqBool(false, Rules.canBeat(t7two, t7one), "三带二不能压三带一")
        eqBool(false, Rules.canBeat(t7one, t7two), "三带一不能压三带二")

        val st5 = ComboParser.parse(hand(3, 4, 5, 6, 7))
        val st5b = ComboParser.parse(hand(4, 5, 6, 7, 8))
        val st6 = ComboParser.parse(hand(3, 4, 5, 6, 7, 8))
        eqBool(true, Rules.canBeat(st5b, st5), "同长顺子比最大牌")
        eqBool(false, Rules.canBeat(st5, st5b), "小顺子不能压大顺子")
        eqBool(false, Rules.canBeat(st6, st5), "不同长度顺子不可比")

        val sp3 = ComboParser.parse(hand(3, 3, 4, 4, 5, 5))
        val sp3b = ComboParser.parse(hand(4, 4, 5, 5, 6, 6))
        eqBool(true, Rules.canBeat(sp3b, sp3), "连对同对数比最大牌")
        eqBool(false, Rules.canBeat(st5, sp3), "顺子不能压连对")

        val plane2 = ComboParser.parse(hand(3, 3, 3, 4, 4, 4))
        val plane2b = ComboParser.parse(hand(4, 4, 4, 5, 5, 5))
        eqBool(true, Rules.canBeat(plane2b, plane2), "飞机同组数比最大牌")
        eqBool(false, Rules.canBeat(plane2, plane2b), "小飞机不压大飞机")

        val bomb9 = ComboParser.parse(hand(9, 9, 9, 9))
        val bomb5 = ComboParser.parse(hand(5, 5, 5, 5))
        val bomb2 = ComboParser.parse(hand(15, 15, 15, 15))
        eqBool(true, Rules.canBeat(bomb9, bomb5), "大炸压小炸")
        eqBool(false, Rules.canBeat(bomb5, bomb9), "小炸不压大炸")
        eqBool(true, Rules.canBeat(bomb5, st5), "炸弹压顺子")
        eqBool(true, Rules.canBeat(bomb5, s3), "炸弹压单张")
        eqBool(true, Rules.canBeat(bomb5, t7), "炸弹压三张")
        eqBool(false, Rules.canBeat(st5, bomb5), "普通牌型压不过炸弹")

        val rocket = ComboParser.parse(hand(16, 17))
        eqBool(true, Rules.canBeat(rocket, bomb2), "王炸压最大炸弹")
        eqBool(true, Rules.canBeat(rocket, st5), "王炸压顺子")
        eqBool(false, Rules.canBeat(bomb2, rocket), "炸弹压不过王炸")
        eqBool(false, Rules.canBeat(rocket, rocket), "王炸不可互压")
        eqBool(true, Rules.canBeat(rocket, null), "自由出牌可出王炸")
        // canBeat 会校验牌型合法性（非法牌型一律压不过）——这比"交给上层负责"更安全，
        // 因为服务端和 AI 都依赖它做唯一判定。原断言期望 true，是把契约写反了。
        eqBool(false, Rules.canBeat(ComboParser.parse(hand(3, 4)), null), "自由出牌也必须先过牌型校验（非法牌型返回 false）")
        eqBool(false, Rules.canBeat(bomb5, null).not(), "自由出牌炸弹合法")
    }

    // ------------------------------------------------------------------
    // 6. contains / remove / sortDesc
    // ------------------------------------------------------------------

    private fun testContainsRemoveSort() {
        println("\n[6] contains / remove / sortDesc")
        val h = hand(3, 3, 4, 5, 5)
        eqBool(true, Rules.contains(h, hand(3)), "contains 单个 3")
        eqBool(true, Rules.contains(h, hand(5, 5)), "contains 两张 5")
        eqBool(false, Rules.contains(h, hand(3, 3, 3)), "contains 三张 3（手里只有两张）")
        eqBool(true, Rules.contains(h, emptyList()), "contains 空列表恒真")
        eqBool(false, Rules.contains(h, hand(6)), "contains 不存在的牌")
        eqBool(true, Rules.contains(h, h), "contains 自身")

        val dup = listOf(Card(3, CardConst.SPADE), Card(3, CardConst.HEART))
        eqBool(true, Rules.contains(dup, listOf(Card(3, CardConst.SPADE), Card(3, CardConst.HEART))), "花色不同的两张 3 都在")
        eqBool(true, Rules.contains(dup, listOf(Card(3, CardConst.HEART))), "只查红桃 3")
        eqBool(false, Rules.contains(dup, listOf(Card(3, CardConst.SPADE), Card(3, CardConst.SPADE))), "同花色不可重复计数")
        eqBool(false, Rules.contains(dup, listOf(Card(3, CardConst.CLUB))), "花色不匹配")

        val rem = Rules.remove(hand(3, 3, 4, 5, 5), hand(3, 5))
        eqInt(3, rem.size, "remove 后张数 3")
        eqBool(true, Rules.contains(rem, listOf(Card(3, CardConst.HEART))), "remove 保留了红桃 3（先移除黑桃 3）")
        eqBool(true, Rules.contains(rem, listOf(Card(5, CardConst.HEART))), "remove 保留了红桃 5")
        val original = hand(3, 3, 4, 5, 5)
        Rules.remove(original, hand(3, 3, 4, 5, 5))
        eqInt(5, original.size, "remove 不修改原列表")

        eqList(listOf(15, 14, 10, 5, 4, 3), ranksInOrder(Rules.sortDesc(hand(3, 15, 5, 14, 4, 10))), "sortDesc rank 降序")
        val same = listOf(
            Card(9, CardConst.DIAMOND), Card(9, CardConst.SPADE),
            Card(9, CardConst.CLUB), Card(9, CardConst.HEART)
        )
        eqList(
            listOf(CardConst.SPADE, CardConst.HEART, CardConst.CLUB, CardConst.DIAMOND),
            Rules.sortDesc(same).map { it.suit },
            "sortDesc 同 rank 按 suit 升序"
        )
        val input = hand(3, 15, 7)
        Rules.sortDesc(input)
        eqList(listOf(3, 15, 7), ranksInOrder(input), "sortDesc 不修改原列表（保序比较）")
    }

    // ------------------------------------------------------------------
    // 7. enumerateAll
    // ------------------------------------------------------------------

    private fun testEnumerate() {
        println("\n[7] enumerateAll")
        val full = Deck.full()
        val all = Rules.enumerateAll(full)
        check(all.size > 200, "全牌堆枚举数量合理 (${all.size})")
        var everyValid = true
        var everyContained = true
        for (cs in all) {
            if (ComboParser.parse(cs).type == ComboType.INVALID) everyValid = false
            if (!Rules.contains(full, cs)) everyContained = false
        }
        eqBool(true, everyValid, "枚举结果全部是合法牌型")
        eqBool(true, everyContained, "枚举结果全部来自手牌")
        eqInt(all.size, all.map { cs -> cs.map { c -> keyOf(c) }.sorted().joinToString("|") }.toSet().size, "枚举结果无重复")

        val e1 = Rules.enumerateAll(hand(3, 4, 5, 6, 7, 8))
        eqBool(true, e1.any { ComboParser.parse(it).type == ComboType.STRAIGHT && toRanks(it) == listOf(3, 4, 5, 6, 7) }, "枚举含 34567 顺子")
        eqBool(true, e1.any { ComboParser.parse(it).type == ComboType.STRAIGHT && toRanks(it) == listOf(4, 5, 6, 7, 8) }, "枚举含 45678 顺子")
        eqBool(true, e1.any { ComboParser.parse(it).type == ComboType.STRAIGHT && it.size == 6 }, "枚举含 3~8 六张顺子")
        eqBool(true, e1.any { ComboParser.parse(it).type == ComboType.SINGLE && toRanks(it) == listOf(8) }, "枚举含最大单张 8")

        val e2 = Rules.enumerateAll(hand(3, 3, 4, 4, 5, 5))
        eqBool(true, e2.any { ComboParser.parse(it).type == ComboType.STRAIGHT_PAIR && toRanks(it) == listOf(3, 3, 4, 4, 5, 5) }, "枚举含 334455 连对")
        eqBool(true, e2.any { ComboParser.parse(it).type == ComboType.PAIR && toRanks(it) == listOf(3, 3) }, "枚举含最小对子 33")
        // 原断言用的是 334455 —— 每个点数只有 2 张，那种手牌里**根本不存在三张**，
        // 断言必然失败。这里换成真正含三张的手牌。
        eqBool(true, Rules.enumerateAll(hand(3, 3, 3, 4, 4, 4)).any { ComboParser.parse(it).type == ComboType.TRIPLE }, "枚举含三张")

        val e3 = Rules.enumerateAll(hand(3, 3, 3, 4, 4, 4, 5, 5))
        eqBool(true, e3.any { ComboParser.parse(it).type == ComboType.PLANE }, "枚举含纯飞机")
        eqBool(true, e3.any { ComboParser.parse(it).type == ComboType.PLANE_ONE }, "枚举含飞机带单")

        // 飞机带对需要 5n 张：(n=2) 必须 10 张。
        // 原断言用了只有 8 张的 33344455，那种手牌在数学上凑不出飞机带对。
        eqBool(
            true,
            Rules.enumerateAll(hand(3, 3, 3, 4, 4, 4, 5, 5, 6, 6)).any { ComboParser.parse(it).type == ComboType.PLANE_TWO },
            "枚举含飞机带对（3334445566）"
        )

        val e4 = Rules.enumerateAll(hand(9, 9, 9, 9))
        eqBool(true, e4.any { ComboParser.parse(it).type == ComboType.BOMB }, "枚举含炸弹")
        eqBool(true, e4.any { ComboParser.parse(it).type == ComboType.TRIPLE }, "枚举含从炸弹拆出的三张")
        eqBool(true, e4.any { ComboParser.parse(it).type == ComboType.SINGLE }, "枚举含从炸弹拆出的单张")
        eqBool(false, e4.any { ComboParser.parse(it).type == ComboType.FOUR_TWO_SINGLE }, "单炸弹手牌枚举不出四带二")

        val e5 = Rules.enumerateAll(hand(16, 17))
        eqBool(true, e5.any { ComboParser.parse(it).type == ComboType.ROCKET }, "枚举含王炸")

        val e6 = Rules.enumerateAll(hand(3, 3, 3, 3, 5, 5, 6, 6))
        eqBool(true, e6.any { ComboParser.parse(it).type == ComboType.FOUR_TWO_PAIR }, "枚举含四带两对")
        eqBool(true, e6.any { ComboParser.parse(it).type == ComboType.FOUR_TWO_SINGLE }, "枚举含四带两单")

        val t0 = System.nanoTime()
        Rules.enumerateAll(Deck.full())
        val ms = (System.nanoTime() - t0) / 1000000L
        check(ms < 500L, "enumerateAll(全牌堆) 耗时 ${ms}ms < 500ms")
    }

    // ------------------------------------------------------------------
    // 8. findHint
    // ------------------------------------------------------------------

    private fun testFindHint() {
        println("\n[8] findHint")
        val target = ComboParser.parse(hand(3, 3))
        val hint = Rules.findHint(hand(5, 5, 9, 9, 9, 9), target)
        check(hint != null, "findHint 找到提示")
        eqBool(true, Rules.canBeat(ComboParser.parse(hint!!), target), "提示能压过目标")
        eqBool(true, ComboParser.parse(hint).type == ComboType.PAIR, "宁可用对子也不用炸弹")
        eqList(listOf(5, 5), toRanks(hint), "提示取最小对子 55")

        val targetBomb = ComboParser.parse(hand(8, 8, 8, 8))
        eqBool(true, Rules.findHint(hand(3, 4, 5), targetBomb) == null, "压不过炸弹时返回 null")
        val bombHint = Rules.findHint(hand(3, 9, 9, 9, 9), targetBomb)
        check(bombHint != null, "有更大炸弹时返回炸弹")
        eqBool(true, ComboParser.parse(bombHint!!).type == ComboType.BOMB, "提示为炸弹")

        val rocketHint = Rules.findHint(hand(16, 17, 3), ComboParser.parse(hand(15, 15, 15, 15)))
        eqBool(true, rocketHint != null && ComboParser.parse(rocketHint).type == ComboType.ROCKET, "只有王炸能压时返回王炸")
        eqBool(true, Rules.findHint(hand(16, 17, 3), ComboParser.parse(hand(16, 17))) == null, "王炸面前无提示")

        eqList(listOf(3, 4, 5, 6, 7), toRanks(Rules.findHint(hand(3, 4, 5, 6, 7), null)!!), "自由出牌且能一手走完则走完")
        eqList(listOf(9), toRanks(Rules.findHint(hand(9, 10, 14), null)!!), "自由出牌返回最小单张")
        eqList(listOf(4), toRanks(Rules.findHint(hand(3, 4, 5), ComboParser.parse(hand(3)))!!), "单张提示取最小可压")
        eqBool(true, Rules.findHint(hand(3), ComboParser.parse(hand(5))) == null, "无更大单张返回 null")

        eqList(
            listOf(4, 5, 6, 7, 8),
            toRanks(Rules.findHint(hand(4, 5, 6, 7, 8), ComboParser.parse(hand(3, 4, 5, 6, 7)))!!),
            "顺子提示取最小可压"
        )
        // 顺子**必须同长度**才能比大小：6 张顺子压不过 5 张顺子。
        // 原断言以为"更长且最大牌更大就算压过"，那是错的——所以这里期望同长度的 45678。
        eqList(
            listOf(4, 5, 6, 7, 8),
            toRanks(Rules.findHint(hand(4, 5, 6, 7, 8, 9), ComboParser.parse(hand(3, 4, 5, 6, 7)))!!),
            "顺子提示必须同长度：手里有 4~9 也只能出 45678，不能出 456789"
        )
        eqList(
            listOf(4, 5, 6, 7, 8),
            toRanks(Rules.findHint(hand(3, 4, 5, 6, 7, 8), ComboParser.parse(hand(3, 4, 5, 6, 7)))!!),
            "顺子提示优先取最大牌最小的那手（同长度里最小的可压顺子）"
        )
        eqBool(
            true,
            Rules.findHint(hand(3, 4, 5, 6, 8), ComboParser.parse(hand(3, 4, 5, 6, 7))) == null,
            "无更大顺子返回 null"
        )
        eqBool(
            true,
            Rules.findHint(hand(3, 4, 5, 6, 7), ComboParser.parse(hand(4, 5, 6, 7, 8))) == null,
            "顺子比不过更大的顺子"
        )

        val plane = Rules.findHint(hand(4, 4, 4, 5, 5, 5, 6, 6), ComboParser.parse(hand(3, 3, 3, 4, 4, 4)))
        eqBool(true, plane != null && ComboParser.parse(plane).type == ComboType.PLANE, "飞机提示：有纯飞机就不带翅膀")

        val tripleOne = Rules.findHint(hand(7, 7, 7, 9), ComboParser.parse(hand(6, 6, 6, 3)))
        eqBool(true, tripleOne != null && ComboParser.parse(tripleOne).type == ComboType.TRIPLE_ONE, "三带一提示同型")

        eqBool(true, Rules.findHint(hand(3, 4), ComboParser.parse(hand(3, 3))) == null, "手里无对子时对子提示返回 null")

        // 遍历：所有枚举出的合法牌型，能提示就必须真的能压
        val rh = Rules.sortDesc(hand(3, 3, 5, 7, 9, 9, 9, 15, 16))
        var consistency = true
        for (t in Rules.enumerateAll(rh)) {
            val c = ComboParser.parse(t)
            val got = Rules.findHint(rh, c) ?: continue
            if (!Rules.contains(rh, got) || !Rules.canBeat(ComboParser.parse(got), c)) consistency = false
        }
        eqBool(true, consistency, "提示结果必然出自手牌且能压过目标")
    }

    // ------------------------------------------------------------------
    // 9. 计分
    // ------------------------------------------------------------------

    private fun testScorer() {
        println("\n[9] Scorer")
        val r1 = Scorer.settle(3, 0, false, false, true)
        eqInt(3, r1.base, "settle base")
        eqInt(1, r1.multiplier, "无炸弹无春天 multiplier=1")
        eqInt(6, r1.landlordDelta, "地主赢 3×1×2=6")
        eqInt(-3, r1.farmerDelta, "农民 -3")

        val r2 = Scorer.settle(3, 0, false, false, false)
        eqInt(1, r2.multiplier, "地主输 multiplier=1")
        eqInt(-6, r2.landlordDelta, "地主输 -6")
        eqInt(3, r2.farmerDelta, "农民 +3")

        val r3 = Scorer.settle(2, 2, false, false, true)
        eqInt(4, r3.multiplier, "两个炸弹 multiplier=4")
        eqInt(16, r3.landlordDelta, "2×4×2=16")
        eqInt(-8, r3.farmerDelta, "农民 -8")

        val r4 = Scorer.settle(1, 0, true, false, true)
        eqInt(2, r4.multiplier, "春天 multiplier=2")
        eqInt(4, r4.landlordDelta, "1×2×2=4")

        val r5 = Scorer.settle(1, 0, false, true, false)
        eqInt(2, r5.multiplier, "反春 multiplier=2")
        eqInt(-4, r5.landlordDelta, "反春地主 -4")
        eqInt(2, r5.farmerDelta, "反春农民 +2")

        val r6 = Scorer.settle(3, 1, true, false, false, 2)
        eqInt(8, r6.multiplier, "1 炸 × 春天 × 加倍2 = 8")
        eqInt(-48, r6.landlordDelta, "3×8×2=48 取反")
        eqInt(24, r6.farmerDelta, "农民 +24")

        val r7 = Scorer.settle(3, 20, false, false, true)
        eqInt(Scorer.MAX_MULTIPLIER, r7.multiplier, "倍数上限 1024")
        eqInt(3 * Scorer.MAX_MULTIPLIER * 2, r7.landlordDelta, "封顶后地主得分 6144")

        eqInt(1, Scorer.settle(3, -5, false, false, true).multiplier, "负炸弹数按 0 处理")
        eqInt(1, Scorer.settle(3, 0, false, false, true, 0).multiplier, "extraMultiplier<1 按 1 处理")
        eqInt(1, Scorer.settle(3, 0, false, false, true, -7).multiplier, "extraMultiplier 为负按 1 处理")
        eqInt(2, Scorer.settle(3, 0, true, true, true).multiplier, "春天与反春同时为真只翻一倍")

        eqInt(0, r1.landlordDelta + r1.farmerDelta * 2, "地主赢时三家和为 0")
        eqInt(0, r2.landlordDelta + r2.farmerDelta * 2, "地主输时三家和为 0")
        eqInt(0, r6.landlordDelta + r6.farmerDelta * 2, "含加倍时三家和为 0")
    }
}
