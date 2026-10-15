# easytier-mini

一个**原生 Android（无 WebView / 无 Chrome 框架）**的 EasyTier 极简客户端。

> **非官方项目。** 这是 EasyTier 的第三方外壳，与官方项目及其作者无关，也不代表官方立场。
> 需要全量 VPN 接管流量请用官方客户端。EasyTier 本体：<https://github.com/EasyTier/EasyTier>（LGPL-3.0）。

只做四件事：

1. 启动 / 停止 `easytier-core`（以子进程方式运行 TOML 配置文件）
2. 选择要加载的配置文件
3. 运行后显示连接设备（等价于 `easytier-cli peer`）
4. 打开 App 时自动运行上次的配置（前台服务常驻，关掉界面也继续跑）

`easytier-core` 与 `easytier-cli` 以**可执行文件**形式随包分发，源码零修改，可随时替换升级。

---

## 为什么用 no_tun 模式

这是本项目的核心设计约束，不是偷懒：

- EasyTier 在 Android 上走 `mobile` 运行时宿主，**永远不会自己创建 `/dev/net/tun`**，只能由 App 进程内调用 `attach_tun_fd()` 注入 VpnService 拿到的 fd。
- `easytier-core` **没有** `--tun-fd` 之类的命令行参数把 fd 传给子进程；而 Android 又会强制关闭 `Runtime.exec` 子进程里继承的非标准 fd —— 所以「子进程跑二进制」这条路**只有 no_tun 模式成立**。

因此：

- 不使用 `VpnService`，**不申请也不弹 VPN 授权**，不需要任何 TUN 权限；
- 不需要任何 Android 特有权限（除网络与读取配置目录）；
- App 只负责「拉起进程 + 读回显 + 调 cli 取 peer」。

> 副作用：不会接管整车流量。用法是 EasyTier 的 `no_tun` 能力（子网代理 / 端口转发 / 中继等）。
> 如果你要的是「像官方 App 那样接管全机流量」，那必须走 `VpnService + JNI 进程内集成`，`easytier-core` 就不能独立替换了 —— 这是二选一。

App 会在**运行期副本**里强制写入 `no_tun = true`（`[flags]` 段），**不会改动你的原始配置文件**。

---

## 目录结构

```
easytier-mini/
├─ binaries/
│   └─ arm64-v8a/
│       ├─ easytier-core        ← 替换这里即可升级（原始文件名）
│       └─ easytier-cli         ← 替换这里即可升级
├─ android/
│   ├─ app/src/main/
│   │   ├─ java/com/kkrainbow/easytier/mini/   # 8 个类，纯框架 API，零第三方依赖
│   │   ├─ res/                                # app_name + mipmap 图标（5 个密度 PNG + 自适应）
│   │   ├─ jniLibs/arm64-v8a/          # 由 tools/sync-binaries.sh 生成，勿手工改
│   │   └─ AndroidManifest.xml
│   ├─ keystore/mini-release.jks       # 自签名密钥（生成的，不在版本库里）
│   └─ keystore.properties
├─ tools/
│   ├─ build-rust-bins.sh       # 从 EasyTier 源码交叉编译两个 aarch64 可执行文件
│   ├─ sync-binaries.sh         # binaries/ → jniLibs/（改名成 lib*.so）
│   ├─ prepare-build-env.sh     # 一次性：生成密钥 + 复用 Gradle wrapper
│   ├─ build-apk.sh             # 出签名 APK + 静态校验（自包含，不调别的脚本）
│   ├─ verify-apk.sh            # 复用：单独校验任意 APK（含图标内在尺寸检查）
│   └─ gen-icons/IconGen.java   # Java2D 程序化生成 mipmap 图标
└─ dist/                        # 产物
```

### 二进制怎么进 APK 的

Android 只把名为 `lib*.so` 的 native 条目解压到 `nativeLibraryDir`。所以项目里的流程是：
替换 `binaries/arm64-v8a/easytier-core` → `tools/sync-binaries.sh` 复制成
`jniLibs/arm64-v8a/libeasytier-core.so` → Gradle 打进 APK → 安装时系统解压到
`nativeLibraryDir`。

这份是**出厂副本**：只读、用户碰不到，而且随 APK 版本走。所以运行期**并不直接执行它** ——
见下一节。

### 二进制在设备上可替换（不用重新打包）

装好之后 App 会把出厂副本**释放**成可替换的副本，用原文件名：

| 位置 | 角色 |
|---|---|
| `/sdcard/Android/data/com.kkrainbow.easytier.mini/files/bin/` | **投放目录**：首次运行自动释放原版，之后你改这里 |
| `/sdcard/EasyTier/bin/` | 可选**覆盖目录**：存在则优先于投放目录（方便 U 盘 / adb 投放，也方便临时回退） |
| `/data/data/<pkg>/files/bin/` | 真正**执行**的位置，从上面同步过来再 `chmod +x` |

优先级：**覆盖目录 > 投放目录 > 出厂副本**。换完二进制要**先停止再启动** —— 每次启动核心都会重新读取投放目录，运行中绝不热替换（`Binaries.reload()`）。

为什么不能直接从 `/sdcard` 执行：**`/sdcard` 是 `noexec` 挂载的**，文件放那儿能读不能运行，必须先同步到 App 数据目录。而"在数据目录里执行"通常被禁 —— 但那只对 `targetSdk ≥ 29` 生效，**本项目是 28**，所以这条路合法（当初刻意选 28 的收益之一）。

启动时日志面板会打印实际来源，排查"我换的到底生效没"看这行就够：

```
[bin] easytier-core <- 应用目录 (/sdcard/Android/data/.../files/bin/easytier-core)
```

另有一层兜底：万一某个 ROM 连数据目录都不给执行，核心会自动**回退到出厂副本**并在日志里写明
（`!! 无法执行 … 回退到内置副本`），不会把 App 弄挂。

代价：设备上会同时存在三份（出厂 + 投放 + 执行），arm64 下约 42 MB。嫌占空间可以把 APK 里的二进制从 `jniLibs` 改成 `assets`（省掉出厂那份 14 MB），代价是要改构建脚本和首次释放逻辑。

---

## 构建

全部在 WSL 里完成（Android SDK / Gradle 缓存在那边）。

```bash
# 0) 一次性
bash tools/prepare-build-env.sh

# 1) 交叉编译 EasyTier 二进制（产物落在 /root/easytier-build/rust-bins-aarch64/）
bash tools/build-rust-bins.sh
cp /root/easytier-build/rust-bins-aarch64/easytier-core binaries/arm64-v8a/
cp /root/easytier-build/rust-bins-aarch64/easytier-cli  binaries/arm64-v8a/

# 2) 出 APK
bash tools/build-apk.sh
# → dist/easytier-mini-arm64-v8a.apk
```

`build-rust-bins.sh` 的关键点（都已写在脚本里）：

- 二进制在 **`easytier` crate**，不是 `easytier-core` crate（后者 2.7.0 起只是控制面库）
- `--features management,cli`、`--target aarch64-linux-android`、`--profile mini`
- 依赖 NDK 环境：`LIBCLANG_PATH` / `KCP_SYS_EXTRA_HEADER_PATH` / `ZSTD_SYS_STATIC` / `BINDGEN_EXTRA_CLANG_ARGS`，以及 `CC_aarch64-linux-android` 等（`kcp-sys`、`zstd-sys`、`ring` 都是 C 依赖）
- 产物必须是 PIE（`ET_DYN`），脚本会用 `readelf` 检查

---

## 安装

```bash
# 连着 adb 时最稳，绕开一切第三方安装器
adb install -r dist/easytier-mini-arm64-v8a.apk
```

侧载时若被第三方「APK 安装器」（如 `com.yxer.packageinstalles`）拦下并报
`Bitmap.createBitmap ... width and height must be > 0`，那是**安装器自己**在栅格化图标时崩了（原因见「启动图标必须是有内在尺寸的位图」；旧版本 APK 的 `<shape>` 图标必崩，现已修好）。此时换系统安装器即可：文件管理里点 APK / 设置 → 应用 → 安装未知应用，或直接 `adb install`。

---

## 配置文件放哪

App 会扫描下面两个目录里的 `*.toml` / `*.conf`，按文件名排序列在下拉框里：

| 目录 | 需要权限 |
|---|---|
| `/sdcard/Android/data/com.kkrainbow.easytier.mini/files/` | 不需要（推荐） |
| `/sdcard/EasyTier/` | 需要读外部存储权限 |

用 adb push / MTP / U 盘拷进去都行：

```bash
adb push car.toml /sdcard/Android/data/com.kkrainbow.easytier.mini/files/
```

也可以直接在 App 里点 **「选择配置文件」** 调系统文件选择器：选中的文件会被**复制**到 App 自己的目录（上表第一条），复制完成后自动选中、随即可启动。复制采用 `.part` 临时文件再改名，所以就算选的文件本来就在该目录里也不会自己把自己截断。车机裁掉了文件选择器时，按钮会提示改用目录方式，不影响其余功能。

示例配置见 [`examples/config.example.toml`](examples/config.example.toml)。

---

## 功能与实现对应

| 功能 | 实现 |
|---|---|
| 启动 / 停止 | 单个切换按钮；标签与可用性全部由 `syncState()` 依 `Core.isRunning()` 推导，点击动作也读实时状态（不依赖标签）。`Core` 单例持有 `Process`，SIGTERM 优雅退出，5s 无响应则 SIGKILL |
| 选择配置 | `ConfigStore` 扫描目录 + `Spinner` 选择 |
| 导入配置 | 「选择配置文件」调系统文件选择器（`ACTION_OPEN_DOCUMENT`，回退 `ACTION_GET_CONTENT`），把所选文件复制进 App 自己的目录并自动选中 |
| 显示连接设备 | `Cli` 执行 `easytier-cli -p 127.0.0.1:15888 -o json peer`，用 `org.json` 解析（3 秒轮询） |
| 自动运行 | 勾选框在 `buildUi()` 里用 `prefs.autoRun()` 初始化（**早于** listener 挂载，避免启动时回写）；`maybeAutoRun()` + `CoreService`（前台服务，`START_STICKY`） |
| 日志 | 子进程 stdout/stderr 汇总到 400 行环形缓冲，界面直接可看 |
| 二进制替换 | `Binaries` 三级解析（覆盖目录 > 投放目录 > 出厂副本），同步到可执行目录后运行，见「二进制在设备上可替换」 |
| 后台控制 | `ControlReceiver`（导出、无权限）+ 广播动作 START / STOP / STATUS / LIST / SHUTDOWN，见下 |

## 后台控制（无界面自动化）

车机自动化用：不弹界面就能启停核心。入口是一个**导出且不设权限**的广播接收器
`com.kkrainbow.easytier.mini/.ControlReceiver`。

**必须用 `-n` 显式指定组件**：自定义 action 属于隐式广播，Android 8+ 不会投递给清单注册的接收器。
下面的命令刻意**写全名、不用 shell 变量**（在 Windows/pwsh 下 `$VAR` 会被本地 shell 吃掉）：

```bash
# 1) 启动（用「上次配置」）
adb shell am broadcast -n com.kkrainbow.easytier.mini/.ControlReceiver -a com.kkrainbow.easytier.mini.action.START

# 2) 指定配置文件启动（名字或绝对路径都行，但只认 App 自己目录里的 .toml/.conf）
adb shell am broadcast -n com.kkrainbow.easytier.mini/.ControlReceiver -a com.kkrainbow.easytier.mini.action.START --es config car.toml

# 3) 停止（核心优雅退出，常驻通知随之消失）
adb shell am broadcast -n com.kkrainbow.easytier.mini/.ControlReceiver -a com.kkrainbow.easytier.mini.action.STOP

# 4) 强制结束整个 App（核心 → 通知 → 进程，一步到位）
adb shell am broadcast -n com.kkrainbow.easytier.mini/.ControlReceiver -a com.kkrainbow.easytier.mini.action.SHUTDOWN
#    或交给系统做（连最近任务一起清、并标记为已停止）
adb shell am force-stop com.kkrainbow.easytier.mini
```

查状态 / 列配置需要**有序广播**，回值才会打印：

```bash
adb shell am broadcast --ordered -n com.kkrainbow.easytier.mini/.ControlReceiver -a com.kkrainbow.easytier.mini.action.STATUS
# → Broadcast completed: result=0, data="running=true config=car.toml error=-"

adb shell am broadcast --ordered -n com.kkrainbow.easytier.mini/.ControlReceiver -a com.kkrainbow.easytier.mini.action.LIST
# → data="car.toml,home.toml"
```

所有动作都会往 App 的日志面板追加一行 `[ctl] ...`，出问题可直接在界面上看或点「复制日志」。

### 四个必须知道的坑

1. **全新安装后必须先用一次 App，或加 `--include-stopped-packages`。** 新装应用处于 *stopped* 状态，
   系统默认给广播加 `FLAG_EXCLUDE_STOPPED_PACKAGES`，广播不会送达：
   ```bash
   adb shell am broadcast --include-stopped-packages -n com.kkrainbow.easytier.mini/.ControlReceiver -a com.kkrainbow.easytier.mini.action.START
   ```
2. **「静默」= 不弹界面，但通知去不掉。** API 28 上前台服务必须有常驻通知（`startForeground(id, null)`
   直接抛异常）。想彻底无通知，只能把 App 做成系统应用 + `android:persistent="true"`。
3. **只认 App 自己目录里的配置文件。** 入口是导出的（无权限正是为了让 adb shell 能调），所以配置路径
   会先与 `ConfigStore` 的扫描结果比对，不在其中的一律拒绝并记日志——不会去读任意文件。代价是：
   这台车机上**任何 App 都能调这个入口**；要收紧只能改用 root 或系统应用做触发方。
4. **`SHUTDOWN` 与 `am force-stop` 的区别。** 前者先给核心发 SIGTERM 优雅退出，再收通知、杀进程
   （所以**不会**因为 `START_STICKY` 竞态被系统重新拉起并自动重跑配置），但不动「最近任务」；
   `am force-stop` 是系统语义，连任务一起清并标记已停止，适合当「彻底结束」的兜底。

未加开机自启：`BOOT_COMPLETED` 有意留空，开机触发交给你的自动化（需要的话再加，约 3 行）。

---

### RPC 门户为什么绑 `0.0.0.0:15888`

必须显式指定：2.7.0 里 `rpc_portal` 已不是 TOML 字段，不指定时核心会在 `15888–15900` 里随机挑端口，`easytier-cli` 就未必连得上。

但**不能**写 `127.0.0.1:15888`：

- EasyTier 绑套接字默认走 `BindDev::Auto`，会按 IP 反查网卡名（`tunnel/common.rs:229-233`），`127.0.0.1` → `lo`；
- Android 分支里这会真的下发 `SO_BINDTODEVICE`（`tunnel/common.rs:128-137`），而普通应用 UID 没有 `CAP_NET_RAW`，旧内核（Android 9 的 4.x）**必然 EPERM** → 核心报 `failed to listen` 并直接退出；
- 绑 `0.0.0.0` 是 unspecified 地址，`get_interface_name_by_ip` 直接返回 `None`，**根本不发这个 syscall**。

绑 `0.0.0.0` **不会**把管理接口暴露到局域网：管理 RPC 的默认白名单就是 `127.0.0.0/8` + `::1/128`（`easytier-core/src/management/rpc_server_hook.rs:16-18`），局域网来的连接在握手阶段就被拒。

所以：核心侧 `--rpc-portal 0.0.0.0:15888`，`easytier-cli` 侧仍连 `127.0.0.1:15888`。

**同理**：配置文件里的 `listeners` 也必须写 `0.0.0.0`（如 `tcp://0.0.0.0:11010`），写具体网卡 IP 会踩同一个 EPERM 坑。

### 启动图标必须是有内在尺寸的位图

`android:icon` **不能**指向 `<shape>` drawable。`ShapeDrawable.getIntrinsicWidth()` 返回 `-1`，而任何把图标栅格化的调用方（第三方「APK 安装器」、部分车机的安装/桌面程序）都会执行
`Bitmap.createBitmap(d.getIntrinsicWidth(), d.getIntrinsicHeight(), ...)`，于是抛：

```
java.lang.IllegalArgumentException: width and height must be > 0
    at android.graphics.Bitmap.createBitmap(Bitmap.java:1336)
```

安装会直接失败，而且报错栈全在安装器里，看起来完全不像本项目的问题。

所以 `android:icon` 指向 `@mipmap/ic_launcher`：5 个密度的真实 PNG（48/72/96/144/192）+ `mipmap-anydpi-v26` 自适应图标，由 `tools/gen-icons/IconGen.java`（Java2D）生成。本机没有任何 SVG 栅格化工具（无 rsvg/inkscape/ImageMagick/PIL），所以图标用程序化绘制、可重复生成：

```bash
wsl -d Ubuntu -u root -- bash -lc "tr -d '\r' < /mnt/d/project/easytier-mini/tools/gen-icons/IconGen.java > /root/IconGen.java && cd /root && javac IconGen.java && java IconGen /mnt/d/project/easytier-mini/android/app/src/main/res"
```

`tools/verify-apk.sh` 会守这条线：图标必须至少解析出一个 PNG，且不得是 `<shape>`。

**签名同时开 v1/v2/v3**：AGP 在 `minSdk >= 24` 时默认只签 v2，而部分第三方安装器和老车机 ROM 仍会去查 `META-INF` 的 JAR 签名，缺失就拒绝安装。

---

## 已知限制

- **仅 arm64-v8a**。
- `targetSdk 28` 是**故意的**：绕开 Android 10+ 的执行限制、前台服务类型强制、通知权限与分区存储。本 App 只做侧载，不上应用商店。
- 车机 Android ≤ 9 时前台服务类型属性根本不生效；Android 14+ 设备上因为 `targetSdk < 34` 也无需声明 FGS 类型。
- `color-mix()` / 现代 CSS 与本项目无关（没有 WebView）。
- 配置里的 `listeners` 必须用 `0.0.0.0`（原因见「RPC 门户为什么绑 0.0.0.0」），写具体网卡 IP 会被 Android 拒绝并导致核心启动失败。
- 未做：开机自启（`BOOT_COMPLETED`）、多实例、配置可视化编辑。

---

## 致谢与许可

**感谢 [EasyTier](https://github.com/EasyTier/EasyTier) 项目。** 本 App 只是它的一个外壳：网络协议、中继、
NAT 穿透、RPC 全部来自 EasyTier 本体。本项目 **没有修改它任何一行源码**，只是把
`easytier-core` 当子进程启动、用 `easytier-cli` 读状态。

| 范围 | 许可 |
|---|---|
| 本仓库自身代码 | **MIT**，见 [`LICENSE`](LICENSE) |
| APK / 发行版中内置的 EasyTier 二进制 | **LGPL-3.0**，全文见 [`LICENSES/LGPL-3.0.txt`](LICENSES/LGPL-3.0.txt) |

- EasyTier 源码：<https://github.com/EasyTier/EasyTier> · 官网文档：<https://easytier.cn>
- 两者许可不同是**有意的**：本项目以**独立进程**方式调用 EasyTier —— 不链接、不修改、不构成衍生作品；
  但一旦分发内置了其二进制的 APK，仍须遵守 LGPL-3.0，因此仓库内附许可全文并指明源码出处。
- 顺带一提：LGPL-3.0 要求使用者**能够替换**其中的库。本项目「二进制在设备上可替换」的设计
  （见「二进制在设备上可替换」一节）恰好满足了这一点 —— 用户可以直接换掉 `easytier-core`，
  无需重新编译本 App。
