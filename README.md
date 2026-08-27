# LeavesX

LeavesX is an independent branch of [Leaves](https://github.com/LeavesMC/Leaves), preserving the Paper/Bukkit APIs while maintaining compatibility and performance improvements.

## 构建

Requires JDK 21 and network access to GitHub/Maven:

```bash
./gradlew applyAllPatches
./gradlew createMojmapLeavesclipJar
```

The server jar is written to `leaves-server/build/libs`.

LeavesX inherits the upstream open-source licenses. See [LICENSE.md](LICENSE.md).
