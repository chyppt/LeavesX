# LeavesX

[中文](README.md) | [English](README_EN.md)

**LeavesX: a next-generation Minecraft server**

LeavesX is an independent branch of [Leaves](https://github.com/LeavesMC/Leaves), built on the Paper server architecture. It focuses on parallel computation, performance, stability, and Paper/Bukkit compatibility.

While keeping vanilla mechanics, technical gameplay, and plugin APIs in mind, LeavesX adds validated parallel calculation paths and hot-path optimizations for expensive workloads such as AI, natural spawning, and entity processing. The goal is to make better use of multi-core CPUs in servers with many entities or loaded chunks.

LeavesX provides more than 100 configurable options for technical, plugin-based, and other high-load servers.

LeavesX keeps world state ownership on the server thread. Only audited, side-effect-free calculations run on worker threads, so Bukkit, Paper, plugin, redstone, TNT, piston, entity, and chunk state changes retain their synchronous semantics.

## Community

- [QQ group: 1104241735](https://qm.qq.com/q/dT9f4qnieI)

## Project Scope

LeavesX does not use Folia's region-thread API and does not require plugins to be rewritten for Folia. Its design keeps the Paper/Bukkit programming model while moving only safe calculations away from the main tick loop.

LeavesX prioritizes safety, stability, technical gameplay, and Paper/Bukkit compatibility rather than claiming to outperform Folia. Some optimizations are adapted from Leaf; performance depends on the Minecraft version, plugins, and chunk and entity workloads.

AI-assisted programming is used during development, but not all code is AI-generated. Please take this into account when deciding whether to use the project.

The main principles are:

- Preserve entity, block, redstone, TNT, piston, and chunk state ownership.
- Never call Bukkit, Paper, plugin, or world-write APIs from worker threads.
- Pass immutable snapshots and primitive data to calculation workers.
- Fall back to one complete main-thread calculation when a worker is busy, rejected, or fails; partial results are never merged.
- Keep compatibility-sensitive optimizations disabled unless they are proven behavior-preserving.

## Optimization Areas

### Safe parallel calculations

- AI target distance sorting and candidate selection.
- Entity activation-range numeric checks.
- Natural-spawn snapshot statistics and local counter merging.
- Stable partitioning, merging, and tail-load balancing for large snapshots.
- Bounded worker pools, backpressure, and main-thread assistance when needed.

### Synchronous hot paths

- Batched and deduplicated lighting notifications, lighting snapshots, and chunk packet assembly.
- Caching and object reuse for entity queries, collision keys, resource identifiers, and state checks.
- Reduced allocation in containers, block entities, tickets, and chunk lifecycle operations.
- Batched network integer encoding and writes.

### Compatibility and diagnostics

- Villager panic, work, breeding, and iron-golem spawning retain vanilla/Paper semantics.
- TNT, redstone, pistons, pressure plates, and technical duplication behavior remain synchronous.
- Bot data migration, invalid-record cleanup, display prefixes, and join/quit messages are configurable.
- Spectator chunk-loading policy, startup load smoothing, and configuration migration are supported.
- `/leavesx` exposes chunk, entity, block-entity, ticket, plugin-task, memory, network, thread, and parallel-fallback diagnostics.

## Configuration

LeavesX keeps configuration responsibilities separate:

- `leaves.yml`: original Leaves settings and compatibility options.
- `leavesx.yml`: LeavesX performance, threading, diagnostics, and display settings.

The server creates or migrates `leavesx.yml` on startup. Options include comments, and `/leavesx reload` reports settings that require a restart.

## Building

The 1.1.0 builds for Minecraft 26.1.2 and 26.2 use JDK 25. A network connection that can reach GitHub and Maven repositories is required:

```bash
./gradlew applyAllPatches
./gradlew :leaves-server:createBundlerJar
```

The generated server JAR is written under:

```text
leaves-server/build/libs/leaves-bundler-26.1.2-R0.1-SNAPSHOT.jar
```

To run the test suite:

```bash
./gradlew :leaves-server:test
```

## Releases

LeavesX 1.1.0 supports Minecraft 26.1.2 and 26.2, maintained separately on `leavesx/26.1.2` and `leavesx/26.2`. Release assets use the form `LeavesX-1.1.0-<minecraft-version>.jar`.

The 26.2 build is manually adapted from LeavesX, retaining its Leaves foundation. It does not use Leaf as its source baseline and is not an official LeavesMC release.

## Upstream and License

LeavesX keeps the upstream relationship with Leaves for history and source reference. LeavesX development branches and releases are maintained in this repository and are not automatically pushed to `LeavesMC/Leaves`.

LeavesX follows the applicable upstream open-source licenses. See [LICENSE.md](LICENSE.md) and the `licenses/` directory for license and third-party notices.
