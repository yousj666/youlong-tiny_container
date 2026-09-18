# 更新日志 / Changelog

本项目的版本号沿用上游 Tiny Container 的 `4.2.3`（`versionCode = 4`），
每次改版交付用一个 `_<标识>.apk` 文件名区分，不覆盖旧包。

版本小节里的日期为对应 APK 的构建/交付日期。

---

## 游龙改版 12 — 系统内置微信（ARM64 原生版，开箱即用）
*2026-09-18*

### 需求
「给系统内置一个微信」——不是再给一条「在线安装」的快捷命令，而是希望装好本应用后，**容器里本来就有微信**。

### 方案：内置腾讯官方的 **ARM64 原生** Linux 微信（完全不经过 Wine）
- 腾讯已经推出 **Linux 原生版微信**（<https://linux.weixin.qq.com/>），官方同时提供 **arm64 / aarch64** 架构的 `deb` 包，那本身就是 **ARM64 原生二进制**。
- 容器正是 arm64-v8a 的 proot Debian，**原生 ARM64 程序可以直接运行，不需要任何指令翻译**，所以把它直接烧进镜像即可。
- 镜像内该部分使用官方包 `WeChatLinux_arm64.deb`（`Package: wechat` 4.1.13.23），解包后完整落到 `/opt/wechat`。

### 可行性核对（做的功课）
- 解析全部 **106 个 ELF** 的 `DT_NEEDED`，只有 25 个 soname 在系统里找不到，而它们**全部都在 `/opt/wechat` 里自带**（`libilink2.so`、`libmmmojo.so`、`libwxocr.so`、`libvlc.so.5`…）→ **对系统库零额外依赖**。
- 镜像里已有 `wqy-microhei / wqy-zenhei` 中文字体，deb 声明的 `fonts-noto-cjk` 依赖实际上已满足（需要时可用「微信自检 / 修复」补装）。
- 镜像本来就支持符号链接（已有 3 万多个），deb 里的软链不会被丢。

### 为什么**不**内置 Windows 版微信
Windows 客户端是 **x86/x64** 程序，在 **ARM64 + Wine** 下要经过**两层**指令翻译，再加上微信自带的反调试/加固，实测同样不可用——这正是改版 7/8 把**抖音 Windows 客户端**从镜像里移除的同一个原因。所以本版只内置 **ARM64 原生** 版，不做 Windows 版移植。

### 镜像里加的东西
- **程序本体**：`/opt/wechat`（官方 deb 原样解包，约 750 MB，含主程序 `wechat`、小程序运行时 `RadiumWMPF/runtime/WeChatAppEx`、`XEditor` / `XFile` / OCR 模型 / VLC 插件等）。
- **本改版原创的管理脚本**：`/opt/tiny/wechat/`
  | 脚本 | 作用 |
  | --- | --- |
  | `wechat-run.sh` | 启动器：**自动适配显示环境**，多方式重试，日志写 `/tmp/wechat.log` 并同步到共享存储 |
  | `wechat-doctor.sh` | 自检 8 项（主程序 / 24 个自带 `.so` / `ldd` 缺库 / X 可达性 / 中文字体 / 执行权限 / 进程 / 数据目录），`--fix` 可一键修复 |
  | `wechat-uninstall.sh` | 卸载（默认保留聊天记录，可省约 750 MB） |
  | `wechat-reinstall.sh` | 联网重新下载官方 ARM64 包安装，用于修复/升级 |
  | `README.md` | 中文说明书（含常见问题） |
- **桌面与菜单入口**：`/usr/bin/wechat`、`/usr/share/applications/wechat.desktop`、`~/.local/share/applications/wechat.desktop`、`~/桌面/微信.desktop`（三处 desktop 都指向 `wechat-run.sh`，避免绕过启动器）。
- **快捷命令**：新增顶层命令组「微信」（启动微信 / 微信自检 · 修复 / 查看微信日志 / 重新安装 · 升级微信 / 卸载微信（可省约 750MB）/ 微信使用说明）；同时把「软件管理 → 常用国产软件 → 微信」改名为「**微信（已内置）**」，原来的在线安装命令**保留**（换机器/卸载重装时仍可用），并补上启动/自检/卸载/说明等命令。

### 首次启动会自动适配（重要）
容器以 **root** 运行、图形环境可能是 VNC / Termux:X11 / termux-desktop 中的任意一种，因此 `wechat-run.sh` 会：
1. 探测 `$DISPLAY` 是否真的可用（`xdpyinfo` → `xset q` → 套接字兜底），不通就自动在 `:6 :0 :1 …` 里换一个能用的；
2. 依次尝试 **标准启动 → `--no-sandbox` → 软件渲染（`LIBGL_ALWAYS_SOFTWARE=1` + `llvmpipe` + `--disable-gpu`）**，任一方式存活 8 秒即判定成功；
3. 全过程写 `/tmp/wechat.log`，并镜像到 `/mnt/sdcard/wechat.log`，方便手机上看。

> 说明：微信的 Chromium 组件在 root 下通常会拒绝开启沙箱，所以「自我修复」这条路径在真机上很可能会走到第 2/3 种方式，这是预期行为。

### 使用提示
- 首次启动较慢（要解压小程序运行时、初始化数据目录 `~/.xwechat`），属正常。
- 启动异常时先跑「微信自检 / 修复」；仍不正常请把 `/tmp/wechat.log`（或共享存储里的 `wechat.log`）反馈过来。

### 兼容性
- `versionCode` 仍为 **4**，升级**不会**触发 `Global.shouldResetBootstrap`，**不会清空现有容器**。
- ⚠️ **本版 rootfs（容器镜像）有变化**：由 115,931 个成员 / 1,014,956,652 B 变为 **116,107 个成员 / 1,297,477,742 B**（未压缩 3,715,276,800 B），新增 176 个成员就是微信本体与上述脚本。

### 交付
- `YoulongContainer_游龙容器插件_v4.2.3_wechat.apk`
  - 大小：**1,307,284,268 B（1,246.72 MB）**
  - SHA-256：`07FAB47FCCFB87B831E68D2A52345C9058C50AD45E47671FBBCF119D4C4DCFD6`
  - MD5：`67A92B3728A5994B709B9088BD108FC2`
  - 签名：V3.0，调试证书 `ce587445d4d624620c95422afac20cfeff0e291298ce707392cb6614816c6b6a`；`zipalign -c -p 4` 通过
- 镜像自检：`E:\tcwork\check_r12.py` 逐项校验 `.tiny.yaml` 两个微信命令组、10 个新增载荷文件（权限/属主/内容特征）、`/opt/wechat` 关键文件、zstd 无损、APK 内嵌 rootfs 哈希一致
- 包内自检：`E:\tcwork\check_apk3.py`（40 项断言，与改版 11 相同，本版未动 Android 侧代码）

---

## 游龙改版 11 — 修好「第三方 App 检测不到/无法启动本应用」
*2026-09-12*

### 问题
改版 9 把 `MAIN`/`LAUNCHER` 从 `MainActivity` 搬到了 `android:enabled="false"` 的 `LauncherAlias` 上（为了「安装后桌面不显示图标」）。副作用是：**包里再也解析不到任何启动入口**，于是第三方 App 走
`Android.openExternalApp` → `PackageManager.getLaunchIntentForPackage(pkg)` → `startActivity`
这条路时拿到 `null`，判定「没装」并弹提示。

原因是 `getLaunchIntentForPackage()` 的真实实现是：
1. 先找 `ACTION_MAIN` + **`CATEGORY_INFO`**；
2. 找不到才退化成 `ACTION_MAIN` + `CATEGORY_LAUNCHER`。
   而 `LauncherAlias` 被禁用后，两个都解析不到 → 返回 `null`。
   （它用的是 `MATCH_DEFAULT_ONLY` 解析，所以意图过滤器里必须有 `CATEGORY_DEFAULT`。）

### 修复
- **新增始终启用的「包入口」别名 `InfoAlias`**（`activity-alias` → `MainActivity`）：
  ```xml
  <intent-filter>
      <action android:name="android.intent.action.MAIN" />
      <category android:name="android.intent.category.INFO" />
      <category android:name="android.intent.category.DEFAULT" />
  </intent-filter>
  ```
  - `CATEGORY_INFO` 是 Android 官方为「这是本包的门面，但它**不是**桌面图标」提供的类别；**没有** `CATEGORY_LAUNCHER`，所以桌面 / 应用列表**依旧不会**出现图标，也不会变成响应 HOME 键的「默认桌面」。
  - 它让 `getLaunchIntentForPackage()` 第 1 步就直接命中 → 第三方 App 能发现本应用并正常 `startActivity`。
- 「设置 → 桌面图标」开关**只**控制 `LauncherAlias`（桌面那个图标），**不碰** `InfoAlias`，所以无论怎么切换，第三方 App 都能启动本应用。
- `LauncherIcon.kt` 与清单注释同步说明两个别名的分工，避免以后又把 `InfoAlias` 关掉。

### 兼容性
- 需要 `versionCode` 不变：本版仍为 4，升级不会触发 `Global.shouldResetBootstrap`，**不会清空容器**。
- 首次安装 / 重新安装后 `InfoAlias` 就生效，无需用户操作。

### 交付
- `YoulongContainer_游龙容器插件_v4.2.3_launchable.apk`
  - 大小：**1,043,723,052 B**（995.37 MB）
  - SHA-256：`E4BC4BF5615ADF738A05FF240AB7A8C36E88A3B558E9696E01B0A7772710001C`
  - MD5：`7452CD3EB5FF394EDEA4DA1B04CE3742`
  - 签名：V3.0，调试证书 `ce587445d4d624620c95422afac20cfeff0e291298ce707392cb6614816c6b6a`；`zipalign -c -p 4` 通过
- **rootfs 未改动**（与改版 10 逐字节相同，仍是 `1,014,956,652 B` / 115,931 个成员）——本版只动清单与注释
- 包内自检：`E:\tcwork\check_apk3.py` 已扩充到 **40 项断言**，新增：`InfoAlias` 存在、`enabled=true`、`targetActivity` 指向 `MainActivity`、带 `MAIN` + `CATEGORY_INFO` + `CATEGORY_DEFAULT`、**且不带 `CATEGORY_LAUNCHER`**（保证桌面依旧无图标）

---

## 游龙改版 10 — 默认显示方式改回 AVNC + 真正修好「双击 exe 没反应」
*2026-09-12*

本版包含 **① 默认图形显示方式改回 `avnc`；② 修掉改版 8 之后仍然存在的「双击 exe 没反应」**。

### 变更 1：默认显示方式改回 AVNC（VNC）
- `.tiny.yaml` 里四种互斥的显示方式（`webview` / `avnc` / `x11` / `termux-desktop`）默认启用项由 `termux-desktop` 改回 **`avnc`**，与上游一致。改版 6~9 期间曾默认 `termux-desktop`。
- 只是**默认值**变化：三种图形方式（VNC / Termux:X11 / Termux Desktop）都还在，「功能」卡片里可随时切换。
- 为什么改回来（与本版第 2 条直接相关）：
  - `avnc` 用的是**容器内置的 `Xtigervnc`**（`-rfbunixpath /tmp/.tiny.vnc … :6`），容器里**一定**会有真实的 `/tmp/.X11-unix/X6`；
  - `termux-desktop` / `x11` 走的是**安卓侧的 Termux:X11** 前端，容器内**通常没有** `/tmp/.X11-unix/X6`，容器里的进程拿不到可用的 X。

### 变更 2：真正修好「双击 exe 没反应」
改版 8 已经做了一整轮修复（新增 `wine-run.sh`、`wine-mime-fix.sh`，重写 `.desktop` 与 `mimeapps.list`），但用户实测**仍然不行**。本版定位到真正的根因：

1. **改版 7 把默认显示方式换成了 `termux-desktop`**，容器内通常没有 `/tmp/.X11-unix/X6`（见上）。
2. **改版 8 的 `wine-autosetup.sh` 判断 X 是否就绪的方式不可靠**：它扫描 `/tmp/.X11-unix/X*`，只要找到**任意**一个 socket 就无条件 `export DISPLAY=":$n"` —— 而 `termux-desktop` 下这个 socket 根本不存在，120 秒扫完仍拿不到可用 X；即使侥幸扫到残留 socket，也会把 `DISPLAY` 指到一个**不可用**的 X 上。
3. 结果：`wineboot --init` 连不上 X → **Wine 前缀从未初始化** → `$WINEPREFIX/system.reg` 永远不出现 → `wine-run.sh` 判定「Wine 不可用」后**静默退出** → 用户看到的就是「双击完全没反应」。
   - 这也解释了「**之前可以现在不行**」：改版 6 及更早默认是 `avnc`，那时容器内 X 真实存在，同一个脚本能正常工作。

**修复内容（全部在 rootfs 层）：**

- **`/opt/tiny/extra/wine-autosetup.sh`**
  - 新增 `x_reachable()`：优先用 `xdpyinfo`（回退 `xset q`）**真实探测 X 是否可达**，只有工具都缺失时才退回「socket 文件是否存在」的粗判断；
  - `wait_x()` 改为：当前 `DISPLAY` 可达就**立即返回**；扫描到其他 socket 时**只有验证它确实可达**才切换 `DISPLAY`（不再无条件改写）；
  - 等待上限 **120s → 45s**，超时不再死等；
  - 触发条件从 `[ -n "$DISPLAY" ] || [ -d /tmp/.X11-unix ]` 收紧为 `[ -n "$DISPLAY" ]`，避免完全无图形环境时白等。
- **`/opt/tiny/extra/wine-run.sh`**（加固）
  - 就绪判断**去掉 `wineserver` 依赖**（`command -v wine` + `$WINEPREFIX/system.reg` 足够），少一个失败点；
  - 支持 `file://` URL 与常见转义（`%20 %23 %26 %2B %3D %25`）解码，从文件管理器/浏览器拖过来的路径也能跑；
  - 自动把**同目录**的少量伴随文件（`dll/ini/dat/cfg/txt/bin/sig/pak/msi/cab/ico/bmp/png/xml/json/db`，单个 ≤32 MiB、合计 ≤256 MiB）一并复制进容器（安卓存储没有可执行位、大文件复制太慢，所以设了上限）；
  - 日志除了 `/tmp/wine-run.log`，还会同步一份到 **`/mnt/sdcard/wine-run.log`**，用手机文件管理器就能直接看，便于自检。
- 排障入口不变：快捷命令「查看 Wine 日志」「诊断 Wine 状态」「修复 exe 打开方式」。

### 交付
- `YoulongContainer_游龙容器插件_v4.2.3_avnc-exefix.apk`
  - 大小：**1,043,723,052 B**（995.37 MB）
  - SHA-256：`E99D9F02F27AECFF44ED16480EC85AE4C06D29AA59D67D589CE93F94D33D23DB`
  - MD5：`D65EC3866B48F8137453804ADA60BE0E`
  - 签名：V3.0，调试证书 `ce587445d4d624620c95422afac20cfeff0e291298ce707392cb6614816c6b6a`；`zipalign -c -p 4` 通过
- **rootfs 本轮有改动**（与改版 9 不同）：`.tiny.yaml` 默认显示方式改回 `avnc`，`wine-autosetup.sh` 6,194 → 7,147 B，`wine-run.sh` 4,408 → 6,841 B；重新打包后 **115,931 个成员**，zstd 压缩后 **1,014,956,652 B**
- 包内自检：`E:\tcwork\check_apk3.py`（图标/清单，33 项）+ `E:\tcwork\check_r10.py`（rootfs 内容，含 `.tiny.yaml` 默认 `avnc`、脚本特征、zstd 无损、APK 内 rootfs 与源文件 SHA-256 一致）
- ⚠️ **不改 `versionCode`**（仍为 4）：`Global.shouldResetBootstrap` 在版本号变化时会重置并重新解压容器，会清掉用户数据

---

## 游龙改版 9 — 启动图标换成 4k.jpg 金龍 + 安装后桌面不显示图标
*2026-09-12*

本版包含两个独立改动：**① 桌面图标真正换成 `4k.jpg` 的金龍；② 应用安装后桌面/应用列表里不再出现图标。**

### 变更 1：启动图标（`4k.jpg` 金龍）
排查后确认，上一版图标"不是我给的那张图"有 **三个** 叠加原因，本版逐个修掉：

1. **`monochrome`（Android 13+ 主题图标层）用的是无关素材**。开启「主题图标」的手机上，系统只拿 `monochrome` 层染色显示，于是看到的是完全另一个图案。现已用同一几何 + 亮度转 alpha 重做成**金龍的白色剪影**。
2. **本工程缺 `mipmap-*/ic_launcher.png` 光栅**，导致 AVNC AAR 自带的 `mipmap/ic_launcher`（蓝色 AVNC logo）在资源合并中胜出。MT 管理器 / 各种 APK 图标提取工具、以及 API<26 的旧路径都会读到那个**蓝色图标**。现已补上自己的光栅（48/72/96/144/192），彻底杜绝。
3. **前景层的自适应几何不对**。自适应画布是 108dp，系统只显示中间 72dp。上一版把整张 1652×1652 的 `4k.jpg` 铺满整张 108dp 画布，遮罩会把图片四周各 1/6 裁掉 —— 构图与 `4k.jpg` 不一致。现改为把**完整的 `4k.jpg` 缩放到正好 72/108 画布并居中**，遮罩后的结果与原图构图逐像素一致（实测 diff = 0.00，四角留黑与纯黑背景自然衔接）。

- 15 个自适应图层 + 5 个方形光栅 + 5 个圆形图标全部由 `E:\tcwork\mkicon.py` 从 `4k.jpg` 重新生成，可复现（只依赖 PIL）。
- 验收图：`E:\tcwork\icon_final_verify.png`（直接读 APK 内图层渲染的 4 格对照）、`E:\tcwork\icon_ratio_cmp.png`（72/108 vs 90% vs 100% 三种构图比例对照）。
- 备注：若希望金龍在图标里更大，只需把 `mkicon.py` 里的 `VISIBLE_RATIO` 由 `72/108` 改为 `0.90`~`1.00` 重新生成即可（本图边缘是纯黑，加大比例只会裁掉黑边，不会出现突兀的切边）。

### 变更 2：安装后桌面不显示图标（可逆）
- **做法**：把 `MAIN` / `LAUNCHER` 从 `.ui.main.MainActivity` 搬到 **`android:enabled="false"` 的 `<activity-alias>`**（`.ui.main.LauncherAlias`）。清单默认关闭 → **装完桌面和应用列表里都没有图标**，且不会出现"装完再也进不去"的死路。
- `MainActivity` 保留两个入口，隐藏后仍可用：
  1. **分享/打开方式**：任意文件管理器选「游龙容器插件」即可启动（`SEND` / `SEND_MULTIPLE`，`mimeType="*/*"`）；
  2. **桌面固定快捷方式**：`com.youlong.computer.action.SHORTCUT` 不受启动图标影响，隐藏后照旧可用。
- **新增 `app/src/main/java/com/fct/tc4/ui/misc/LauncherIcon.kt`**：`isEnabled()` / `setEnabled()` 封装 `setComponentEnabledSetting`。
- **设置页新增「桌面图标」开关**（`SettingsViewModel` + EN / 简体 / 繁體 三语言文案）：打开即恢复普通图标，关闭重新隐藏。
- ⚠️ `setComponentEnabledSetting` 的状态只在**本次安装**内有效，重装或升级 APK 会回到清单默认值（隐藏）。
- ⚠️ **不改 `versionCode`**：`Global.shouldResetBootstrap` 在版本号变化时会重置并重新解压容器，会清掉用户数据。

### 交付
- `YoulongContainer_游龙容器插件_v4.2.3_hiddenicon.apk`
  - 大小：**1,043,718,956 B**（995.37 MB）
  - SHA-256：`FBCDE973ECC973358BA778A358E86DC23D99715EA91A5DA3624AEFA8627AA36F`
  - MD5：`4BCE550899997784D4CB82206393AC86`
  - 签名：V3.0，调试证书 `ce587445d4d624620c95422afac20cfeff0e291298ce707392cb6614816c6b6a`；`zipalign -c -p 4` 通过
- rootfs **未改动**（本轮只在 APK/清单层改动，`app/src/main/assets/rootfs.tar.zst` 与上一版逐字节相同）
- 包内自检：`E:\tcwork\check_apk3.py`（33 项断言全部通过：badging 无 `launchable-activity`、别名 `enabled=false` 且仍带 `MAIN`+`LAUNCHER`、`versionCode` 仍为 4、20 张图标图层均由 `4k.jpg` 生成）

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
