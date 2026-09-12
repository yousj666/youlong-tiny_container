# 版权与来源声明 / Copyright & Attribution Notices

本文件说明 **youlong-tiny_container（游龙容器插件）** 的来源、版权归属与第三方组件许可。

---

## 1. 上游项目

本项目是基于以下开源项目的**改版（fork / remake）**：

| 项目 | Tiny Container（小小容器） |
| --- | --- |
| 仓库 | https://github.com/Cateners/tiny_container |
| 作者 | Caten Hu (Cateners) — <catenhu@gmail.com> |
| 版权所有 | Copyright (C) 2026 Caten Hu |
| 许可证 | GNU General Public License v3.0 or later（见仓库根目录 `COPYING`） |

原项目还有更早的一代实现 Tiny Computer，代码保留在上游的 `v1_tiny_computer` 分支。

## 2. 本改版

| 项目 | 游龙容器插件 / youlong-tiny_container |
| --- | --- |
| 仓库 | https://github.com/iill392/youlong-tiny_container |
| 作者 | 游龙 (iill392) |
| 版权所有 | Copyright (C) 2026 游龙 iill392（**仅限本改版新增与修改的部分**） |
| 许可证 | GNU General Public License v3.0 or later（与上游一致） |

## 3. 遵守 GPL-3.0 的说明

Tiny Container 以 GPL-3.0 发布，属于 **强著佐权（copyleft）** 协议，因此本改版：

1. **沿用同一许可证**：GPL-3.0-or-later，完整协议文本见 `COPYING`（与上游同一份文件，未做任何修改）；
2. **保留全部原版权声明**：所有上游源文件头部的 `Copyright (C) 2026 Caten Hu` 与 GPL 声明注释均**原样保留**，未被删除或替换；
3. **明确标注修改**：本文件第 4 节逐项列出本改版相对上游的修改内容；
4. **不额外附加限制**：本改版的整体（含修改部分）同样以 GPL-3.0-or-later 授权，任何人都可以自由使用、修改与再分发，包括商业用途，但再分发时必须同样开源并保留版权声明。

`app/src/main/assets/gpl_v3.txt` 是随 APK 一起分发的 GPL 全文（应用内「帮助 → 许可证」直接显示它），`app/src/main/assets/open_source_libraries.json` 是应用内「帮助 → 开源库」所列的组件清单。

## 4. 本改版相对上游的修改清单

### 4.1 应用标识

- `applicationId`：`com.fct.tc4` → `com.youlong.computer`（`namespace` 仍为 `com.fct.tc4`，以避免大规模改动上游代码）。
- 应用名：`Tiny Container` → `游龙容器插件`（30 余个语言目录同步补齐）。
- 启动图标：黑底金龍自适应图标（`mipmap-anydpi-v26/ic_launcher.xml`、`mipmap-*/ic_launcher_foreground.png`、`mipmap-*/ic_launcher_monochrome.png`、`mipmap-*/ic_launcher_round.png`、`mipmap-*/ic_launcher.png`、`values/ic_launcher_background.xml`）。全部由 `4k.jpg` 重新生成：前景层按「完整图片 = 画布 72/108 并居中」的自适应几何制作，`monochrome` 为同几何的白色剪影；`ic_launcher.png` / `ic_launcher_round.png` 为 48/72/96/144/192 的方形光栅（兼作旧式兵底，同时避免第三方 AAR 的 `ic_launcher` 位图在资源合并中抢占）。
- 应用安装后**桌面不显示图标**：`MAIN` / `LAUNCHER` 从 `.ui.main.MainActivity` 移到 `android:enabled="false"` 的 `<activity-alias android:name=".ui.main.LauncherAlias">`，安装后无启动器入口；可在应用内「设置 → 桌面图标」开关恢复。隐藏后仍可通过「分享/打开方式」或桌面固定快捷方式启动。

### 4.2 新增文件（本改版原创）

| 文件 | 说明 |
| --- | --- |
| `app/src/main/java/com/fct/tc4/kbd/DisplayKeyboard.kt` | 键盘按钮接管（三种显示方式）+ 自愈轮询 + 悬浮兜底按钮 |
| `app/src/main/java/com/fct/tc4/kbd/DesktopKeyboardView.kt` | 仿真实键盘的 6 行电脑键盘面板 |
| `app/src/main/java/com/fct/tc4/ui/page/LaunchFragment.kt` | 首页「启动」大按钮页 |
| `app/src/main/java/com/fct/tc4/ui/page/X11DisplayActivity.kt` | 继承 termux-x11 的 `MainActivity`，加入屏幕方向询问与同步 |
| `app/src/main/res/drawable/ic_tc4_keyboard.xml` | 键盘按钮图标 |
| `app/src/main/java/com/fct/tc4/ui/misc/LauncherIcon.kt` | 桌面图标（启动器别名）开关封装：`isEnabled` / `setEnabled` |

### 4.3 修改的上游文件（主要）

- `app/build.gradle.kts`：`applicationId`、`noCompress += "zst"`（rootfs 已是 zstd 高压缩，避免 aapt2 二次 deflate）。
- `AndroidManifest.xml`：`.ui.page.X11DisplayActivity` 声明；`MAIN` / `LAUNCHER` 移至 `.ui.main.LauncherAlias` 别名（`enabled=false`）。
- `app/src/main/java/com/fct/tc4/ui/page/SettingsViewModel.kt`：新增「桌面图标」开关项。
- `app/src/main/java/com/fct/tc4/Tc4Application.kt`：注册键盘接管。
- `app/src/main/java/com/fct/tc4/ui/main/MainActivity.kt`：首次启动法律声明弹窗。
- `app/src/main/java/com/fct/tc4/ui/page/FeaturesViewModel.kt`：新增 `termux-desktop` 功能类型、13 种桌面环境列表、图形方式互斥逻辑。
- `app/src/main/java/com/fct/tc4/ui/page/FeaturesFragment.kt`、`ContainerMainViewModel.kt`、`ContainerMainFragment.kt`、`HelpFragment.kt`、`SettingsFragment.kt` 等：配合上述功能调整。
- `app/src/main/res/values*/strings.xml`（30+ 语言）：新增/改写文案。
- `app/src/main/assets/open_source_libraries.json`：补充 `termux-desktop` 等条目。
- 上游 `README.md` / `README.zh.md` 被本改版的 `README.md`（中文）/ `README.en.md`（英文）取代；`doc/tc4.png`、`doc/intro.*.md`、`COPYING`、`OWNERS` 原样保留。

### 4.4 容器镜像（rootfs）

镜像本身由上游的容器制作流程生成，本改版对其内容做了以下调整：

- 默认桌面环境为 Xfce；内置壁纸 `4k.jpg` / `bz.png`。
- 新增 `termux-desktop` 相关脚本：`/opt/tiny/termux-desktop/{td.conf,td-session.sh,td-gui.sh,td-install.sh,README.md}` 及对应的 `.desktop`。
- 新增 Steam 相关脚本：`/opt/tiny/steam/{steam-install.sh,steam-launch.sh,steam-stop.sh,steam-uninstall.sh,steam.desktop,README.md}` 与官方 `SteamSetup.exe`。
- 新增抖音网页版脚本：`/opt/tiny/douyin/{douyin-web.sh,douyin-web.desktop,README.md}` 等。
- 移除抖音 Windows 客户端（Electron 32 位在 ARM64 + Wine 下不可用）。
- 修复「双击 exe 没反应」：新增 `/opt/tiny/extra/wine-run.sh`、`/opt/tiny/extra/wine-mime-fix.sh`，重写 `/usr/share/applications/wine.desktop`（改为调用 `wine-run.sh`、补全 `.exe/.dll/.bat` 等 MIME）与 `~/.config/mimeapps.list`（含 `[Added Associations]`），新增用户级 `~/.local/share/applications/wine.desktop` 与 Thunar 右键项「用 Wine 运行」，并加固 `/opt/tiny/extra/wine-autosetup.sh`（真实 `DISPLAY` 探测、`wineboot` 重试、失败不写就绪标记）。以上脚本均为本改版原创，与上游无关。
- `.tiny.yaml`：容器名改为「游龙容器插件」、`preview` 指向 `4k.jpg`、`webpage` 指向本仓库、快捷命令组调整为 10 组、默认启用 `termux-desktop` 图形方式。

> 注意：容器镜像（`app/src/main/assets/rootfs.tar.zst`）体积约 968 MB，**未随本仓库分发**（超出 GitHub 单文件限制）。镜像内同样包含 Debian 以及若干第三方软件，它们各自遵循自身许可证（主要是 GPL / LGPL / Apache-2.0 / MIT 等）。

## 5. 第三方组件

应用内「帮助 → 开源库」页面的数据来自 `app/src/main/assets/open_source_libraries.json`。主要组件：

| 组件 | 作者 | 许可证 |
| --- | --- | --- |
| [Tiny Container](https://github.com/Cateners/tiny_container)（上游） | Cateners | GPL-3.0 |
| [AVNC](https://github.com/gujjwal00/avnc) | Gaurav | GPL-3.0 |
| [Termux:X11](https://github.com/termux/termux-x11) | Termux | GPL-3.0 |
| [termux-desktop](https://github.com/sabamdarif/termux-desktop) | sabamdarif | GPL-3.0 |
| [neoterm / terminal-lib](https://gitlab.com/kalilinux/nethunter/apps/kali-nethunter-term) | Offensive Security | GPL-3.0 |
| [proot](https://github.com/tiny-computer/proot-termux)（termux-packages fork 预编译） | Termux / tiny-computer | GPL-2.0 |
| BusyBox | BusyBox Contributors | GPL-2.0 |
| AndroidX、Material Components、ConstraintLayout、Lifecycle、Fragment、Navigation | Google | Apache-2.0 |
| [ViewPagerIndicator](https://github.com/zhpanvip/viewpagerindicator) | zhpanvip | Apache-2.0 |
| [BlurView](https://github.com/Dimezis/BlurView) | Dimezis | Apache-2.0 |
| [SnakeYAML](https://bitbucket.org/snakeyaml/snakeyaml) | SnakeYAML | Apache-2.0 |
| [Coil](https://coil-kt.github.io/coil/) | Coil Contributors | Apache-2.0 |
| Kotlin Coroutines | JetBrains | Apache-2.0 |
| [Fluent GTK Theme](https://github.com/vinceliuice/Fluent-gtk-theme) | vinceliuice | GPL-3.0 |
| [霞鹜文楷 Kose Font](https://github.com/lxgw/kose-font) | lxgw | OFL-1.1 |
| [Debian GNU/Linux](https://www.debian.org/) | Debian Project | 各软件自身许可证 |

若上表与 `open_source_libraries.json` 有出入，以该 JSON 与应用内页面为准。

## 6. 免责声明

本项目按「现状」提供，不提供任何明示或默示担保。使用本软件可能产生的任何风险与后果由使用者自行承担。
本项目与上游作者 Caten Hu **无隶属关系**，改版中的问题**请不要向上游项目反馈**，请提到 [本仓库的 Issues](https://github.com/iill392/youlong-tiny_container/issues)。
