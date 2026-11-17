# Neko 移动检测只读探针

目标是确认运行时 `MovementValidation` 检测对象的实际 Java 类、类加载器和来源，并记录首次预测偏差或拉回前后的有限事件。它不反编译或修改 Neko JAR，不触发授权/云下载入口，不取消事件、不修改检测配置、VL 或处罚结果。

## 构建

在 LeavesX 仓库根目录，用 Java 25 执行：

```powershell
$oldJavaHome = $env:JAVA_HOME
$env:JAVA_HOME = 'C:\Program Files\Zulu\zulu-25'
try { .\scripts\build-neko-movement-probe.ps1 }
finally { $env:JAVA_HOME = $oldJavaHome }
```

默认输入为 `D:\Users\Administrator\Desktop\NekoAntiCheat\[FN]NekoAntiCheat-2.9.8-Loader (1).jar`，产物为 `build\LeavesXNekoMovementProbe.jar`。如果服务端的加载器文件不同，通过 `-NekoJar` 指定。该构建只依赖 Leaves API、Neko 自带公开 API 和仓库已有库。

离线专项测试使用同一个 Java 25 环境执行：

```powershell
.\scripts\run-gradle-tests.ps1 -I scripts/integration-probes/neko/tests.init.gradle
```

加载器路径不同可增加 `'-PnekoProbeJar=绝对路径'`。测试只调用本地公开 API 和纯 Java 包解析器，不运行 Neko 主类初始化或 native 授权入口。

## 使用

将 JAR 放进服务器 `plugins`，在目标玩家开始复现前由 OP 执行：

```text
/nekoorigin start 玩家名 60
```

时长范围为 10–180 秒，默认 60 秒。复现后可看 `/nekoorigin status` 或 `/nekoorigin stop`。输出在 `plugins/LeavesXNekoProbe`：

- `*.log`：文件头固定记录探针版本、MC 版本与包观察器注册状态；最多保留首次 MovementValidation 偏差前 512 条、触发后 512 条记录；队列和总行数有上限。记录内容包括移动预测、MovementValidation 实际 flag、坐标、Neko 事件、世界 UUID、线程名，以及观察到的客户端方块操作、服务端单方块/多方块/区块更新、方块序号确认和 Neko ping/pong 事务包。文件尾 `wire-out/wire-in` 是实际观察数量，可用于识别没有包记录的采样。
- `wire-observer=false` 表示 Neko 自带的 PacketEvents 观察器没有成功注册；采样期间会周期性检查当前实例并尝试重新绑定。`true` 只表示监听器已注册，需要同时检查计数和 `wire-*` 记录。有 Via 等协议转换时只记录包种类和观察顺序，不按 26.1.2 格式猜测转换后的内容。
- `ACKNOWLEDGE_BLOCK_CHANGES blockSequence` 是 Minecraft 方块操作序号确认；`PING/PONG transactionId` 是另一条事务往返。它们不应与 Neko API 的 `txSent/txReceived` 计数混为一谈。
- 网络事件标记为 `PacketEvents-MONITOR`，表示观察插件边界，不保证其他更晚的变换插件或最终客户端收包结果。`reencode=true` 时记录的是该事件边界上的字节，不代表最终重新编码字节。
- 多方块更新仅保留最多 32 个方块状态 ID 样本；大区块包不会完整复制或解析。探针只读取有界数据，不取消、不改写、不延迟网络包。
- `classes/<类加载器标识>/`：运行时类的类加载器链、`CodeSource`、普通本地 class 资源的 SHA-256 和可选字节文件。资源只从 CodeSource 指向的本地目录/JAR 读取，不调用未知类加载器的资源查找方法。普通资源不一定等同 JVM 实际执行字节，文件内有明确说明。

`MovementValidation` 的阈值/配置读取 API、检查实现下载路径和最初的客户端包事件并未由该探针改写。类若由 native 代码实现、运行时定义但不提供本地 CodeSource，探针只能记录 native/动态类加载栈边界，不会尝试提取受保护内存。远程来源只记脱敏 URL，不打开 URL。停止命令不等待磁盘，关闭插件时会等待采样写入线程结束。

## 已验证范围

探针 1.1.2 的 27 项离线专项测试通过，除上述范围外，新增独立 `NekoFlagEvent` 记录回归，确认预测事件与实际 flag 使用不同 HandlerList，且观察器不修改取消状态、VL 或玩家 API。没有在实际授权加载 Neko 的服务器上验证事件触发，也没有复现并修复移动误判；不能把这些测试解释成线上兼容性结论。
