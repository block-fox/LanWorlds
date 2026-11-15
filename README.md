# LanWorlds

独立的 Fabric 客户端局域网房间模组，支持 Minecraft **26.3、26.2、1.21.11**。当前开发版本：**0.1.0**。

## 功能

- 增强原版多人游戏界面的局域网列表，保留原版加入方式。
- 查看房间封面、广播 MOTD、服务器 MOTD、地址、人数与延迟。
- 右键查看房间详情，按 IP、IP 和端口或 MOTD 屏蔽房间。
- 顶部 LanWorlds 入口提供已屏蔽房间和设置标签页；房间暂未被发现时也能恢复屏蔽规则。
- 独立的查询开关、查询超时和默认屏蔽方式，保存至 `config/lanworlds.json`。
- 可选联动 [RadminLAN](https://github.com/block-fox/radminlan)，无需硬依赖其代码或 Windows 服务。

## 独立使用与联动

仅安装 LanWorlds 与 Fabric API 即可使用局域网功能。详情只显示原版可查询的信息；属性操作区域提示 **“加装radmin管理模组后生效”**，房间屏蔽仍可使用。

同时安装 **RadminLAN 0.3.0 或更新版本**时，匹配到 Radmin 虚拟 IP 的房间会显示“Radmin局域网世界”，详情增加 Radmin 用户、网络和连接信息，并提供用户与网络属性入口。未匹配的普通局域网房间保持原版信息。两个模组必须对应同一 Minecraft 版本。

RadminLAN 0.2.x 已自带旧房间功能，与 LanWorlds 不兼容；请先更新 RadminLAN。首次启动且 `lanworlds.json` 不存在时，自动从旧 `radminlan.json` 迁移房间查询设置和屏蔽规则，原文件保持完整。之后两个模组各自保存配置。

## 开发

26.2 与 26.3 共用根源码，使用 JDK 25；1.21.11 的独立工程使用 JDK 21。

```powershell
./scripts/run-26.3.ps1 -JavaHome '<JDK 25 路径>' -Online
./scripts/run-26.2.ps1 -JavaHome '<JDK 25 路径>' -Online
./scripts/run-1.21.11.ps1 -JavaHome '<JDK 21 路径>' -Online
```

使用对应的 `scripts/build-*.ps1` 构建，产物位于 `versions/<游戏版本>/build/`。脚本用参数选择版本，不改写根配置；构建或切换版本前请退出开发客户端。离线缓存齐全时可省略 `-Online`。默认从公开 Maven 下载依赖，`libs/`、`local-maven/` 不是项目必需文件。

回归检查覆盖旧配置迁移、独立配置优先级、损坏数据、可选桥接和屏蔽匹配：

```powershell
./gradlew.bat -I tests/regression.init.gradle lanWorldsRegressionCheck --no-configuration-cache
./gradlew.bat -p versions/1.21.11 -I ../../tests/regression.init.gradle lanWorldsRegressionCheck --no-configuration-cache
```

26.2 检查追加 `versions/26.2/gradle.properties` 中的 `-P` 参数。联动接口见 [docs/radmin-integration.md](docs/radmin-integration.md)。

## 来源与许可

局域网功能从 [RadminLAN](https://github.com/block-fox/radminlan) 0.2.0 拆出并独立维护，遵循原项目的 MIT 许可。Radmin 管理、控制管道和平台后端不包含在本仓库中。
