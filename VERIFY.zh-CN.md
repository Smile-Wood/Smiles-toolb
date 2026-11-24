# 逐版本验证清单

本模组的**发布物只有一个 jar**：`build/libs/smile_toolb-<版本>.jar`，`depends.minecraft`
是 `>=1.20.1`，1.20.1 ~ 26.2 通用（模组侧零 `net.minecraft` 引用，见 README 的
「单 jar 与 Stonecutter」）。工程里只保留 Stonecutter 的**两个节点**（1.20.1 基准 +
1.21.11 最新），产物**不发布**，只作为两端夹逼的回归检查：两端各对着该版本的 MC
编译一遍，字节码逐字节相同才说明这一份 jar 通用。中间 6 个节点已归档到
`archived-versions/`。配置界面只定义预设
（`SmileToolbConfig.build()`），渲染全部由前置库 `ConfigScreen` 负责，
**不依赖 MaLiLib**。运行时需要把**同版本的前置库 jar**一起放进 `mods/`
（详见库 README 的「构建与安装」），`depends` 里只有 `smile_libbox`。

启动后按下面几项走一遍。任何一项失败，把 `logs/latest.log` 里 `smile_toolb` /
`SmileTTS` 相关行发出来即可定位。

## 0. 自动验证记录

### 配置界面预设化：定义留在 toolb，渲染搬进库（2026-11-11）

**背景**：上一版配置界面直接继承 MaLiLib 的 `GuiConfigsBase`，26.2 上更是只能退回
`UiCanvas.fill` 手绘色块，既不像原版材质，也把 MaLiLib 拖成了硬依赖。
现在改成：toolb 只产出 `ConfigSpec`（纯 Java 数据结构），`ModMenuIntegration` 里
「报个名」交给 `Ui.config(parent, spec)`，页签、原版按钮、原版滑条、滚动条、
`重置` 置灰、窗口缩放重算全部由库负责。

| 层 | 改动 |
| --- | --- |
| 库 `api/ui/ConfigSpec` | 预设数据结构：`title / parent / onSave / pages`；行工厂带 `key`（对应配置文件项），`Row.resettable()` 标记可按行重置 |
| 库 `api/ui/ConfigLayout` + `ConfigValues` | 第二条定义路线：JSON → `ConfigSpec`，值经 `ConfigValues` 读写配置文件；与手写构造产出**同一个** `ConfigSpec` |
| 库 `api/ui/ConfigScreen` | 唯一渲染实现：`UiHost.button/slider` 建真控件，`applySlot()` 只让完整可见的行显示，标签列 `scissor` 裁剪 |
| 库 `client/compat/VersionedScreen` | 8 节点 + 26.2 各一份宿主；所有输入回调**先 `super`** 再交 `spec` —— 否则控件「看着在那儿却点不动」 |
| toolb `config/SmileToolbConfig` | 六个讲述人行 + 杂项页（含按 `BarFeatureDetector` 动态增减的追踪条两项）；每次改动 `SmileConfig.save()` + `TtsEngine.applyConfig()` |
| toolb `integration/ModMenuIntegration` | 只剩一个工厂方法：`(Screen) Ui.config(parent, SmileToolbConfig.build(parent))` |
| 删除 | `Configs` / `GuiSmileToolbConfigs` / `MalilibTextFieldMixin` / `EditBoxCursorAccess(Mixin)`；`build.gradle` / `settings.gradle` / `fabric.mod.json` / 8 个节点的 `gradle.properties` 全部去掉 malilib |

先前在 MaLiLib 上踩到的布局问题，算式原样迁进了库的 `measure()`，因此不会再回来：

| 旧现象 | 现在的做法 |
| --- | --- |
| 配置项压住列表右侧滚动条 | 右端锚点：`RESET 右端 = 宽度 − max(24×3/缩放, 4+8+6)`，滚动条占 8 px、与 `重置` 留 6 px |
| 两页配置项左右宽度不一致 | `configWidth` 由**全页最长标签**算一次（206 封顶、120 下限），标签列吸收余量，切页整表不动 |
| 全屏下滑条背景右段缺一块 | 控件宽仍封在 206（`widgets.png` 按钮条带只有 200 px） |
| 切页签 / 进全屏后错位 | `render()` 里发现 `width/height` 变化就 `rebuild()`，控件全部重建后重算槽位 |
| 滚出视口的控件仍在画 | 原版 widget 不受画布裁剪，`applySlot()` 对非完整可见行调 `widgetVisible(id, false)` |
| 标题显示成 `smile_toolb.gui.title.configs` | 标题在 `render()` 里 `Ui.tr(...)`，构造期不翻译 |

**构建**：

```
库  ./gradlew build publishToMavenLocal      → 1.0.4，8 节点全绿并入 mavenLocal
库  python tools/build_named26.py            → smile_libbox-1.0.4+26.2.jar（14 个 named class）
toolb ./gradlew build                        → 8 个节点全绿（回归检查）
toolb python tools/build_release.py          → smile_toolb-1.0.4.jar（单 jar，通吃 1.20.1~26.2）
```

产物校验（Python 逐 class 读常量池）：toolb jar 内 `fi/dy/masa` 引用 **0** 处、
`net/minecraft/` 引用 **0** 处；`depends` 只剩
`fabric-api / fabricloader / java / minecraft / smile_libbox`。

**真机观感仍需按第 2 节人工走查**：本次只保证「编译通过 + 控件换成了原版实现」，
按钮材质、悬停高亮、滑条拖动、`重置` 置灰这些要看实际画面。

### 收敛成一个 jar（2026-11-11，构建期验证）

**背景**：预设化之后 toolb 只剩最后一处 MC 类型 —— `ModMenuIntegration` 里
`ConfigScreenFactory.create(Screen)` 的 `Screen`。它逼着每个版本各出一个 jar
（intermediary 是 `class_437`，26.2 是官方名，一个 class 文件只能写一种）。

**做法**：这个工厂改成 `java.lang.reflect.Proxy` 生成，方法参数按 `Object` 收、
把 `Ui.config(parent, spec)` 的返回值直接交回去。本类于是只引用 Mod Menu 自己的接口
（包名跨版本一致），`Screen` 只存在于接口描述符里。配套：

| 改动 | 位置 |
| --- | --- |
| 工厂代理化 | `client/integration/ModMenuIntegration`（`ScreenFactory implements InvocationHandler`） |
| `version` 去掉 `+<MC>` 后缀 | `build.gradle` |
| `depends.minecraft` 固定 `>=1.20.1`、`java` 固定 `>=17` | `build.gradle` 的 `replaceMeta` |
| 新增发布脚本 | `tools/build_release.py`（基准取 1.20.1，交叉核对其余节点） |
| 删除 | `tools/build_named26.py`（26.2 不再需要单独出包） |
| 清理 | 8 个 `versions/*/gradle.properties` 里已无用的 `deps.minecraft_range` / `deps.java_range` |

**验证（纯构建期，已通过）**：

```
基准：smile_toolb-1.0.4.jar（43 个条目）
  一致：1.20.4 / 1.20.6 / 1.21.1 / 1.21.4 / 1.21.5 / 1.21.8 / 1.21.11
        全部 43 个条目与基准只差 class 版本号
已生成 build/libs/smile_toolb-1.0.4.jar
  class 版本 61（Java 17 起可加载）、43 个条目、零 net/minecraft 引用
  depends: fabricloader >=0.20.1 / minecraft >=1.20.1 / java >=17 /
           fabric-api * / smile_libbox >=1.0.4
```

「只差 class 版本号」的判定方式：把每个 class 的第 6~7 字节（major version）抹成 0
再算 SHA-1。8 个节点源码完全一致（1.20.1/1.20.4 按 17 编，其余按 21 编），
说明没有任何代码按版本分叉 —— 这是「一个 jar」能成立的硬证据。

**仍需实机**：1.20.1 与 26.2 各开一次配置界面，重点看 Mod Menu 那个「配置」按钮
（代理路径）与 F8（`Ui.open` 路径）两条入口都能进界面。

### 坐下就崩 / 数字输入实时校验（2026-11-12 下午，构建 + 26.2 启动验证）

| 现象 | 根因与改动 |
| --- | --- |
| 坐在椅子（家具模组）、船、矿车上 **Render Frame 崩溃** | 26.2 的 `Hud.JumpableVehicleBar` 构造函数是 `Objects.requireNonNull(minecraft.player.jumpableVehicle())`，而椅子/船/矿车只是「有载具」，这个值是 `null`。我们的高级状态条控制原先用 `Ui.hasVehicle()`（任意载具）强制返回 `JUMPABLE_VEHICLE`，正好把它喂进 `requireNonNull` → NPE。改成三层防护：① toolb 只沿用原版算出的跳跃条状态，并叠 `Ui.hasJumpableVehicle()`；② 库 `BarEvents.jumpStateAllowed` 在写回前再判一次「原版也是跳跃条 + 真有可跳跃坐骑」；③ `UiBackend` 在 26.2 走官方 `LocalPlayer.jumpableVehicle()`，1.20.1~1.21.11（intermediary）按接口形状探测 `canJump()Z` + `onPlayerJump(I)V`，船/矿车/座椅的接口没有这一组、按载具类缓存结果 |
| 数字输入框点别处会变回滑条 | 改为**点别处只失焦、保持输入模式**；只有回车、再点切换按钮、`Esc` 才结束。重建（切页 / 改窗口大小）时把输入文本暂存后按行恢复，不提交也不切回滑条 |
| 想边输入边知道哪里不对 | 每次按键调 `ConfigSpec.Row#numberError`，返回码只有两类：`empty`/`pattern`（不是这条正则能接受的东西）与 `range_error`（数字但越界，含溢出 int），界面据此套两条固定模板 —— 「不合法的字符串！仅支持{正则名}」「不合法的数字！仅支持{min}~{max}之间的数据」。非法立刻 `setTextColor` 标红；**红字 tooltip 只在鼠标悬停在输入框上时出现**，合法时完全不弹 —— tooltip 框刚好压在输入框上方，常驻会挡住正在键入的字。正则名默认是内置的「整数」，`row.regex(pattern, "本地文件夹")` 自定义，`row.regexFile("txt")` 一步得到「.txt格式文件」；JSON 对应 `"regex"` / `"regexName"` / `"regexFile"`。写错正则不崩、自动退回只校验整数 |
| 选项之间没有依赖关系 | `ConfigSpec.Row#requires(前置行)`（JSON：`"requires": "<前置项的 key>"`）：前置必须是布尔行，关掉后所有指向它的从属行一起禁用 —— 控件变原版灰色禁用态、标签变暗、重置键失效、正在编辑的输入框退出。悬停禁用行时 tooltip 是两行：第一行该行说明（默认色），**最后一行红字**「要启用此项，你需要开启『xxx』」。一个前置可带任意多个从属行 |
| tooltip 是自己画的黑框，不跟鼠标 | 改为**原版 tooltip**：`UiHost#tooltip` 由宿主按版本调 `renderComponentTooltip`（1.20.1~1.21.5）或 `setComponentTooltipForNextFrame`（1.21.8+ / 26.2），拿到原版的框、换行、贴图与边界翻转，且以鼠标为锚点。同时补上**配置项自身的 tooltip**：鼠标停在某行标签文字区时显示该行 `tooltip` 字段（此前这个字段完全没被使用） |
| 非法输入被写进配置 | 回车只提交合法值；再点切换按钮、关界面时非法内容按「丢弃」处理，不落盘 |

**纯 Java 单测**（库的 `python tools/selftest-number/run_selftest.py`，桩掉 `Ui.tr`、不启动游戏）
覆盖 105 项并全过：默认正则与自定义正则、非法正则兜底、整数溢出、`null`/空白、上下边界、
超范围、正则显示名（默认「整数」/ 自定义「本地文件夹」/ `regexFile("txt")` 的后缀匹配与
大小写）、前置项的一对多与 JSON 后置声明、底部那一行的自动宽度/左右对齐/禁用提示/网页按钮、
「完成」与重置的开关、字符串行的正则校验与复位，
以及「代码定义」与「JSON 定义」两条路线产出的 `Row` 结构一致、`intSave` 能写回使用方配置。

**构建与回归**：库 `./gradlew build publishToMavenLocal` 8 节点全绿 +
`tools/build_named26.py`（13 个 named class，无中介名）；toolb 仍是唯一
`smile_toolb-1.0.4.jar`（零 `net/minecraft` 引用）。26.2 装
`smile_libbox-1.0.4+26.2.jar` + `smile_toolb-1.0.4.jar`，
`VERIFY_CAPTURE=0 python tools/run26_verify.py 80`：日志可疑行 **0**，
`26.2 hook active: Hud.ContextualInfo`、Mod Menu `hasConfigScreen=true`、
配置页 2 个（讲述人 6 行 / 杂项 7 行，共 13 行）、假人间隔 5 tick（4.0 条/秒）。

**未覆盖**：坐椅子/船/矿车的实机复现（需要进服玩一次）、红字与 tooltip 的画面观感。

### 滚动网格 / 数字输入框 / 假人间隔（2026-11-12，构建 + 26.2 启动验证）

| 现象 | 改动 |
| --- | --- |
| 滚动很奇怪、有一行只渲染一半 | 行高改为 `可视区高 ÷ 可见行数`，行数按 `ceil(view/22)` 取（比旧的 floor 多一行）并受 `floor(view/20)` 约束；滚动量改成**整行数**，滚轮/拖动/点轨道都吸附到整行，两页共用同一套行高 |
| 滚动条只有一层色块 | 改成「暗色轨道（背景）+ 一颗原版按钮做的滑块（前景）」；滑块能拖，轨道能点。各版本 `blitSprite` 签名差异太大，用真 widget 反而最稳 |
| 滑条想直接键入数字 | 滑块行右侧加一颗原版小按钮（占位字形 `T` / `≡`），点它切到 `EditBox`：回车提交、`Esc` 丢弃、失焦提交、值钳在 `min~max`。`UiHost` 增 `numberBox` / `textBoxValue(id,..)` / `textBoxFocus`，`UiScreen` 增 `beforeKey` 让 Esc 不被 Screen 抢去关界面 |
| 批量召唤被踢（`由于滥发消息而被踢出游戏`） | 原实现每 tick 一条（20 条/秒）正好贴着服务端 `TickThrottler` 的令牌桶上限，tick 一错开就抽干。改成默认每 **5 tick** 一条（4 条/秒，5 倍余量），`-Dsmile_toolb.dummyIntervalTicks=N` 可覆盖 |

**构建**：库 `./gradlew build publishToMavenLocal`（8 节点全绿）+ `tools/build_named26.py`
（13 个 named class）；toolb `./gradlew build`（8 节点全绿）+ `tools/build_release.py`
（43 个条目全部只差 class 版本号，零 `net.minecraft` 引用）。两侧版本统一升到 **1.0.4**
（避免 mavenLocal 里同版本号的旧字节码被下游复用）。

**26.2 实机启动**（`python tools/run26_verify.py 45`，隔离启动、未动整合包其它模组）：

```
Loading 149 mods: - smile_libbox 1.0.4+26.2 / - smile_toolb 1.0.4
假人：间隔=5 tick（4.0 条/秒）
Mod Menu 集成：已安装，hasConfigScreen=true，自检建界面=VersionedScreen
配置界面自检：页签=2（讲述人=6，杂项=7），共 13 行
Sound engine started
```

零 `Mixin apply ... failed`、零我方异常；`自检建界面=VersionedScreen` 是走
`ModMenu.getConfigScreen(id, parent)` 真实调用链拿到的，说明新的 Proxy 工厂在
named 命名空间上确实能建出界面。

**未覆盖**：按钮材质、滚动条观感、数字输入框的键鼠交互需要真人看画面走第 2 节清单
（无人值守截图被桌面上另一个全屏程序挡住了，`verify26-window.png` 的 PrintWindow
只抓到 Mojang splash，OpenGL 内容抓不到）。

### 历史：配置界面曾直接用 MaLiLib（2026-11-11，已被取代）

这一版的作用是定位「Mod Menu 入口时有时无」与布局错位，结论已并入库的 `measure()`；
代码已随预设化删除，记录留作对照。

| 现象 | 当时的根因 | 当时的处理 |
| --- | --- | --- |
| 配置项压住列表右侧滚动条 | `getConfigGuiDisplayName()` 回退成原始键名，标签列被撑到 240px；重置键实际 40px 而只按 22 预留 | 覆写显示名；`measureResetWidth()` 同口径计算 |
| 切页签 / 进全屏后错位 | 列表控件只在首次 `initGui` 建一次并跨调用复用 | 宽度变了主动 `reCreateListWidget()` |
| 两页配置项的左右宽度不一致 | MaLiLib 按**当前页**的 `listContents` 算 `maxLabelWidth` | 覆写 `getMaxNameLengthWrapped` 返回两页公共最大值 |
| 全屏下滑块背景右段缺一块 | 滑条拿 `widgets.png` 按钮条带做九宫格，条带只有 200 px | 控件宽上限收到 206 |
| 全屏下 RESET 往左缩 / 压住滚动条 | 右边界跟着控件走；留白是固定逻辑像素 | 右端改锚点 + 留白按 `24×3/缩放` 折算 |
| 配置界面里打一个字就崩游戏 | MaLiLib 0.16.3 的 `setCursorPosition ⇄ moveCursorTo` 死循环、`getCursorPosition` 自递归 | 两个 mixin 还原语义；**MaLiLib 整条依赖移除后此问题一并消失** |

**实测数据**（当时 `smile_toolb.selftest.open_config=true`，日志 `配置界面布局`）：

```
427×240，缩放 3：标签列=135，控件宽=206，重置键=40，右端=403
480×270，缩放 4：标签列=194，控件宽=206，重置键=40，右端=462
```

### 杂项：快速召唤 / 删除假人（2026-11-11，实机 + 纯 Java 冒烟）

**需求**：杂项里一键随机召唤 20 个假人（数量可调）用于打试炼；点删除后移除；
依赖 Carpet 的 `/player` 命令，没有该命令时无效；命令仅发包、不开聊天框；删除前先丢物品。

| 层 | 改动 |
| --- | --- |
| 库 `Ui#sendCommand` / `Ui#hasCommand` | 纯 Java 门面：发包发命令（返回空串=已发出，否则 `offline` / `empty` / 异常名）；探测服务端命令树里有没有某个根节点 |
| 库 `UiScreen#drag` / `#release` | 新增默认方法；两代 `VersionedScreen` 分别转发 `mouseDragged` / `mouseReleased`（1.20.1 是 `double,double,int` 系，26.2 是 `MouseButtonEvent` 系） |
| 库 `ConfigScreen` 滑块行 | 原版 `AbstractSliderButton`：点哪跳哪、按住可拖，`updateMessage()` 把 0~1 的 `value` 换算成整数显示；右侧是原版 `重置` 按钮 |
| toolb `client/dummy/DummySpawner` | 随机名 `bot_xxxx`、命令队列（默认每 5 tick 一条）、按地址记账、删除时先 `drop` 再 `kill` |
| toolb `config/SmileToolbConfig#miscPage` | 杂项页：召唤数量滑块 + 召唤 / 删除两个动作行 + 状态行；没有 `/player` 时只加一行说明 |

**纯 Java 自检**：旧界面时代的 `ConfigScreenSmoke`（点数值框 → 键入 37 → 拖滑条，
直接驱动旧 `ConfigScreen`）已随界面重写删除 —— 现在的滑条是原版控件，命中判定由
`AbstractSliderButton#mouseClicked` 负责，库只把整数区间映射进 `value`。
替代它的是启动日志里的行数统计（见下），以及第 2 节的人工走查。

**26.2 隔离整合包实机**（`Loading 149 mods`）：

| 场景 | 日志 | 结论 |
| --- | --- | --- |
| 主菜单（没有服务端命令树） | `假人：sendCommand 探测=offline，/player 可用=false` | 门面在未连接时返回 `offline` 而不是抛异常 |
| 主菜单 + 打开配置界面 | `行数=15 … 假人=不可用（无 /player 命令）` | 不可用时只多一行说明，按钮不出现 |
| 强制可用（仅自检属性） | `行数=18 … 假人=可用（每次 20 个，已记录 0 个）` | 滑块 + 两个按钮 + 状态行正常渲染，界面打开无异常 |
| 记录自检 | `名字=bot_s29i（合法=true），写入→读回=一致，其它地址=不串，清空后=已清除，文件=已删除` | 命名合法、按地址隔离、清空即删文件 |

两种场景都是 `Sound engine started`、零 `Mixin apply ... failed`、零我方异常；
toolb jar 34 个 class 的常量池仍为零 `net/minecraft` / `com/mojang/blaze3d` 引用。

**未覆盖**：真实 Carpet 服务器上的 `spawn` / `drop` / `kill` 实际效果（无人值守环境没有
装 Carpet 的服务器），按第 8 节人工走查。

### 杂项：保留聊天框内容（2026-11-11，实机）

**需求**：关闭聊天框后再打开保留已输入内容；退出服务器后短时间重进保留；换客户端顶号
重启保留；**判定以连接地址为准**。

| 层 | 改动 |
| --- | --- |
| 库 `smile_libbox.api.event.ChatDraftEvents` | 纯 Java 草稿总线：`onCapture` / `onRestore` / `dispatchCapture` / `dispatchRestore` |
| 库 `Ui#serverAddress` | 取 `ServerData.ip` 的 `String`，两代 `UiBackend` 各实现一份；被踢 / 断线导致地址已清空时，短期（60s）返回最后一次非空地址 |
| 库 `LibChatDraftMixin` | 8 个 Stonecutter 版本 + 26.2 named 各一份；注入 `ChatScreen#init` / `removed` / `handleChatInput` |
| toolb `ChatDraftStore`（新） | 按地址分 key、TTL、用户主目录共享文件、原子写、提交后清除 |
| toolb 配置界面 | 杂项页加「保留聊天框内容」开关、保留时长滑块（60~3600s，默认 600）、缓存条数状态行 |

**26.2 隔离实例实机**（`Loading 149 mods`，日志 `verify-named-out8.txt`；
加入地址缓存后回归 `verify-named-out9.txt`，两次结论一致）：

```
聊天草稿：捕获=已注册，恢复=已注册，判定=连接地址，保留=600s，文件=C:\Users\wyj12\.smile_toolb\chat-drafts.json
聊天草稿自检：写入→读回=一致，其它地址=不串，清空后=已清除，文件=已删除
配置界面自检：页数=2，行数=14（开关 5）… 聊天草稿开关=显示，有效草稿=0 条
Sound engine started
```

`mixin.debug.export` 导出的 `net/minecraft/client/gui/screens/ChatScreen.class` 反编译确认
三个注入点全部织入：`init()` TAIL → `dispatchRestore` → `EditBox.setValue`；
`removed()` HEAD → `EditBox.getValue` → `dispatchCapture`；
`handleChatInput` HEAD → `dispatchCapture(addr, "")`。零 `Mixin apply ... failed`、
零我方异常、零崩溃报告。toolb jar 29 个 class 常量池仍零 `net/minecraft` /
`com/mojang/blaze3d` 引用，单 jar 架构未破坏。

**版本差异**（逐版本用 mappings / `javap` 静态核对，Loom 产物注解已抽查 1.20.1 /
1.20.6 / 1.21.11）：1.20.1 与 1.20.4 的 `handleChatInput` 返回 `boolean`，用
`CallbackInfoReturnable<Boolean>`；1.20.6 起（含 26.2）返回 `void`，用 `CallbackInfo`。
`init()` / `removed()` / `@Shadow EditBox input` 在 8 个版本都映射为
`method_25426()V` / `method_25432()V` / `field_2382`；26.2 那份直接写官方名、无 refmap。

**边界**：无人值守覆盖到「注入织入 + 配置开关 + 纯 Java 存取链路」。三个真实场景仍需
按第 7 节在游戏内人工走查；1.20.1~1.21.11 未跑聊天框内交互，其注入点目前是静态核对结论。

### 使用方自决注入 + 第三方联动总线（2026-11-09，实机）

**要解决的问题**：依赖库的模组**自己按版本决定要不要注入**。判断权归使用方，
库只提供判断依据（`Conditional` / `ModPresence`）与门控基类，不替使用方拍板。

| 改动 | 位置 |
| --- | --- |
| 新增 `SoftTargetMixinPlugin` | 库 `smile_libbox.api`，继承 `VersionedMixinPlugin`；`when()` 版本表达式 + `modId()` + `markerClasses()`，在 `shouldApplyMixin()`（解析前）门控，`getRefMapperConfig()` 固定 null，Loader 未就绪时走 classpath 资源探测兜底 |
| 新增 `ModLinks` | 库 `smile_libbox.api.link`，纯 Java 的 hook 字符串总线；注入类只派发，业务规则注册回调 |
| `BackportMixinPlugin` | 由 70 行手写改成继承基类 + 2 个覆写（`modId` / `markerClasses`） |
| `VanillaBackportBarMixin` | 薄注入，只做一次 `ModLinks.dispatchBoolean(HOOK, ...)` |
| `BackportBarLink`（新） | 原 mixin 里的定位条业务规则，普通 Java 类，在 client 入口注册 |

**26.2 隔离实例实机**（`Loading 149 mods`，日志 `verify-named-out7.txt`）：

```
软目标联动门控已加载：mod=vanillabackport markers=[...LocatorBarRenderer]
第三方联动已注册：hook=vanillabackport.locator_bar.skip，目标模组可用=false
联动总线：hooks=1 [vanillabackport.locator_bar.skip(1)]
状态条特性检测：… 上下文枚举可用=true
配置界面自检：页数=2，行数=11（开关 4）… 上下文条接管=可用
Sound engine started
```

第一行证明**跨 jar 继承库基类在 mixin 极早期能正常加载**；26.2 没装 VanillaBackport，
门控按预期跳过该 mixin，零我方异常。toolb jar 的 26 个 class 常量池扫描仍是
**零 `net/minecraft` / `com/mojang/blaze3d` 引用**，单 jar 架构未破坏。

**边界**：本次只覆盖「门控正确跳过 + 总线注册成功」。26.2 未装 VanillaBackport，
注入真正命中需在 1.20.1 + VanillaBackport 1.2.2 上按第 5 节人工走查；
注入方法 `shouldSkipRendering()Z` 的描述符未改（不含 MC 类型），低版本行为不变。
验证脚本另修了混合编码问题：log4j 走 GBK、mixin 早期 `System.out` 走 UTF-8，
之前整体回退 GBK 会把门控那行显示成乱码。

### 最终架构验证（2026-11-07，库多 jar + toolb 单 jar + 26.2 named 适配）

| 环境 | 注入方式 | 结果 |
| --- | --- | --- |
| 1.20.1 `runClient` | `smile_libbox-1.0.0+1.20.1.jar` + `smile_toolb-1.0.0.jar` | **通过**：进主界面（`Sound engine started`），初始化日志齐全，无 Mixin 失败 |
| 26.2 PCL2 正式整合包 | `smile_libbox-1.0.0+26.2.jar` + `smile_toolb-1.0.0.jar`，隔离 gameDir + `-Dfabric.addMods` | **通过**：`Loading 148 mods` 接受两个 jar，进主界面，零我方异常 |

**1.20.1**：`Smile's toolb 已加载` / `版本环境：mc=1.20.1 protocol=763 world=3465
data=15.0 resource=15.0 java=17` / `门控自检：…` / `讲述人已切换到系统语音引擎` /
`Sound engine started`。本轮把 `InputConstants.Key` 全部换成库的 `KeyRef` 后重建回归，
toolb jar 内 25 个 class 的常量池扫描为**零 `net/minecraft` 引用**。

**26.2**：Java 25（实跑 JDK 26）、Fabric Loader 0.20.1、`fabric-api 0.161.0+26.2`，
148 个模组（含注入的 2 个）。用隔离 gameDir 复刻 + `-Dfabric.addMods` 注入，
**未改动原实例 mods 目录**。日志：

```
Loading 148 mods: - smile_libbox 1.0.0+26.2 / - smile_toolb 1.0.0
版本环境：mc=26.2 protocol=776 world=4903 data=107.1 resource=88.0 java=25
门控自检：>=1.20=true, datapack:>=15.0=true, ... mod:minecraft=true
Smile's toolb 已加载
讲述人已切换到系统语音引擎
Sound engine started
```

无 `Crash Report` / `ModResolutionException` / `NoClassDefFoundError` /
`Mixin apply ... failed`，也没有任何含 `smile_*` 类名的错误行。
控制台中文是 GBK，按 UTF-8 解码会误判「自检日志没出现」。

**怎么跑通的**：26.2 是 named 命名空间（无 intermediary、无 refmap），库新增
`tools/build_named26.py`，用 `javac --release 25` 直接对 26.2 客户端 jar 编译
`VersionedScreen` / `UiBackend` / `VersionedNarrator`（后来又补上三个官方名 mixin，
见下一节），产出 `+26.2.jar`；
toolb 则彻底迁到 `smile_libbox.api.ui.Ui` 纯 Java 门面，删除 Cloth Config / ModMenu
以及 `InputConstants` 残留，主 jar 不再写死任何 MC 类名。

**26.2 功能边界（已补齐，见下一节）**：最初 `+26.2.jar` 不带 client mixin，
`InputEvents` / `BarEvents` 不触发，两个状态条开关只显示不生效。现在库为 26.2
单独编译了三个注入点（Hud / Screen / MouseHandler），第 2~6 节的功能通道在 26.2
全部接通，配置界面里那行「适配层尚未接管」提示会自动消失。
日志里的 401 / Realms 失败来自开发账号，其它模组的
`Failed to fetch class node ...` 属整合包软目标探测，与本模组无关。

### 26.2 高版本功能补齐（2026-11-07 晚，实机）

改动**只在库侧 `+26.2.jar`**，toolb 单 jar 一行没动，仍是零 MC 引用：

| 26.2 官方名注入点 | 织入后调用 | 对应功能 |
| --- | --- | --- |
| `Hud#nextContextualInfoState` | `BarEvents.dispatch` | 第 5 节两个状态条开关真正接管上下文条 |
| `Screen#keyPressed(KeyEvent)` | `InputEvents.dispatchScreenKey` | 第 4 节自定义退出键 |
| `MouseHandler#onButton(long,MouseButtonInfo,int)` | `InputEvents.dispatchMouseButton` | 第 4 节任意界面的鼠标退出键 |

配套修正：四状态枚举在 26.2 从 `Gui$ContextualInfo` 搬到 `Hud$ContextualInfo`
（常量顺序不变），`UiBackend.contextualStates()` 两个类都查；`hasWaypoints()`
改走 `ClientPacketListener#getWaypointManager()#hasWaypoints()`；
`currentScreen()` 改读 `Minecraft.gui.screen()`，原版自己开的界面也能识别。
mixin 配置 `smile_libbox.named26.mixins.json` 用 `JAVA_25`、无 refmap、无 plugin，
注入点直接写官方名描述符；`fabric.mod.json` 里声明为 client mixin。

验证方式：隔离实例加 `-Dmixin.debug.export=true`，导出被改写的类后反编译核对。

```
[smile_libbox] 26.2 hook active: Hud.ContextualInfo
状态条特性检测：游戏版本=26.2 ... 结论=VANILLA ... 上下文枚举可用=true
配置界面自检：页数=2，行数=11（开关 4），玩家追踪条开关=显示 ... 上下文条接管=可用
Sound engine started
```

`Hud.class` 的 `nextContextualInfoState` 每个 RETURN 分支前都出现
`libbox$chooseBar` 并 `checkcast Hud$ContextualInfo`；`Screen.class` /
`MouseHandler.class` 分别含 `InputEvents.dispatchScreenKey` /
`InputEvents.dispatchMouseButton`。零 `Mixin apply ... failed`、零崩溃、零我方异常。
行数由 12 降到 11，是因为 `ModConfigScreen` 的降级提示条件
（`VANILLA && states() == null`）不再成立——**界面文案不需要改，由数据自己决定**。

仍需人工进世界走查：按 Tab 切定位条、骑乘蓄力条 2.5 秒窗口、录制退出键、
F8/F9 与音色列表交互。无人值守的启动验证只保证注入与数据链路，覆盖不到这些
游戏内交互；1.20.1 的隐藏开关结论不受影响（没动 toolb 与 1.20.1 库 jar）。


### 按版本显示 / 隐藏「玩家追踪条开关」（2026-11-07 实机）

需求：26.2 显示两个状态条开关，1.20.1 不显示、其余配置项正常且模组不崩。

门控在 `BarFeatureDetector.get()`：第一行 `GameVersion.atLeast("1.21.9")` 用
Fabric Loader 的语义版本比较，`26.2` 的 major=26 > 1，高版本直接判 `VANILLA`；
低版本继续查 VanillaBackport / 上下文条 API / 第三方定位条模组，都没有就返回
`NONE`。`ModConfigScreen.addMiscEntries` 只认 `Result.present()`，所以两个
toggle 与状态提示一起出现或一起消失——不需要在 toolb 单 jar 里写版本分支。

为让结论可观测，加了两条启动日志（`状态条特性检测` / `配置界面自检`），以及
`-Dsmile_toolb.selftest.open_config=true`（或环境变量
`SMILE_TOOLB_SELFTEST_OPEN_CONFIG=1`）在启动时自动打开一次配置界面；默认关闭，
正常游玩不受影响。

| 环境 | 状态条特性检测 | 配置界面自检 | 结果 |
| --- | --- | --- | --- |
| 1.20.1 `runClient` | `游戏版本=1.20.1 atLeast(1.21.9)=false 结论=NONE 显示开关=false` | `页数=2 行数=9（开关 2）玩家追踪条开关=隐藏` | **通过**：配置界面实际打开，音色/语速/音量/字数/退出按键齐全，`Sound engine started`，零异常 |
| 26.2 隔离整合包（补齐前） | `游戏版本=26.2 atLeast(1.21.9)=true 结论=VANILLA 显示开关=true 上下文枚举可用=false` | `页数=2 行数=12（开关 4）玩家追踪条开关=显示 上下文条接管=未接管` | **通过**：界面已打开，`Sound engine started`，零我方异常 |
| 26.2 隔离整合包（补齐接管后） | `... 上下文枚举可用=true` | `页数=2 行数=11（开关 4）玩家追踪条开关=显示 上下文条接管=可用` | **通过**：三个注入点均已织入，零我方异常 |

行数差：补齐前比 1.20.1 多 3 行 = 两个 toggle + 一行 `bar_feature_display_only`
提示；补齐接管后只多 2 行（两个 toggle），提示行自动消失。

**两个仍需注意的点**：

- 「上下文条接管=可用」的依据是**枚举拿到了 + mixin 注入成功**。26.2 的
  `smile_libbox.named26.mixins.json` 是 `required: true` / `defaultRequire: 1`，
  注入点找不到会直接让启动失败，不会出现「枚举可用但钩子没接上」的静默降级。
  如果以后 Mojang 再改 `Hud#nextContextualInfoState` 的名字，先看启动日志里的
  `Mixin apply for mod smile_libbox failed`，再按新签名改 `named26/src` 下的注入点。
  早期那行「适配层尚未接管」提示仍保留在语言文件里，供未来再次降级时使用。
- 自检**不能**在 `ClientModInitializer` 里构造完整 `ConfigSpec`：它会经过
  `Ui.narratorOff()` → `Minecraft.options.narrator()`，而 client 入口阶段
  `options` 还是 null，实测两代都会
  `Could not execute entrypoint stage 'client'`。现在入口只做纯 Java 的版本门控
  诊断，界面自检放到运行时（tick 与 `CLIENT_STARTED` 双触发兜底）。

### 改造中途的失败记录（保留作对照）

最早用 `+generic.jar` 时 Loader 能接受两个 jar、库自检正常，但客户端入口崩在
`McCalls.keyMapping` → `NoClassDefFoundError: net/minecraft/class_304`，
ModMenu 集成报 `ClassNotFoundException: net.minecraft.class_437`。根因是
**命名空间代际断裂**：当时 toolb 单 jar 含 262 处 intermediary 符号引用，
`+generic.jar` 的适配层也带着它们；类名是编译期写进常量池的，
`Conditional` 延迟加载 / `Compat` 反射 / 放宽 `depends.minecraft` 都改不了。
另外第一次补 `+26.2.jar` 时漏了 `VersionedNarrator`，报
`NoClassDefFoundError: smile_libbox/client/compat/VersionedNarrator`
（26.2 的 `Narrator` 只剩三参数 `say`，不能复用 1.20.1 编译的基类），
补上按 26.2 接口编译的同名类后通过。

### 改造前的旧记录（2026-11-06，已过时，保留作对照）

用 `./gradlew :1.20.1:runClient` 与 `:1.21.11:runClient` 实跑，**同一个
`smile_libbox-1.0.0.jar`** 在两个版本都进到主界面，三条日志全部出现，没有
`Mixin apply ... failed`。两版自检分别为
`mc=1.20.1 ... data=15.0 resource=15.0 java=17` 与
`mc=1.21.11 ... data=94.1 resource=75.0 java=21`；六类条件表达式均按预期求值。
`runClient` 任务末尾的 `BUILD FAILED / exit value -1` 是验证完成后主动结束客户端
进程所致，不是编译或模组错误。

## 1. 启动与注入

- [ ] 游戏能正常进主界面（没有 `Mixin apply for mod smile_toolb failed`）
- [ ] 日志出现 `Smile's toolb 已加载` 与 `讲述人已切换到系统语音引擎`
- [ ] 日志出现 `系统语音讲述人已初始化（启用=…，在线音色=…）`

## 2. 配置界面

- [ ] 按 `F8` 能打开 **Smile's toolb 配置**；标题不能显示成 `smile_toolb.gui.title.configs`
- [ ] 顶部两个页签：**讲述人** / **杂项** 都在，当前页置灰；点另一页能换过去且不报错
- [ ] 控件行全是**原版材质**：开关是原版按钮、拖动条是原版滑条（悬停会变亮），
      右侧是原版 `重置` 按钮；界面里不应再出现纯色块画出来的控件
- [ ] 最右侧滚动条是**两层**：暗色轨道（背景）+ 带原版按钮贴图的滑块（前景）；
      完整可见，没有被控件或 `重置` 压住；427×240 与全屏各试一次
- [ ] 427×240 / 缩放 3：可视区 148px 应完整显示 **7 行**（旧版 6 行 + 半截），
      最后一行下面只剩 ≤1px 空隙，没有半截控件、也没有半截标签
- [ ] 滚轮一次走 2 整行，拖滑块 / 点轨道都吸附到整行，停下来后列表里全是完整行
- [ ] 两页切来切去：行高、列边界、滚动条长度都不变
- [ ] 窗口缩放、切全屏、切页签来回几次，行宽跟着变且不错位
- [ ] 拖语速滑条后 `config/smile_toolb/tts.json` 立即变化，下一条朗读就用新语速
- [ ] 滑条右侧的小按钮（占位字形 `T`）点一下 → 滑条变成数字输入框并自动聚焦；
      输入 `137` 回车 → 值变成 137、滑条回来、`tts.json` 同步；再点一次按钮应显示 `≡`
- [ ] 数字输入框按 `Esc` 应只丢弃输入、**不关界面**
- [ ] **点别处不会变回滑条**：点页签以外的空白、点别的行、点滚动条，输入框与已键入
      的内容都应留着（只失焦）；点回来能继续改。只有回车 / 再点 `≡` / `Esc` 才结束
- [ ] 实时校验：键入 `abc` → 文字立刻**变红**；**鼠标移到输入框上**才弹红字 tooltip，
      且只有一行「不合法的字符串！仅支持整数」；键入 `9999` → 红字 +
      「不合法的数字！仅支持1~50之间的数据」；清空 → 「不合法的字符串！仅支持整数」。
      **合法时不弹 tooltip**（否则挡住输入），鼠标移开则红字 tooltip 消失、
      输入框里的红字保留
- [ ] **前置项联动**：关掉「保留经验条」→「高级状态条控制」整行变灰、点不动，悬停它
      第一行是该项说明、最后一行红字「要启用此项，你需要开启『保留经验条』」；重新开启
      前置项后立刻恢复。同类联动：关「启用系统语音」→ 语速/音量/单条最大字符数/进游戏自动
      开启讲述人一起禁用；关「保留聊天框内容」→ 草稿保留时长禁用；关「启用退出按键」→
      退出按键列表按钮禁用
- [ ] 非法值按回车：不写回配置、仍停在输入框；再点 `≡` 或关界面则丢弃这次输入
- [ ] 合法值（如 `7`）回车：红字消失、值写回、滑条回来、`tts.json` 同步
- [ ] **只要输入框的行**：「单条最大字符数」（讲述人页，0~1000）与「草稿保留时长」
      （杂项页，60~3600）**没有滑条、也没有右侧 `T`/`≡`**，控件列整条就是一个输入框，
      右边直接接 `重置`；这两行适合填精确秒数/字数
- [ ] 这两行键入合法数字（如 `450`）后回车 → 值写回 `tts.json`；不按回车、直接点
      「完成」或按 Esc 关界面同样会写回（关界面就是保存点）
- [ ] 这两行键入越界值（如 `5000`）→ 立刻变红且不写回；此时直接关界面 → 这次输入被丢弃，
      显示恢复成原值，`tts.json` 不变
- [ ] **tooltip 是原版样式**：黑底 + 原版边框贴图（不是自绘的紫色边），跟随鼠标移动，
      靠近屏幕右/下边缘时自动翻到另一侧，长文本自动换行
- [ ] **配置项说明**：鼠标停在任意一行的**文字区**（不是滑条、不是重置键）应弹出该行说明；
      例如「保留聊天框内容」「召唤假人数量」「假人发送间隔」各有自己的说明文字；
      移到别行就换成别行的；移到控件区不弹；鼠标位置离文字区远了就消失
- [ ] `重置` 键在「值等于默认值」时是灰的，改过之后变可点；点它能把这一行恢复默认值
- [ ] 底部「全部重置」把所有行恢复默认值，并同步落盘
- [ ] 「音色：<当前音色>」按钮显示当前音色名；点进去是独立列表，不是长长的下拉

## 3. 音色选择界面（1.21.9+ 重点）

- [ ] 列表能正常渲染、能滚动（滚轮）
- [ ] 点一行：立即切换 + 立即试听 + 按钮文字变化
- [ ] 搜索框能输入文字（1.21.9+ 输入事件走的是转发壳，这里最容易出问题）
- [ ] `↑` / `↓` / 回车 有效，`Esc` 返回

## 4. 退出按键映射（杂项）

- [ ] 「退出按键映射」开关默认开；关掉后侧键不再关界面
- [ ] 任意界面按鼠标侧后键 == 按 Esc（关闭当前界面）
- [ ] 录制过程中按侧键不会把设置界面自己关掉
- [ ] **1.20.1 / 1.20.4 上点「退出按键列表」右侧的编辑按钮不崩**（MaLiLib 时代
      文本输入递归要打两个 mixin 才不崩；现在界面由库渲染、输入走原版控件，这条变成回归项）
- [ ] 退出按键那一行的按钮文字随条目数变化（`编辑（N 个）`），点进去是独立的按键列表界面
- [ ] 手改 `tts.json` 里的 `exitKeys` 后重启，效果与界面改一致

## 5. 状态条控制（1.21.9+ / VanillaBackport，杂项）

### 通用

- [ ] **坐在交互物体上不崩**（关键回归）：分别在**家具座椅**（mcwfurnitures 的椅子）、
      **船**、**矿车**上坐至少 3 秒，Render Frame 不应有任何异常。这三类都是「有载具但不可跳跃」，
      `LocalPlayer.jumpableVehicle()` 为 `null`；旧实现会因此让 26.2 的 `JumpableVehicleBar`
      在 `requireNonNull` 处崩掉
- [ ] 坐着椅子/船/矿车时快捷栏上方显示的是**经验条**（或定位条），**不是**空的跳跃蓄力条
- [ ] 同一场景下按住 Tab / 松开，切定位条与回经验条都正常，不崩
- [ ] 真正骑**马 / 骆驼**按空格：蓄力条照常出现，松开约 2.5 秒回经验条（说明修崩溃没把正常路径改坏）
- [ ] 「杂项」里能看到「使用旧版经验条动作」（默认开）与「高级状态条控制」（默认关）；
      **1.21.8 及以下、且没装定位条 backport 模组时两个都不显示**
- [ ] 低版本装了别的定位条类模组（名字带 locator / waypoint，或 Wayfarer）时，两个开关仍会出现，
      下方多一行「仅接管原版与 VanillaBackport」的说明；日志里能搜到 `检测到外部定位条特性`
- [ ] 低版本混进模组名带 locator 的无关模组：开关会显示属预期（启发式检测），但不影响其它功能
- [ ] 关闭第一项并保存：完全回到原版 / backport 自己的行为
- [ ] 旁观者模式下不会画出一条空的经验条

### 1.21.9+（原版上下文条）

- [ ] 只开第一项：附近有带路径点的队友时，快捷栏上方仍是经验条；骑乘时原版跳跃条照旧
- [ ] 两项都开：默认是经验条；按住 Tab 切到定位条，松开回到经验条
- [ ] 两项都开：骑乘马 / 骆驼按住空格出现蓄力条，松开后约 2.5 秒才切回经验条
- [ ] 两项都开：骑乘但不按空格时是经验条，不是一直挂着的蓄力条
- [ ] 两项都开：没有队友路径点的服务器里按 Tab 不切定位条

### 1.20.1 + VanillaBackport 1.2.2（针对性适配）

- [ ] 日志里能搜到 `检测到 VanillaBackport 定位条` 与 `VanillaBackport 定位条接口解析成功`
- [ ] 「杂项」里开关下方显示「已适配 VanillaBackport 1.2.2：两个开关可直接控制定位条。」
- [ ] 只开第一项：附近有路径点时经验条背景与等级数字完整可见，定位条背景和路径点图标都不出现
- [ ] 两项都开：默认经验条；按住 Tab 时定位条背景 + 路径点图标一起出现，松开立即恢复经验条
- [ ] 两项都开：没有路径点时按 Tab 不会出现一条空的定位条背景
- [ ] 若 backport 的 `locator_display_info_bar` 被打开，界面出现一行提示；关掉后 Tab 能显示完整定位条
- [ ] 骑乘但不按跳跃时是空的跳跃条（1.20.1 原版限制，不是回归）；按住跳跃时定位条不盖住蓄力条
- [ ] `F1` 隐藏界面时不会画出定位条

## 6. 发声

- [ ] `Ctrl + B` 开启讲述人后，聊天/界面能听到系统语音（不是原版 SAPI 的机械音）
- [ ] `F9` 试听当前音色
- [ ] 切到在线音色（Edge）能出声；断网后自动退回本地音色而不是静默

## 7. 保留聊天框内容（杂项）

配置位置：**杂项 → 保留聊天框内容**（默认开）、**草稿保留时长**（默认 600 秒）。
草稿文件在 `用户主目录/.smile_toolb/chat-drafts.json`，不同客户端实例（不同游戏目录 /
不同启动器）读的是同一份，这就是「换客户端顶号重启」的实现方式；key 是玩家填写的
连接地址（大小写不敏感，带不带默认端口 `:25565` 视为同一台服务器）。草稿清空后文件
自动删除。两个客户端同时在线时每次读写都重新读盘合并，但仍不建议并发编辑同一地址。
**边界**：文本只在 `ChatScreen#removed()`（关闭界面 / 断开 / 被踢）与提交消息时落盘；
直接强杀进程不会走 `removed()`，最后一次编辑会丢失。两个客户端若使用不同的系统账户，
`user.home` 不同，也读不到同一份文件。

**发送不落盘**：原版 `handleChatInput` 只发包、不清空 `EditBox`，随后才 `setScreen(null)`，
所以 `removed()` 里读到的仍是刚发出去的那条 —— 早期实现因此会把已发送内容当草稿存回去
（回车发送→重开→内容还在）。现在库侧在提交时记下原文，`removed()` 发现内容没变就跳过；
1.21.x 原版自己的 `exitReason == DONE` 是同一套判定，1.20.x 没有这个字段。

- [ ] 进服务器，按 `T` 输入一段**未发送**的文字，Esc 关闭，再按 `T` —— 内容还在
- [ ] 保持上一条状态退出服务器，10 分钟内重进同一地址，按 `T` —— 内容还在
- [ ] 换一个客户端 / 启动器用同一账号顶号，进同一地址按 `T` —— 内容还在
- [ ] 把保留时长调到 60 秒，超时后再进，草稿应已丢弃
- [ ] 按 Enter 发送消息后重新打开聊天框，内容应为空（不会重复出现）
- [ ] 在两个不同地址的服务器各留一份草稿，互相不串
- [ ] 关掉「保留聊天框内容」后，不再写入也不再回填
- [ ] 1.20.1 / 1.21.11 / 26.2 各跑一次前三步：注入点按版本各编一份，需分别确认

## 8. 快速召唤 / 删除假人（杂项）

配置位置：**杂项 → 召唤数量**（滑块，1~50，默认 20）、**假人发送间隔**（滑块，1~100 tick，
默认 10）、**快速召唤假人**、**删除假人**。
需要服务端安装 Carpet、`commandPlayer` 未关闭、当前玩家有权限；否则杂项里只显示一行
「未检测到 Carpet 的 /player 命令」，按钮不出现。记录文件在
`用户主目录/.smile_toolb/dummies.json`，按连接地址分组，全部删完自动删除。

- [ ] 进有 Carpet 的服务器，打开配置界面，确认能看到两个滑块与两个按钮
- [ ] **底部不随滚动**：把中间列表滚到底 / 滚到顶，两颗按钮与红字位置一动不动
- [ ] 按钮文字是「召唤假人」（不再带数量）；删除按钮带当前记录数「删除假人（N）」
- [ ] 拖滑条到 7，召唤按钮文字**不变**（数量只在上面的滑块里）
- [ ] **批量进行中两颗按钮一起变灰**：点「召唤假人」后立刻看底部 —— 两个按钮都点不动，
      同一行右侧出现红字「有进行中的批量执行任务」；队列发完（约 10 秒）后自动恢复，红字消失
- [ ] 灰着的按钮悬停应有一行 tooltip；没有特别配原因时是「该选项暂不可用」
- [ ] 按住滑条左右拖动，数值应连续变化；松开后停止
- [ ] **间隔可调并立即生效**：把「假人发送间隔」调到 20 tick，点召唤后观察聊天/日志的
      发包节奏明显变慢（约 1 条/秒）；调到 1 tick 再试，应明显变快（也更容易被踢，只做短测）
- [ ] 点「召唤」，20 个假人在默认 10 tick 下约 10 秒建完，服务器出现 `bot_xxxx`
      （按名字在 Tab 列表里能认出来）；全程不应被「由于滥发消息而被踢出游戏」踢掉
- [ ] 再点一次召唤，应新增一批且名字不与上一批重复
- [ ] 让假人捡/穿几件物品后点「删除」，物品应先掉在地上，随后假人消失
- [ ] 删除后按钮变成「删除假人（0）」，`dummies.json` 应被删除
- [ ] 换客户端顶号重启，进同一地址，按钮应显示上一轮记录的数量（未删完时）
- [ ] **进服自动校准**：召唤几个假人 → 退出服务器 → 用 `/player <name> kill` 在服务端
      手动清掉其中 2 个（或直接重启服务器让假人消失）→ 重新进服，等约 5 秒，
      日志应出现「假人：进服校准，移除 N 个已不存在的记录 [...]」，按钮上的数量随之减少
- [ ] 校准不应误删：刚进服时（5 秒内）不要把还在线的假人清掉；如果服务器没下发完
      Tab 列表，日志应是「在线名单为空……本次跳过校准」，记录保持不变
- [ ] 在没有 Carpet 的服务器（或关掉 `commandPlayer`）打开界面：两颗按钮应灰着点不动，
      悬停提示「该选项暂不可用」
- [ ] 1.20.1 / 1.21.11 / 26.2 各跑一次第 1~3 步：滑块交互由库侧 `ConfigScreen` 实现，
      两代共用一份代码，但宿主转发 `mouseDragged` / `mouseReleased` 的签名不同
- [ ] 1.20.1 / 1.21.11 / 26.2 各验证一次「在线名单」：日志无 `NoSuchMethodError`
      （`GameProfile.getName()` 在 authlib 7 起改成了 `name()`，库已按版本分支）

**边界**：命令按配置的间隔发送（默认 10 tick ≈ 2 条/秒），20 个约 10 秒；删除会翻倍
（每个假人 drop + kill 两条）。召唤中途断线会作废剩余命令，已发出的假人仍留在服务器
（下次进服校准会依据在线名单修正记录）。如果服务器仍判刷屏，把间隔继续调大
（最大 100 tick = 5 秒一条）；调小会更快但有被踢风险（原版 `disconnect.spam` 的令牌桶
约 20 条/秒，客户端与服务端 tick 错开时「每 tick 一条」就已经会把它抽干，这是实测过的）。
假人名字随机，理论上可能与真实玩家重名，概率极低；重名时 Carpet 会拒绝该条，日志里有 warn。

## 版本相关的额外注意

| 版本 | 关注点 |
| --- | --- |
| 1.20.1 | 基准版本，功能应完全正常 |
| 1.20.4 / 1.20.6 | 背景渲染改成了模糊版，界面不应该出现纯黑或残影 |
| 1.21.1 / 1.21.4 | 同上 |
| 1.21.5 / 1.21.8 | 讲述人 `say` 多了音量参数，注意回退到原版时是否也带上音量 |
| 1.21.11 | 输入全走事件对象：配置界面、音色列表、退出按键三处都要重点看；键位设置里应能看到独立的 “Smile's toolb” 分类（注册失败会退回“杂项”） |

## 战斗辅助（新增页签）

地形判定先跑纯 Java 自检，不必进游戏：

```bash
python tools/selftest-terrain/run.py     # 期望 通过 84 / 84（地形 15 + 守卫判据 11 + 换装 9 + 烟花 5 + 收工恢复 4 + 滑翔 9 + 恢复飞行 6 + 提前量 11 + 落点 9 + 竖井 5）
```

落点判定的 9 条纯 Java 用例（玩家 (0.5, 30, 0.5)、竖直 -1.2、下方 10 格是地面、手长 3）：

| 用例 | 实体 | 预期 |
| --- | --- | --- |
| S1 | 正下方 9 格的船 | 手长柱命中 |
| S2 | 正下方 8.5 格的盔甲架 | 手长柱命中 |
| S3 | 落点旁 2.5 格的船（着地高度） | 手长柱命中 |
| S4 | 两倍手长内（5 格）静止的船 | 不命中 |
| S5 | 12 格外的船 | 不命中 |
| S6 | 7 格处、以 0.5 格/tick 开过来的船 | 运动预判命中 |
| S7 | 玩家上方、以 1.5 格/tick 一起下落的盔甲架 | 运动预判命中 |
| S8 | 12 格外横向飞过的船 | 不命中 |
| S9 | 地面以下的船（落点下方 10 格） | 不命中 |

四个基准场景的预期（自检与游戏内应一致）：

| 场景 | 净空 | 落脚宽 | 预期 |
| --- | --- | --- | --- |
| 半空单方块小路 | 64 | 1 | 危险（宽 1 + 两侧无平台） |
| 地下 2 格高 1 格宽小道 | 2 | 1 | 危险（净空 2） |
| 地下 3×3 通道 | 3 | 3 | 危险（净空 3） |
| 地下 4×4 通道 | 4 | 4 | 安全（净空 4 且不露天） |
| 露天草地上起跳 1.5 格 | 64 | 17 | 安全（基准取脚下最近的地面，不是脚部坐标） |
| 单方块小路上方 3 格 | 64 | 1 | 危险（落点仍是 1 格宽） |
| 高空悬停 20 格 | 64 | 8 | 安全（脚下 12 格内无地面，宽度规则不适用） |
| 3×3 通道内起跳 1.5 格 | 3 | 3 | 危险（离落脚面不到一次跳跃，仍算在这一格里） |
| 1×1 竖井（四面是墙、直通天空） | 64 | 1 | **安全**（竖直通道，允许起飞） |
| 1×1 三格深坑（封顶） | 3 | 1 | 危险（井也救不了，净空 3） |
| 两面墙的壁龛 | 64 | 1 | 危险（不成井，仍按宽 1 窄道算） |

`宽 1` 有两种地形，靠**四面有没有墙**分开：半空单方块小路四面临空 → 危险；
1×1 竖井（脚部与头部两格都被满格墙夹住）→ 安全。竖井里张开鞘翅往上飞是正常玩法，
锁了等于把矿洞直井封死。

守卫判据 `inside()` = 危险 + 离落脚面 ≤ 3 格 +（**头顶有天花板** 或 **真的站在面上**）：

| 情形 | 净空 | 离落脚面 | 结论 |
| --- | --- | --- | --- |
| 3×3 通道里站立 / 跑 / 起跳 / 跳到顶 | 3 | 0 ~ 2.9 | 守卫（有天花板，飞不出去） |
| 站在单方块小路上 | 64 | 0 | 守卫 |
| 从单方块小路上**起跳**（想滑翔） | 64 | 1 ~ 3 | 让位（露天窄道，起跳不拦） |
| 单方块小路上方 6 格（真在坠落） | 64 | 6 | 让位（那枚鞘翅可能是唯一的自救手段） |

「站在雪层上」也是安全：雪层 / 地毯 / 台阶的碰撞高度不到一格，脚部坐标落在那格里，
它是地面不是天花板。改前净空被算成 0，雪地上站着每 tick 都在「开始守卫 / 停止守卫」之间抖，
一起跳就把刚穿上的鞘翅扒掉——体感就是「刚起飞就被拽下来」。

游戏内走查（配置界面打开时底栏常驻 `护甲锁：…` 一行，状态翻转时日志也打一行）：

- [ ] 站在**半空单方块小路**上打开配置界面：底栏显示 `守卫中|净空=64 宽=1 露天=是 平台=无 危险=是`
- [ ] 走进**地下 2 格高 1 格宽**小道：`净空=2 宽=1`，守卫中
- [ ] 走进**地下 3×3** 通道：`净空=3 宽=3`，守卫中
- [ ] 走进**地下 4×4** 通道：`净空=4 宽=4 危险=否`，状态回到空闲
- [ ] 在危险地形里让自动换装模组穿鞘翅：胸甲槽不变成鞘翅（日志 `护甲锁：拦下一条自动换鞘翅的操作` 或 `已换回护甲`）
- [ ] 在危险地形里**自己打开背包**手动穿鞘翅：正常穿上（放行手动操作）
- [ ] 在空中把胸甲换成鞘翅后再进危险区：胸甲槽被换成背包里最强的那件护甲
- [ ] **正常滑翔飞行**（不潜行、不按任何键）飞过窄道 / 低空：胸甲槽**不会**被换成护甲，
      底栏也不会变红（状态停在 `空闲`）
- [ ] 露天平地上**原地起跳**：不进守卫（改前会误判成「宽 1 窄道」并拆掉鞘翅）
- [ ] **在地下 3×3 通道里来回跑跳**：全程停在 `守卫中`，底栏 `离地` 在 0~2.5 之间跳动，
      自动换装模组**始终换不上鞘翅**（改前只要脚离地就整体让位，跑两下就冒出鞘翅）
- [ ] 同一通道里走过一道门 / 跳上台阶：状态与 `净空/宽` 当 tick 就跟着变（不再是 5 tick 才刷新）
- [ ] 从 20 格高空自由落体（不张鞘翅）：坠落途中不拦截（离落脚面 > 3 格），
      落地后 1 tick 内恢复守卫
- [ ] **站在雪层 / 地毯 / 台阶上**（脚部坐标落在薄方块里）：底栏是 `空闲`、`净空=64` 左右，
      不再每 tick 抖「守卫中」
- [ ] 在露天窄道（单方块小路 / 桥）上**起跳滑翔**：鞘翅不会被扒（离地超过 0.6 格就让位）
- [ ] 日志里若出现 `护甲锁：胸甲槽的鞘翅被换走了（换成 …）——不是本模组做的`，
      说明有别的模组/服务端动了胸甲槽，把这行连同前后几行贴出来
- [ ] 穿着鞘翅在危险地形里落地站住：胸甲槽保持鞘翅不动（进来时就穿着的是玩家自己的装备），
      只有「守卫期间被模组自动换上的鞘翅」才会被换回护甲
- [ ] 1.21+ 下落中按住潜行、背包有重锤、正下方有生物：日志依次出现
      `重锤猛击：锁定护甲并切出重锤（…，目标 手长柱内 minecraft:zombie（N tick 后落地））`
      → `落地前挥击` → `已落地，解除护甲锁`，且落地瞬间有猛击的范围伤害
- [ ] 同样的操作，目标换成**船**或**盔甲架**：同样触发（日志里的 id 是
      `minecraft:oak_boat` / `minecraft:armor_stand`）
- [ ] 目标在 6 格外横向朝你开过来：日志的 `目标` 是 `运动预判命中 …`，落地前后照样挥击
- [ ] 下方只有**掉落物 / 箭 / 经验球**：不触发（它们不在实体快照里）
- [ ] 目标在你的落点下方、隔着地面（比如坑底）：不触发
- [ ] 背包里同时有**致密 V 的重锤**和一把无附魔重锤（两把都在热键栏，无附魔那把在前面）：
      触发后手里是**致密 V** 的那把（日志里的「切出重锤」之后看手持物即可确认）
- [ ] 更贴近实战的一对：`破甲 IV + 耐久 III + 经验修补` 与 `风爆 I + 致密 V + 火焰附加 II`
      （破甲那把在背包里更靠前）→ 手上必须是**致密 V** 那把（26.2 曾因条件注释被吃掉、
      致密等级全读成 0，永远切到靠前的那把破甲锤）
- [ ] 致密那把只在背包里、热键栏放的是无附魔重锤：依然切到致密那把（会发一次交换包）
- [ ] 高延迟（200ms）服务器上砸一次：配置底栏右侧显示 `延迟 200ms（自动 3，实际提前 3 tick）`，
      日志 `重锤猛击：落地前挥击（距地 … 格，提前 3 tick，延迟 200ms）`，猛击成立
- [ ] 同一台服务器把「按延迟自动提前」关掉：底栏变成 `手动 2`，提前量回到 2 tick（对照用）
- [ ] 手动值调到 5、开着自动：底栏 `实际提前 5 tick`（手动是下限，自动值顶不上去就听手动的）
- [ ] 开着 tweakeroo 的「自动切换烟花火箭」，半空连点攻击（潜行不松）直到落地：
      日志出现 `重锤猛击：手上是 minecraft:firework_rocket，重新切重锤（…）`，
      随后的 `落地前挥击（… 手上 minecraft:mace）` 是锤子；落地后手上换回原武器
- [ ] 滑翔中**平视**（视角不动）按住 shift 约半秒：8 tick 后摘鞘翅下落，日志
      `滑翔中等下砸（… 需保持 8 tick，已保持 …）` → `摘下鞘翅，取消滑翔`
- [ ] 滑翔中**低头盯着怪**按住 shift：3 tick 就动手（不需要等半秒）
- [ ] 滑翔中按住 shift 只按一下就松（点一下加速）：**不触发**（累计随之回退）
- [ ] 滑翔中下方没有实体时按住 shift：只出现 `滑翔中等下砸（…无…）` 一类的读数，鞘翅不动
- [ ] 同一场景把锤子只放在背包里（热键栏留空）：第一次校验返回「下一 tick 再打」，
      下一 tick 生效，仍能砸中（提前量 ≥ 2 tick 时才有这个余量）
- [ ] 落地后手上没有残留烟花：`重锤猛击：把手换回 …` 出现，即使中途丢过锤
- [ ] **原地跳一下**（脚边有怪、按住潜行、背包有重锤）：**不触发**（离地 1.25 格 < 1.5），
      手上不换锤、日志没有 `锁定护甲并切出重锤`；从 2 格以上落下才触发
- [ ] 守卫中（3×3 通道里站着）开着 tweakeroo 的「自动切换烟花火箭」并按空格想飞：
      火箭被换进手里的一瞬间又被换回武器，日志 `护甲锁：守卫中手里的烟花是别的模组换上的，已换回 …`
- [ ] 同样的位置**自己滚轮 / 按数字键**切到烟花格：**不换回**（底栏仍是守卫中，但手不动）
- [ ] 滑翔中（贴地飞）tweakeroo 自动换火箭：不干预，火箭正常留在手上加速
- [ ] 在 3×3 通道 / 有天花板的地方被怪击退到两三格高，立刻换鞘翅起飞：**能穿上**
      （离地 ≥ 1.75 让位），日志不再出现 `护甲锁：拦下一条往胸甲槽放鞘翅的操作`
- [ ] 同一地点站着（离地 0）手动按快捷键换鞘翅：仍被拦（那是窄道保护的本职）
- [ ] 在 2×2 通道里快速跑跳（离地 1.25）：仍拦，鞘翅不会钻进来
- [ ] 滑翔砸中后**立刻被弹飞**（风暴 / 爆炸 / 怪弹射），且下方已无活体：
      日志出现 `把鞘翅穿回去，恢复飞行` → `把烟花火箭换到手上，可以继续飞`，
      按空格能重新起飞
- [ ] 同一场景下方还有活体、且**按着潜行**：不恢复飞行，继续走第二次猛击
- [ ] 同一场景下方还有活体、但**没按潜行**：恢复飞行（有目标也不想砸，就该能飞）
- [ ] 砸完安稳站着：两秒观察窗口内不穿鞘翅、不换手（日志里没有「恢复飞行」）
- [ ] 流程进入 `重锤锁定` 之后把目标打死：3 tick 内 `解除护甲锁（下方没有可砸的目标）`，
      随后按上面那条恢复飞行
- [ ] 背包里没有别的护甲、也没有重锤：点一下潜行应**还在滑翔**（先切锤失败即放弃，
      不会被白拽下来），日志 `重锤切不到手上，放弃这次`
- [ ] 重锤猛击过程中胸甲不会变成鞘翅（LOCKED 期间包被拦）
- [ ] **原地下砸＝取消滑翔**：滑翔飞过一只怪正上方、按住潜行：日志出现
      `重锤猛击：摘下鞘翅，取消滑翔（目标 …）`，鞘翅被摘下、人当场往下掉，
      但**横向速度还在**——按住 WASD 能往左右前后微调落点（不会被钉在一条竖直线上）
- [ ] 同上但背包里只有一件护甲（鞘翅本身）：日志出现 `背包里没有别的护甲，取消滑翔失败`，
      底栏回到 `空闲`，不卡在「重锤锁定」
- [ ] 掉到目标头上时挥击命中，落地不掉血（猛击命中即重置坠落高度）
- [ ] 关掉页内「原地下砸」后：滑翔中怎么按潜行都不触发；普通下落仍会锁甲/切锤/落地前挥击
- [ ] 独立于重锤的「坠落防护」已在 1.0.24 移除（会误触发），上列坠落实测只看重锤流程
- [ ] **1×1 竖井**里站着 / 起跳：底栏是 `空闲`、带 `竖井=是`，鞘翅不会被扒
- [ ] 1.20.1 没有重锤：功能静默不触发，不报错
- [ ] 潜行键关掉 `需要潜行` 后，只要下落就会触发
