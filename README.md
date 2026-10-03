# LeavesX

[中文](README.md) | [English](README_EN.md)

LeavesX 是 [Leaves](https://github.com/LeavesMC/Leaves) 的独立分支，基于 Paper，主要改进多线程计算、性能和服务器诊断，面向生电服和插件服。

[QQ 群：1104241735](https://qm.qq.com/q/dT9f4qnieI)

## 版本

当前版本为 **1.2.1**，本分支对应 **Minecraft 26.1.2**。

| Minecraft | 源码分支 |
| --- | --- |
| 26.1.2 | `leavesx/26.1.2` |
| 26.2 | `leavesx/26.2` |

两个分支分别适配和构建，功能并非完全同步。26.1.2 已包含本轮配方候选缓存重构、网络缓冲优化和 AI 木铲调整；26.2 尚未同步这些改动，AI 工具仍为木棍。相同的 LeavesX 版本号不表示两个构建包含相同的优化。

发布文件采用 `LeavesX-1.2.1-<Minecraft版本>.jar` 命名。请使用与服务器版本对应的文件，不要跨版本替换。

## 功能

- 多线程计算：AI 候选排序、实体激活判定、生物生成统计，以及满足条件的快照计算任务。
- 区块与网络：区块发送快照、延迟编码和实体追踪计算，减少主线程的重复工作。
- 算法优化：查询缓存、稳定排序、配方候选筛选和临时对象复用。
- 假人：显示前缀、作用标签、名称限制及加入/退出消息配置。
- 管理工具：实体 AI 切换、性能 GUI，以及区块、实体、内存、网络和线程诊断。

并行计算并不等于整个 Tick 异步执行，也不能保证 CPU 的每个核心持续满载。任务量不足、结果失效或线程池繁忙时，部分工作仍会同步处理。

LeavesX 保留 Paper/Bukkit 插件接口，不要求插件改写为 Folia 插件。线程相关选项仍需按配置注释使用；修改运行方式不代表所有插件组合都已经过兼容性验证。

## 配置与命令

- `leaves.yml`：Leaves 原有功能和兼容性设置。
- `leavesx.yml`：LeavesX 的性能、多线程、诊断和显示设置。

配置在启动时生成或迁移。使用 `/leavesx reload` 重载 LeavesX 配置，需要重启的改动会单独提示；Leaves 配置使用原有的 `/leaves reload`。

常用命令：

| 命令 | 用途 |
| --- | --- |
| `/leavesx status` | TPS、MSPT、区块、实体和内存概览 |
| `/leavesx gui` | 打开性能诊断界面 |
| `/leavesx health` | 检查服务器运行状态 |
| `/leavesx report` | 保存诊断报告 |
| `/leavesx parallel 60` | 查看最近 60 秒的并行计算和回退情况 |
| `/leavesx network` | 查看网络与区块编码统计 |
| `/leavesx config check` | 检查配置，不修改文件或重载运行状态 |
| `/leavesx ai` | 获取实体 AI 切换工具，仅游戏内 OP 可用 |

其余命令见 `/leavesx help`。

## 构建

两个分支均使用 **JDK 25**。需要能够访问 GitHub 和构建依赖仓库。

Linux / macOS：

```bash
./gradlew applyAllPatches
./gradlew test createLeavesclipJar
```

Windows PowerShell：

```powershell
.\gradlew.bat applyAllPatches
.\gradlew.bat test createLeavesclipJar
```

本分支生成的服务端文件为：

```text
leaves-server/build/libs/leavesx-26.1.2.jar
```

仅运行服务端测试可执行 `./gradlew :leaves-server:test`，Windows 使用 `.\gradlew.bat :leaves-server:test`。

## 使用注意

更新前备份世界、插件和配置，先在副本上验证刷铁机、TNT 设备、跨维度加载器和常用插件。自动化测试与空服启动检查不能代替实际存档的长期测试。

性能取决于版本、插件和负载，目前没有统一条件下的对比结果可以证明 LeavesX 与 Leaf 或 Folia 性能持平。

## 项目说明

部分优化参考或移植自 Leaf 等开源项目。LeavesX 以 Leaves/Paper 为基础维护，不是 LeavesMC 官方发行版；26.2 从 LeavesX 手动适配，不以 Leaf 源码作为底包。源码在本仓库的独立分支中维护。

项目开发过程中使用了 AI 辅助编程，并非全部代码由 AI 生成。请根据自己的使用需求决定是否采用。

项目保留上游许可证及第三方声明，见 [LICENSE.md](LICENSE.md) 和 `licenses/`。
