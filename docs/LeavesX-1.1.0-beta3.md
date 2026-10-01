# LeavesX 1.1.0-beta3

适用 Minecraft 26.1.2，Java 25。

## 配置整理

`config-version` 升为 78，仍在顶部。性能配置按 `ai`、`entities`、`chunks`、`spawning`、`blocks`、`memory`、`network`、`events` 和 `startup` 分组；每组 `enabled` 放在前面。异步任务与并行计算分别保留在 `async` 和 `parallelism`，假人、加入退出消息在文件后部。

旧配置自动迁移，保留用户值及未知扩展项，不创建备份或迁移报告。新旧路径同时存在时，新路径优先。`leaves.yml` 继续独立管理。

完整默认配置见 [leavesx.example.yml](leavesx.example.yml)。示例由本版服务器实际生成；升级时保留已有开关取值，不会用新默认值覆盖服主设置。

常用路径变更：

| 旧路径 | 新路径 |
| --- | --- |
| `performance.ai-optimizations` | `performance.ai.enabled` |
| `performance.villager-compatibility-mode` | `performance.ai.villager-compatibility` |
| `performance.dense-monster-ai` | `performance.ai.dense-monsters` |
| `performance.chunk-unloading` | `performance.chunks.unloading` |
| `performance.startup-ai` | `performance.startup.ai` |
| `performance.startup-resident-bots` | `fakeplayer.startup-restore` |
| `performance.container-copy-reuse` | `performance.blocks.container-copy-reuse` |
| `performance.spectator-no-chunk-loading` | `features.spectator-no-chunk-loading` |
| `async.virtual-chat` | `performance.use-virtual-thread.async-chat-executor` |

## Leaf 优化的适配与影响

| 配置（均位于 `performance` 下） | 默认 | 本版行为与影响 |
| --- | --- | --- |
| `faster-random-generator.enabled` | false | 使用 JDK Xoroshiro128PlusPlus 处理世界运行逻辑和随机刻。天气、作物等随机结果会变化，依赖 RNG 序列的装置可能受影响。保留世界生成、史莱姆区块、实体共享 RNG；不提供改写地形生成算法的子开关。需重启。 |
| `cache-biome.enabled` | true | 缓存精确方块位置对应的群系选择计算，实际群系仍从当前数据读取。每个使用缓存的线程、每个群系管理器约增加 13 KiB 数组；不缓存区块或群系对象，不合并 4×4×4 区域内的不同位置。 |
| `cache-biome.mob-spawning` / `advancements` | true | 分别控制刷怪和进度检查是否使用上述缓存；总开关关闭时两者回到原流程。 |
| `optimize-player-movement` | true | 玩家区块和视距均不变时跳过无效范围更新；保留碰撞、反作弊、潜行防跌落及移动事件。 |
| `optimized-powered-rails` | false | 复用一次查电中的临时坐标，保持原查电次序、八格传播规则、邻居更新和 Bukkit 红石事件。采用保守实现，未移植整条轨道批量通断电。 |
| `use-virtual-thread.async-chat-executor` | false | 可选虚拟聊天线程，聊天事件流程不变。依赖固定线程或线程局部状态的插件可能不兼容。需重启。 |
| `use-virtual-thread.bukkit-async-scheduler` | false | 仅替换 Bukkit 异步调度的执行线程，同步任务仍在服务器线程。适合较多阻塞 I/O 的异步插件任务，不保证 CPU 计算加速；依赖线程复用的插件需自行兼容。需重启。 |
| `optimize-random-tick` | true | 复用有界缓存中的不可变坐标；回调清空随机刻列表后，按原次数推进随机数。不降低频率、不重采样，保持执行顺序和随机序列。每世界额外缓存最多 1,024 个坐标。 |

Leaf 的实验性随机刻方案会跨区块重采样，改变随机调用和执行顺序；其整条铁轨更新也改写了状态提交路径。这两种算法没有移植。本版对这两个入口采用行为保持不变的局部优化。群系缓存也没有采用以粗群系网格作为结果缓存键的方式。

`/leavesx reload` 可重载普通开关，需要重启的项目会提示。虚拟聊天线程启用时，Paper 配置重载也会检查实际运行的执行器类型，避免错误强制转换。

## 验证范围

完整回归共 9,556 项，9,534 项通过、22 项跳过、0 失败。新增的两份补丁均通过反向检查，并在隔离目录重新应用后逐文件比对一致。

测试覆盖旧配置迁移、冲突优先级、未知项保留、重复加载；精确群系边界、并发查询和群系数据修改；随机数快速推进与原循环逐位对照；动力及激活铁轨的查电结果、读取顺序；玩家移动范围回调对照。

真实服务器完成三轮验证：新功能启用、关闭后重启、默认配置重启。每轮均检查异步线程类型、同步任务仍在主线程、群系修改立即可见、铁轨插件事件取消、传播范围及断电；随后执行 Paper 与 LeavesX 配置重载、存档和正常停服。首次探针因未加载区块而无法修改群系，补齐测试前置条件后通过，未因此修改生产逻辑。

另在新开关全部启用的实例运行 TNT 复制结构：30 个 TNT 的生成、初速度、首 Tick 位移及爆炸触发检查通过，结构中的 10 个 TNT 方块保留。探针取消爆炸事件以保护测试结构，不代表覆盖所有 TNT 炮。日志保留在 `build/reports/beta3`。

### 命名生物保留

使用实际命名牌交互验证僵尸、掠夺者、村民和末影螨：成功命名后设置持久化标记，远距离自然消失检查保留实体，连续两次重启后名称及标记均存在。未命名的对照僵尸在相同距离会正常消失；插件取消命名事件时不消耗命名牌，也不会强行设置名称或持久化。

当前未复现命名牌持久化丢失，未改写原版自然消失逻辑。命名牌防止自然刷掉，不提供死亡、和平难度清除或插件主动移除的保护。

这些结果属于行为回归，不能代替生产存档、插件组合的长期测试，也不能据此给出综合性能提升比例。Leaf 参考源码来自本地 26.2 分支，代码已按 LeavesX 的 26.1.2 源码适配，并非直接合并其补丁。

参考：Leaf 的 `FasterRandomSource`（HaHaWTH）、`cache biome for mob spawning and advancements`（hayanesuru）、`Optimise player movement checks`（Taiyou06）、虚拟线程配置，以及 FxMorin/RailOptimization 的方向。随机刻序列推进、不可变坐标缓存和保守铁轨查电实现为本轮独立实现。
