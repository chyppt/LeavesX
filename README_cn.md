# LeavesX

LeavesX 是 [Leaves](https://github.com/LeavesMC/Leaves) 的独立分支，保留 Paper/Bukkit API，并维护兼容性与性能改进。

## 构建

需要 JDK 21 和可访问 GitHub/Maven 的网络环境：

```bash
./gradlew applyAllPatches
./gradlew createMojmapLeavesclipJar
```

生成的服务端位于 `leaves-server/build/libs`。

LeavesX 继承上游项目的开源协议，详见 [LICENSE.md](LICENSE.md)。
