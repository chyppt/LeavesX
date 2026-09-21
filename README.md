# LeavesX

[中文](README.md) | [English](README_EN.md)

**LeavesX 新一代 Minecraft 服务端**

LeavesX 是 [Leaves](https://github.com/LeavesMC/Leaves) 的独立分支，基于 Paper 服务端架构，专注于多线程计算、性能优化、稳定性以及 Paper/Bukkit 兼容性。

在保留原版机制、生电特性和插件 API 的基础上，LeavesX 针对 AI、自然生成和实体处理等高负载路径引入经过验证的并行计算与热路径优化，更充分地利用多核 CPU，改善高实体量和高区块负载场景下的运行表现。

LeavesX 提供 100+ 可配置选项，适用于生电服、插件服及其他高负载服务器。

## 社区交流

- [QQ 群：1104241735](https://qm.qq.com/q/dT9f4qnieI)

LeavesX 保留 Paper、Bukkit 和 Leaves 的公开 API 及传统插件加载语义，适合生电服、插件服和需要保持原版机制的服务端环境。

## 项目定位

LeavesX 不使用 Folia 的区域线程模型，也不会要求插件改写为 Folia 插件。世界状态仍由服务器线程统一管理，只有经过边界审计的纯计算任务才会交给 LeavesX 计算线程。
现阶段LeavesX性能并不如Folia，勉强可以与Leaf持平，LeavesX是在安全与稳定的基础上进行性能优化，如果您需要极致的性能，LeavesX不是您的首选，稳定+安全+性能才是LeavesX的定位，LeavesX有部分代码为Leaf移植，同时开发过程中使用了Vibe coding（AI开发）若有介意请不要使用。

核心原则：

- 不改变实体、方块、红石、TNT、活塞和区块状态的所有权。
- 不在异步线程调用 Bukkit、Paper、插件或世界写入 API。
- 计算线程只读取不可变快照、基本类型数组和确定性参数。
- 计算异常、线程池繁忙或任务拒绝时，自动执行一次完整的主线程计算，不混用不完整结果。
- 优化默认保持 Paper、Leaves 和原版行为；高风险功能不会默认开启。

## 当前优化方向

### 多线程纯计算

- AI 目标距离排序和候选选择。
- 实体激活范围的数值判断。
- 自然刷怪快照统计和局部计数合并。
- 大批量快照的稳定分片、归并和尾部负载平衡。
- 有界计算线程池、空闲线程准入、队列背压和主线程协助执行。

### 同步热路径优化

- 区块光照通知、光照快照和区块数据包组装的批处理与去重。
- 实体查询、碰撞键、资源标识和状态检查的缓存与对象复用。
- 容器、方块实体、票据和区块生命周期中的分配优化。
- 网络整数编码和批量写入优化。

### 兼容性与诊断

- 村民恐慌、工作、繁殖和铁傀儡生成保留原版/Paper 语义。
- TNT、红石、活塞、压力板和复制特性不移入异步线程。
- 假人数据加载、损坏登记清理、显示前缀和加入/退出消息可配置。
- 支持旁观者区块加载策略、启动阶段负载平滑和配置迁移。
- `/leavesx` 提供区块、实体、方块实体、票据、插件任务、内存、网络、线程和并行回退诊断。

## 配置文件

LeavesX 不覆盖 Leaves 的配置职责：

- `leaves.yml`：Leaves 原有配置和兼容性选项。
- `leavesx.yml`：LeavesX 的性能、多线程、诊断和显示选项。

LeavesX 会在服务端启动时生成或迁移 `leavesx.yml`。配置项均带有注释；不支持热重载的选项会在 `/leavesx reload` 时明确提示需要重启。

## 构建

需要 JDK 21 或更高版本，以及可以访问 GitHub 和 Maven 仓库的网络环境：

```bash
./gradlew applyAllPatches
./gradlew :leaves-server:createLeavesclipJar
```

生成的服务端位于：

```text
leaves-server/build/libs/leavesx-26.1.2.jar
```

如果只需要运行测试，可以执行：

```bash
./gradlew :leaves-server:test
```

项目中的测试覆盖配置迁移、Bukkit/Paper 兼容性、假人数据、村民行为、TNT/技术特性探针，以及 LeavesX 计算线程池的分片、回退和异常处理。

## 上游关系

LeavesX 保留 GitHub fork 的上游关联，便于查看 Leaves 的历史和手动同步；LeavesX 的开发提交只推送到本仓库的 `leavesx/26.1.2` 分支，不会自动合并或推送到 `LeavesMC/Leaves`。

## 开源协议

LeavesX 继承上游项目的开源协议。具体许可和第三方声明请参阅 [LICENSE.md](LICENSE.md) 及 `licenses/` 目录。
