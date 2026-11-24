package smile_toolb.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import smile_libbox.api.ui.KeyRef;
import smile_libbox.api.ui.Ui;
import smile_toolb.SmileSToolb;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 模组配置。
 *
 * <p>刻意保持为普通 POJO + Gson 读写，而不是 {@code @ConfigData}：
 * 音色列表是运行时从系统枚举出来的动态数据，用 Cloth Config 的
 * {@code ConfigBuilder} 手写界面比注解式 AutoConfig 更直接。</p>
 *
 * <p>该类的 {@link #get()} 会在 {@code Narrator.getNarrator()} 被调用时
 * （即 {@code Minecraft} 构造期间）访问，因此不能依赖客户端入口点的执行顺序，
 * 只能做“首次访问时惰性读取”。</p>
 */
public final class SmileConfig {

	private static final Logger LOGGER = SmileSToolb.LOGGER;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE =
			FabricLoader.getInstance().getConfigDir().resolve("smile_toolb").resolve("tts.json");

	/**
	 * 退出按键的出厂默认值：鼠标侧后键（后退键）。
	 *
	 * <p>{@code key.mouse.N} 里的 N 是「第 N 个鼠标键」而不是 GLFW 的 0 基编号：
	 * GLFW 的 button 3（Windows 上的后退键 / XBUTTON1）对应 {@code key.mouse.4}，
	 * button 4（前进键 / XBUTTON2）对应 {@code key.mouse.5}。只绑侧后键，避免误触
	 * 前侧键；需要别的键（包括键盘键）时在界面里自行添加。</p>
	 */
	private static final List<String> DEFAULT_EXIT_KEYS = List.of("key.mouse.4");

	private static SmileConfig instance;

	/** 总开关：关闭时完全退回原版讲述人。 */
	public boolean enabled = true;

	/** 选中的系统音色 Id（WinRT 注册表路径形式），空串表示自动选择。 */
	public String voiceId = "";

	/** 语速百分比，100 为正常速度。 */
	public int rate = 100;

	/** 音量百分比。 */
	public int volume = 100;

	/** 单条消息最大朗读字符数，0 表示不限制。 */
	public int maxChars = 300;

	/** 是否在音色列表中显示 Edge 在线音色（需要联网）。 */
	public boolean includeOnlineVoices = true;

	/** 是否在进入游戏时自动把讲述人模式切换为“全部”。 */
	public boolean autoEnableNarrator = false;

	/**
	 * 使用旧版经验条动作：1.21.9 起原版把快捷栏上方那一条让给了「玩家追踪条」
	 * （locator bar），只要附近有带路径点的队友，经验条就会被顶掉。开启后恢复正常。
	 *
	 * <p>低版本（&lt; 1.21.9）没有追踪条，这个字段不生效，配置界面也不会显示。</p>
	 */
	public boolean forceExperienceBar = true;

	/**
	 * 高级状态条控制：开启后这一条按场景切换——默认经验条，按住玩家列表键（默认 Tab）
	 * 显示定位条，骑乘可跳跃坐骑时按住跳跃键显示蓄力条（松开后保留 2.5 秒）。
	 *
	 * <p>只在 {@link #forceExperienceBar} 开启时有意义。</p>
	 */
	public boolean advancedBarControl = false;

	/**
	 * 保留聊天框内容：关闭聊天框后再打开、退出服务器后短时间内重进、换客户端顶号重启，
	 * 都能回填上一次未发送的输入。草稿按「连接地址」隔离并落盘。
	 */
	public boolean keepChatDraft = true;

	/** 聊天草稿保留时长（秒），从最后一次关闭聊天框算起；超时自动丢弃。 */
	public int chatDraftTtlSeconds = 600;

	/**
	 * 快速召唤假人的数量：一次点击创建多少个随机取名的假人（默认 20，用于打试炼）。
	 *
	 * <p>依赖服务端提供 Carpet 的 {@code /player} 命令；命令不存在时本项在配置界面
	 * 只显示一行说明，不出现按钮。</p>
	 */
	public int dummyCount = 20;

	/**
	 * 假人命令的发送间隔（tick）：两条 {@code /player} 命令之间隔多少 tick。
	 *
	 * <p>这是被服务端反刷屏踢出后的可调项。1 tick ≈ 20 条/秒，正贴着原版
	 * {@code TickThrottler} 的上限，客户端与服务端 tick 稍一错开就会瞬间抽干令牌桶；
	 * 默认 10 tick（约 2 条/秒）留出 10 倍余量。服务端装了更严的聊天/命令反刷屏时
	 * 继续往上调（最大 100 tick，5 秒一条）。</p>
	 */
	public int dummyIntervalTicks = 10;

	/** 退出按键映射总开关：启用后，在任意界面按下列表中的按键等同于按 Esc。 */
	public boolean exitKeyEnabled = true;

	// ---------------------------------------------------------------- 战斗辅助

	/**
	 * 鞘翅安全守卫：在「不适合穿鞘翅」的位置，禁止盔甲栏被自动换成鞘翅。
	 *
	 * <p>判定见 {@code smile_toolb.client.combat.Terrain}：净空 ≤ 3 格，
	 * 或露天落脚面宽度 ≤ 3 格且两侧下方 5 格内没有 5×5 的安全平台。</p>
	 */
	public boolean elytraGuardEnabled = true;

	/** 重锤猛击：下落中潜行、背包有重锤且附近有实体时，自动锁甲、切锤、落地前挥击。 */
	public boolean maceSlamEnabled = true;

	/** 重锤猛击是否要求按住潜行；关掉后只要在下落就会触发。 */
	public boolean maceSlamRequireSneak = true;

	/**
	 * 原地下砸：滑翔中触发猛击时，把胸甲槽的鞘翅摘掉。
	 *
	 * <p>原版「身上没有可用的鞘翅就飞不了」，所以摘掉鞘翅就等于取消滑翔，
	 * 人从当前位置往下掉——重锤猛击命中后不结算坠落伤害，正好用来砸下方的人。
	 * <b>只取消滑翔，不动速度</b>：横向动量、WASD 全保留，落点不对可以自己微调。</p>
	 *
	 * <p>滑翔里必须低头（俯视 ≥ 60°）盯着正下方的目标、连续 3 tick 才动手：
	 * 潜行键在滑翔时是原版的「低头加速下降」，按住它飞过生物是日常操作，
	 * 只看按键会把人从半路拽下来。关掉之后滑翔中完全不干预。</p>
	 */
	public boolean maceSlamDive = true;

	/**
	 * 重锤猛击的「手长」（格），默认 3（原版交互距离）。
	 *
	 * <p>判定用两段：手长柱（脚下到手长范围）里有没有实体，以及 2 倍手长内的实体
	 * 按当前速度外推后会不会撞进落点。实际挥击仍受原版交互距离限制。</p>
	 */
	public int maceSlamReach = 3;

	/** 重锤猛击的提前量（tick）：预计还有几 tick 落地时挥击。 */
	public int maceSlamLeadTicks = 2;

	/**
	 * 提前量按延迟自动计算（高延迟玩家用）。
	 *
	 * <p>攻击包在路上要飞半程，延迟 200ms 就是 2 tick：还按固定值在落地前 2 tick 发，
	 * 服务端处理时人已经落地，猛击不成立。开启后取
	 * {@code max(maceSlamLeadTicks, ceil(ping / 100) + 1)}——手动值当下限，
	 * 自动值只会把它顶高。换算规则见 {@code combat/LeadEstimate}。</p>
	 *
	 * <p>关掉就严格按 {@link #maceSlamLeadTicks} 走，适合单人本地或延迟稳定且想自己
	 * 精调的场合。</p>
	 */
	public boolean maceSlamLeadAuto = true;

	/**
	 * 无目标时恢复飞行：猛击收工后发现人还在空中、下方又没有可以砸的实体，
	 * 就把鞘翅穿回去、把手换成烟花火箭。
	 *
	 * <p>砸完立刻被弹飞（风暴、爆炸、击退、怪物弹射）是常见情形：那一刻手上是剑、
	 * 胸甲槽是护甲，飞不起来只能干看着自己往下掉。打开这个开关，落地后这一小段时间里
	 * 只要满足「在空中 + 空中有 1.75 格以上 + 下方没东西可砸」，就把飞行装备装回去。</p>
	 */
	public boolean maceSlamResumeFlight = true;

	/**
	 * 退出按键列表，元素是键名（{@code key.mouse.4}、{@code key.keyboard.f13} 之类），
	 * 键盘与鼠标键可以混用。键名解析由库按命名空间完成。
	 */
	public List<String> exitKeys = new ArrayList<>(DEFAULT_EXIT_KEYS);

	/** 旧版配置里的 GLFW 键码列表。只用于迁移，迁移后置空（Gson 不写 null 字段）。 */
	private List<Integer> exitKeyCodes;

	private SmileConfig() {
	}

	public static synchronized SmileConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	private static SmileConfig load() {
		if (Files.isRegularFile(FILE)) {
			try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
				SmileConfig loaded = GSON.fromJson(reader, SmileConfig.class);
				if (loaded != null) {
					return loaded.sanitized();
				}
			} catch (IOException | JsonSyntaxException e) {
				LOGGER.warn("读取配置失败，将使用默认值: {}", e.toString());
			}
		}
		return new SmileConfig();
	}

	public static synchronized void save() {
		SmileConfig config = get();
		try {
			Files.createDirectories(FILE.getParent());
			try (Writer writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
				GSON.toJson(config, writer);
			}
		} catch (IOException e) {
			LOGGER.warn("保存配置失败: {}", e.toString());
		}
	}

	private SmileConfig sanitized() {
		this.rate = clamp(this.rate, 50, 200);
		this.volume = clamp(this.volume, 0, 100);
		this.maxChars = clamp(this.maxChars, 0, 1000);
		this.chatDraftTtlSeconds = clamp(this.chatDraftTtlSeconds, 60, 3600);
		this.dummyCount = clamp(this.dummyCount, 1, 50);
		this.dummyIntervalTicks = clamp(this.dummyIntervalTicks, 1, 100);
		this.maceSlamReach = clamp(this.maceSlamReach, 2, 6);
		this.maceSlamLeadTicks = clamp(this.maceSlamLeadTicks, 1, 8);
		if (this.voiceId == null) {
			this.voiceId = "";
		}
		// Gson 用无参构造器建实例，旧文件里没有的字段会保留字段初始值；
		// 这里再兜一次 null，避免配置被手改成 "exitKeys": null 时炸在渲染线程。
		if (this.exitKeys == null) {
			this.exitKeys = new ArrayList<>(DEFAULT_EXIT_KEYS);
		}
		migrateLegacyKeyCodes();
		this.exitKeys = cleanKeys(this.exitKeys);
		return this;
	}

	/** 把旧版 {@code exitKeyCodes}（GLFW 键码）转成键名，用户已配置过的键优先于默认值。 */
	private void migrateLegacyKeyCodes() {
		if (this.exitKeyCodes == null) {
			return;
		}
		List<String> migrated = new ArrayList<>();
		for (Integer code : this.exitKeyCodes) {
			if (code == null || code <= 0) {
				continue;
			}
			KeyRef key = Ui.keyFromCode(0, code);
			String name = key == null ? null : key.name;
			if (name != null && !migrated.contains(name)) {
				migrated.add(name);
			}
		}
		if (!migrated.isEmpty()) {
			this.exitKeys = migrated;
		}
		this.exitKeyCodes = null;
		LOGGER.info("已把旧版退出键码迁移为键名：{}", this.exitKeys);
	}

	/** 去掉无法识别的键名与重复项：手改配置文件写错名字时直接丢弃，而不是每帧抛异常。 */
	private static List<String> cleanKeys(List<String> source) {
		List<String> cleaned = new ArrayList<>();
		for (String name : source) {
			KeyRef key = parseKey(name);
			if (key != null && !cleaned.contains(key.name)) {
				cleaned.add(key.name);
			}
		}
		return cleaned;
	}

	/** 解析配置里的键名；无法识别时返回 {@code null}。实际解析在库里按命名空间完成。 */
	public static KeyRef parseKey(String name) {
		KeyRef key = Ui.keyFromName(name);
		if (key == null) {
			LOGGER.debug("忽略无法识别的退出按键 {}", name);
		}
		return key;
	}

	/** 退出按键列表（永不返回 null，供渲染线程直接遍历）。 */
	public List<String> exitKeyNames() {
		return this.exitKeys == null ? List.of() : this.exitKeys;
	}

	/** 判断某个键（键盘或鼠标）是否被配置为退出键。 */
	public boolean isExitKey(KeyRef key) {
		return this.exitKeyEnabled && key != null && this.exitKeyNames().contains(key.name);
	}

	/** 键盘按键是否命中退出键映射。 */
	public boolean isExitKey(int keyCode) {
		return this.isExitKey(Ui.keyFromCode(0, keyCode));
	}

	/** 鼠标按键是否命中退出键映射。 */
	public boolean isExitMouseKey(int button) {
		return this.isExitKey(Ui.keyFromCode(1, button));
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
