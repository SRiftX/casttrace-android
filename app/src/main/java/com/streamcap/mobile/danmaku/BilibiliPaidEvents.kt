package com.streamcap.mobile.danmaku

import org.json.JSONObject

internal object BilibiliPaidEvents {
    fun parse(root: JSONObject, context: DanmakuConnectContext, elapsedMs: Long): DanmakuEvent? {
        val cmd = root.optString("cmd").substringBefore(':')
        if (cmd !in setOf("SUPER_CHAT_MESSAGE", "SEND_GIFT", "GUARD_BUY")) return null
        val d = root.optJSONObject("data") ?: return null
        val u = d.optJSONObject("user_info")
        val uid = d.optString("uid").takeIf(String::isNotBlank)
        val name = (u?.optString("uname") ?: d.optString("uname").ifBlank { d.optString("username") })
        val attrs = linkedMapOf<String, String>()
        var amount: Long? = null
        var currency: String? = null
        val type: DanmakuEventType
        val text: String
        when (cmd) {
            "SUPER_CHAT_MESSAGE" -> {
                type = DanmakuEventType.SUPER_CHAT
                text = d.optString("message").takeIf(String::isNotBlank) ?: return null
                // SC price is CNY yuan, whereas SEND_GIFT gold is a platform coin.
                amount = d.optLong("price").takeIf { d.has("price") && it >= 0 }
                currency = amount?.let { "CNY" }
                listOf("start_time", "end_time", "time", "background_color").forEach {
                    if (!d.isNull(it)) attrs[it] = d.optString(it)
                }
            }
            "SEND_GIFT" -> {
                type = DanmakuEventType.GIFT
                val giftId = d.optString("giftId").takeIf(String::isNotBlank) ?: return null
                val giftName = d.optString("giftName").ifBlank { "礼物 #$giftId" }
                val count = d.optLong("num").takeIf { it > 0 } ?: return null
                attrs.putAll(mapOf("giftId" to giftId, "giftName" to giftName, "quantity" to "$count", "quantityMode" to "increment"))
                listOf("combo_id", "batch_combo_id", "combo_num", "price", "coin_type").forEach {
                    if (!d.isNull(it)) attrs[it] = d.optString(it)
                }
                amount = d.optLong("total_coin").takeIf { d.has("total_coin") && it >= 0 }
                currency = when (d.optString("coin_type")) { "gold" -> "BILIBILI_GOLD"; "silver" -> "BILIBILI_SILVER"; else -> null }
                text = "送出 $giftName ×$count"
            }
            else -> {
                type = DanmakuEventType.GUARD
                val count = d.optLong("num").takeIf { it > 0 } ?: return null
                val level = d.optInt("guard_level")
                val label = d.optString("gift_name").ifBlank { when (level) { 1 -> "总督"; 2 -> "提督"; 3 -> "舰长"; else -> "大航海" } }
                attrs.putAll(mapOf("quantity" to "$count", "quantityMode" to "increment", "guardLevel" to "$level", "giftName" to label))
                // Preserve price without assuming whether it is unit or order total.
                if (!d.isNull("price")) attrs["platformPrice"] = d.optString("price")
                text = "开通 $label ×$count"
            }
        }
        val id = when (cmd) {
            "SUPER_CHAT_MESSAGE" -> d.optString("id")
            "SEND_GIFT" -> d.optString("tid")
            else -> "" // guard level is not a transaction ID
        }.takeIf { it.isNotBlank() && it != "0" }
        return PaidEventFactory.event(context, "bilibili", cmd, elapsedMs, text, uid, name, id, type,
            amount, currency, PaidEventFactory.time(d.optLong("start_time", d.optLong("timestamp"))), attrs)
            .copy(user = DanmakuUser(uid, name, u?.optString("face")?.takeIf(String::isNotBlank), guardLevel = d.optInt("guard_level").takeIf { it > 0 }))
    }
}
