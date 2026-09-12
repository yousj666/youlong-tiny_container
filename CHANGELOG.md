# 更新日志 / Changelog

本项目的版本号沿用上游 Tiny Container 的 `4.2.3`（`versionCode = 4`），
每次改版交付用一个 `_<标识>.apk` 文件名区分，不覆盖旧包。

版本小节里的日期为对应 APK 的构建/交付日期。

---

## 游龙改版 8 — 修复「双击 exe 没反应」
*2026-09-12*

### 修复
- **问题**：把默认图形显示方式换成 `termux-desktop` 后，容器里双击 `.exe` 完全没反应（老版本正常）。
- **根因**：exe 的打开方式一直是 `Exec=env WINEPREFIX=/home/tiny/.wine wine %f`，也就是裸调 `/usr/bin/wine`，这个入口本身非常脆弱：
  - 不自己设置 `PATH` / `HOME` / `DISPLAY` / `XAUTHORITY`，完全靠桌面会话继承；
  - 首次进入桌面时 Wine 还在后台安装（约 1~3 分钟），这段时间点击必然毫无反应；
  - `wine-autosetup.sh` 原来**无条件**写 `.wine-ready`，即使 `wineboot` 失败也会被标记成“已就绪”，之后每次点击都静默失败；
  - 只注册了系统级 `wine.desktop`，`~/.local/share/applications` 缺失、`mimeapps.list` 里没有 `[Added Associations]`，一旦关联没生效双击也没有任何提示。
- **修复**（全部在 rootfs 层，不涉及安卓代码）：
  1. **新增 `/opt/tiny/extra/wine-run.sh`（0755）**：所有 exe 入口统一走这个包装脚本——固定 `PATH`/`HOME`/`WINEPREFIX`/`DISPLAY`、探测 `XAUTHORITY`、Wine 未就绪时自动触发安装并等待、把安卓存储（没有可执行位）里的文件复制到容器内部再运行、`.msi` 走 `msiexec /i`、`wine` 调用失败时回退为“直接执行 PE（交给 proot `--wine=/usr/bin/wine` 接管）”，全过程写入 `/tmp/wine-run.log` 并弹桌面通知。
  2. **新增 `/opt/tiny/extra/wine-mime-fix.sh`（0755）**：幂等重建 `wine.desktop` / `mimeapps.list` / 自启动项，修正权限并执行 `xdg-mime default`。
  3. **`usr/share/applications/wine.desktop`** 改为调用 `wine-run.sh`，去掉 `TryExec=wine`（Wine 未装好时不再从菜单里消失），MIME 补全到 7 种（含 `application/x-dosexec`）；**`~/.config/mimeapps.list`** 增加 `[Added Associations]` 段；**新增用户级 `~/.local/share/applications/wine.desktop`** 双保险。
  4. **Thunar 右键菜单新增「用 Wine 运行」**（`*.exe;*.msi;*.bat;*.cmd;*.com;*.lnk`），文件管理器里一定能跑起来。
  5. **加固 `wine-autosetup.sh`**：补全 `PATH`；`dpkg -i` 不再把错误丢进 `/dev/null`；用循环等待真实 `DISPLAY`（最多 120s）而不是写死 `:6`；`wineboot` 失败自动重试 `-u`；**只有 `system.reg` 存在才写 `.wine-ready`**，否则通知用户并退出 1（避免“假就绪”）。
  6. **`.tiny.yaml`**：`post_start_container_command` 同时启动安装脚本与关联修复脚本（间隔 3 秒）；Hangover 选项说明补充“首次 1~3 分钟”提示；「安装和卸载」组新增「查看 Wine 日志」「诊断 Wine 状态」；新增子组「运行 Windows 程序（运行 exe）」，含「运行 exe」「修复 exe 打开方式」「查看运行日志」。
- 排障入口：双击没反应时先执行快捷命令「查看 Wine 日志」或「诊断 Wine 状态」，也可以直接看容器里的 `/tmp/wine-autosetup.log`、`/tmp/wine-run.log`、`/tmp/wine-mime-fix.log`。

### 交付
- `YoulongContainer_游龙容器插件_v4.2.3_exefix.apk`（1,043,878,476 B，SHA-256 `2D6E25C22E3355AC9DADE66EFA3B26243CFD45DFC507944DAE320D4CCA4B932A`）
- rootfs：新增 3 个文件、替换 2 个、补丁 3 个 → zstd 压缩后 1,014,955,432 B（115,931 个成员，`.tiny.yaml` 44,724 B）

---

## 游龙改版 7 — 默认显示方式改为 termux-desktop，图标改为 4K.jpg 金龍
*2026-09-12*

### 变更
- **默认图形显示方式改为 `termux-desktop`**（改 `.tiny.yaml` 中 feature 的启用开关：`avnc` / `x11` 关闭、`termux-desktop` 开启，保证三种图形方式互斥且只启用一个）。
- **应用启动图标改为 `4k.jpg` 的金龍图**：
  - 重新生成自适应图标前景层（mdpi 108 / hdpi 162 / xhdpi 216 / xxhdpi 324 / xxxhdpi 432）与 Android 13+ 主题图标层 `monochrome`；
  - 旧式圆形图标 `ic_launcher_round` 同步重做（48/72/96/144/192）；
  - 背景色由 `#6750A4` 改为纯黑 `#FF000000`；
  - 删除上一版遗留的矢量图标（`drawable/ic_launcher_background.xml`、`foreground.xml`、`monochrome.xml`）与 `mipmap-*/ic_launcher_round.webp`。

### 交付
- `YoulongContainer_游龙容器插件_v4.2.3_tddefault-4kicon.apk`（1,043,915,340 B）
- rootfs：`.tiny.yaml` 重打包 → zstd 压缩后 1,014,992,004 B（115,928 个成员）

---

## 游龙改版 6 — AVNC 键盘按钮接管的加固
*2026-09-12*

### 修复
- 用户实测「VNC 里点键盘按钮只调出系统输入法，不询问」→ 说明工具栏按钮的监听器被宿主覆盖。
- 改为不依赖时序的方案：
  - **自愈轮询**：`onActivityResumed` 起一个 600ms 的 `Handler` 循环，无条件重装监听器；
  - **4 条独立查找路径**找工具栏键盘按钮（反射 `com.gaurav.avnc.R$id` → `getIdentifier` → `contentDescription` 匹配 → `virtual_keys_btn` 前一个可见 `ImageButton`），并缓存找到的按钮；
  - **悬挂浮键盘按钮兜底**，即使工具栏按钮永远接管不上也有入口；
  - 接管点击时自行关闭 AVNC 的工具栏抽屉（原行为会顺带收起抽屉）。
- 日志 tag `TC4KBD`，便于定位是「查找失败」还是「被覆盖」。

---

## 游龙改版 5 — 抖音只留网页版 + 电脑键盘模式
*2026-09-12*

### 新增
- **电脑键盘模式**：三种显示方式的键盘按钮统一接管，弹出「电脑键盘 / 手机键盘」二选一；
  - 新增 `DesktopKeyboardView.kt`：仿真实键盘 6 行面板，含 `Esc`、`F1`~`F12`、`Ins`/`Del`、`Home`/`End`/`PgUp`/`PgDn`、方向键等；
  - 修饰键（`Ctrl`/`Win`/`Alt`/`Shift`）轻点 = 一次性、长按 = 锁定；
  - 按键通过 `dispatchKeyEvent` 注入，等同真实外接键盘，可长按连发；
  - 新增 `DisplayKeyboard.kt` 统一接管入口 + 44dp 悬浮键盘按钮。
- 新增 `ic_tc4_keyboard.xml` 与 7 条键盘相关字符串（30+ 语言补齐）。

### 变更
- **抖音 Windows 客户端被移除**（Electron 32 位 x86 在 ARM64 + Wine 下不可用），改为**纯网页版**：`firefox-esr` + 独立 profile `~/.mozilla/douyin-web`，快捷命令组「抖音」共 5 条。
- 移除 Steam 中阻止自我更新的 `steam.cfg` 逻辑（`BootStrapperInhibitAll` / `BootStrapperForceSelfUpdate`），新增「升级到最新版」命令，Steam 组共 7 条命令。

### 交付
- `YoulongContainer_游龙容器插件_v4.2.3_webdouyin-pckbd.apk`（1,043,538,120 B）
- `YoulongContainer_游龙容器插件_v4.2.3_webdouyin-pckbd-avnckbd.apk`、`..._avnckbd2.apk`（1,043,542,216 B）

---

## 游龙改版 4 — 内置 Steam 与抖音电脑版
*2026-09-12*

### 新增
- **内置 Steam（Windows 版）**：`/opt/tiny/steam/` 下 6 个文件（安装/启动/停止/卸载脚本、官方 `SteamSetup.exe`、图标、说明），配合容器内置的 Hangover Wine 运行；快捷命令组「Steam 游戏平台」，桌面与开始菜单图标齐全。
- **内置抖音电脑版**：`/opt/tiny/douyin/` 下官方 8.5.1 离线安装包与脚本。

### 说明
- Steam 上游的 `SteamSetup.exe` 只是**引导器**（本身无版本号），「不是最新版」的真正原因是阻止更新的 `steam.cfg`，已在本版移除。

---

## 游龙改版 3 — 首次进入图形界面询问屏幕方向
*2026-09-11*

### 新增
- 新增 `X11DisplayActivity`（继承 termux-x11 的 `MainActivity`）与 VNC 侧的处理：**首次进入图形界面时询问屏幕方向**——横屏 / 竖屏 / 跟随手机。
- 「跟随手机」会把屏幕方向设为 `SCREEN_ORIENTATION_FULL_SENSOR`，手机转界面跟着转。
- 与「设置 → forceOrientation」双向同步。

---

## 游龙改版 2 — 第三种显示方式 termux-desktop
*2026-09-11*

### 新增
- 新增图形显示方式 **`termux-desktop`**（参考 [sabamdarif/termux-desktop](https://github.com/sabamdarif/termux-desktop)），支持 13 种桌面环境：Xfce、LXQt、Openbox、MATE、Cinnamon、KDE Plasma、i3、Fluxbox、IceWM、Awesome、dwm、bspwm、Window Maker。
- 容器内烧录 `/opt/tiny/termux-desktop/{td.conf, td-session.sh, td-gui.sh, td-install.sh, README.md}` 与两个 `.desktop`，桌面环境按需 `apt` 安装，复用内置 Termux:X11 前端显示。
- 快捷命令组「Termux Desktop 桌面」（7 条命令）。
- 图形显示方式改为互斥（`webview` / `avnc` / `x11` / `termux-desktop`）。

---

## 游龙改版 1 — 基础改版
*2026-09-11*

### 变更
- 应用名改为「游龙容器插件」，`applicationId` 改为 `com.youlong.computer`。
- 「帮助」页改为 4 项：原版仓库 / 游龙改版仓库（红字提示）/ 开源库 / 许可证。
- 首次启动弹出**法律声明与免责声明**，同意后才继续。
- 首页新增**启动页**（大「启动」按钮），底部 4 个标签页。
- 容器信息：名称「游龙容器插件」、预览图与壁纸改为 `4k.jpg` / `bz.png`、`webpage` 指向本仓库。
- `app/build.gradle.kts` 加 `noCompress += "zst"`，避免 aapt2 对已 zstd 压缩的镜像再做一次 deflate。
- 30+ 语言文案同步补齐。

---

## 上游版本

本改版基于上游 Tiny Container **v4.2.3**（`versionCode 4`）。上游更新日志见
<https://github.com/Cateners/tiny_container/commits/main>。
