# 数据来源、计算方式与桌面差异

本文描述当前代码实际使用的口径。手机看板是共享数据的本机视图，**不保证与桌面端显示完全一致，参考费用也不是实际扣款或订阅账单**。

## 1. 数据从哪里来

### 请求用量

桌面 Magpie 生成、归并请求记录后，将自身设备的数据按自然日分包，上传到用户自己的 WebDAV 用量目录。手机读取：

```text
<computer-id>-YYYY-MM-DD.magpie-usage
```

包内包含设备、日期和 calls；每条记录可带时间、Agent、供应商、模型、输入/输出 Token、缓存、状态、重试、积分和显式费用等字段。手机不直接查询供应商的余额接口，也不会扫描其他应用的聊天会话。

网关日志与本地 Agent 会话的匹配主要由桌面完成。手机按共享包的记录统计，不额外以 rid 或模型名跨设备推断去重；若多个源本身记录了同一次请求，不能保证手机自动消除重复。

### 额度历史

手机同时读取 `<computer-id>.magpie-quotas`。这是桌面共享的“供应商 | 账号 → 窗口 → 历史点”，主要字段为采样时间 at、剩余百分比 left，以及可选的周期起点和 resetsAt。

这类文件不是完整的实时额度对象。手机不能据此恢复准确的绝对积分、余额或订阅账单，也不能从中获取桌面实时响应的 AsOf 等全部状态。

### 参考价格

内置价格快照裁剪自 [models.dev/api.json](https://models.dev/api.json)，也可在设置中主动更新。只保留计算需要的价格、分档和模型 canonical 对应关系。更新价格会重新估算历史记录；本项目没有逐日价格版本档案。

### 参考上游代码

对齐基线：[Magpie 6acf5ee5](https://github.com/yetone/magpie/tree/6acf5ee5c38865eae2fe47e879905120b2ca272c)。

- [共享包定义](https://github.com/yetone/magpie/blob/6acf5ee5c38865eae2fe47e879905120b2ca272c/internal/usage/shared.go)
- [WebDAV 用量共享](https://github.com/yetone/magpie/blob/6acf5ee5c38865eae2fe47e879905120b2ca272c/internal/davsync/usage.go)
- [统计规则](https://github.com/yetone/magpie/blob/6acf5ee5c38865eae2fe47e879905120b2ca272c/internal/usage/usage.go)
- [账本与定价链](https://github.com/yetone/magpie/blob/6acf5ee5c38865eae2fe47e879905120b2ca272c/internal/usage/ledger.go)
- [额度历史](https://github.com/yetone/magpie/blob/6acf5ee5c38865eae2fe47e879905120b2ca272c/internal/provider/quota_history.go)

桌面上传间隔约 15 分钟、共享窗口约 90 天，均是该版本实现细节，不是稳定 API。手机初次连接只能导入云端仍存在的文件，无法补回已经从未导入的旧历史。

## 2. 区间与记录筛选

- 今天：所选统计时区的当天 00:00 起。
- 7 天：今天及前 6 个自然日；30 天：今天及前 29 个自然日。
- 全部：本机当前连接归档中持有的记录；不是所有桌面上的全部历史。
- 默认 Asia/Shanghai，也可使用系统时区；日期按记录时间 t 换算，不单纯按文件名筛选。
- 本地 rejected，或无供应商且状态码至少 400 的本地拒绝记录，不进入汇总。
- 界面隐藏供应商/账号只影响概览展示；用量页供应商筛选是独立的统计条件。

## 3. Token、请求与缓存

### Token

```text
Token（含缓存） = input + output + cache_read + cache_write
```

cache_write_1h 是 cache_write 的子集，不再另加一次。reasoning 按记录单独保留，通常已含在 output 内，不重复累计。本项目显示的含缓存口径可能与桌面某个只显示 input + output 的栏目不同。

### 请求计数

- handed_on 的尝试仍累加其 Token、费用及积分，但不再计作一个独立完成请求。
- 其他记录计入请求次数。
- 取消：状态 499，且对应 agent_canceled 类型或约定错误文本。
- 失败：状态码至少 400 或带错误文本，同时不属于取消。
- 重试后成功：不属于取消/失败、不是 handed_on，且 tries > 1。
- 失败、取消、重试后成功使用互斥分类；一次重试不表示额外增加一个完成请求。

### 缓存命中率

```text
分母 = Σinput + Σcache_read + Σcache_write − Σcache_unknown_prompt
命中率 = Σcache_read / 分母
```

cache_unknown_prompt 是缓存未计数记录的 input + cache_read + cache_write。当前也对无缓存计数的 kiro / kiro-plugin 使用对应排除规则。分母不大于零时显示“—”。采用总体比值，不平均逐条请求的百分比。

## 4. 参考费用

优先使用记录内显式 cost，包括明确的 0；没有 cost 才使用公开价格目录估算。当前总费用/未计价统计只对 input + output 不为零的记录累加，沿用参考代码的门槛。

价格查找先尝试供应商与请求模型，再尝试返回模型；供应商目录找不到时尝试模型原厂价和 canonical 别名。session-unknown 记录还可用 session_provider 作最后兜底。移动端无法恢复桌面完整的供应商配置和所有自定义映射，别名识别是有限实现。

以美元/百万 Token 为单价：

```text
费用 = (input × 输入价
      + output × 输出价
      + cache_read × 缓存读价
      + (cache_write − 一小时写入量) × 普通缓存写价
      + 一小时写入量 × 一小时缓存写价) / 1,000,000
```

一小时写入量限制在 0 到 cache_write 内。若有上下文分档，以 input + cache_read + cache_write 为 prompt 大小，选择超过阈值的最高档，对整条请求应用该档价格；缺失档位字段继承基础价。

Claude 的一小时缓存写入在缺少专门价格时使用当前实现的推算规则。部分供应商还应用按请求发生时间判断的峰谷倍率；规则、适用模型与节假日表是静态实现，不能覆盖所有未来变化。

价格无法解析时记录为未计价，不把“未知”当成免费。费用后的“+”表示存在未计价调用，已显示金额不是完整总成本。credits 单独累计，不换算为美元，也不与美元直接相加。

人民币仅按用户填写的汇率折算显示；不是实时外汇报价。K/M/B 和万/亿以及四舍五入也仅影响显示。

## 5. 额度合并与过期

每个账号、窗口采用历史中最新的百分比点。已用百分比 = 100 − 剩余百分比。

同供应商下可合并的同名窗口，使用各账号百分比的算术平均。**没有绝对限额，因此不是按套餐容量加权的余额**。例如两账号额度上限差异很大时，50% 平均剩余不能解释成总积分剩余 50%。

已过 resetsAt，或超过 24 小时没有新采样，标记为过期。24 小时是手机端展示策略，不是桌面端 AsOf 的等价定义，也不能证明服务商实际余额已改变。合并读数只要包含过期账号就标记，展示时间取参与合并的最早读数时间。

## 6. 为什么可能与桌面端不一致

| 原因 | 可能表现 |
| --- | --- |
| 桌面尚未上传、手机尚未下载、系统延迟后台任务 | 最近请求暂缺 |
| 首次导入只能读云端保留窗口 | 手机全部记录少于桌面 |
| 手机保留已导入归档，远端历史被轮换清理 | 手机全部记录可能多于桌面当前共享窗口 |
| 多设备/重复来源、桌面会话匹配方式变化 | 请求数量或分组不同 |
| 不同统计时区、筛选条件、Token 口径与显示舍入 | 日统计、Token、百分比不同 |
| 公开价格快照日期不同，更新后重算历史 | 同一批请求费用发生变化 |
| 桌面自定义单价、费率、时间窗、供应商配置、优惠与计费模式 | 公开牌价估算无法复现桌面成本 |
| 移动端模型别名、canonical、峰谷规则是有限移植 | 选价不同或无法计价 |
| 订阅积分、免费额度、税费、充值折扣、服务商账单规则 | 估算费用不等于实际扣款 |
| 额度只是历史百分比，账号合并为简单平均 | 与实时托盘、绝对余额或加权总额不同 |
| 上游协议或记录字段更新 | 新字段不生效或文件暂不支持 |

对账时先统一时区和筛选，比较已持有记录范围及请求次数，再比较 Token 口径，最后比较价格表与自定义费率。不要仅根据总金额判断同步是否正确。

## 7. 实现位置

- 汇总与区间：`app/src/main/kotlin/dev/magpie/usage/domain/UsageAggregator.kt`
- 额度：`domain/QuotaOverviewBuilder.kt`
- 价格解析及公式：`domain/pricing/PriceLookup.kt`、`PriceTable.kt`、`MagpiePrice.kt`
- 同步：`sync/UsageRepository.kt`
- 协议：`core/pack/`、`core/crypto/`
