package com.uberauto

/**
 * 從派單畫面的文字解析出「價格」與「距離」。
 * 嚴格模式：任何一項讀不到、有多個價格、價格是範圍、距離數量不對 → 判定為「不明確」。
 */
data class Offer(val price: Double, val pickupKm: Double?, val tripKm: Double)

sealed class ParseResult {
    data class Ok(val offer: Offer) : ParseResult()
    data class Unclear(val reason: String) : ParseResult()
}

object OfferParser {

    // 價格範圍，例如 NT$150-180、$150~$180、150-180 元
    private val rangeRegex = Regex(
        """(?:NT\s?\$|\$|＄)\s*[0-9][0-9,.]*\s*[-–—~～至]\s*(?:NT\s?\$|\$|＄)?\s*[0-9]|[0-9]\s*[-–—~～至]\s*[0-9][0-9,.]*\s*元"""
    )

    // 前置貨幣符號：NT$185、$185、＄185、NTD 185
    private val pricePrefix = Regex("""(?:NT\s?\$|NTD\s?|TWD\s?|\$|＄)\s*([0-9][0-9,]*(?:\.[0-9]+)?)""")

    // 後置：185 元、185 TWD
    private val priceSuffix = Regex("""([0-9][0-9,]*(?:\.[0-9]+)?)\s*(?:元|TWD)""")

    // 距離：1.2 公里、8 km、500 公尺、300 m（排除 min / mi）
    private val distRegex = Regex(
        """([0-9]+(?:\.[0-9]+)?)\s*(公里|千米|km|KM|Km|公尺|米|m)(?![a-zA-Z])"""
    )

    fun parse(texts: List<String>): ParseResult {
        val all = texts.joinToString("\n")

        if (rangeRegex.containsMatchIn(all)) return ParseResult.Unclear("價格是範圍")

        // 價格（去重）
        val prices = LinkedHashSet<Double>()
        for (t in texts) {
            pricePrefix.findAll(t).forEach { toNum(it.groupValues[1])?.let(prices::add) }
            priceSuffix.findAll(t).forEach { toNum(it.groupValues[1])?.let(prices::add) }
        }
        if (prices.isEmpty()) return ParseResult.Unclear("找不到價格")
        if (prices.size > 1) return ParseResult.Unclear("出現多個價格 ${prices.joinToString("/")}")
        val price = prices.first()
        if (price <= 0) return ParseResult.Unclear("價格為 0")

        // 距離（依畫面順序，去重）
        val dists = LinkedHashSet<Double>()
        for (t in texts) {
            distRegex.findAll(t).forEach { m ->
                val v = toNum(m.groupValues[1]) ?: return@forEach
                val unit = m.groupValues[2]
                val km = if (unit == "公尺" || unit == "米" || unit == "m") v / 1000.0 else v
                dists.add(km)
            }
        }
        val list = dists.toList()
        return when (list.size) {
            0 -> ParseResult.Unclear("找不到距離")
            1 -> if (list[0] > 0) ParseResult.Ok(Offer(price, null, list[0]))
                 else ParseResult.Unclear("距離為 0")
            2 -> if (list[1] > 0) ParseResult.Ok(Offer(price, list[0], list[1]))
                 else ParseResult.Unclear("距離為 0")
            else -> ParseResult.Unclear("出現 ${list.size} 個距離，無法判斷")
        }
    }

    private fun toNum(s: String): Double? = s.replace(",", "").toDoubleOrNull()
}

/** 決策結果 */
data class Decision(val notify: Boolean, val message: String)

object DecisionMaker {
    fun decide(result: ParseResult, minPerKm: Double, includePickup: Boolean): Decision {
        return when (result) {
            is ParseResult.Unclear -> Decision(false, "略過：不明確（${result.reason}）")
            is ParseResult.Ok -> {
                val o = result.offer
                if (includePickup && o.pickupKm == null) {
                    return Decision(false, "略過：不明確（讀不到接客距離）")
                }
                val km = if (includePickup) o.tripKm + (o.pickupKm ?: 0.0) else o.tripKm
                val perKm = o.price / km
                val info = "NT$%.0f / %.1f 公里 = %.1f 元/公里".format(o.price, km, perKm)
                if (perKm >= minPerKm) Decision(true, info)
                else Decision(false, "略過：$info（低於 %.0f）".format(minPerKm))
            }
        }
    }
}
