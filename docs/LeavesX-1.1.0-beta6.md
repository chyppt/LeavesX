# LeavesX 1.1.0-beta6（含 beta7 优化）

适用 Minecraft 26.1.2，Java 25。本版交付区域化多线程 R0–R4 全部地基与 worlds 并行世界：四相位流水线、线程域守卫、有序 intent 设施、**并行世界 Tick（worlds 模式，实验性）**、同步事件闸（插件代码仍只在服务器线程执行）、自动降级与安全计数诊断。**默认 `mode: off`，行为与本系列之前版本逐 Tick 等价。**

本版并行世界设计研究了 Leaf 的实验性 SparklyPaper parallel-world-ticking，并针对其已知问题做了三项稳定性改进：①同步 Bukkit 事件经事件闸递交服务器线程分发，插件契约（invariant A）成立，`Bukkit.isPrimaryThread()` 语义与 Paper 一致；②strict-serial-worlds 支持把指定世界钉在服务器线程做混合部署；③世界 Tick 失败自动降级串行并计数，可观测可回退。`regions` 模式在本版解析配置但降级为 worlds（R3 区域所有权模型未落地，降级计入 `/leavesx regions`）。

## 使用

升级旧配置自动补全以下节点（配置版本 81）：

```yaml
regionized-ticking:
  mode: off            # off | worlds | regions（worlds/regions 实验性）
  merge-radius-chunks: 8
  strict-serial-worlds: []
  intent-queue-size: 65536
  auto-degrade-barrier-percent: 25
  auto-degrade-replay-threshold: 64
  threads: 0
```

- `mode: off` 是唯一受支持的生产取值；`worlds` 为实验性，开启前请先用小世界验证插件兼容性，出问题立即改回 `off`。`mode` 修改需重启。
- `threads: 0` 自动取 CPU-1（不超过世界数）；世界数少于 2 无收益。
- `strict-serial-worlds` 大小写不敏感；装有 NMS 反射型插件的世界的名字可加入此列表。

## 计算边界

1. **四相位流水线**：并行模拟 → 屏障 → 串行提交 → 并行后处理。off 模式不进入任何相位；worlds 模式下非 strict 世界在独立 `TickThread` 执行器上并行 Tick（moonrise 线程断言天然通过），服务器线程屏障等待并泵送事件。
2. **事件闸（invariant A）**：世界线程触发的同步事件入队并阻塞，服务器线程在屏障窗口按到达序经正常插件管理器分发后唤醒世界线程；异步事件、服务器线程事件走原路径零开销。
3. **动态世界同步**：插件加载/卸载世界时执行器自动重建，不会遗漏新世界或持有已卸载世界的线程。
4. **降级链**：世界 Tick 异常 → 记录并降级串行；屏障超时/重放超阈值的自动降档参数已就绪（随 regions 模式在后续版本激活）；`recordModeDegrade` 同时降低 effectiveMode。
5. **诊断**：`/leavesx regions` 显示配置模式→实际模式（降级时显示 `regions→worlds`）、intent 与 tick-critical 计数；`/leavesx safety` 显示串行重放、屏障超时、自动降档、intent 失败、线程域违规与事件闸计数。

## 兼容与安全

- 不引入 Folia 代码或 API；LeavesX 运行时类继续通过「禁止引用 threadedregions」发布守卫。
- 事件、调度器、`Bukkit.isPrimaryThread()` 语义不变；插件零改动；off 模式逐 Tick 等价于旧流水线。
- 全量回归 **9,627 项 0 失败、22 跳过**（含 R0 26 项、R1 13 项、L0 3 项 POI 序列化、L1 3 项扫描路由、L2/L3 4 项传感器复用测试）。
- 补丁经往返验证：内部基线 + 各补丁与开发工作树逐字节一致。

## 已知限制

- `regions` 模式在本版降级为 worlds（启动日志与诊断注明）；真实区域所有权模型在 R3 门禁满足后交付。
- worlds 模式下跨维度传送仍在触发世界的线程执行（本版未 defer 到屏障相位）；高传送频率的跨维度场景建议先 off。
- 未进行与 Folia/Leaf 的同负载性能对比；worlds 模式的性能数据待真实服 soak 后在后续版本公布。

产物：`build/LeavesX-1.1.0-beta6-26.1.2.jar`。

SHA-256：`732d9f18a9fa23d31b9510d4774e1409a4bf7fed8fb7298f388c1280421fe1fe`。

## 补丁清单

| 补丁 | 内容 |
| --- | --- |
| paper 0229 | R0：区域化流水线骨架（LeavesXRegionTicking/ThreadDomain/IntentPipeline）、配置 v81、诊断子命令；收编 inner-repo 缺失的配置简化与 Lithium 总闸内容 |
| paper 0230 | R1：LeavesXWorldTicker 并行世界调度器 + LeavesXEventGate 事件闸 + PaperEventManager 接线 |
| paper 0231 | R2/R3/R4：CraftServer 世界加载/卸载动态同步执行器 + strict-serial 大小写不匹配 + 配置风险注释强化 + effectiveMode 降级路径（regions→worlds→off）与诊断展示 |
| mc 0308 | R0：服务器线程标记与四相位跟踪 |
| mc 0312 | R1：worlds 模式并行世界分发 + 事件泵 + 生命周期（启动 refresh、关闭 shutdown） |
| mc 0314 | R3/R4：regions 门禁降级为 worlds + effectiveMode 分发判定 |
| mc 0316 / paper 0233 | L0：POI 区块在保存线程序列化（Tick 线程只做不可变快照），细粒度开关 `performance.chunk.poi-async-serialization`（默认开），输出与同步路径逐字节一致 |
| mc 0317 / paper 0234 | beta7：村民竞争扫描不再同步加载 POI 区块（`performance.ai.poi-competitor-scan-no-load`，默认开）；寻床/回家 AI 改走批量非加载扫描路径；两个配置注释均标注 Leaf 来源（GPL-3.0，作者 HaHaWTH） |
| mc 0318 / paper 0235 | L2/L3：最近实体/掉落物传感器复用实例级扫描列表（`performance.ai.sensor-list-reuse`，默认开；传感器实例 Brain 私有，复用零逃逸风险），开关关闭时回落原每次新建路径 |
| mc 0320 | 修复：异步区块打包与主线程 setBlock 竞争时调色板换档导致 "Didn't fill chunk buffer" 错误——每区块节固定同一不可变数据快照完成尺寸计算与写入，快照间竞态自动重试一次，两次竞态回落原同步路径 |
| mc 0321 | 修复：beta8 配方计数索引的热路径直接读字段绕过懒初始化，数据包重载后合成器（Crafter）首次查询即 NPE 崩服——改经懒初始化访问器，新增 4 项回归测试（空表查询/失效后重建/多周期循环） |
| mc 0322 | 修复：beta8 索引漏掉材料数不固定的特殊配方（工具修复/染色/复制/地图扩展等），导致任意材料合成出错误产物（按数量命中错误桶的"最后匹配"）——索引改为计数桶 + 特殊配方哨兵桶（-1）合并候选，桶间按注册序线性归并，"最后匹配优先"与原版逐位一致；新增 4 项回归测试 |
| mc 0319 / paper 0236 | beta8：合成台查询按材料数索引直达候选（`performance.crafting.recipe-index`，默认开，桶内保持注册序故最后匹配者优先不变）；睡眠检查计数增量化（每刻流遍历→update 内顺带统计）；掉落物合并扫描复用查询缓冲；计算池领取宽度按溢出均值连续自适应；事件闸批量泵送与 intent 已序直通；`/leavesx safety` 增加事件闸递交/分发/超时计数 |

注：R1 屏障等待期间服务器线程持续泵送事件闸，消除「世界线程等待事件分发、服务器线程等待世界完成」的死锁窗口。
