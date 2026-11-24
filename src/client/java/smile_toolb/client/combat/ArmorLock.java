package smile_toolb.client.combat;

import org.slf4j.Logger;
import smile_libbox.api.ui.Ui;
import smile_libbox.api.world.Packets;
import smile_libbox.api.world.World;
import smile_toolb.SmileSToolb;
import smile_toolb.client.config.SmileConfig;

/**
 * 护甲锁状态机：在「这个位置不适合穿鞘翅」时，阻止盔甲栏被换成鞘翅。
 *
 * <h2>三种状态</h2>
 * <ul>
 *   <li>{@link State#IDLE}：环境安全，完全不干预，模组/玩家想怎么换怎么换；</li>
 *   <li>{@link State#GUARDED}：地形危险（或下方没有安全落脚点），
 *       拦掉「往胸甲槽放鞘翅」的容器操作，并且每 tick 复查一次胸甲槽，
 *       万一有模组绕过了发包入口直接改本地栏位，就立刻换回胸甲；</li>
 *   <li>{@link State#LOCKED}：重锤流程占用了护甲锁（见 {@link MaceSlam}），
 *       行为同 GUARDED，但退出由重锤流程决定而不是地形。</li>
 * </ul>
 *
 * <h2>什么时候绝对不碰胸甲槽</h2>
 * <ul>
 *   <li><b>正在滑翔</b>：鞘翅就是玩家此刻的翅膀，拆掉等于让他直接摔死。<b>而且滑翔中仍然拦包</b>
 *       ——早先这里直接退回 IDLE，拦包规则跟着一起失效，「飞一半被换成胸甲」就是从这条缝里
 *       漏出去的（贴地低飞离地五六格，还被当成「坠落」让了位）；</li>
 *   <li><b>已经被抛到空中</b>（离落脚面达到 {@link Terrain#AIR_ESCAPE}，比普通跳跃顶点高，
 *       且没在滑翔）：这时一枚鞘翅往往是玩家最后的自救手段——被击退、被怪弹飞、被爆炸抛起
 *       都属于这一类。旧判据只在「超过 3 格」时让位，而头顶有天花板的地方根本不看高度、
 *       一路锁到落地，于是「被弹飞却切不出鞘翅」正好卡在离地两三格那一段。<b>跑跳不算</b>
 *       ——离地一格半、两 tick 就落回同一格，让位等于把窄道保护整段关掉
 *       （见 {@link Terrain.Scan#inside()}）；</li>
 *   <li><b>进守卫前就穿着鞘翅</b>：那是玩家自己的装备，需求只禁止「自动切换」，不拆家。</li>
 * </ul>
 *
 * <h2>怎么区分「玩家手动」与「模组自动」</h2>
 * <p>拦包时看当前是否开着容器 / 背包界面：玩家自己换鞘翅必然是在界面里点的，
 * 这时一律放行；只有界面没开、却有「往胸甲槽放鞘翅」的操作，才判定为自动换装并拦掉。</p>
 *
 * <p>换回动作本身也走容器点击包，所以内部用 {@link #SELF_SWAP} 标志把自己发的包放行，
 * 否则会自己拦自己、无限循环。</p>
 *
 * <h2>为什么判据要看光标与热键栏</h2>
 * <p>换装的实际形态是「先点背包里的鞘翅拿起、再点胸甲槽放下」。第二步点的是胸甲槽，
 * 那一刻槽里还是旧胸甲，光看「槽内物品」根本认不出这一下要把鞘翅装进去——2×2 通道里
 * 快速跑跳时漏出去的包就是这么漏的。判据因此改成看 {@code carriedId} / {@code hotbarId}
 * （见 {@link ChestSwap#putsElytraInChest}）。</p>
 *
 * <h2>为什么每 tick 扫一次</h2>
 * <p>周边环境是按格变的：跑过一道门、跳上台阶，判定就可能翻转。早先 5 tick 一次的刷新
 * 跟不上跑跳节奏，人已经进了 3×3 通道、状态还停在「安全」。平台检测那段最贵的扫描
 * 只在可能左右结论时才跑（见 {@link Terrain.Scan#platformChecked}），每 tick 一次也不重。</p>
 *
 * <h2>守卫态下：被塞进手上的烟花要还回去</h2>
 * <p>窄道里打怪时手里没了武器，攻击直接断掉。会干这事的典型是 tweakeroo 的
 * 「自动切换烟花火箭」——它挂在自己的起飞判定上，把背包里最好的火箭<b>换进当前手上那一格</b>。
 * 所以只处理这一种形态：<b>选中槽没变、槽里的东西却变成了烟花</b>。</p>
 *
 * <p>反过来，玩家自己滚轮 / 按数字键切到烟花是<b>换槽</b>（选中槽变了），一律不管；
 * 滑翔中也不管（火箭就是加速手段）；真正站在危险格子里才管（判据同鞘翅，见
 * {@link #stillInside()}，在露天窄道上起跳准备飞时不会把火箭抢走）。</p>
 *
 * <p>退出守卫有 {@link #RELEASE_TICKS} tick 的迟滞：快速跑跳穿过格与格的接缝时，偶尔会有
 * 一两 tick 扫到「脚下更深的面 → 净空变大 → 不危险」，一 tick 的空档足够让别的模组把鞘翅塞进来。</p>
 */
public final class ArmorLock {

	private static final Logger LOGGER = SmileSToolb.LOGGER;

	/** 与鞘翅自动换装对照的物品 id。 */
	public static final String ELYTRA = ChestSwap.ELYTRA;

	/** 烟花火箭：守卫中「被别的模组塞进手里」时要换回原来的武器。 */
	public static final String FIREWORK = HandSwap.FIREWORK;

	/**
	 * 换回武器后的冷却（tick）。
	 *
	 * <p>连发容器点击包没有必要，也给对方模组一个「别再塞」的余地。</p>
	 */
	private static final int FIREWORK_COOLDOWN = 10;

	public enum State {
		/** 环境安全，不干预。 */
		IDLE,
		/** 地形危险，正在守卫。 */
		GUARDED,
		/** 重锤流程锁定。 */
		LOCKED
	}

	/** 换回操作的冷却（tick）：给服务端一个往返的时间，避免连发点击包。 */
	private static final int SWAP_COOLDOWN = 8;

	/**
	 * 退出守卫的迟滞（tick）：连续这么多 tick 扫到「安全」才真的放行。
	 *
	 * <p>快速跑跳穿过格与格的接缝时，会有一两 tick 扫到脚下更深的面（净空变大、宽度变宽）
	 * 从而判成安全；一 tick 的空档足够让自动换装模组把鞘翅塞进胸甲槽。</p>
	 */
	private static final int RELEASE_TICKS = 3;

	private static volatile State state = State.IDLE;
	private static volatile boolean selfSwap;

	private static Terrain.Scan lastScan;
	private static int swapCooldown;
	/** 连续判定为「安全」的 tick 数，用于 {@link #RELEASE_TICKS} 迟滞。 */
	private static int safeTicks;
	/**
	 * 拦下换鞘翅的操作时打一行日志（每 {@value #BLOCK_LOG_TICKS} tick 最多一行）。
	 *
	 * <p>以前这里是 {@code debug}，默认日志里根本看不到——「被弹飞后切不出鞘翅」时
	 * 无法判断是护甲锁拦的，还是别的模组自己失败。抬到 info 并带上当时的扫面读数，
	 * 事后翻日志就能定位到是哪一条判据在挡。</p>
	 */
	private static void logBlocked(Packets.Action action) {
		if (blockLogAge > 0) {
			return;
		}
		blockLogAge = BLOCK_LOG_TICKS;
		Terrain.Scan scan = lastScan;
		LOGGER.info("护甲锁：拦下一条往胸甲槽放鞘翅的操作（{}）——{}",
				action, scan == null ? "还没扫描过" : scan.describe());
	}

	/** 拦包日志的节流间隔（tick）。 */
	private static final int BLOCK_LOG_TICKS = 40;

	/** 拦包日志的节流计数。 */
	private static int blockLogAge;

	/** 上一 tick 胸甲槽里的物品 id，用来发现「别人动过胸甲槽」。 */
	private static String lastChest = "";
	/**
	 * 本模组刚发过换回包时剩余的保护 tick 数。
	 *
	 * <p>换回要走三条容器点击包（拿护甲 → 点胸甲槽 → 放回鞘翅），服务端一来一回
	 * 要两三 tick 才在本地生效。早先用一个布尔标志，只要中间有一次变化就把它清掉，
	 * 第二次变化会被误判成「不是本模组做的」；改成计时的窗口，窗口内的变化一律算自己。</p>
	 */
	private static int selfChangeAge;
	/** 上一 tick 手上拿的东西与选中的槽：用来认出「槽没变、东西却被换成了烟花」。 */
	private static String lastHand = "";
	private static int lastSlot = -1;
	/** 最近一次「手上不是烟花」时拿着的东西：被塞烟花时换回它。 */
	private static String combatItem = "";
	/** 换回武器的冷却（tick）。 */
	private static int fireworkCooldown;
	private static String lastLogged = "";

	/**
	 * 进入守卫时胸甲槽里本来就是鞘翅。
	 *
	 * <p>这种情况是玩家自己穿着的，不是「被模组自动换上的」，
	 * 需求与常识都不该把它扒下来——滑翔落地在窄道上时尤其致命。</p>
	 */
	private static boolean baselineElytra;

	private ArmorLock() {
	}

	public static void register() {
		// 黑箱拦截：库负责在发包入口判定，这里只说「什么时候不许换鞘翅」
		Packets.addRule(ArmorLock::blocksElytraSwap);
		Ui.onTick(ArmorLock::tick);
	}

	public static State state() {
		return state;
	}

	public static boolean guarding() {
		return state != State.IDLE;
	}

	/** 最近一次地形扫描（可能为 {@code null}，还没扫过）。 */
	public static Terrain.Scan scan() {
		return lastScan;
	}

	/** 重锤流程请求锁甲。 */
	public static void lock() {
		if (state != State.LOCKED) {
			state = State.LOCKED;
			logOnce("LOCKED", "重锤流程锁甲");
		}
	}

	/** 重锤流程结束，交还控制权（下一 tick 按地形重新判定）。 */
	public static void unlock() {
		if (state == State.LOCKED) {
			state = State.IDLE;
			baselineElytra = false;
		}
	}

	// ---------------------------------------------------------------- 包规则

	/**
	 * 由库在 {@code handleInventoryMouseClick} 的 HEAD 处调用。
	 *
	 * <p>关心两件事：<b>不许把鞘翅穿进胸甲槽</b>（地形危险时），
	 * 以及<b>不许把鞘翅从胸甲槽拿走</b>（滑翔时）。除此之外一律放行。</p>
	 */
	private static boolean blocksElytraSwap(Packets.Action action) {
		if (selfSwap) {
			return false;
		}
		// 玩家开着背包/容器时是他自己在换，明确放行
		if (World.containerOpen()) {
			return false;
		}
		// 滑翔中：鞘翅就是此刻的翅膀。这条不看地形、也不看状态机 ——
		// 贴地低飞离地五六格，地形判定随时可能翻成「安全」，一翻就把翅膀判没了。
		if (World.gliding() && ChestSwap.takesElytraOut(action)) {
			LOGGER.info("护甲锁：滑翔中拦下一条要换走胸甲槽鞘翅的操作 {}", action);
			return true;
		}
		if (state == State.IDLE) {
			return false;
		}
		// 守卫态下真的在坠落（离落脚面超过一次跳跃）：拦下来的可能是他摔死前唯一的
		// 救命鞘翅，落地再说。跑跳不算——那正是「3 格通道里跑两下就冒出鞘翅」的来源。
		if (state == State.GUARDED && !stillInside()) {
			return false;
		}
		if (ChestSwap.putsElytraInChest(action)) {
			logBlocked(action);
			return true;
		}
		return false;
	}

	// ---------------------------------------------------------------- 每 tick

	private static void tick() {
		if (blockLogAge > 0) {
			blockLogAge--;
		}
		watchChest();
		watchFirework();
		boolean enabled = SmileConfig.get().elytraGuardEnabled;
		boolean slamNeedsLock = state == State.LOCKED;

		if (!enabled && !slamNeedsLock) {
			if (state != State.IDLE) {
				state = State.IDLE;
				baselineElytra = false;
			}
			return;
		}

		// 每 tick 扫一次：周边环境是按格变的，跑跳时 5 tick 一次会跟不上
		lastScan = Terrain.scan();
		Terrain.Scan scan = lastScan;

		if (scan == null) {
			return;
		}

		if (slamNeedsLock) {
			enforce(scan);
			return;
		}

		if (World.gliding()) {
			// 滑翔中：地形判定没有意义，但这恰恰是最不能动胸甲槽的时候。
			// 早先这里直接退回 IDLE，拦包规则也跟着失效 —— 「飞一半被换成胸甲」
			// 就是从这条缝里漏出去的（贴地低飞离地五六格，还被当成坠落让了位）。
			// 现在改成「只守不拆」：状态留在 GUARDED 让拦包继续生效，enforce 不跑。
			safeTicks = 0;
			if (state != State.GUARDED) {
				state = State.GUARDED;
				// 翅膀是玩家此刻正在用的东西，落地也不主动扒
				baselineElytra = true;
				logOnce("GLIDING", "滑翔中，护甲锁转为只守不拆（" + scan.describe() + "）");
			}
			return;
		}

		if (scan.dangerous) {
			safeTicks = 0;
			if (state != State.GUARDED) {
				state = State.GUARDED;
				// 记下「进来时穿的是什么」：本来就穿着鞘翅就不动它
				baselineElytra = World.chestIsElytra();
				logOnce("GUARDED", "地形不适合鞘翅（" + scan.describe() + "），开始守卫");
			}
			enforce(scan);
		} else if (state != State.IDLE && ++safeTicks >= RELEASE_TICKS) {
			// 迟滞：跑跳穿过格子接缝时偶尔会有一两 tick 扫成「安全」，
			// 一 tick 的空档就够别的模组把鞘翅塞进来
			state = State.IDLE;
			baselineElytra = false;
			safeTicks = 0;
			logOnce("IDLE", "地形安全（" + scan.describe() + "），停止守卫");
		}
	}

	/**
	 * 守卫中「手里的东西被换成烟花」时把它换回去。
	 *
	 * <p>只认一种形态：<b>选中的槽没变、槽里的物品却变成了烟花</b>。玩家用滚轮 / 数字键切到烟花
	 * 是<b>换槽</b>（选中槽会跟着变），那是他自己的选择，一律不管；被 tweakeroo 那类
	 * 「自动切换烟花火箭」换进当前槽的，选中槽不变、东西变了，那才是别人的手。</p>
	 *
	 * <p>换回走 {@link World#holdItem(String)}：原武器在热键栏就切槽，只在背包里就发一次
	 * 容器点击包把它换回手上（服务端权威，多人服同样有效）。</p>
	 *
	 * <p>四种情况不动手：<b>滑翔中</b>（火箭就是加速手段）、<b>开着背包 / 容器</b>（自己的鼠标）、
	 * <b>环境安全</b>（不干预）、<b>已经离开危险格子</b>（{@link #stillInside()} 为假——
	 * 露天窄道上起跳准备飞，正是要火箭的时候）。</p>
	 */
	private static void watchFirework() {
		if (fireworkCooldown > 0) {
			fireworkCooldown--;
		}
		if (!World.ready()) {
			return;
		}
		String hand = World.handItem();
		int slot = World.selectedSlot();
		boolean firework = FIREWORK.equals(hand);
		boolean swappedInPlace = HandSwap.swappedIntoHand(hand, lastHand, slot, lastSlot);
		if (!firework) {
			// 手上是武器 / 空手：记下来，被塞烟花时换回这件
			combatItem = hand;
		}
		lastHand = hand;
		lastSlot = slot;
		if (!swappedInPlace || state == State.IDLE) {
			return;
		}
		LOGGER.debug("护甲锁：守卫中手里的物品变成了烟花火箭（第 {} 格，{}）", slot + 1, state);
		if (World.gliding() || World.containerOpen() || !stillInside() || fireworkCooldown > 0) {
			return;
		}
		String back = combatItem;
		if (back.isEmpty() || FIREWORK.equals(back)) {
			return;
		}
		fireworkCooldown = FIREWORK_COOLDOWN;
		if (World.holdItem(back)) {
			LOGGER.info("护甲锁：守卫中手里的烟花是别的模组换上的，已换回 {}", back);
		}
	}

	/**
	 * 盯一眼胸甲槽：鞘翅「凭空消失」时打一行日志说明不是本模组做的。
	 *
	 * <p>会改胸甲槽的模组往往没有日志，出问题时只能看到「飞一半掉下去」。
	 * 这一行把时间点、当时的状态和离地高度记下来，配合 {@code 护甲锁：} 的其它行
	 * 就能判断是自己拦错了，还是别的模组/服务端动了手。</p>
	 */
	private static void watchChest() {
		if (selfChangeAge > 0) {
			selfChangeAge--;
		}
		if (!World.ready()) {
			return;
		}
		String chest = World.chestItem();
		if (chest.equals(lastChest)) {
			return;
		}
		String previous = lastChest;
		lastChest = chest;
		if (selfChangeAge > 0) {
			// 是本模组刚才那三条点击包的结果（服务端回包可能分两 tick 到）
			return;
		}
		if (ELYTRA.equals(previous) && !ELYTRA.equals(chest)) {
			Terrain.Scan scan = lastScan;
			LOGGER.info("护甲锁：胸甲槽的鞘翅被换走了（换成 {}）——最近 10 tick 内本模组没发过换回包；"
							+ "状态={}，滑翔={}，离地={}",
					chest.isEmpty() ? "空" : chest, state,
					World.gliding() ? "是" : "否",
					scan == null || scan.drop == Double.MAX_VALUE
							? "?" : String.format("%.1f", scan.drop));
		}
	}

	/**
	 * 「仍在危险格子里」：最近一次扫描判定危险，且人离落脚面不超过一次跳跃。
	 *
	 * <p>还没扫过时按「不让位」处理——宁可多守一 tick，也不要在进危险的第一个 tick 放行。</p>
	 */
	public static boolean stillInside() {
		Terrain.Scan scan = lastScan;
		return scan == null || scan.inside();
	}

	/** 每 tick 复查胸甲槽：被换成鞘翅就立刻换回护甲。 */
	private static void enforce(Terrain.Scan scan) {
		if (swapCooldown > 0) {
			swapCooldown--;
			return;
		}
		if (!World.ready()) {
			return;
		}
		if (!World.chestIsElytra()) {
			// 胸甲槽回到护甲了，基线跟着走：之后再被换上鞘翅就算自动换装
			baselineElytra = false;
			return;
		}
		if (World.containerOpen()) {
			// 玩家正开着背包自己换，不抢他的鼠标
			return;
		}
		if (state != State.LOCKED) {
			// 守卫态：滑翔中 / 真的在坠落 / 进来时就穿着鞘翅，一律不碰
			if (World.gliding()) {
				return;
			}
			if (!scan.inside()) {
				return;
			}
			if (baselineElytra) {
				return;
			}
		}
		if (swapToArmor()) {
			swapCooldown = SWAP_COOLDOWN;
			LOGGER.info("护甲锁：检测到胸甲槽被换成鞘翅，已换回护甲（{}）", scan.describe());
		}
	}

	/**
	 * 主动把胸甲槽的鞘翅换回背包里的胸甲。
	 *
	 * <p>内部发的容器点击包要放行，否则会被自己的规则拦下。</p>
	 */
	public static boolean swapToArmor() {
		if (selfSwap) {
			return false;
		}
		selfSwap = true;
		selfChangeAge = 10;
		try {
			return World.equipChestArmor();
		} finally {
			selfSwap = false;
		}
	}

	/**
	 * 主动把背包里的鞘翅穿回胸甲槽（恢复飞行用，见 {@link MaceSlam}）。
	 *
	 * <p>与 {@link #swapToArmor()} 完全对称：内部发的容器点击包必须放行，否则会被
	 * 自己的「不许往胸甲槽放鞘翅」规则拦下；{@link #selfChangeAge} 也要顶上，
	 * 免得 {@link #watchChest()} 把这次变化记成「别人动过胸甲槽」。</p>
	 */
	public static boolean equipElytra() {
		if (selfSwap) {
			return false;
		}
		selfSwap = true;
		selfChangeAge = 10;
		try {
			return World.equipElytra();
		} finally {
			selfSwap = false;
		}
	}

	/** 状态变化才打日志，避免每 tick 刷屏。 */
	private static void logOnce(String key, String message) {
		if (key.equals(lastLogged)) {
			return;
		}
		lastLogged = key;
		LOGGER.info("护甲锁：{}", message);
	}

	/**
	 * 一行状态摘要，给配置界面底栏与日志用（纯 Java，不含 MC 类型）。
	 *
	 * <p>格式：{@code 状态|净空=2 宽=1 露天=否 平台=无}，没扫过时后半段为 {@code -}。</p>
	 */
	public static String statusLine() {
		String name;
		if (state == State.GUARDED && World.gliding()) {
			// 滑翔中是「只守不拆」，跟地面上的守卫分开叫，免得看着像要夺人翅膀
			name = "滑翔保护";
		} else {
			switch (state) {
				case GUARDED -> name = "守卫中";
				case LOCKED -> name = "重锤锁定";
				default -> name = "空闲";
			}
		}
		Terrain.Scan scan = lastScan;
		if (scan == null) {
			return name + "|-";
		}
		return name + "|净空=" + String.format("%.1f", scan.clearance) + " 宽=" + scan.width
				+ " 露天=" + (scan.openSky ? "是" : "否")
				+ " 平台=" + (scan.platformChecked ? (scan.platform ? "有" : "无") : "-")
				+ (scan.shaft ? " 竖井=是" : "")
				+ " 危险=" + (scan.dangerous ? "是" : "否");
	}

	/**
	 * 当前是否「正在干预」（给界面上色用）。
	 *
	 * <p>看的是状态而不是地形扫描结果：滑翔中只守不拆（不会动手），底栏不该红着脸吓人。</p>
	 */
	public static boolean dangerous() {
		return state != State.IDLE && !World.gliding();
	}
}
