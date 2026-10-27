# 平台验证记录（2026-10-28）

本报告覆盖目录全部 52 项，按国内、海外顺序补查。当前状态是**仍有失败与阻断，未达到全平台功能验收通过**。目录中 44 项有取流/录制入口（含直链），5 项仅限制说明，3 项标记停运。弹幕也没有全平台完成，不能把已注册入口当成可用适配。

环境：MuMu Android 15，`emulator-5556`，独立包 `com.streamcap.mobile.platformqa`。保留正式包及所有已有录制。Instrumentation 串行运行；没有登录他人账号、测试发言或伪造聊天。

## 验证口径

- `RECORDING_SAMPLE_PASS`：表中具体成品通过头中尾抽样解码、视频 DTS/包间隔及重复包检查；只代表这个房间、这个时段。短测不等于长稳。
- `RECORDING_REVIEW` / `PARTIAL_RECORDING`：确有成品，但仍有疑点或主播中途下播，不能认定完整验收。
- `NO_SOURCE`：请求走到房间链路但当前样本没有可用流，不证明是正确下播，也不证明平台可用。旧分享链接需要换当前开播样本。
- `BLOCKED` / `FAILED`：实测失败，保留原因；未放宽 TLS 验证来规避失败。
- `NO_LIVE_CANDIDATE` / `NOT_IMPLEMENTED`：未取得可测房间或协议未实现；没有用首页 HTTP 200 代替验收。
- JVM 模拟响应只证明协议分支和回归行为，`PlatformCatalogBatchTest` 只证明入口登记；Instrumentation 的 `OK` 不能替代媒体审计通过。

## 本轮修改

修复微博复合直播 ID 截断、心动热播 JS 注释和末尾逗号解析、Blued 页面 CONFIG 包装和媒体 Referer、百度空 FLV 屏蔽 HLS 备用、京东下播后返回旧播放地址。花猫改为专用 preview API，SOOP 改为专用播放授权 API；这两项仍缺有效开播成品验收。

前续实测修复一并保留：YY FLV 时钟跳变；HLS 画质、初始化段换代、会话 Cookie、CDN 刷新；猫耳 HTTPS、AcFun adaptationSet、映客精确匹配当前直播；快手风控识别、小红书深链、ShowRoom 备用线路、Twitch 匿名握手、Bigo/YouTube 限制分类、FlexTV 当前 API 与 token Cookie。

新增文字聊天客户端：快手、CHZZK、ShowRoom、Twitch、TwitCasting；YY 与小红书采用实验性网页接收。原有抖音/虎牙/斗鱼/B站客户端保留。各平台真实接收状态见表；未实现的平台不会显示成已支持。

## 全目录逐项结果

| 平台 | 结果 | 实测与限制 | 文字聊天 |
| --- | --- | --- | --- |
| HLS / FLV 直链 (`custom`) | MEDIA_PREFLIGHT_PASS | 本地 HLS/FLV 协议回归；设备公开 HLS 点播直链预检通过，未单独实录 | 未实现 |
| 虎牙直播 (`huya`) | RECORDING_SAMPLE_PASS | 116.6 秒成品审计通过；2 条真实聊天 | 真实接收 |
| 抖音直播 (`douyin`) | NO_SOURCE | 两轮旧样本及首页候选 369324308707 均无源，未验收当前开播 | 原有客户端，本轮未实收 |
| B站直播 (`bilibili`) | RECORDING_SAMPLE_PASS | 123.12 秒成品审计通过；74 条真实聊天 | 真实接收 |
| 斗鱼直播 (`douyu`) | RECORDING_SAMPLE_PASS | 125.4 秒成品审计通过；本轮弹幕连接失败 | 连接失败 |
| 快手直播 (`kuaishou`) | BLOCKED | 当前公开房间返回请求过快；已修复风控误报及状态提取，聊天未实收验收 | 协议已接入，风控阻断 |
| TikTok直播 (`tiktok`) | NO_SOURCE | 参考主播设备无源；PC API 返回空正文，未获当前开播验收 | 未实现 |
| YY直播 (`yy`) | RECORDING_SAMPLE_PASS | 636.59 秒成品审计通过；16 条聊天；非 20 分钟长稳 | 实验性网页真实接收 |
| 小红书直播 (`rednote`) | NO_LIVE_CANDIDATE | 公开目录为空/跳转登录，feed 406；没有有效房间，不能算取流验收 | 实验性网页，未验收 |
| Bigo直播 (`bigo`) | BLOCKED | 两个当前目录样本 needLogin；已支持 Cookie 并纠正误报下播 | 未实现 |
| Blued直播 (`blued`) | RECORDING_SAMPLE_PASS | 修复 CONFIG/Referer 与三分片直播起点；隔离快照 72.01 秒成品审计通过，无旧分片多录 | 未实现 |
| 网易CC直播 (`netease`) | FAILED | 参考房间重定向/缺少旧页面数据；未恢复当前可用线路 | 未实现 |
| PandaTV (`pandatv`) | BLOCKED | 设备 API HTTP 400；PC 当前接口拒绝 444；未取得有效流 | 未实现 |
| 猫耳FM直播 (`maoer`) | RECORDING_SAMPLE_PASS | 122.01 秒成品审计通过，平台自带占位视频；音频可解码 | 未实现 |
| Look直播 (`look`) | NO_SOURCE | 参考房间 65108820 无源；未找到当前开播目录 | 未实现 |
| FlexTV (`flextv`) | FAILED_RECORDING_AUDIT | token/格式修复后能保存双轨；101.21 秒复测仍有 4.033 秒缺口（之前为 6.033 秒），未通过 | 未实现 |
| PopkonTV (`popkontv`) | NOT_IMPLEMENTED | 仅限制说明，需要账号及年龄验证 | 未实现 |
| TwitCasting (`twitcasting`) | RECORDING_SAMPLE_PASS | 88.12 秒最新样本审计通过、43 条聊天；另一房间缺口失败保留 | 真实接收 |
| 百度直播 (`baidu`) | NO_SOURCE | 参考房间无源；修复空 FLV 阻断 HLS 备用地址，协议回归通过 | 未实现 |
| 微博直播 (`weibo`) | BLOCKED | 修复复合 ID 截断；设备仍 400，PC 正文为 User does not exists | 未实现 |
| 酷狗直播 (`kugou`) | NO_SOURCE | 12 个官网样本未返回有效线路；未恢复当前可用取流 | 未实现 |
| Twitch (`twitch`) | RECORDING_REVIEW | 98.16 秒样本通过；隔离快照 70.2 秒出现 360 个重复包，审计失败，未解决 | 其他频道真实接收；ESLCS 无消息 |
| LiveMe (`liveme`) | NOT_IMPLEMENTED | 仅限制说明，动态签名未实现 | 未实现 |
| 花椒直播 (`huajiao`) | NO_SOURCE | 修复官网 /l/ 链接；参考样本数据不可用，未取得当前开播验收 | 未实现 |
| ShowRoom (`showroom`) | RECORDING_SAMPLE_PASS | 88.4 秒最新样本审计通过、24 条聊天；其他房间 403 失败保留 | 真实接收 |
| AcFun直播 (`acfun`) | PARTIAL_RECORDING | 对象/数组修复后两间取流通过；494 秒保存后主播下播，未作固定墙钟验收 | 未实现 |
| 映客直播 (`inke`) | RECORDING_REVIEW | 125.4 秒可解码、无大缺口；51 包重复疑点保留 | 未实现 |
| 音播直播 (`yinbo`) | DISCONTINUED | 目录标记停运；无已注册录制链路 | 未实现 |
| 畅聊直播 (`changliao`) | BLOCKED | 参考房间设备证书链失败；未禁用 TLS 验证；协议回归通过 | 未实现 |
| 知乎直播 (`zhihu`) | NO_SOURCE | 参考剧场返回无源；直播剧场解析回归通过 | 未实现 |
| CHZZK (`chzzk`) | RECORDING_SAMPLE_PASS | 608.21 秒 144p 成品审计通过、10103 条聊天；旧高画质缺口失败保留 | 真实接收 |
| 嗨秀直播 (`haixiu`) | NOT_IMPLEMENTED | 仅限制说明，动态签名未实现 | 未实现 |
| VV星球 (`vvxq`) | NO_SOURCE | 参考 LP115924473 房间数据无流；协议解析回归通过 | 未实现 |
| 17Live (`yiqilive`) | BLOCKED | 参考房间设备 HTTP 420；PC 目录尝试未获取有效开播 ID | 未实现 |
| 浪Live (`langlive`) | BLOCKED | 历史房间曾取流通过；新参考 3349463 设备 HTTP 403，无持续录制验收 | 未实现 |
| 漂漂直播 (`piaopiao`) | NO_SOURCE | 参考 anchorUid=91625862 无流；已补 Origin，协议回归通过 | 未实现 |
| 六间房直播 (`sixroom`) | RECORDING_SAMPLE_PASS | 632.77 秒成品审计通过；无已实现聊天客户端 | 未实现 |
| 乐嗨直播 (`lehai`) | NOT_IMPLEMENTED | 仅限制说明，动态签名未实现 | 未实现 |
| Shopee直播 (`shopee`) | BLOCKED | 参考 session 802458 设备 HTTP 500；未取得当前有效直播 | 未实现 |
| YouTube (`youtube`) | BLOCKED | 设备 LOGIN_REQUIRED；PC 播放格式未提供 HLS，显式报错，未验收 | 未实现 |
| 淘宝直播 (`taobao`) | BLOCKED | 设备明确缺 _m_h5_tk 登录 Cookie；未使用用户账号 | 未实现 |
| 京东直播 (`jd`) | RECORDING_SAMPLE_PASS | 隔离暂存快照复测 76.06 秒成品审计通过；下播旧地址回归通过 | 未实现 |
| FACEIT (`faceit`) | BLOCKED | 参考玩家与官方玩家入口 HTTP 403；未验收 Twitch 转接 | 未实现 |
| 花猫直播 (`huamao`) | NO_SOURCE | 专用 preview API 替代通用网页抓取；参考房间无源，协议回归通过 | 未实现 |
| 连接直播 (`lianjie`) | NO_SOURCE | 参考房间 10000258 无源；首页 HTTPS 失败，API 设备可达；协议回归通过 | 未实现 |
| 咪咕直播 (`migu`) | NOT_IMPLEMENTED | 仅限制说明，WASM 签名未实现 | 未实现 |
| 来秀直播 (`laixiu`) | NO_SOURCE | 参考 roomId=1710496 无源；官网目录 API 参数要求仍待确认 | 未实现 |
| Picarto (`picarto`) | RECORDING_SAMPLE_PASS | 隔离快照 UFO200 72.07 秒成品审计通过；历史十分钟缺口失败保留 | 未实现 |
| 心动热播 (`xindongrebo`) | RECORDING_SAMPLE_PASS | 两间当前开播取流；修复注释/末尾逗号后 77.95 秒成品审计通过 | 未实现 |
| SOOP (`soop`) | NO_SOURCE | 专用授权 API 替代网页抓取；当前参考返回 RESULT=0/无开播信息，授权协议回归通过 | 未实现 |
| 千度热播 (`qiandurebo`) | DISCONTINUED | 目录标记停运；无已注册录制链路 | 未实现 |
| WinkTV (`winktv`) | DISCONTINUED | 目录标记停运；无已注册录制链路 | 未实现 |

## 可复测入口

最新全目录设备预检见 [`qa/latest-device-preflight.json`](qa/latest-device-preflight.json)：11 项 MEDIA_OK、18 项无源、14 项失败、9 项没有房间样本（含仅计划/停运及小红书）。这次快照与前面的累积成品证据分开保存，不覆盖历史失败。

机器可读清单：[`qa/platform-coverage.json`](qa/platform-coverage.json)，包含全 52 项与持久房间样本。样本可能下播或过期；小红书没有有效直播间，不能复用目录充当房间。原始网页、临时签名流、Cookie、DB、APK 与录制视频不提交。

已安装 QA APK 后运行（不要在录制期间启动 instrumentation）：

```powershell
python scripts/check_platforms.py --adb <adb.exe路径> --serial emulator-5556
```

脚本核对目录无遗漏、国内先于海外逐项预检，将结果存入 `build/platform-preflight-results.json`，明确保留失败；它不会登录账号或声称录制通过。没有房间样本的项目只记录边界，不伪造网络验收。

录制：`ConcurrentRecordingAcceptanceTest` 的 `platformUrls`、`batchRecordSeconds` 参数；聊天：`PlatformChatAcceptanceTest` 的 `chatUrls`、`chatSeconds`。输出每个平台分别运行 `scripts/audit_recording.py --ffmpeg <ffmpeg.exe> --wall-seconds <秒数> <成品.mp4>`，不能将不同平台时长相加通过门槛。

从暂存区单独导出 301 个源码/构建文件，在隔离目录重新构建，JVM 测试 214 项、0 失败；Debug/AndroidTest APK 与 lintDebug 通过。lint 仍有既存警告。设备全目录注册检查通过。新增京东 session 37（76.06 秒）与心动热播 session 36（77.95 秒）成品审计通过，无超过一秒的视频包缺口、无 DTS 倒退，头中尾可解码。新增 Blued 116.04 秒成品包/解码审计通过，起点包含旧分片已追加三分片直播窗回归修复；FlexTV 双输入复测 101.21 秒仍有 4.033 秒缺口，明确失败。脱敏媒体审计 JSON 见 `qa/recording-audits/`，隔离构建哈希见 `qa/build-validation.json`。

## 仍需解决

需要有效公开房间或授权登录态的平台继续保留失败/阻断状态；网易 CC 当前页面数据结构已不可用。FlexTV 媒体缺口、Twitch/映客重复包疑点仍需独立核对；减少启动探测未消除 FlexTV 缺口。未实现聊天的平台还需真正接入并实收验收。当前提交是可复查的修复和覆盖记录，不能作为“全部平台都已修好”的交付证明。

最终隔离快照设备复测：京东 76.06 秒、Blued 72.01 秒、Picarto 72.07 秒成品审计通过；Twitch 70.2 秒重复包审计失败（360 包）。Blued 三分片起点修复后没有前次约 16 秒旧分片多录。全部平台功能及全部弹幕尚未完成，保留问题，不以提交替代验收。
