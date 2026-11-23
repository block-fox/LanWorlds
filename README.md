# LanWorlds

独立的 Fabric 客户端局域网房间模组，支持 Minecraft **26.3、26.2、1.21.11、1.21.1**。发布版本为 **0.1.0**。请在 [GitHub Releases](https://github.com/block-fox/LanWorlds/releases) 下载对应 Minecraft 版本的 JAR。

## 功能

- 增强原版多人游戏界面的局域网列表，保留原版加入方式。
- 查看房间封面、广播 MOTD、服务器 MOTD、地址、人数与延迟。
- 右键查看房间详情，按 IP、IP 和端口或 MOTD 屏蔽房间。
- 房间列表支持多选和按默认规则批量屏蔽；已屏蔽规则也可批量恢复。
- 顶部 LanWorlds 入口提供已屏蔽房间和设置标签页；房间暂未被发现时也能恢复屏蔽规则。
- 独立的查询开关、查询超时和默认屏蔽方式，保存至 `config/lanworlds.json`。
- 可选联动 [RadminLAN](https://github.com/block-fox/radminlan)，无需硬依赖其代码或 Windows 服务。

## 独立使用与联动

仅安装 LanWorlds 与 Fabric API 即可使用局域网功能。详情只显示原版可查询的信息；属性操作区域提示 **“加装radmin管理模组后生效”**，房间屏蔽仍可使用。

同时安装 **RadminLAN 0.3.0 或更新版本**时，匹配到 Radmin 虚拟 IP 的房间会显示“Radmin局域网世界”，详情增加 Radmin 用户、网络和连接信息，并提供用户与网络属性入口。未匹配的普通局域网房间保持原版信息。两个模组必须对应同一 Minecraft 版本。

RadminLAN 0.2.x 已自带旧房间功能，与 LanWorlds 不兼容；请先更新 RadminLAN。首次启动且 `lanworlds.json` 不存在时，自动从旧 `radminlan.json` 迁移房间查询设置和屏蔽规则，原文件保持完整。之后两个模组各自保存配置。

## 开发

多人游戏页左上角的“多选房间”可切换批量操作，也可用 Ctrl 点击房间进入多选。多选期间点击切换选择，Shift 点击选择连续范围，Ctrl+Shift 点击追加范围，Ctrl+A 全选；工具栏提供全选、反选和清空。Esc 先清空选择，再结束多选。刷新房间数据后按地址与端口保持选择；批量操作只作用于当前列表中可见的所选房间。多选期间暂停加入房间，结束多选后恢复原版单击选中、双击加入和右键属性操作。

“已屏蔽房间”页支持相同的多选操作，可一次恢复多条规则，房间暂未被发现时也能操作。

26.2 与 26.3 共用根源码，使用 JDK 25；1.21.11 和 1.21.1 的独立工程使用 JDK 21。

```powershell
./scripts/run-26.3.ps1 -JavaHome '<JDK 25 路径>' -Online
./scripts/run-26.2.ps1 -JavaHome '<JDK 25 路径>' -Online
./scripts/run-1.21.11.ps1 -JavaHome '<JDK 21 路径>' -Online
./scripts/run-1.21.1.ps1 -JavaHome '<JDK 21 路径>' -Online
```

使用对应的 `scripts/build-*.ps1` 构建，产物位于 `versions/<游戏版本>/build/`。脚本用参数选择版本，不改写根配置；构建或切换版本前请退出开发客户端。离线缓存齐全时可省略 `-Online`。默认从公开 Maven 下载依赖，`libs/`、`local-maven/` 不是项目必需文件。

回归检查覆盖旧配置迁移、独立配置优先级、损坏数据、可选桥接、屏蔽匹配、房间选择和规则批量操作：

```powershell
./gradlew.bat -I tests/regression.init.gradle lanWorldsRegressionCheck --no-configuration-cache
./gradlew.bat -p versions/1.21.11 -I ../../tests/regression.init.gradle lanWorldsRegressionCheck --no-configuration-cache
./gradlew.bat -p versions/1.21.1 -I ../../tests/regression.init.gradle lanWorldsRegressionCheck --no-configuration-cache
```

26.2 检查追加 `versions/26.2/gradle.properties` 中的 `-P` 参数。联动接口见 [docs/radmin-integration.md](docs/radmin-integration.md)。

运行时 Mixin 检查使用临时测试模组加载多人游戏列表和房间行。1.21.1 还会在客户端验证右键详情、多选、刷新和批量屏蔽。测试不会进入房间，配置保存在 `.tmp/mixin-smoke-runtime/`：

```powershell
./gradlew.bat -I tests/mixin-smoke.init.gradle runClient --no-configuration-cache
./gradlew.bat -p versions/1.21.11 -I ../../tests/mixin-smoke.init.gradle runClient --no-configuration-cache
./gradlew.bat -p versions/1.21.1 -I ../../tests/mixin-smoke.init.gradle runClient --no-configuration-cache
```

## 来源与许可

局域网功能从 [RadminLAN](https://github.com/block-fox/radminlan) 0.2.0 拆出并独立维护，遵循原项目的 MIT 许可。Radmin 管理、控制管道和平台后端不包含在本仓库中。
