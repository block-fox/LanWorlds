# LanWorlds

![LanWorlds](docs/lanworlds-cover.png)

**English** · [简体中文](README.zh-CN.md) · [Tiếng Việt](README.vi.md) · [Русский](README.ru.md) · [Українська](README.uk.md) · [日本語](README.ja.md)

LanWorlds is a client-side Fabric mod that improves Minecraft's vanilla LAN room list. It supports Minecraft **26.3, 26.2, 1.21.11, and 1.21.1**. Install Fabric API and choose the JAR for your game version from [GitHub Releases](https://github.com/block-fox/LanWorlds/releases).

## Features

- Join rooms from the vanilla multiplayer screen. Right-click a room to see its icon, broadcast and server MOTDs, address, player count, and latency when available.
- Block rooms by IP, IP and port, or broadcast MOTD. Select multiple rooms to block them in one action. You can also restore multiple rules from the blocked rooms tab, even while the rooms are offline.
- Configure room status queries, timeouts, the default block rule, and multiplayer button appearance and placement. In icon-only mode, enable dragging and use the left mouse button to place the icons. Settings are stored in `config/lanworlds.json`.

## Room folders and search

Open the **…** menu on the multiplayer screen to enable grouping, choose a grouping mode, search, or enter multi-select mode. Group rooms by Radmin versus ordinary LAN, IP, port, or MOTD. Click a folder heading to collapse or expand it. In multi-select mode, use its checkbox to select or clear every room in that folder, including collapsed rooms.

Search by IP, port, MOTD, or Radmin username. Suggestions appear above or below the search field, depending on where the menu opens. Choose a suggestion to locate the room without changing which rooms the main list shows.

Ctrl-click a room to enter multi-select mode. Click to toggle a room, Shift-click for a range, or Ctrl+Shift-click to add a range. Ctrl+A selects all rooms. The toolbar also supports selecting all, inverting the selection, and clearing it. Esc first clears the selection, then exits multi-select mode. Selection survives a refresh by matching rooms by address and port. Joining is paused while multi-select is active.

## Optional Radmin integration

LanWorlds works with Fabric API on its own. With Radmin Linker 1.0.1 for the same Minecraft version, rooms that match a Radmin virtual IP show user, network, and connection details. The room details page can also open Radmin user and network properties. The blue shield button uses the appearance and position settings in LanWorlds. [Radmin Linker](https://github.com/block-fox/radmin-linker)

RadminLAN 0.2.x includes an older LAN room feature and is incompatible with LanWorlds. If `config/lanworlds.json` does not exist, LanWorlds can transfer old query settings and block rules from `config/radminlan.json`. The original file stays intact.

## Development

Minecraft 26.2 and 26.3 share the root source tree and use JDK 25. The standalone projects for 1.21.11 and 1.21.1 use JDK 21. Use the script for your target version to run the development client.

```powershell
./scripts/run-26.3.ps1 -JavaHome '<JDK 25 path>' -Online
./scripts/run-26.2.ps1 -JavaHome '<JDK 25 path>' -Online
./scripts/run-1.21.11.ps1 -JavaHome '<JDK 21 path>' -Online
./scripts/run-1.21.1.ps1 -JavaHome '<JDK 21 path>' -Online
```

Use the corresponding `scripts/build-*.ps1` to build. Artifacts go to `versions/<minecraft-version>/build/`. Close the development client before building or switching versions. Omit `-Online` when dependencies are cached. A standard checkout uses public Maven dependencies.

The regression checks cover config migration, optional integration, room selection, and batch blocking and restoration. For 26.2, also pass the properties from `versions/26.2/gradle.properties` as `-P` arguments. [Integration documentation (Chinese)](docs/radmin-integration.md)

```powershell
./gradlew.bat -I tests/regression.init.gradle lanWorldsRegressionCheck --no-configuration-cache
./gradlew.bat -p versions/1.21.11 -I ../../tests/regression.init.gradle lanWorldsRegressionCheck --no-configuration-cache
./gradlew.bat -p versions/1.21.1 -I ../../tests/regression.init.gradle lanWorldsRegressionCheck --no-configuration-cache
```

The Mixin smoke checks load the multiplayer list and LAN entries in a development client. On 1.21.1, they also check right-click details, multi-select, refresh, and batch blocking. Test configuration is stored in `.tmp/mixin-smoke-runtime/`.

```powershell
./gradlew.bat -I tests/mixin-smoke.init.gradle runClient --no-configuration-cache
./gradlew.bat -p versions/1.21.11 -I ../../tests/mixin-smoke.init.gradle runClient --no-configuration-cache
./gradlew.bat -p versions/1.21.1 -I ../../tests/mixin-smoke.init.gradle runClient --no-configuration-cache
```

## Origins and license

LanWorlds began as the LAN room feature in RadminLAN 0.2.0, now called Radmin Linker. It is maintained separately under the original MIT license. [License](LICENSE)
