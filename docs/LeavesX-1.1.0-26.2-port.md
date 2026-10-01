# LeavesX 1.1.0：26.2 适配记录

目标是为 Minecraft 26.1.2 和 26.2 发布同一 LeavesX 版本。两个版本分别编译和验证；26.2 必须保留 Leaves 功能、LeavesX 优化、配置迁移、假人和诊断功能。

## 源码与工作区

- 26.1.2：当前项目目录，包含 beta9 后的卡顿修复，`leavesXVersion=1.1.0`。
- 26.2：`C:\Users\Administrator\.codex\worktrees\leavesx-26-2\LeavesX`，从当前 LeavesX `6b487435` 创建，另行复制并校验了未提交的源码与补丁。
- Paper 26.2：固定到 `9240f586a4aa2623b3581e331817d527cbae2723`，Mache `26.2+build.1`，Java 25。
- 不以 Leaf 为底包，不整合其他分支的整套源码。新版本差异逐项检查和适配。
- 项目内旧 `versions-src/Leaf-26.2-probe` 和 `versions-src/LeavesX-26.2` 都是 Leaf 检查目录，已移入回收站，可恢复；不属于本次适配成果。

`scripts/prepare-port-source.ps1` 仅将现有源码、未提交改动和回归测试保存到独立工作区。它不进行版本号替换、API 自动改写或第三方代码合并。目标工作区的 `build/port-source-manifest.json` 记录了复制文件的校验值。

## 已处理

- 新旧两个版本的 LeavesX 版号统一为 `1.1.0`，发布类型设置为正式版；这不代表测试已完成。
- 26.2 的 10 个 Leaves API 补丁已重新应用通过，包括假人、录像、Bytebuf 和配置 API。
- `Player` 在新版本增加 `ObjectContentsLike`，适配时保留该接口，同时保留 Leaves `PacketAudience`。
- Timings 删除补丁按 26.2 的新文件内容调整，不保留已被 Leaves 移除的实现。
- Gradle 更新到 Paper 26.2 使用的 9.4.1；构建脚本保留 Leaves API/Server 目录和 Leavesclip 打包方式。
- 依赖按所选 Paper 26.2 版本调整：并发库由 concurrentutil 变为 leafpile，保留新版本原有依赖。
- Leaves API 构建不依赖 Paper 私有的 `paper-checkstyle` 子项目，Javadoc 标签仍保留。
- `applyPaperSingleFilePatches` 已通过。
- 两个版本的构建脚本均读取 `leavesXVersion=1.1.0`，写入 Jar 的 `Implementation-Version` 和 `LeavesX-Version`。目标产物名为 `LeavesX-1.1.0-26.1.2.jar`、`LeavesX-1.1.0-26.2.jar`；尚未生成正式版产物。Gradle 的 Minecraft API 坐标仍保留上游 `R0.1-SNAPSHOT` 格式，不作为 LeavesX 发布版号。

## 26.2 补丁检查点（2026-09-26）

Minecraft 基础源码已生成，前 131 个 feature 补丁已适配并导出。生成源码仓库当前检查点为 `dddfaff8`，下一项是 `0132-Lithium-Equipment-Tracking.patch`，已确认它在 `LivingEntity` 存在接口冲突，尚未解决。工作区已退回已保存的第 131 个补丁边界，没有挂起的 `git am` 冲突。

本次处理的主要差异：

- 保留 26.2 `Slot.safeClone(player)` 调用链，将 Leaves 堆叠数量计算放在默认槽位实现中，不绕过制图台覆写的回调。
- 对照本地 26.2 原版源码适配随机数选项：不恢复已移除的鱿鱼 ID 种子设置；掉落物保持使用实体随机源；TNT 的可配置随机源仍按原 Leaves 功能保留。
- 聊天和命令限流保留新版 `TickThrottler` 参数；方块更新、区域文件接口及守卫者移动控制保留新版签名和类型。
- 折跃门保留传送原因和可配置票据；传送出口事件使用新版 transition 复制方法，保留其他传送属性。
- 死亡流程保留 Paper 26.2 的可取消事件与延后掉落任务，在延后经验掉落前检查幽匿催发体是否已消费经验，不恢复旧版死亡流程。
- 方块放置保留新版 `updatedBlockEntity` 上下文标记和捕获状态清理流程，迁移更新抑制后的放置同步。
- Sleeping Block Entity 的箱子、末影箱和潜影盒唤醒钩子使用 26.2 事件常量，保留现有配置控制。药水效果补丁保留 Leaves 对插件来源的延后处理条件，改用新版队列；其更新抑制行为仍需专项回归。

验证范围：

- 使用独立临时 Git index，从 Minecraft feature 补丁基线 `f26ee9af` 顺序重放前 131 个补丁，得到的树与当前生成源码 HEAD 完全一致：`f1e96c09f2a9cb9e63be6ba68a343f01ac161115`。此前第 127 个补丁边界也独立重放通过。
- 生成源码工作区干净，`git diff --check` 通过，未发现合并冲突标记。
- 这只证明前 131 个补丁可重放，不证明完整源码能编译，也不证明游戏行为已通过回归。自动合并的代码仍需后续编译及行为审查。

## 仍需完成

- Minecraft 第 132 个及后续补丁与 Paper Server 补丁链的逐项适配。
- 审核 EntityType、配方、实体移动与碰撞、AI/POI、传送、区块票据和存档接口差异。
- 两个版本独立编译和运行回归测试，核对 LeavesX 配置及实际执行入口，不能以配置项存在代替功能已实现。
- 复测配方、村民恐慌与刷铁、TNT 复制、地狱门加载器、跨世界传送、假人、AI 工具及重启存档。
- 高负载实体追踪与线程等待验证；不能用低负载启动成功代替压力验证。
- 产物元数据、命名和发布内容核对。完成之前不上传正式 Release。

26.1.2 已禁用的危险实验路径在迁移时保留安全保护，不能为了列出“全部优化”重新开启不安全实现。
