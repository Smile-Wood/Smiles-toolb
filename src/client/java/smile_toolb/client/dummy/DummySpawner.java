package smile_toolb.client.dummy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import smile_libbox.api.ui.Ui;
import smile_toolb.SmileSToolb;
import smile_toolb.client.config.SmileConfig;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 快速召唤 / 删除 Carpet 假人，用于打试炼（trial chambers）。
 *
 * <p>全部通过服务端的 Carpet {@code /player} 命令实现，命令走 {@link Ui#sendCommand(String)}
 * <b>直接发包</b>，不打开聊天框、不写聊天记录。命令由服务端按权限下发，因此
 * {@link Ui#hasCommand(String)} 为 false 时（没装 Carpet、关了假人命令、没有权限）
 * 整个功能视为不可用，配置界面只显示一行说明。</p>
 *
 * <p><b>名单以游戏内为准（实时同步）</b>：真正「现在有哪些假人」只信服务器的在线名单
 * （tab 列表），每 {@value #LIVE_REFRESH_TICKS} tick 对一次账，取其中名字以
 * {@value #PREFIX} 开头的玩家 —— 本客户端召唤的、别的客户端召唤的、上一轮留下的都算在内。
 * 界面上的数量、「删除假人」的目标都来自这份实时名单，不读磁盘：假人被打死、被手动清掉、
 * 被别人补进来，数字半秒内跟着变，不用退出重进、也不用重开界面。</p>
 *
 * <p><b>为什么还落一份盘</b>：Carpet 不提供「列出我的假人」这种查询，删除只能按名字逐个
 * {@code /player <name> kill}；名单还没同步完（刚进服、刚换维度）时也要有个线索。
 * 所以对账结果按连接地址镜像到 {@code ~/.smile_toolb/dummies.json} 里该地址那一栏 ——
 * 放在用户主目录而不是 {@code gameDir}，换客户端顶号重启后照样能删；不同服务器互不干扰。
 * 名单为空（没进服 / 还在下发）时只更界面数字、不碰文件，避免把记录清成空。</p>
 *
 * <p><b>为什么排队</b>：一次 20~50 条命令在短时间内全发出去会直接被服务端判成刷屏
 * （原版 {@code disconnect.spam} / 服务端的 {@code TickThrottler}：约 20 条/秒的令牌桶，
 * 客户端 tick 与服务端 tick 稍微错开就有两条命令落在同一 tick 里，瞬间把桶抽干）。
 * 间隔由配置界面的「假人发送间隔」决定，默认 {@value #DEFAULT_INTERVAL_TICKS} tick
 * （约 2 条/秒）；装更严的反刷屏插件可以继续调大（最大 {@value #MAX_INTERVAL_TICKS}）。</p>
 *
 * <p>本类只依赖 {@link Ui} 的纯 Java 门面，常量池里没有任何 {@code net.minecraft}
 * 描述符，因此仍是 toolb 单 jar 的一部分。</p>
 */
public final class DummySpawner {

	/** Carpet 的命令根节点。 */
	private static final String COMMAND_ROOT = "player";

	private static final Logger LOGGER = SmileSToolb.LOGGER;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = resolveFile();
	private static final Path TMP = FILE.resolveSibling(FILE.getFileName() + ".tmp");

	/** 假人名字前缀：方便在玩家列表里一眼认出，也不容易和真实玩家撞名。 */
	private static final String PREFIX = "bot_";
	private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
	private static final int SUFFIX_LEN = 4;
	private static final int MAX_PER_ACTION = 50;
	private static final String DEFAULT_PORT_SUFFIX = ":25565";

	/**
	 * 命令间隔的默认值（tick），仅当配置里读不出来时兜底。
	 * 真正生效的是 {@link SmileConfig#dummyIntervalTicks}（界面可调，1~100）。
	 */
	private static final int DEFAULT_INTERVAL_TICKS = 10;
	private static final int MAX_INTERVAL_TICKS = 100;

	/**
	 * 在线名单的对账间隔（tick）：0.5 秒一次，界面上的数字基本跟着游戏走。
	 *
	 * <p>再快没有意义（tab 列表本身按秒级下发），再慢「假人死了数字不动」就又回来了。</p>
	 */
	private static final int LIVE_REFRESH_TICKS = 10;

	/**
	 * 进服后等多久才允许拿在线名单去修剪记录（tick）。连接刚建立时 tab 列表可能还没
	 * 下发完，太早比对会把还在线的假人误判成「已不存在」。这段时间里界面数字照常
	 * 实时更新，只是不落盘。
	 */
	private static final int SYNC_DELAY_TICKS = 100;

	/**
	 * 一批命令刚发完后的静默期（tick）。最后一条 {@code spawn} 要等服务端处理、再经
	 * tab 列表下发才看得见；这段时间只更新界面数字，不去修剪记录，免得把刚召唤好、
	 * 还没在名单里出现的名字删掉。
	 */
	private static final int QUIET_TICKS = 20;

	private static final Random RANDOM = new Random();
	private static final Deque<Task> QUEUE = new ArrayDeque<>();
	/** 距离下一条命令还剩几个 tick。 */
	private static int cooldown;
	/** 进服后还剩几个 tick 才开始允许修剪记录；{@code <=0} 表示已经可以了。 */
	private static int syncCountdown;
	/** 距离下一次对账还剩几个 tick。 */
	private static int liveAge;
	/** 刚发完一批命令后的静默期（tick）：等最后一条召唤在 tab 列表里现身再落盘。 */
	private static int quietTicks;
	/**
	 * 最近一次对账得到的「在线假人」：小写名 → 原始名。
	 *
	 * <p>界面每帧都问一次数量，删除也按这份名单发命令，所以只在这里缓存，
	 * 不能每次去读文件、更不能每次去翻 tab 列表。</p>
	 */
	private static final Map<String, String> LIVE = new LinkedHashMap<>();
	/** {@link #LIVE} 属于哪个连接地址；换服 / 退出后与当前地址对不上就退回文件记录。 */
	private static String liveKey = "";
	/** {@link #LIVE} 是否已经对过账（false 时还是文件里的上一轮数字）。 */
	private static boolean liveSynced;
	/** 本批次发送成功的召唤名，队列空闲时统一落盘。 */
	private static final List<String> SPAWNED = new ArrayList<>();
	/** 本批次发送成功的删除名，队列空闲时统一从记录里剔除。 */
	private static final Set<String> REMOVED = new LinkedHashSet<>();
	private static String lastError = "";

	private DummySpawner() {
	}

	private static Path resolveFile() {
		String home = System.getProperty("user.home");
		Path root = (home == null || home.isBlank())
				? FabricLoader.getInstance().getGameDir()
				: Paths.get(home);
		return root.resolve(".smile_toolb").resolve("dummies.json");
	}

	/** client 入口调用一次：打印一次状态，按需跑纯 Java 自检。 */
	public static void register() {
		int interval = intervalTicks();
		LOGGER.info("假人：命令=/{}/{}，间隔={} tick（{} 条/秒，界面可调 1~{}），记录文件={}",
				COMMAND_ROOT, available() ? "可用" : "暂不可用（启动阶段通常还没进服）",
				interval, String.format(Locale.ROOT, "%.1f", 20.0 / interval),
				MAX_INTERVAL_TICKS, FILE);
		// 进服先按实时名单对一次账：上一次留下的记录可能已经过时（服务器重启、
		// 假人被手动清掉），但也要等 tab 列表下发改完才敢拿它修剪记录。
		Ui.onJoin(DummySpawner::onJoin);
		if (Boolean.getBoolean("smile_toolb.selftest.dummy")) {
			runSelfTest();
		}
	}

	/**
	 * 当前生效的命令间隔（tick）。
	 *
	 * <p>优先级：JVM 参数 {@code -Dsmile_toolb.dummyIntervalTicks=N}（验证脚本用）
	 * &gt; 配置界面的 {@link SmileConfig#dummyIntervalTicks} &gt; {@value #DEFAULT_INTERVAL_TICKS}。</p>
	 */
	private static int intervalTicks() {
		Integer override = Integer.getInteger("smile_toolb.dummyIntervalTicks");
		int value = override != null ? override : SmileConfig.get().dummyIntervalTicks;
		if (value <= 0) {
			value = DEFAULT_INTERVAL_TICKS;
		}
		return Math.max(1, Math.min(MAX_INTERVAL_TICKS, value));
	}

	/** 进服：立刻刷新一次界面数字，但推迟 {@value #SYNC_DELAY_TICKS} tick 才允许修剪记录。 */
	private static synchronized void onJoin() {
		syncCountdown = SYNC_DELAY_TICKS;
		liveAge = LIVE_REFRESH_TICKS;
		liveSynced = false;
	}

	/** 每 tick 调一次：节流地对一次账（{@value #LIVE_REFRESH_TICKS} tick 一次）。 */
	private static synchronized void tickLive() {
		if (syncCountdown > 0) {
			syncCountdown--;
		}
		if (quietTicks > 0) {
			quietTicks--;
		}
		if (++liveAge < LIVE_REFRESH_TICKS) {
			return;
		}
		liveAge = 0;
		syncLive();
	}

	/**
	 * 用服务器的在线名单刷新实时假人列表，顺便把本地记录镜像成同一份。
	 *
	 * <p><b>怎么认假人</b>：只看名字前缀 {@value #PREFIX}。Carpet 的假人在服务端就是一个
	 * 普通玩家，没有「谁召唤的」这种标记，前缀是唯一可靠的线索 —— 这样别的客户端召唤的、
	 * 上一轮没删干净的假人也能算进数量、也能被删掉，不会因为换了客户端就变成幽灵。</p>
	 *
	 * <p><b>什么时候不动记录</b>：名单为空、或者里面没有玩家自己（刚连接、换维度时
	 * tab 列表会短暂清空），只更新界面数字不落盘；批量任务进行中也不落盘 ——
	 * 那时队列里的召唤还没全部生效，落盘会和 {@link #flush()} 抢着写。</p>
	 */
	private static void syncLive() {
		List<String> online;
		try {
			online = Ui.onlinePlayers();
		} catch (Throwable t) {
			LOGGER.debug("假人：取在线名单失败，跳过本次同步 {}", t.toString());
			return;
		}
		String key = key(Ui.serverAddress());
		Map<String, String> bots = new LinkedHashMap<>();
		boolean selfOnline = false;
		String self = playerKey();
		for (String name : online) {
			if (name == null || name.isBlank()) {
				continue;
			}
			String lower = name.toLowerCase(Locale.ROOT);
			if (!self.isEmpty() && self.equals(lower)) {
				selfOnline = true;
			}
			if (lower.startsWith(PREFIX)) {
				bots.put(lower, name);
			}
		}
		boolean firstSync = !liveSynced || !liveKey.equals(key);
		int before = LIVE.size();
		LIVE.clear();
		LIVE.putAll(bots);
		liveKey = key;
		liveSynced = true;
		if (firstSync || before != LIVE.size()) {
			LOGGER.info("假人：实时名单 {} 个（在线共 {} 人，地址={}）",
					LIVE.size(), online.size(), key);
		}
		// 名单不可信：还没下发完 / 正在换维度 / 已经退出游戏
		if (online.isEmpty() || !selfOnline) {
			return;
		}
		// 刚进服的前几秒只更数字，别急着拿「还没下发完的名单」去修剪记录
		if (syncCountdown > 0 || quietTicks > 0 || !QUEUE.isEmpty()) {
			return;
		}
		List<String> next = new ArrayList<>(LIVE.values());
		next.sort(String.CASE_INSENSITIVE_ORDER);
		if (!sameNames(load(key), next)) {
			save(key, next);
			LOGGER.info("假人：记录已与游戏内同步（{} 个，地址={}）", next.size(), key);
		}
	}

	/** 两个名单是不是同一批名字（不区分大小写、不看顺序）。 */
	private static boolean sameNames(List<String> a, List<String> b) {
		Set<String> left = new HashSet<>();
		for (String name : a) {
			left.add(name.toLowerCase(Locale.ROOT));
		}
		Set<String> right = new HashSet<>();
		for (String name : b) {
			right.add(name.toLowerCase(Locale.ROOT));
		}
		return left.equals(right);
	}

	/** 玩家自己的名字（小写），取不到时是空串。 */
	private static String playerKey() {
		try {
			String name = Ui.playerName();
			return name == null ? "" : name.toLowerCase(Locale.ROOT);
		} catch (Throwable t) {
			return "";
		}
	}

	/**
	 * 要处理（去重 / 删除）的假人名单。
	 *
	 * <p>优先用实时名单；还没对上过账（刚开界面、刚换服）才退回本地记录 —— 那条路径
	 * 只在连接的极早期走，等半秒就有真数据了。</p>
	 */
	private static List<String> targets() {
		String key = key(Ui.serverAddress());
		if (liveSynced && liveKey.equals(key)) {
			List<String> names = new ArrayList<>(LIVE.values());
			names.sort(String.CASE_INSENSITIVE_ORDER);
			return names;
		}
		return load(key);
	}

	/** 服务端是否下发了 Carpet 的 {@code /player} 命令。 */
	public static boolean available() {
		// 无人值守验证用：强制走「可用」分支，把滑块与两个按钮真正渲染一遍；
		// 正常游玩时没有任何影响（该属性只有验证脚本会加）。
		return Boolean.getBoolean("smile_toolb.selftest.dummy_force") || Ui.hasCommand(COMMAND_ROOT);
	}

	/**
	 * 当前在线的假人数量（配置界面状态行与删除按钮用）。
	 *
	 * <p>读的是每 {@value #LIVE_REFRESH_TICKS} tick 更新一次的实时名单，不碰磁盘 ——
	 * 界面每帧都会问一次，读文件既卡帧又只会给出上一轮的旧数字。</p>
	 */
	public static synchronized int count() {
		String key = key(Ui.serverAddress());
		if (liveSynced && liveKey.equals(key)) {
			return LIVE.size();
		}
		try {
			return load(key).size();
		} catch (Throwable t) {
			return 0;
		}
	}

	/** 最近一次失败原因，空串表示没有错误。 */
	public static String lastError() {
		return lastError;
	}

	/**
	 * 是否还有一批命令没发完。
	 *
	 * <p>配置界面拿它做两件事：召唤 / 删除两个按钮一起置灰（避免在队列里再叠一堆命令，
	 * 也避免「刚点召唤又点删除」把两批命令交错），以及在底部用红字提示「有进行中的
	 * 批量执行任务」。队列发空、{@link #flush()} 落盘后立刻恢复可用。</p>
	 */
	public static synchronized boolean busy() {
		return !QUEUE.isEmpty();
	}

	/** 队列里还剩多少条命令（配置界面红字状态用，能看出「跳过了多少」）。 */
	public static synchronized int queued() {
		return QUEUE.size();
	}

	/** 召唤 {@code count} 个随机取名的假人；数量会被夹到 1~50。 */
	public static synchronized void spawn(int count) {
		if (!available()) {
			lastError = "no-command";
			LOGGER.info("假人：服务端没有 /player 命令，忽略这次召唤");
			return;
		}
		int amount = Math.max(1, Math.min(MAX_PER_ACTION, count));
		Set<String> used = new LinkedHashSet<>(targets());
		for (Task task : QUEUE) {
			used.add(task.name);
		}
		List<String> created = new ArrayList<>();
		for (int i = 0; i < amount; i++) {
			String name = randomName();
			while (!used.add(name)) {
				name = randomName();
			}
			created.add(name);
			QUEUE.add(new Task(Kind.SPAWN, name));
		}
		lastError = "";
		LOGGER.info("假人：排队召唤 {} 个（地址={}，队列={}，预计 {} 秒发完）",
				created.size(), key(Ui.serverAddress()), QUEUE.size(),
				QUEUE.size() * intervalTicks() / 20);
	}

	/**
	 * 删除当前在线的假人：先让每个假人 {@code /player <name> drop} 把背包里的
	 * 东西全部丢出来，再 {@code /player <name> kill} 移除，避免打试炼捡的装备一起消失。
	 *
	 * <p>名单来自游戏内的实时同步（见 {@link #syncLive()}），所以「按钮上的数字」
	 * 和「实际会发的命令条数」永远是同一份。</p>
	 */
	public static synchronized void removeAll() {
		if (!available()) {
			lastError = "no-command";
			LOGGER.info("假人：服务端没有 /player 命令，忽略这次删除");
			return;
		}
		List<String> names = targets();
		if (names.isEmpty()) {
			lastError = "";
			LOGGER.info("假人：当前没有在线的假人，无需删除");
			return;
		}
		for (String name : names) {
			QUEUE.add(new Task(Kind.DROP, name));
			QUEUE.add(new Task(Kind.KILL, name));
		}
		lastError = "";
		LOGGER.info("假人：排队删除 {} 个（先丢物品再移除，队列={}）", names.size(), QUEUE.size());
	}

	/**
	 * 把队列里「人已经不在了」的删除任务一次剔干净，免得按间隔白等。
	 *
	 * <p>删除是 {@code drop} → {@code kill} 两条命令逐个发的，20 个假人就是 40 条、
	 * 按默认间隔要 20 秒。这中间假人可能被怪打死、被别的客户端清掉、被服主手动踢掉；
	 * 再对它发命令只会拿到「玩家不在线」的报错，还要白等一个间隔。所以每条命令发出
	 * 之前先取一次实时在线名单，名字不在的一次性剔掉 —— 全没了就当场收工，不拖到最后。</p>
	 *
	 * <p>召唤任务不参与：那些名字本来就还没上线。名单不可信时（空的、里面没有玩家自己、
	 * 或者刚进服还没下发完）一律不动队列 —— 宁可按原样发，也不要拿一份残缺名单把还在线
	 * 的名字当成「已经没了」剔掉。</p>
	 */
	private static void pruneGone() {
		boolean hasDeletion = false;
		for (Task task : QUEUE) {
			if (task.kind != Kind.SPAWN) {
				hasDeletion = true;
				break;
			}
		}
		if (!hasDeletion) {
			return;
		}
		Set<String> online = new HashSet<>();
		boolean selfOnline = false;
		String self = playerKey();
		try {
			for (String name : Ui.onlinePlayers()) {
				if (name == null) {
					continue;
				}
				String lower = name.toLowerCase(Locale.ROOT);
				online.add(lower);
				if (!self.isEmpty() && self.equals(lower)) {
					selfOnline = true;
				}
			}
		} catch (Throwable t) {
			return;
		}
		// 名单不可信（还没下发完 / 已经退出 / 刚进服的头几秒）：宁可照原样发，
		// 也不要凭一份残缺名单把还在线的名字当成「已经没了」剔掉
		if (online.isEmpty() || !selfOnline || syncCountdown > 0) {
			return;
		}
		Set<String> gone = new LinkedHashSet<>();
		int before = QUEUE.size();
		Iterator<Task> it = QUEUE.iterator();
		while (it.hasNext()) {
			Task task = it.next();
			if (task.kind == Kind.SPAWN) {
				continue;
			}
			if (online.contains(task.name.toLowerCase(Locale.ROOT))) {
				continue;
			}
			it.remove();
			gone.add(task.name);
			// 人都没了，记录里也不用再留着这个名字
			REMOVED.add(task.name);
		}
		if (!gone.isEmpty()) {
			LOGGER.info("假人：{} 个已经不在服务器里，跳过 {} 条命令（队列 {} → {}）{}",
					gone.size(), before - QUEUE.size(), before, QUEUE.size(), gone);
		}
	}

	/** 按间隔发一条命令；由 client 入口注册到 {@link Ui#onTick(Runnable)}。 */
	public static synchronized void tick() {
		// 实时对账：与命令队列互不影响，先处理
		tickLive();
		if (QUEUE.isEmpty()) {
			cooldown = 0;
			flush();
			return;
		}
		if (cooldown > 0) {
			cooldown--;
			return;
		}
		// 发下一条之前先看人还在不在：删除批次里已经消失的，直接作废，别按间隔白等
		pruneGone();
		if (QUEUE.isEmpty()) {
			cooldown = 0;
			flush();
			return;
		}
		Task task = QUEUE.poll();
		cooldown = intervalTicks() - 1;
		String command = COMMAND_ROOT + " " + task.name + " " + task.kind.action;
		String error = Ui.sendCommand(command);
		if (error.isEmpty()) {
			lastError = "";
			switch (task.kind) {
				case SPAWN -> SPAWNED.add(task.name);
				case KILL -> REMOVED.add(task.name);
				default -> {
					// DROP 只是删除前的准备动作，不改变记录
				}
			}
		} else {
			lastError = error;
			LOGGER.warn("假人：{} {} 失败（{}），已跳过", task.kind.action, task.name, error);
			if ("offline".equals(error)) {
				// 已经不在服务器里了，剩下的命令不可能成功，直接作废本批次
				QUEUE.clear();
				SPAWNED.clear();
				REMOVED.clear();
				return;
			}
		}
		if (QUEUE.isEmpty()) {
			flush();
		}
	}

	/** 队列空闲时把本批次的增删一次性写盘，避免每个假人写一次文件。 */
	private static void flush() {
		if (SPAWNED.isEmpty() && REMOVED.isEmpty()) {
			return;
		}
		quietTicks = QUIET_TICKS;
		String key = key(Ui.serverAddress());
		try {
			Data data = read();
			ServerRecord record = recordOf(data, key);
			Set<String> names = new LinkedHashSet<>(record.names);
			names.removeAll(REMOVED);
			names.addAll(SPAWNED);
			record.names = new ArrayList<>(names);
			record.updated = System.currentTimeMillis();
			write(data);
			LOGGER.info("假人：记录已更新（新增 {}，移除 {}，现有 {}）",
					SPAWNED.size(), REMOVED.size(), record.names.size());
		} catch (Throwable t) {
			LOGGER.warn("假人：写记录失败 {}", t.toString());
		} finally {
			SPAWNED.clear();
			REMOVED.clear();
		}
	}

	// ---------------------------------------------------------------- 命名 / 记录

	/** {@code bot_} + 4 位小写字母数字：总长 8，符合 MC 用户名 3~16 的限制。 */
	private static String randomName() {
		StringBuilder sb = new StringBuilder(PREFIX);
		for (int i = 0; i < SUFFIX_LEN; i++) {
			sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
		}
		return sb.toString();
	}

	/** 地址归一化，与聊天草稿保持一致：大小写不敏感，默认端口视为同一台。 */
	private static String key(String address) {
		String value = address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
		if (value.endsWith(DEFAULT_PORT_SUFFIX)) {
			value = value.substring(0, value.length() - DEFAULT_PORT_SUFFIX.length());
		}
		return value.isEmpty() ? "local" : value;
	}

	private static List<String> load(String key) {
		return new ArrayList<>(recordOf(read(), key).names);
	}

	private static void save(String key, List<String> names) {
		try {
			Data data = read();
			ServerRecord record = recordOf(data, key);
			record.names = new ArrayList<>(names);
			record.updated = System.currentTimeMillis();
			write(data);
		} catch (Throwable t) {
			LOGGER.debug("假人：保存记录失败 {}", t.toString());
		}
	}

	private static ServerRecord recordOf(Data data, String key) {
		return data.servers.computeIfAbsent(key, ignored -> new ServerRecord());
	}

	private static Data read() {
		if (!Files.isRegularFile(FILE)) {
			return new Data();
		}
		try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
			Data data = GSON.fromJson(reader, Data.class);
			if (data == null) {
				return new Data();
			}
			if (data.servers == null) {
				data.servers = new HashMap<>();
			}
			return data;
		} catch (IOException | JsonSyntaxException e) {
			LOGGER.debug("假人记录文件不可读，按空记录处理: {}", e.toString());
			return new Data();
		}
	}

	private static void write(Data data) throws IOException {
		boolean empty = data.servers.values().stream().allMatch(r -> r.names == null || r.names.isEmpty());
		if (empty) {
			// 全部删完了就不留空文件，避免主目录堆垃圾
			try {
				Files.deleteIfExists(FILE);
			} catch (IOException ignored) {
				// 另一个客户端可能正持有文件
			}
			try {
				Files.deleteIfExists(TMP);
			} catch (IOException ignored) {
				// 同上
			}
			return;
		}
		Files.createDirectories(FILE.getParent());
		try (Writer writer = Files.newBufferedWriter(TMP, StandardCharsets.UTF_8)) {
			GSON.toJson(data, writer);
		}
		try {
			Files.move(TMP, FILE, StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(TMP, FILE, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** 无人值守验证用：确认命名规则与「按地址隔离、写后能读、清空即删」这条链路。 */
	private static void runSelfTest() {
		String address = "__smile_selftest__";
		String name = randomName();
		boolean legal = name.length() >= 3 && name.length() <= 16 && name.matches("[a-z0-9_]+");
		save(address, List.of(name));
		boolean roundTrip = load(address).contains(name);
		boolean isolated = load("__smile_selftest_other__").isEmpty();
		save(address, List.of());
		boolean cleared = load(address).isEmpty();
		LOGGER.info("假人自检：名字={}（合法={}），写入→读回={}，其它地址={}，清空后={}，文件={}",
				name, legal, roundTrip ? "一致" : "不一致",
				isolated ? "不串" : "串了",
				cleared ? "已清除" : "仍存在",
				Files.exists(FILE) ? "保留" : "已删除");
	}

	private enum Kind {
		SPAWN("spawn"),
		DROP("drop"),
		KILL("kill");

		final String action;

		Kind(String action) {
			this.action = action;
		}
	}

	private record Task(Kind kind, String name) {
	}

	private static final class Data {
		int version = 1;
		Map<String, ServerRecord> servers = new HashMap<>();
	}

	private static final class ServerRecord {
		List<String> names = new ArrayList<>();
		long updated;
	}
}
