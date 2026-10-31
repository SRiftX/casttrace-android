# SC、礼物和订阅事件接入

日期：2026-10-28。这里描述付费事件的实现和本轮验证，不替代平台录制验收。

| 平台 | 已接入的事件 | 保存的价值信息 |
| --- | --- | --- |
| 哔哩哔哩 | SUPER_CHAT_MESSAGE、SEND_GIFT、GUARD_BUY | SC 人民币金额；礼物金/银瓜子；大航海保留原始 price |
| 抖音 | WebcastGiftMessage | 抖币单价、数量、累计连击、结束标记、组合 ID |
| 快手 | WebGiftFeed | 礼物 ID、批次数量、连击累计、组合 ID、发送时间；消息无价格时不推算 |
| 虎牙 | SendItemSubBroadcast / URI 6501 | 礼物 ID、数量、订单及分组信息；消息无价格时不推算 |
| 斗鱼 | dgb | 礼物 ID、数量、连击相关原始字段；消息无价格时不推算 |
| Twitch | Bits、订阅/续订、赠送订阅及批量赠送通知 | Bits；保留订阅档位和收礼人字段，不推算订阅实付金额 |
| CHZZK | msgTypeCode=10 打赏，cmd 93101/93102 | 原始 payAmount，单位 Cheese |

不属于上述范围的平台（包括 YY、小红书、YouTube、TikTok）本轮没有新增礼物采集。Showroom、TwitCasting 仍只采集普通聊天。

## 保存、展示和重复消息

- 事件沿用 JSONL 资产、录像时间映射、类型筛选、JSONL/SRT/ASS 导出。SC 回放和字幕显示人民币金额，事件列表显示已知币种的价值。
- 礼物数量在 attributes 中保存，amount 只承载平台提供的价值。没有名称时显示礼物 ID，缺失价格保持空值；平台币不换算人民币。
- B 站只采集 SEND_GIFT，不把 COMBO_SEND 再次当作送礼；SC 不重复采集日语镜像事件。
- 抖音、快手的连击推送保留累计数量和分组，不可将每条累计数量相加。连击更新的事件 ID 包含数量/结束状态，重传同一更新才去重。
- 虎牙保留平台快照语义；Twitch 保留批量赠送汇总与单个收礼通知的区别。当前没有消费金额排行榜或自动汇总统计，不能直接累加快照/汇总/收礼通知。
- 跨类型消息 ID 独立去重，避免 SC/礼物与普通发言同 ID 时误丢。取消虎牙每秒前 20 帧以外全部忽略的行为，队列满时会显式报错重连。
- “礼物”显示开关同时控制大航海事件。仅修改显示不影响原始采集。

## 验证

协议单测覆盖 B 站三类事件、抖音连击、快手混合消息、虎牙 TARS、斗鱼 dgb、Twitch Bits/赠送订阅、CHZZK 打赏，以及无效消息、金额溢出、跨类型去重、落盘/筛选/导出。

MuMu 独立包 `com.streamcap.mobile.platformqa`，正式包和已有录像未清除。直播探测只接收，不发送消息或购买礼物。国内先于海外。

第一版 APK 的短时公开房间探测：

| 平台/样本 | 结果 | 付费事件实收 |
| --- | --- | --- |
| B 站 4350043 | 60 秒收到 26 条普通发言 | 0，SC/礼物/上舰待真实样本验证 |
| 虎牙 281014 | 当前房间信息不可用 | 未验证 |
| 虎牙 333003（来自本轮公开开播目录） | 60 秒收到 284 条普通发言；发现旧解析把普通发言归为 UNKNOWN，最终版本已修正并加单测 | 0，礼物待真实样本验证 |
| 抖音 369324308707 | 房间解析未通过 | 未验证 |
| 快手 yall1102 | 握手参数不可用 | 未验证 |
| 斗鱼 252140 | IOException，仍有既有连接故障 | 未验证 |
| Twitch eslcs | IOException，当前连接未成功 | 未验证 |
| CHZZK 792acf9ca28b42918710327e14b06b68 | 当前直播信息不可用 | 未验证 |

因此，本轮“接入”表示已实现协议与保存/展示逻辑，不代表七个平台的付费事件都已在真实直播中验收。最终待提交源码的独立构建和 Android 协议样本验证结果见 `qa/paid-events-validation.json`。Android 样本是固定协议数据，不是真实付费流水。

最终独立源码构建：227 项 JVM 测试通过，debug、androidTest 和 Lint 通过。MuMu 上 B 站三类付费事件的固定协议包解析、JSONL 落盘读取、类型覆盖计数和 SRT 金额显示通过（1 项 Android 测试）。最终 APK 再测虎牙 333003：60 秒收到 92 条 `CHAT`，已确认普通发言分类修复；该样本仍未出现礼物事件。

## 协议依据

- B 站命令字段：[bilibili-API-collect](https://github.com/SocialSisterYi/bilibili-API-collect/tree/master/docs/live/message)
- 抖音 GiftMessage / GiftStruct：[DouyinLiveWebFetcher](https://github.com/saermart/DouyinLiveWebFetcher/blob/main/protobuf/douyin.proto)
- 快手 SCWebFeedPush / WebGiftFeed：[real-url](https://github.com/wbt5/real-url/blob/master/danmu/danmaku/kuaishou.proto)
- 虎牙 URI 6501 和 SendItemSubBroadcastPacket：[huya-danmu](https://github.com/BacooTang/huya-danmu/blob/master/lib.js)
- Twitch：[IRC 标签与 USERNOTICE](https://dev.twitch.tv/docs/irc/)

仅依据字段结构适配，未将下载的第三方实现或协议文件复制进发布源码。
