# LeavesX

[中文](README.md) | [English](README_EN.md)

LeavesX is an independent fork of [Leaves](https://github.com/LeavesMC/Leaves), built on Paper. It focuses on parallel computation, performance and server diagnostics for technical and plugin-based servers.

[QQ group: 1104241735](https://qm.qq.com/q/dT9f4qnieI)

## Versions

LeavesX maintains separate source branches and builds for each Minecraft version. See [Releases](https://github.com/chyppt/LeavesX/releases) for supported versions and downloads.

| Minecraft | Source branch |
| --- | --- |
| 26.1.2 | `leavesx/26.1.2` |
| 26.2 | `leavesx/26.2` |

Each branch adapts Minecraft source and APIs while keeping LeavesX features and optimizations in sync. Changes, supported versions and known issues are documented in the release notes.

Release files use `LeavesX-<LeavesX-version>-<Minecraft-version>.jar`. Use the file matching your server's Minecraft version.

## Features

- Parallel computation for AI candidate sorting, entity activation checks, spawn statistics and eligible snapshot-based tasks.
- Chunk-send snapshots, deferred encoding and entity-tracking calculations to reduce repeated work on the main thread.
- Query caches, stable sorting, recipe-candidate filtering and temporary-object reuse.
- Configurable bot display prefixes, role labels, name restrictions and join/quit messages.
- An entity AI toggle tool, a diagnostic GUI, and chunk, entity, memory, network and thread reports.

Parallel computation does not make the entire tick asynchronous or keep every CPU core busy. Small tasks, stale results and busy worker pools can still require synchronous work.

LeavesX retains Paper/Bukkit plugin interfaces without requiring Folia-specific plugins. Follow the configuration comments when enabling threading options; this does not imply that every plugin combination has been tested.

## Configuration and commands

- `leaves.yml`: original Leaves features and compatibility settings.
- `leavesx.yml`: LeavesX performance, threading, diagnostics and display settings.

Configuration is generated or migrated on startup. Use `/leavesx reload` for LeavesX settings; changes that require a restart are reported separately. Leaves settings continue to use `/leaves reload`.

| Command | Purpose |
| --- | --- |
| `/leavesx status` | TPS, MSPT, chunk, entity and memory overview |
| `/leavesx gui` | Open the diagnostic interface |
| `/leavesx health` | Check server health |
| `/leavesx report` | Save a diagnostic report |
| `/leavesx parallel 60` | Show parallel work and fallbacks over the last 60 seconds |
| `/leavesx network` | Show network and chunk-encoding statistics |
| `/leavesx config check` | Validate configuration without writing files or reloading runtime state |
| `/leavesx ai` | Give the AI toggle tool to an in-game operator |

See `/leavesx help` for additional commands.

## Building

Both branches require **JDK 25**, with network access to GitHub and the dependency repositories.

Linux / macOS:

```bash
./gradlew applyAllPatches
./gradlew test createLeavesclipJar
```

Windows PowerShell:

```powershell
.\gradlew.bat applyAllPatches
.\gradlew.bat test createLeavesclipJar
```

The server artifact is generated at:

```text
leaves-server/build/libs/leavesx-<Minecraft-version>.jar
```

To run only the server tests, use `./gradlew :leaves-server:test` or `.\gradlew.bat :leaves-server:test` on Windows.

## Before updating

Back up worlds, plugins and configuration. Test iron farms, TNT machines, cross-dimensional chunk loaders and commonly used plugins on a copy of your server. Automated tests and empty-server startup checks do not replace long-running tests on a real world.

Performance depends on the version, plugins and workload. There are currently no controlled comparative results establishing parity with Leaf or Folia.

## About the project

Some optimizations are adapted from Leaf and other open-source projects. LeavesX maintains its Leaves/Paper foundation and is not an official LeavesMC release. The 26.2 branch is manually adapted from LeavesX, not built on a Leaf source baseline. Development is maintained on separate branches in this repository.

AI-assisted programming is used during development, but not all code is AI-generated. Please take this into account when deciding whether to use the project.

Upstream licenses and third-party notices are retained. See [LICENSE.md](LICENSE.md) and `licenses/`.
