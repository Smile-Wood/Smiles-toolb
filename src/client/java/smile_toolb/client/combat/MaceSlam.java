package smile_toolb.client.combat;

import org.slf4j.Logger;
import smile_libbox.api.ui.Ui;
import smile_libbox.api.world.World;
import smile_toolb.SmileSToolb;
import smile_toolb.client.config.SmileConfig;

/**
 * 重锤猛击（Mace smash）自动执行。
 *
 * <h2>触发条件</h2>
 * <ol>
 *   <li>正在下落（竖直速度向下，且不在地面）；</li>
 *   <li>或者正在滑翔、开了「原地下砸」、<b>按住潜行键</b>、目标就在手长柱里——
 *       滑翔时潜行键是原版的「加速下降」，飞过生物上方按着它是常事，所以「按多久算数」
 *       按视角分两档：低头盯着落点 3 tick 就动手，平视飞着按 shift 要 8 tick
 *       （见 {@link GlideDive}）。早先只看「俯视 ≥60°」，于是平视按 shift 怎么按都不触发；</li>
 *   <li>离地（或已坠落）<b>超过 1.5 格</b>——原地跳一下只有 1.25 格，原版猛击的强化伤害也要
 *       坠落距离 > 1.5 格才成立，低于这个数挥出去是空挥，只会白锁一次甲、白换一次锤；</li>
 *   <li>按住潜行；</li>
 *   <li>背包里有重锤（1.20.x 没有这个物品，自然永不触发）；</li>
 *   <li>落点附近有实体：手长柱里有东西，或两倍手长内的实体按当前速度会在你落地时
 *       撞进落点（见 {@link SlamTargets}）。船与盔甲架同样算数。</li>
 * </ol>
 *
 * <h2>动作顺序</h2>
 * <ol>
 *   <li>锁护甲（{@link ArmorLock#lock()}）：期间任何模组都换不上鞘翅，
 *       胸甲槽已经是鞘翅的会当场换成背包里最强的那件护甲；</li>
 *   <li>把重锤切到手上（热键栏有就直接切，只在背包就用容器点击包换过来）——
 *       <b>先切锤、再摘鞘翅</b>：切不出锤就放弃，人还在滑翔，不会被白拽下来；</li>
 *   <li>滑翔中触发时（按住够久、鼠标扫过不算），把胸甲槽的鞘翅摘掉——原版「身上没有鞘翅就飞不了」，
 *       滑翔状态当场就取消了，人从当前位置往下掉。**不动速度**：横向操作照旧，
 *       要微调落点自己按 WASD；</li>
 *   <li>在预计落地前「提前量」个 tick 挥击最近的实体——
 *       原版的猛击判定要求「攻击时玩家仍在下落」，落地那一帧再打就晚了。
 *       提前量默认按<b>延迟自动算</b>（见 {@link LeadEstimate}）：200ms 的玩家提前 3 tick，
 *       本地玩家提前 1~2 tick，避免「包还在路上、人已经落地」；</li>
 *   <li>挥击前<b>再确认一次手上是重锤</b>：流程开头那次切锤只管「开始下砸」那一刻，
 *       落地前这几 tick 里滑翔判定可能成立，别的模组（tweakeroo 的「自动切换烟花火箭」）
 *       会把火箭换进当前这一格，那一挥就成了拿烟花空挥。发现不是锤就当场重切；
 *       切不回来（包还在路上）则这一 tick 不打，下一 tick 再说；</li>
 *   <li>落地后解除护甲锁，交还给地形守卫；</li>
 *   <li><b>收工后留一段观察窗口</b>（{@value #RESUME_WATCH_TICKS} tick）：砸完立刻被弹飞
 *       （风暴、爆炸、击退、怪物弹射）是很常见的，那一刻手上是剑、胸甲槽是护甲，
 *       飞不起来只能干看着自己往下掉。窗口内只要「人在空中 + 离地 ≥ 1.75 格 +
 *       下方没有可砸的实体」，就把鞘翅穿回去、把手换成烟花火箭（见 {@link FlightResume}），
 *       玩家按一下空格就能重新起飞；有东西可砸时什么都不做，等下一次猛击。</li>
 * </ol>
 *
 * <p>Xaero 那一套的下落判定口径是「不在地面 + 竖直速度为负」，
 * 这里另外要求速度有实际分量（{@code -0.08} 以下），避免把「刚起跳的顶点抖动」
 * 也算成下落。</p>
 *
 * <p>「原地下砸」指的只是<b>取消滑翔</b>，不是把人钉在一条竖直线上：
 * 摘掉鞘翅之后重力接管，横向速度与 WASD 都原样保留。</p>
 *
 * <h2>为什么提前量要跟着延迟走</h2>
 * <p>攻击包在路上要飞半程。本地玩家这一程约等于 0，还剩 1 tick 落地再发，服务端处理时
 * 人还在空中；200ms 的玩家这一程是 2 tick，同一时刻发包，服务端（以及它自己 tick 出来的
 * 玩家位置）已经落地，猛击判定不成立——白锁甲、白换锤。所以提前量取
 * {@code max(手动值, ceil(ping / 100) + 1)}，见 {@link LeadEstimate}。</p>
 *
 * <p>两点需要知道：<b>提前量不是越大越好</b>，它直接吃掉坠落距离，而坠落距离正是猛击的
 * 伤害倍率来源，所以上限压在 8 tick、200ms 落在 3 tick；<b>它治不了「距离不够」</b>——
 * 高延迟时服务端看到的玩家位置比实际落后，挥击若被判超出手长，再早发包也没用，
 * 那是手长（{@code maceSlamReach}）该调的事。</p>
 */
public final class MaceSlam {

	private static final Logger LOGGER = SmileSToolb.LOGGER;

	/** 判定为「正在下落」的最小向下速度（格/tick）。 */
	private static final double FALLING_EPSILON = -0.08D;

	/**
	 * 触发所需的离地高度（格），也等于原版猛击强化伤害的门槛。
	 *
	 * <p>一次普通跳跃的顶点只有 1.25 格：不加这道门槛，在怪身边跳一下就会被当成「要砸」，
	 * 锁甲、切锤、落地再换回来整套跑一遍，而那一锤根本没有强化伤害。
	 * 原版 {@code MaceItem} 也是按「坠落距离 &gt; 1.5」给强化伤害的，低于它挥了等于空挥。</p>
	 */
	private static final double SLAM_MIN_FALL = 1.5D;

	/** 重锤注册名（收工时用来判断手上是不是自己切的锤子）。 */
	private static final String MACE = "minecraft:mace";

	/** 恢复飞行时换到手上的东西。 */
	private static final String FIREWORK = HandSwap.FIREWORK;

	/**
	 * 收工后观察「要不要恢复飞行」的窗口（tick）。
	 *
	 * <p>砸中与被弹飞之间常常只隔一两 tick（风暴、爆炸、怪物弹射），落地那一帧
	 * 人还在方块上，{@link FlightResume#wanted} 一定为假。留两秒的窗口，
	 * 等那股力把人抛起来再接住；再长就会开始干扰「砸完自己跳一下」这类正常操作。</p>
	 */
	private static final int RESUME_WATCH_TICKS = 40;

	/**
	 * 「下方有没有可砸实体」的刷新间隔（tick）。
	 *
	 * <p>这个结论同时被本类和 {@link ArmorLock} 用（人在空中、下方又没东西可砸时
	 * 护甲锁该让位）。扫描要走一遍地面与实体，没必要每 tick 都做——
	 * 4 tick 一次足够跟上人的移动，也不会让每 tick 的判定变贵。</p>
	 */
	private static final int BELOW_REFRESH = 4;

	/** 判断「离地多高」时往下扫的深度（格）；用来区分「落地」与「被弹到半空」。 */
	private static final int RESUME_GROUND_SCAN = 64;

	/**
	 * 流程中「目标连续消失」多少 tick 才收工。
	 *
	 * <p>运动预判的命中会出现一两 tick 的抖动（怪走位、船漂移），
	 * 一丢就放弃会把整条流程打断在半路。3 tick 足够过滤抖动，又不会让人
	 * 白锁着甲往下摔。</p>
	 */
	private static final int TARGET_LOST_TICKS = 3;

	/** 同一次下落里两次挥击的最小间隔（tick）。 */
	private static final int SWING_COOLDOWN = 4;

	/**
	 * 挥击前「重切重锤」最多试几次。
	 *
	 * <p>正常情况下 0~1 次就够（换手包一 tick 落地）。反复失败说明这一格正被别的模组
	 * 每 tick 抢走（滑翔判定持续成立时的「自动切换烟花火箭」），继续切只会刷包，
	 * 不如收手，让落地后的恢复逻辑把原武器换回来。</p>
	 */
	private static final int RESWAP_TRIES = 3;

	/**
	 * 提前多少 tick 开始「捏稳锤子」（tick）。
	 *
	 * <p>换手若走容器点击包要一 tick 才生效，所以要在挥击窗口之前就开始校验：
	 * 等到最后一 tick 才发现手上是烟花，只能白等。
	 * 2 tick 正好覆盖「一 tick 发包 + 一 tick 让出挥击窗口」。</p>
	 */
	private static final int RESWAP_MARGIN = 2;

	/**
	 * 收工后追补「把武器换回来」的窗口（tick）。
	 *
	 * <p>换手走的是容器点击包，服务端确认要一 tick；落地那一帧发出的包如果还在路上，
	 * 当场看手上仍是锤子就白等一次。所以不要求一次成功，接下来这点时间里每 tick 再试，
	 * 玩家自己换了别的东西就立刻放手。</p>
	 */
	private static final int RESTORE_TICKS = 20;

	/**
	 * 滑翔诊断日志的间隔（tick）。
	 *
	 * <p>滑翔中按着下砸键、但还没到下砸时机时，每这么多 tick 打一行读数
	 * （俯仰 / 需保持 / 已保持 / 离地），专门用来排查「按了 shift 却不下落」。
	 * 保持时长本身由 {@link GlideDive} 按视角决定：低头盯着落点 3 tick，平视按 shift 8 tick——
	 * 原版滑翔时 shift 就是加速下降，把「没低头」当成「不想砸」正是之前一次都不触发的原因。</p>
	 */
	private static final int GLIDE_DIAG_TICKS = 20;

	/**
	 * 往下找地面时扫多深（格）。
	 *
	 * <p>滑翔砸人往往在几十格高空，库默认的 48 格会把「脚下有地」判成悬空，
	 * 于是飞高一点就再也不触发了。</p>
	 */
	private static final int GROUND_SCAN = 160;

	private static boolean active;
	private static int swingCooldown;
	/** 滑翔中「按住下砸键 + 目标在手长柱里」连续成立的 tick 数。 */
	private static int glideHold;
	/** 动手之前手上拿的东西与选中的槽位，收工时换回去。 */
	private static String savedHand = "";
	private static int savedSlot = -1;
	/** 还有多少 tick 可以继续尝试换回武器（{@code 0} 表示没有待办）。 */
	private static int restoreTicks;
	/** 平滑后的往返延迟（毫秒），每 tick 采一次 tab 列表。 */
	private static int pingMs;
	/**
	 * 本次流程里「手上的重锤被换走过」。
	 *
	 * <p>正常情况手上一直是锤，收工时 {@link #restoreHand()} 靠「手上还是锤」认账；
	 * 被别的模组换成烟花之后这条判据就失效了（那时手上不是锤，看起来像玩家自己换的），
	 * 于是收工时什么都不做，玩家落地还攥着烟花。这个标志把「异常」与「玩家自己换的」
	 * 分开：置上之后即使手上不是锤也强制换回原武器。</p>
	 */
	private static boolean maceLost;
	/** 挥击前「重切重锤」已经试了几次（正常 0~1 次；反复被换走就放弃）。 */
	private static int reswapTries;
	/** 滑翔诊断日志的节流计数（tick）。 */
	private static int glideDiagAge;
	/** 下方手长柱里有没有可砸的实体（{@code null} 表示还没扫过）。 */
	private static Boolean belowTarget;
	/** 距离上次刷新 {@link #belowTarget} 过了多少 tick。 */
	private static int belowAge;
	/** 流程中目标连续消失了多少 tick。 */
	private static int lostTicks;
	/** 收工后「要不要恢复飞行」的窗口剩余（tick）。 */
	private static int resumeTicks;
	/** 这次的恢复流程里鞘翅是不是已经穿回去了。 */
	private static boolean resumeElytraDone;

	private MaceSlam() {
	}

	public static void register() {
		Ui.onTick(MaceSlam::tick);
	}

	/** 当前是否处在一次猛击流程里（调试 / 状态展示用）。 */
	public static boolean active() {
		return active;
	}

	private static void tick() {
		SmileConfig config = SmileConfig.get();
		if (!World.ready()) {
			finish("世界未就绪");
			return;
		}
		// 延迟采样放在最前面：即便这一次不触发，提前量也在后台跟着网络状况走
		pingMs = LeadEstimate.smooth(pingMs, World.ping());
		// 「下方有没有可砸实体」与按键无关，护甲锁也要用，所以放在所有早退之前
		refreshBelow(config);
		// 先问「要不要恢复飞行」：命中的话它会接管收尾（手上要拿烟花，不再换回武器），
		// 排在前面的 tickRestore 也就不会白换一次、再被烟花覆盖掉
		tickResume();
		// 收工后的尾巴：容器点击包要一 tick 才落地，这一段时间里接着把武器换回来
		tickRestore();
		if (swingCooldown > 0) {
			swingCooldown--;
		}

		// 落地即收工，把护甲交还给地形守卫
		if (World.onGround()) {
			finish("已落地");
			return;
		}

		if (!config.maceSlamEnabled) {
			finish("功能已关闭");
			return;
		}

		double[] motion = World.motion();
		if (motion.length < 3) {
			return;
		}

		// 滑翔中只有开了「原地下砸」才参与——那时候是主动把鞘翅摘掉换一个下砸，
		// 关掉之后滑翔仍然完全不干预。
		boolean gliding = World.gliding();
		if (gliding && !config.maceSlamDive) {
			glideHold = 0;
			finish("正在滑翔");
			return;
		}
		// 滑翔下砸必须有主动按键：关掉「要求潜行」之后，下落自动砸是合理的，
		// 但滑翔中自动砸等于飞着飞着被人从天上摘翅，只能由按键发起。
		if (gliding && !World.sneaking()) {
			glideHold = 0;
			return;
		}
		if (!gliding && motion[1] > FALLING_EPSILON) {
			// 没在下落（起跳上升段之类），什么都不做
			glideHold = 0;
			return;
		}

		if (config.maceSlamRequireSneak && !World.sneaking()) {
			glideHold = 0;
			return;
		}
		if (!World.maceInInventory()) {
			return;
		}
		double distance = World.groundDistance(GROUND_SCAN);
		if (distance == Double.MAX_VALUE) {
			// 脚下几十格都没有地面，砸空气没有意义
			return;
		}
		// 离地 / 已坠落都不到 1.5 格：原地跳一下、贴着台阶往下挪一步，都不算「要砸」。
		// 两个数取大的那个——刚跳起来时 fallDistance 与 groundDistance 都只有 1.25，
		// 而从高处坠落时 fallDistance 会先长起来、groundDistance 还要看脚下有多深。
		if (Math.max(distance, World.fallDistance()) <= SLAM_MIN_FALL) {
			glideHold = 0;
			return;
		}
		double reach = clampReach(config.maceSlamReach);
		// 滑翔时按「摘掉鞘翅后竖直落下去」算落点：滑翔的水平速度能到 1 格/tick 以上，
		// 拿它做预判会把目标指到十几格开外。落地后想往哪偏，玩家自己微调。
		double[] aim = gliding
				? new double[] {0.0D, Math.min(motion[1], 0.0D), 0.0D}
				: motion;
		SlamTargets.Hit target = SlamTargets.find(World.pos(), aim, distance, reach,
				World.entities(Math.max(8.0D, reach * 2.0D + 4.0D)));
		if (!target.found()) {
			// 滑翔中目标会滑出扫描范围：只回退一步，不清零（见 GlideDive#decay）
			glideHold = GlideDive.decay(glideHold, gliding);
			if (active && ++lostTicks >= TARGET_LOST_TICKS) {
				// 甲锁了、翅膀摘了、锤子也切出来了，目标却没了：再锁下去就是拿命换一次空挥。
				// 收工，交给恢复飞行把鞘翅和烟花装回去（见 FlightResume）。
				finish("下方没有可砸的目标");
				return;
			}
			if (gliding) {
				glideDiag(GlideDive.ticksNeeded(World.pitch()), distance, "下方没有可砸的目标");
			}
			return;
		}
		lostTicks = 0;

		if (gliding) {
			// 滑翔中的两道硬门槛：目标必须就在手长柱里（不接受运动预判——路过的怪、
			// 开过来的船都能把预判"喂"成命中），并且按住下砸键要够久。
			// 按多久由视角决定：低头盯着落点 3 tick 就够；平视按 shift（原版的加速下降）
			// 要 8 tick——不把「没低头」当成「不想砸」，否则飞着按 shift 一次都不触发。
			int need = GlideDive.ticksNeeded(World.pitch());
			if (target.rule != SlamTargets.Rule.COLUMN) {
				// 目标还在、只是不在正下方（正滑出柱子）：同样只回退，不清零
				glideHold = GlideDive.decay(glideHold, true);
				glideDiag(need, distance, target.describe() + "，不在正下方");
				return;
			}
			glideHold = GlideDive.advance(glideHold);
			if (glideHold < need) {
				glideDiag(need, distance, "目标 " + target.describe());
				return;
			}
		}

		if (!active) {
			active = true;
			maceLost = false;
			reswapTries = 0;
			ArmorLock.lock();
			// 切锤会把手上这把换掉（只在背包里时要走容器点击包），先记住原样，收工换回来
			savedHand = World.handItem();
			savedSlot = World.selectedSlot();
			restoreTicks = 0;
			// 先切锤、再拆翅膀。顺序反过来的话，背包里没锤时会先把鞘翅摘掉——
			// 人已经被拽下坠了，锤又切不出来，等于白摔一次。先切锤，切不出来就还在滑翔。
			if (!World.maceToHotbar()) {
				// 连锤子都切不出来，挥出去的是手里那把，猛击判定不会成立
				LOGGER.info("重锤猛击：重锤切不到手上，放弃这次");
				finish("切不出重锤");
				return;
			}
			if (World.chestIsElytra()) {
				// 摘鞘翅就是取消滑翔：原版只要胸甲槽里没有可用的鞘翅就飞不了，
				// 服务端会在下一 tick 一并停下，不需要碰速度。
				ArmorLock.swapToArmor();
				if (World.chestIsElytra()) {
					// 背包里没有别的护甲，摘不下来——别硬锁着，免得卡在锁定态
					LOGGER.info("重锤猛击：背包里没有别的护甲，取消滑翔失败");
					finish("摘不下鞘翅");
					return;
				}
				LOGGER.info("重锤猛击：摘下鞘翅，取消滑翔（目标 {}）", target.describe());
			}
			LOGGER.info("重锤猛击：锁定护甲并切出重锤（下落 {} 格，目标 {}，延迟 {}ms）",
					String.format("%.1f", World.fallDistance()), target.describe(), pingMs);
		}

		// 滑翔还没真正停下之前不打：原版滑翔中不算猛击，白挥一刀
		if (World.gliding()) {
			return;
		}

		double ticksToGround = target.ticksToImpact;
		if (ticksToGround <= 0) {
			return;
		}
		int lead = LeadEstimate.choose(config.maceSlamLeadTicks, pingMs, config.maceSlamLeadAuto);
		// 比挥击窗口早几 tick 就把锤子捏稳：换手包要一 tick 才落地，等到最后一 tick
		// 才发现手上是烟花就来不及了
		if (ticksToGround <= lead + RESWAP_MARGIN && !ensureMace()) {
			return;
		}
		if (ticksToGround <= lead && swingCooldown == 0) {
			if (World.attackNearest(reach)) {
				swingCooldown = SWING_COOLDOWN;
				LOGGER.info("重锤猛击：落地前挥击（距地 {} 格，提前 {} tick，延迟 {}ms，手上 {}）",
						String.format("%.2f", distance), lead, pingMs, World.handItem());
			}
		}
	}

	/**
	 * 滑翔中「已经按住下砸键、但还没到下砸时机」时的诊断（每 {@value #GLIDE_DIAG_TICKS} tick 一行）。
	 *
	 * <p>这条日志是给「按了 shift 却不下落」这种反馈留的：滑翔分支在门槛前返回时什么都不打，
	 * 事后翻日志只能看到「没触发」，看不出卡在俯仰、保持时长还是离地高度。控制在一秒一行，
	 * 不拖着按 shift 都不会刷屏。</p>
	 */
	private static void glideDiag(int need, double distance, String where) {
		if (glideDiagAge > 0) {
			glideDiagAge--;
			return;
		}
		glideDiagAge = GLIDE_DIAG_TICKS;
		LOGGER.info("重锤猛击：滑翔中等下砸（{}；俯仰 {}°，需保持 {} tick，已保持 {}，离地 {} 格）",
				where, String.format("%.0f", World.pitch()), need, glideHold,
				String.format("%.1f", distance));
	}

	/**
	 * 确认手上是重锤，不是就当场重切一次。
	 *
	 * <p>流程开头那次切锤只管「开始下砸」那一刻，之后每 tick 都可能有别的模组动这一格：
	 * 客户端一旦把「跳起 + 按住空格」判成起飞，tweakeroo 的「自动切换烟花火箭」就会把
	 * 火箭换进当前槽。于是落地前那一挥变成拿烟花空挥——猛击不成立，还白耗一发火箭。</p>
	 *
	 * <p>切不回来（锤只在背包里，容器点击包要一 tick 落地）就返回 {@code false}，让这一 tick
	 * 先不打；锤被反复抢走超过 {@link #RESWAP_TRIES} 次就收手，免得每 tick 刷包。</p>
	 */
	private static boolean ensureMace() {
		String hand = World.handItem();
		if (MACE.equals(hand)) {
			reswapTries = 0;
			return true;
		}
		maceLost = true;
		if (++reswapTries > RESWAP_TRIES) {
			LOGGER.info("重锤猛击：重锤被反复换走（{} 次），放弃这次", RESWAP_TRIES);
			finish("重锤被反复换走");
			return false;
		}
		boolean reswapped = World.maceToHotbar() && MACE.equals(World.handItem());
		LOGGER.info("重锤猛击：手上是 {}，重新切重锤（{}）",
				hand.isEmpty() ? "空手" : hand,
				reswapped ? "本 tick 已生效" : "下一 tick 再打");
		return reswapped;
	}

	/**
	 * 底栏用的一行：当前延迟与据此算出的提前量。
	 *
	 * <p>高延迟玩家最需要看到的就是这两个数——提前量不再是配置里那个固定值，
	 * 而是跟着网络走；看不到它就没法判断「为什么这一锤没砸中」。</p>
	 */
	public static String leadLine() {
		SmileConfig config = SmileConfig.get();
		int lead = LeadEstimate.choose(config.maceSlamLeadTicks, pingMs, config.maceSlamLeadAuto);
		String mode = config.maceSlamLeadAuto
				? "自动 " + LeadEstimate.autoTicks(pingMs)
				: "手动 " + LeadEstimate.clamp(config.maceSlamLeadTicks);
		return String.format("延迟 %dms（%s，实际提前 %d tick）", pingMs, mode, lead);
	}

	/** 手长夹在 2~6 格，配置被手改成 0 也不会把判定范围收成一条线。 */
	private static double clampReach(int configured) {
		int clamped = Math.max(2, Math.min(6, configured));
		return clamped;
	}

	private static void finish(String reason) {
		if (!active) {
			return;
		}
		active = false;
		glideHold = 0;
		glideDiagAge = 0;
		lostTicks = 0;
		ArmorLock.unlock();
		resumeTicks = RESUME_WATCH_TICKS;
		resumeElytraDone = World.chestIsElytra();
		if (FlightResume.wanted(SmileConfig.get().maceSlamResumeFlight,
				World.onGround(), drop(), slamWanted())) {
			// 人已经在空中、下方又没东西可砸：不换回武器了，直接装回飞行装备。
			// 这时手上是锤，正好让后面的 holdItem 换成烟花。
			savedHand = "";
			savedSlot = -1;
			maceLost = false;
			restoreTicks = 0;
			LOGGER.info("重锤猛击：解除护甲锁（{}），下方没有可砸的目标，恢复飞行装备", reason);
			resumeFlight();
			return;
		}
		// 正常收工：把武器换回去。落地那一下常常紧接着被弹飞，观察窗口照开。
		restoreTicks = RESTORE_TICKS;
		restoreHand();
		LOGGER.info("重锤猛击：解除护甲锁（{}）", reason);
	}

	/**
	 * 收工后的观察窗口：砸完被弹飞时，把飞行装备装回去。
	 *
	 * <p>落地那一帧人还在方块上，{@link FlightResume#wanted} 必然为假；
	 * 但如果下一两 tick 被风暴 / 爆炸 / 击退抛起来，条件就成立了。窗口给
	 * {@value #RESUME_WATCH_TICKS} tick，够覆盖「砸中 → 被弹」的时间差，
	 * 又不会长到干扰「砸完自己跳一下」。</p>
	 */
	private static void tickResume() {
		if (resumeTicks <= 0) {
			return;
		}
		resumeTicks--;
		if (!FlightResume.wanted(SmileConfig.get().maceSlamResumeFlight,
				World.onGround(), drop(), slamWanted())) {
			return;
		}
		// 接管收尾：飞行时手上要拿烟花，不再把武器换回去
		restoreTicks = 0;
		savedHand = "";
		savedSlot = -1;
		maceLost = false;
		resumeFlight();
	}

	/**
	 * 把飞行装备装回去：先穿鞘翅，再把烟花换到手上。
	 *
	 * <p>两步分开做是因为换装要走容器点击包，一 tick 才生效：本 tick 发出穿鞘翅的包，
	 * 下一 tick 才能确认它在胸甲槽里，那时再去拿烟花。</p>
	 *
	 * <p>背包里根本没有鞘翅时不硬撑：关掉恢复窗口，照常把武器换回来——
	 * 把人晾在半空、手上还攥着锤子才是最糟的结果。</p>
	 */
	private static void resumeFlight() {
		if (!resumeElytraDone) {
			if (World.chestIsElytra()) {
				resumeElytraDone = true;
			} else if (ArmorLock.equipElytra()) {
				resumeElytraDone = true;
				LOGGER.info("重锤猛击：把鞘翅穿回去，恢复飞行");
				return;
			} else {
				resumeTicks = 0;
				restoreTicks = RESTORE_TICKS;
				restoreHand();
				return;
			}
		}
		if (FIREWORK.equals(World.handItem())) {
			resumeTicks = 0;
			return;
		}
		if (World.holdItem(FIREWORK)) {
			resumeTicks = 0;
			LOGGER.info("重锤猛击：把烟花火箭换到手上，可以继续飞");
		}
	}

	/**
	 * 下方有东西可砸、<b>而且玩家也还想砸</b>（按着潜行）——这时不该改成飞。
	 *
	 * <p>只看「下方有没有实体」会让「被弹飞、不想砸只想飞」也飞不起来：
	 * 下方有只怪就算「有活干」，可玩家根本没按潜行。加上按键条件，
	 * 「继续砸」与「继续飞」就都由玩家自己说了算。</p>
	 */
	private static boolean slamWanted() {
		return canSlamBelow() && World.sneaking();
	}

	// ---------------------------------------------------------------- 供护甲锁查询

	/**
	 * 下方手长柱里有没有可以砸的实体（最近一次扫描）。
	 *
	 * <p>{@link ArmorLock} 用它判断「要不要给飞行让路」：人在空中、下方又没有可砸的东西时，
	 * 锁着鞘翅没有任何意义——既飞不了，也砸不成。还没扫过时按「有」处理，
	 * 宁可多守一 tick，不要在进危险的第一个 tick 就把门打开。</p>
	 */
	public static boolean canSlamBelow() {
		return belowTarget == null || belowTarget;
	}

	/**
	 * 每 {@value #BELOW_REFRESH} tick 刷新一次 {@link #canSlamBelow()}。
	 *
	 * <p>与按键无关：护甲锁每 tick 都要问这个结论，不能等玩家按下潜行才算。
	 * 站在地上时直接记成「有」——那时锁鞘翅是地形保护的本职，与会不会砸无关。</p>
	 */
	private static void refreshBelow(SmileConfig config) {
		if (belowAge > 0) {
			belowAge--;
			return;
		}
		belowAge = BELOW_REFRESH;
		if (!config.maceSlamEnabled || World.onGround()) {
			belowTarget = Boolean.TRUE;
			return;
		}
		double distance = World.groundDistance(GROUND_SCAN);
		if (distance == Double.MAX_VALUE) {
			// 脚下几十格都没有地面：砸不到任何东西
			belowTarget = Boolean.FALSE;
			return;
		}
		double[] motion = World.motion();
		if (motion.length < 3) {
			belowTarget = Boolean.TRUE;
			return;
		}
		double reach = clampReach(config.maceSlamReach);
		SlamTargets.Hit hit = SlamTargets.find(World.pos(), motion, distance, reach,
				World.entities(Math.max(8.0D, reach * 2.0D + 4.0D)));
		belowTarget = hit.found();
	}

	/**
	 * 脚部到脚下地面的高度（格）；脚下没有地面时返回 {@link Double#MAX_VALUE}。
	 *
	 * <p>不借用 {@link ArmorLock} 的地形扫描：那个在「鞘翅锁」被关掉时不跑，
	 * 而恢复飞行与鞘翅锁是两个独立开关，不该被它连累。</p>
	 */
	private static double drop() {
		return World.groundDistance(RESUME_GROUND_SCAN);
	}

	/** 收工后的追补：每 tick 试一次，直到换回来、玩家自己换了东西、或者超时。 */
	private static void tickRestore() {
		if (savedSlot < 0 && savedHand.isEmpty()) {
			return;
		}
		if (restoreTicks <= 0) {
			savedHand = "";
			savedSlot = -1;
			maceLost = false;
			return;
		}
		restoreTicks--;
		restoreHand();
	}

	/**
	 * 把手上换回动手之前的那把。
	 *
	 * <p>只在 {@link HandSwap#shouldRestore(String, String, boolean)} 为真时动手：玩家自己
	 * 中途换了别的东西，就别抢他的槽。原手上是空的（比如把锤子从背包换到了原本空着的槽）
	 * 就只把选中槽切回去。</p>
	 *
	 * <p>换手包发出去不等于当场生效（服务端要一 tick），所以成功、放弃、待重试三种情况
	 * 分开处理：只有「手上不是锤子也不是空手」、「已经换回原物」才清掉待办，其余留着
	 * 让 {@link #tickRestore()} 下一 tick 再试。</p>
	 */
	private static void restoreHand() {
		String now = World.handItem();
		if (!HandSwap.shouldRestore(now, MACE, maceLost)) {
			// 手上不是我们切的锤子，也不在「被换走」的异常态里：自己换过就放手；
			// 空手则可能是包还没回来，等下一 tick
			if (!now.isEmpty()) {
				savedHand = "";
				savedSlot = -1;
			}
			return;
		}
		String hand = savedHand;
		int slot = savedSlot;
		if (!hand.isEmpty() && !MACE.equals(hand)) {
			if (hand.equals(now)) {
				// 换回来了（可能是上一 tick 的包刚落地）
				savedHand = "";
				savedSlot = -1;
				maceLost = false;
				return;
			}
			if (World.holdItem(hand)) {
				savedHand = "";
				savedSlot = -1;
				maceLost = false;
				LOGGER.info("重锤猛击：把手换回 {}", hand);
			}
			return;
		}
		if (slot >= 0 && World.selectedSlot() != slot) {
			World.selectSlot(slot);
		}
		savedHand = "";
		savedSlot = -1;
		maceLost = false;
	}
}
