package com.streamcap.mobile.danmaku

internal object ProtobufGiftEvents {
    fun douyin(payload: ByteArray, context: DanmakuConnectContext, elapsedMs: Long): DanmakuEvent? {
        val giftId = ProtoWire.firstLong(payload, 2)?.takeIf { it > 0 } ?: return null
        val common = ProtoWire.firstBytes(payload, 1) ?: byteArrayOf()
        val user = ProtoWire.firstBytes(payload, 7) ?: byteArrayOf()
        val gift = ProtoWire.firstBytes(payload, 15) ?: byteArrayOf()
        val name = ProtoWire.firstString(gift, 16)?.takeIf(String::isNotBlank) ?: "礼物 #$giftId"
        val count = ProtoWire.firstLong(payload, 5)?.takeIf { it > 0 } ?: 1L
        val combo = ProtoWire.firstLong(gift, 10) == 1L
        val unitPrice = ProtoWire.firstLong(gift, 12)
        val group = ProtoWire.firstLong(payload, 11)
        val attrs = linkedMapOf("giftId" to "$giftId", "giftName" to name, "quantity" to "$count",
            "quantityMode" to if (combo) "cumulative" else "increment",
            "repeatEnd" to "${ProtoWire.firstLong(payload, 9) ?: 0}")
        group?.let { attrs["comboId"] = "$it" }
        unitPrice?.let { attrs["unitPrice"] = "$it" }
        val id = ProtoWire.firstLong(common, 2)?.takeIf { it != 0L }?.let(java.lang.Long::toUnsignedString)
        // Different updates of one combo may share a source ID. Keep the cumulative snapshots.
        val updateId = id?.let { "$it:${group ?: 0}:$count:${attrs["repeatEnd"]}" }
        return PaidEventFactory.event(context, "douyin", "WebcastGiftMessage", elapsedMs,
            "送出 $name ×$count" + if (combo) "（连击累计）" else "",
            ProtoWire.firstLong(user, 1)?.let(java.lang.Long::toUnsignedString), ProtoWire.firstString(user, 3), updateId,
            amount = PaidEventFactory.product(unitPrice, count), currency = unitPrice?.let { "DOUYIN_DIAMOND" },
            sourceTimeMs = PaidEventFactory.time(ProtoWire.firstLong(common, 4)), attributes = attrs)
    }

    fun kuaishou(payload: ByteArray, context: DanmakuConnectContext, elapsedMs: Long): DanmakuEvent? {
        val giftId = ProtoWire.firstLong(payload, 4)?.takeIf { it > 0 } ?: return null
        val user = ProtoWire.firstBytes(payload, 2) ?: byteArrayOf()
        val batch = ProtoWire.firstLong(payload, 7)?.takeIf { it > 0 } ?: 1L
        val combo = ProtoWire.firstLong(payload, 8)?.takeIf { it > 0 } ?: 1L
        val attrs = linkedMapOf("giftId" to "$giftId", "batchSize" to "$batch", "comboCount" to "$combo",
            "quantityMode" to "cumulative", "quantity" to "${PaidEventFactory.product(batch, combo) ?: batch}")
        ProtoWire.firstString(payload, 6)?.let { attrs["comboId"] = it }
        val id = ProtoWire.firstString(payload, 1)?.takeIf(String::isNotBlank)?.let { "$it:$batch:$combo" }
        return PaidEventFactory.event(context, "kuaishou", "WebGiftFeed", elapsedMs,
            "送出 礼物 #$giftId ×${attrs["quantity"]}（连击累计）",
            ProtoWire.firstString(user, 1), ProtoWire.firstString(user, 2), id,
            sourceTimeMs = PaidEventFactory.time(ProtoWire.firstLong(payload, 3)), attributes = attrs)
    }
}
