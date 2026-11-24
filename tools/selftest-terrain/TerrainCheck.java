package smile_toolb.client.combat;

import smile_libbox.api.world.EntityView;
import smile_libbox.api.world.Packets;
import smile_libbox.api.world.World;

import java.util.HashSet;
import java.util.Set;

/**
 * 地形判定的纯 Java 自检：用假的方块集合跑需求里给的四个基准场景。
 *
 * <p>不加载 Minecraft，只替换 {@link World.Backend}，所以能直接 {@code javac + java} 跑。</p>
 *
 * <pre>
 *   场景 A 半空单方块小路            → 危险（宽 1 + 无平台）
 *   场景 B 地下 2 格高 1 格宽小道    → 危险（净空 2）
 *   场景 C 地下 3×3 通道             → 危险（净空 3）
 *   场景 D 地下 4×4 通道             → 安全（净空 4 且不露天）
 *   对照  露天草地（宽 20 + 平台）   → 安全
 *   场景 E 草地上起跳 1.5 格         → 安全（基准取落点地面，不是脚部坐标）
 *   场景 F 单方块小路上方 3 格       → 危险（落点仍是 1 格宽）
 *   场景 G 高空悬停 20 格            → 安全（脚下 12 格内无地面，宽度规则不适用）
 *   场景 H 3×3 通道内起跳 1.5 格     → 危险（离落脚面不到一次跳跃，仍算在这一格里）
 *   场景 P 站在雪层上（脚部在薄方块里）→ 安全（薄方块是地面，不是天花板）
 *   场景 Q 2×2 通道内跑跳           → 危险（净空 2）
 *   场景 V 1×1 竖井（四面是墙）      → 安全（竖直通道，允许起飞）
 *   场景 V3 1×1 三格深坑（封顶）     → 危险（净空 3，井也救不了）
 *   场景 V4 两面墙的壁龛            → 危险（不成井，仍按宽 1 窄道算）
 *
 *   守卫判据 {@link Terrain.Scan#inside()}：危险 + 离落脚面 ≤ 一次跳跃 +（有天花板 或 真的站着）；\n *   离地 ≥ 1.75 格（被击退 / 弹飞，比跳跃顶点高）时一律让位，让鞘翅能穿上去自救
 *   → 仍要锁甲；真从高处坠落（离地 6 格，且不在滑翔）、或露天窄道上刚起跳，才让位。
 *   滑翔中一律算「还在这一格里」——贴地低飞离地五六格是常态飞法，不是坠落。
 *
 *   换装判据 {@link ChestSwap}：看光标 / 热键栏，而不是被点槽位里现有的东西。
 *   烟花判据 {@link HandSwap}：同槽换物（模组塞进来的）才换回，换槽（玩家自己切的）不管。
 *   收工恢复 {@link HandSwap#shouldRestore}：手上是锤、或流程中丢过锤才收回，玩家自己换的不抢。\n *   恢复飞行 {@link FlightResume}：在空中 + 离地 ≥ 1.75 + 下方无目标时，收工后把鞘翅与烟花装回去。
 *   滑翔下砸 {@link GlideDive}：低头 3 tick、平视 8 tick，不把「没低头」当成「不想砸」；\n *   提前量 {@link LeadEstimate}：200ms → 3 tick；自动模式取 max(手动值, 延迟换算值)；
 *   延迟读数做 1/8 指数平滑，压掉 tab 列表的秒级抖动。
 * </pre>
 */
public final class TerrainCheck {

	private static int passed;
	private static int failed;

	public static void main(String[] args) {
		// A：半空单方块柱顶，四周全是空气
		Box air = new Box();
		air.floor(-0, 63, 0);
		check("A 半空单方块小路", air, 0.5D, 64.0D, 0.5D, true);

		// B：地下 2 格高、1 格宽的小道（沿 z 方向）
		Box tunnel2 = new Box();
		tunnel2.slab(63, -6, 6, -6, 6);
		tunnel2.slab(66, -6, 6, -6, 6);
		tunnel2.wallX(-1, 64, 65, -6, 6);
		tunnel2.wallX(1, 64, 65, -6, 6);
		check("B 地下 2 格高 1 格宽", tunnel2, 0.5D, 64.0D, 0.5D, true);

		// C：地下 3×3 通道（净空 3）
		Box tunnel3 = new Box();
		tunnel3.slab(63, -8, 8, -8, 8);
		tunnel3.slab(67, -8, 8, -8, 8);
		tunnel3.wallX(-2, 64, 66, -8, 8);
		tunnel3.wallX(2, 64, 66, -8, 8);
		tunnel3.wallZ(-2, 64, 66, -8, 8);
		tunnel3.wallZ(2, 64, 66, -8, 8);
		check("C 地下 3×3 通道", tunnel3, 0.5D, 64.0D, 0.5D, true);

		// D：地下 4×4 通道（净空 4，宽度 4）
		Box tunnel4 = new Box();
		tunnel4.slab(63, -8, 8, -8, 8);
		tunnel4.slab(68, -8, 8, -8, 8);
		tunnel4.wallX(-3, 64, 67, -8, 8);
		tunnel4.wallX(2, 64, 67, -8, 8);
		tunnel4.wallZ(-3, 64, 67, -8, 8);
		tunnel4.wallZ(2, 64, 67, -8, 8);
		check("D 地下 4×4 通道", tunnel4, 0.0D, 64.0D, 0.0D, false);

		// 对照：露天平地（宽 20 格，两侧都有大平台）
		Box field = new Box();
		field.slab(63, -20, 20, -20, 20);
		check("对照 露天草地", field, 0.5D, 64.0D, 0.5D, false);

		// E：在草地上起跳 1.5 格（脚下一片空气，基准必须是落点那层地面）→ 安全
		check("E 草地起跳", field, 0.5D, 65.5D, 0.5D, false);

		// F：悬在单方块小路正上方 3 格 → 落点仍是 1 格宽，危险
		check("F 单块小路上方", air, 0.5D, 67.0D, 0.5D, true);

		// G：高空 20 格（脚下 12 格内没有地面）→ 悬空，不判危险
		check("G 高空悬停", field, 0.5D, 84.0D, 0.5D, false);

		// H：3×3 通道里跑跳（离地 1.5 格）→ 仍危险，护甲锁必须照常生效
		check("H 3×3 通道内起跳", tunnel3, 0.5D, 65.5D, 0.5D, true);

		// P：站在雪层 / 地毯上——脚部坐标落在薄方块里，它是地面不是天花板。
		//    改前净空被算成 0，平地上每 tick 都在「开始守卫 / 停止守卫」之间抖。
		Box snow = new Box();
		snow.slab(64, -8, 8, -8, 8);
		check("P 站在雪层上", snow, 0.5D, 64.125D, 0.5D, false);

		// P2 / P3：站在双地毯上（两层地毯，落脚面 45.0625）。
		//   走廊净高 4 格（地板 43、地毯 44/45、天花板 49）→ 净空 3.9 → 安全；
		//   净高 3 格（天花板 48）→ 净空 2.9 → 危险。
		//   改前按「整格 + isSolid」量：地毯被当成挡头的方块，脚部坐标被抬到 46 格，
		//   四格高的走廊量成三格 —— 站着不动也判危险，日志里就是「净空=1，离地=0.1」。
		Box carpet4 = carpetHall(49);
		check("P2 双地毯 4 格走廊", carpet4, 0.5D, 45.0625D, 0.5D, false);
		check("P2b 双地毯 起跳 仍安全", carpet4, 0.5D, 46.0D, 0.5D, false);

		Box carpet3 = carpetHall(48);
		check("P3 双地毯 3 格走廊", carpet3, 0.5D, 45.0625D, 0.5D, true);

		// 守卫判据：跑跳不放行，真坠落才让位
		inside("I 3×3 站立 应守卫", tunnel3, 0.5D, 64.0D, 0.5D, true);
		inside("J 3×3 起跳 仍守卫", tunnel3, 0.5D, 65.4D, 0.5D, true);
		// K：跳跃提升能顶到 2.9 格，已经在空中了 —— 让位。
		//   有天花板的地方旧逻辑会一直锁到落地，「被怪弹飞却切不出鞘翅」就卡在这两三格里。
		inside("K 3×3 顶点 让位", tunnel3, 0.5D, 66.9D, 0.5D, false);
		// K2 / K3：起跳一半（1.4）仍拦，被弹到两格（2.0）放行 —— 门槛在 1.75
		inside("K2 3×3 起跳 1.4 仍守卫", tunnel3, 0.5D, 65.4D, 0.5D, true);
		inside("K3 3×3 被弹到 2 格 让位", tunnel3, 0.5D, 66.0D, 0.5D, false);
		inside("L 站在小路上 仍守卫", air, 0.5D, 64.0D, 0.5D, true);
		inside("M 小路上方 3 格 让位", air, 0.5D, 67.0D, 0.5D, false);
		inside("N 小路上方 6 格 让位", air, 0.5D, 70.0D, 0.5D, false);
		inside("O 4×4 通道 不守卫", tunnel4, 0.0D, 64.0D, 0.0D, false);
		inside("U 双地毯 4 格 不守卫", carpet4, 0.5D, 45.0625D, 0.5D, false);

		// 贴地滑翔：离地五六格是躲怪 / 避地形的常态飞法，绝不能当成坠落让位
		insideGliding("Q 贴地滑翔 6 格 仍守卫", air, 0.5D, 70.0D, 0.5D, true);
		insideGliding("Q2 滑翔 20 格 悬空不守卫", field, 0.5D, 84.0D, 0.5D, false);

		// 2×2 通道：快速跑跳时最容易漏包，必须在守卫范围里
		Box tunnel22 = new Box();
		tunnel22.slab(63, -8, 8, -8, 8);
		tunnel22.slab(66, -8, 8, -8, 8);
		tunnel22.wallX(-1, 64, 65, -8, 8);
		tunnel22.wallX(2, 64, 65, -8, 8);
		check("R 2×2 通道 站立", tunnel22, 0.5D, 64.0D, 0.5D, true);
		inside("R2 2×2 通道 跑跳 仍守卫", tunnel22, 0.5D, 65.4D, 0.5D, true);

		// V：1×1 竖井（四面是墙、往上直通天空）→ 允许起飞。
		//   同样是「宽 1」，半空单方块小路（场景 A）飞出去就是坠落，
		//   竖井四面夹住、往上是一条通道，锁了等于把矿洞直井封死。
		Box shaft = new Box();
		shaft.floor(0, 63, 0);
		shaft.wallX(-1, 64, 120, -6, 6);
		shaft.wallX(1, 64, 120, -6, 6);
		shaft.wallZ(-1, 64, 120, -6, 6);
		shaft.wallZ(1, 64, 120, -6, 6);
		check("V 1×1 竖井 站立", shaft, 0.5D, 64.0D, 0.5D, false);
		inside("V2 1×1 竖井 起跳 不守卫", shaft, 0.5D, 65.5D, 0.5D, false);

		// V3：井口往下三格就封顶的 1×1 小坑 —— 净空 ≤ 3，还是守卫
		Box pit3 = new Box();
		pit3.floor(0, 63, 0);
		pit3.wallX(-1, 64, 70, -6, 6);
		pit3.wallX(1, 64, 70, -6, 6);
		pit3.wallZ(-1, 64, 70, -6, 6);
		pit3.wallZ(1, 64, 70, -6, 6);
		pit3.set(0, 67, 0);
		check("V3 1×1 三格坑 封顶", pit3, 0.5D, 64.0D, 0.5D, true);

		// V4：只有两面墙的「壁龛」—— 不成井，仍按窄道守卫
		Box niche = new Box();
		niche.floor(0, 63, 0);
		niche.wallX(-1, 64, 120, -6, 6);
		niche.wallX(1, 64, 120, -6, 6);
		check("V4 两面墙 不成井", niche, 0.5D, 64.0D, 0.5D, true);

		swaps();
		fireworks();
		restores();
		flights();
		leads();
		glides();
		slam();
		System.out.println();
		System.out.println("通过 " + passed + " / " + (passed + failed));
		if (failed > 0) {
			System.exit(1);
		}
	}


	// ------------------------------------------------------------ 重锤落点判定

	/**
	 * 玩家固定在 (0.5, 30, 0.5)、竖直速度 -1.2，脚下地面在 y=20（落 10 格，8 tick）。
	 * 手长取 3，也就是 2 倍手长 = 6。每个用例只换实体。
	 */
	private static void slam() {
		double[] pos = {0.5D, 30.0D, 0.5D};
		double[] motion = {0.0D, -1.2D, 0.0D};
		double ground = 10.0D;
		double reach = 3.0D;

		slamCheck("S1 正下方一条船", pos, motion, ground, reach,
				new EntityView[] {boat(0.5D, 21.0D, 0.5D, 0.0D, 0.0D, 0.0D)},
				SlamTargets.Rule.COLUMN);
		slamCheck("S2 正下方盔甲架", pos, motion, ground, reach,
				new EntityView[] {stand(0.6D, 21.5D, 0.6D, 0.0D, 0.0D, 0.0D)},
				SlamTargets.Rule.COLUMN);
		slamCheck("S3 落点旁 2.5 格的船", pos, motion, ground, reach,
				new EntityView[] {boat(0.5D, 20.0D, 3.0D, 0.0D, 0.0D, 0.0D)},
				SlamTargets.Rule.COLUMN);
		slamCheck("S4 两倍手长内静止", pos, motion, ground, reach,
				new EntityView[] {boat(0.5D, 21.0D, 5.0D, 0.0D, 0.0D, 0.0D)},
				SlamTargets.Rule.NONE);
		slamCheck("S5 远处静止", pos, motion, ground, reach,
				new EntityView[] {boat(0.5D, 21.0D, 12.0D, 0.0D, 0.0D, 0.0D)},
				SlamTargets.Rule.NONE);
		slamCheck("S6 横向开进落点", pos, motion, ground, reach,
				new EntityView[] {boat(0.5D, 21.0D, 7.0D, 0.0D, 0.0D, -0.5D)},
				SlamTargets.Rule.PREDICTED);
		slamCheck("S7 同高度一起下落", pos, motion, ground, reach,
				new EntityView[] {stand(0.5D, 34.0D, 0.5D, 0.0D, -1.5D, 0.0D)},
				SlamTargets.Rule.PREDICTED);
		slamCheck("S8 从落点横穿而过", pos, motion, ground, reach,
				new EntityView[] {boat(12.0D, 28.0D, 0.5D, -0.3D, 0.0D, 0.0D)},
				SlamTargets.Rule.NONE);
		slamCheck("S9 地面以下的实体", pos, motion, ground, reach,
				new EntityView[] {boat(0.5D, 10.0D, 0.5D, 0.0D, 0.0D, 0.0D)},
				SlamTargets.Rule.NONE);
	}

	private static void slamCheck(String name, double[] pos, double[] motion,
			double ground, double reach, EntityView[] entities, SlamTargets.Rule expect) {
		SlamTargets.Hit hit = SlamTargets.find(pos, motion, ground, reach, entities);
		boolean ok = hit.rule == expect;
		if (ok) {
			passed++;
		} else {
			failed++;
		}
		System.out.printf("%s %-22s 期望=%-9s 实际=%-9s  [%s]%n",
				ok ? "OK  " : "FAIL", name, expect, hit.rule, hit.describe());
	}

	/** 船的碰撞箱：1.375 × 0.5625，非活体。 */
	private static EntityView boat(double x, double y, double z, double dx, double dy, double dz) {
		return new EntityView("minecraft:oak_boat", x, y, z, dx, dy, dz, 1.375D, 0.5625D, false);
	}

	/** 盔甲架的碰撞箱：0.5 × 1.975，是活体。 */
	private static EntityView stand(double x, double y, double z, double dx, double dy, double dz) {
		return new EntityView("minecraft:armor_stand", x, y, z, dx, dy, dz, 0.5D, 1.975D, true);
	}

	/**
	 * 一条 3 格宽、天花板底面在 {@code ceilingY} 的走廊：地板在 43 格，
	 * 两层地毯铺满 44 / 45 格（站立面 45.0625），用来复现「站在双地毯上」的误判。
	 */
	private static Box carpetHall(int ceilingY) {
		Box box = new Box();
		box.slab(43, -8, 8, -8, 8);
		box.slab(ceilingY, -8, 8, -8, 8);
		box.wallX(-2, 44, ceilingY - 1, -8, 8);
		box.wallX(2, 44, ceilingY - 1, -8, 8);
		box.wallZ(-8, 44, ceilingY - 1, -2, 2);
		box.wallZ(8, 44, ceilingY - 1, -2, 2);
		for (int x = -1; x <= 1; x++) {
			for (int z = -7; z <= 7; z++) {
				box.carpet(x, 44, z);
				box.carpet(x, 45, z);
			}
		}
		return box;
	}

	private static void check(String name, Box box, double x, double y, double z, boolean expectDangerous) {
		World.install(new FakeWorld(box, x, y, z));
		Terrain.Scan scan = Terrain.scan();
		boolean ok = scan.dangerous == expectDangerous;
		if (ok) {
			passed++;
		} else {
			failed++;
		}
		System.out.printf("%s %-22s 期望=%s 实际=%s  [%s]%n",
				ok ? "OK  " : "FAIL", name,
				expectDangerous ? "危险" : "安全", scan.dangerous ? "危险" : "安全",
				scan.describe());
	}

	/** 守卫判据：{@code inside()} 为真表示「还要继续锁甲」。 */
	private static void inside(String name, Box box, double x, double y, double z, boolean expect) {
		insideAt(name, box, x, y, z, false, expect);
	}

	/** 同上，但这一刻正在滑翔。 */
	private static void insideGliding(String name, Box box, double x, double y, double z, boolean expect) {
		insideAt(name, box, x, y, z, true, expect);
	}

	private static void insideAt(String name, Box box, double x, double y, double z,
			boolean gliding, boolean expect) {
		World.install(new FakeWorld(box, x, y, z, gliding));
		Terrain.Scan scan = Terrain.scan();
		boolean ok = scan.inside() == expect;
		if (ok) {
			passed++;
		} else {
			failed++;
		}
		System.out.printf("%s %-24s 期望=%-4s 实际=%-4s  [%s]%n",
				ok ? "OK  " : "FAIL", name,
				expect ? "守卫" : "让位", scan.inside() ? "守卫" : "让位", scan.describe());
	}


	// ------------------------------------------------------------ 换装判据

	/**
	 * 「这一下容器操作会不会把鞘翅塞进胸甲槽」——护甲锁最容易漏的一段。
	 *
	 * <p>真实形态是「先点背包里的鞘翅拿起、再点胸甲槽放下」，第二下被点槽位里
	 * 还是旧胸甲，所以判据必须看光标 {@code carried}。</p>
	 */
	private static void swaps() {
		swapPut("X1 手上鞘翅点胸甲槽", act(6, true, "minecraft:diamond_chestplate",
				"minecraft:elytra", "", "PICKUP"), true);
		swapPut("X2 手上胸甲点胸甲槽", act(6, true, "minecraft:elytra",
				"minecraft:diamond_chestplate", "", "PICKUP"), false);
		swapPut("X3 数字键鞘翅换胸甲槽", act(6, true, "minecraft:diamond_chestplate",
				"", "minecraft:elytra", "SWAP"), true);
		swapPut("X4 背包鞘翅 shift 点", act(9, false, "minecraft:elytra",
				"", "", "QUICK_MOVE"), true);
		swapPut("X5 背包鞘翅 单击", act(9, false, "minecraft:elytra",
				"", "", "PICKUP"), false);
		swapPut("X6 空手点空胸甲槽", act(6, true, "", "", "", "PICKUP"), false);

		swapOut("X7 空手点胸甲槽的鞘翅", act(6, true, "minecraft:elytra",
				"", "", "PICKUP"), true);
		swapOut("X8 鞘翅换鞘翅", act(6, true, "minecraft:elytra",
				"minecraft:elytra", "", "PICKUP"), false);
		swapOut("X9 点胸甲", act(6, true, "minecraft:diamond_chestplate",
				"", "", "PICKUP"), false);
	}

	/**
	 * 烟花：只认「同槽换物」（模组把火箭换进当前这一格），不认「换槽」（玩家自己滚轮 / 数字键）。
	 *
	 * <p>这条判据决定了「守卫中被模组切烟花了就换回去」和「不许烟花出现在手里」的区别：
	 * 判错了就是把玩家自己选的烟花抢走。</p>
	 */
	private static void fireworks() {
		fire(  "F1 同槽被换成烟花", "minecraft:firework_rocket", "minecraft:diamond_sword", 3, 3, true);
		fire(  "F2 滚轮切到烟花格", "minecraft:firework_rocket", "minecraft:diamond_sword", 4, 3, false);
		fire(  "F3 一直拿着烟花",   "minecraft:firework_rocket", "minecraft:firework_rocket", 3, 3, false);
		fire(  "F4 同槽换成剑",     "minecraft:diamond_sword",   "minecraft:firework_rocket", 3, 3, false);
		fire(  "F5 空槽变成烟花",   "minecraft:firework_rocket", "", 0, 0, true);
	}

	/**
	 * 重锤收工的准入判定：什么时候该把原武器换回来。
	 *
	 * <p>关键区分「锤被模组换走」与「玩家自己换了」：两者从当前物品看起来一样，
	 * 只有流程中发现过丢锤（{@code lost}）才该强制收回，否则会抢玩家的槽。</p>
	 */
	private static void restores() {
		String mace = "minecraft:mace";
		truth("M1 手上是重锤", true, HandSwap.shouldRestore(mace, mace, false));
		truth("M2 锤被换走（异常态）", true,
				HandSwap.shouldRestore("minecraft:firework_rocket", mace, true));
		truth("M3 玩家自己换了剑", false,
				HandSwap.shouldRestore("minecraft:diamond_sword", mace, false));
		truth("M4 空手且无异常", false, HandSwap.shouldRestore("", mace, false));
	}

	private static void truth(String name, boolean expect, boolean actual) {
		boolean ok = expect == actual;
		if (ok) {
			passed++;
		} else {
			failed++;
		}
		System.out.printf("%s %-24s 期望=%-4s 实际=%-4s%n",
				ok ? "OK  " : "FAIL", name, expect ? "收回" : "放手", actual ? "收回" : "放手");
	}

	/**
	 * 收工后「要不要恢复飞行装备」：砸完被弹飞、下方又没东西可砸时才装回鞘翅 + 烟花。
	 *
	 * <p>这几条把「被弹飞」与「自己跳一下」分开：普通跳跃顶点 1.25 格，门槛在 1.75；
	 * 也把「没目标该飞」与「有目标该继续砸」分开。</p>
	 */
	private static void flights() {
		resume("W1 落地不动", false,
				FlightResume.wanted(true, true, 5.0D, false));
		resume("W2 空中 2 格无目标", true,
				FlightResume.wanted(true, false, 2.0D, false));
		resume("W3 空中 1.2 格（跳跃）", false,
				FlightResume.wanted(true, false, 1.2D, false));
		resume("W4 空中 3 格但有目标", false,
				FlightResume.wanted(true, false, 3.0D, true));
		resume("W5 功能关掉", false,
				FlightResume.wanted(false, false, 4.0D, false));
		resume("W6 悬空无目标", true,
				FlightResume.wanted(true, false, Double.MAX_VALUE, false));
	}

	private static void resume(String name, boolean expect, boolean actual) {
		boolean ok = expect == actual;
		if (ok) {
			passed++;
		} else {
			failed++;
		}
		System.out.printf("%s %-24s 期望=%-4s 实际=%-4s%n",
				ok ? "OK  " : "FAIL", name, expect ? "装回" : "不动", actual ? "装回" : "不动");
	}

	/**
	 * 提前量：把延迟换算成 tick，并检查平滑与夹取。
	 *
	 * <p>公式 {@code clamp(ceil(ping / 100) + 1, 1, 8)}；自动模式取
	 * {@code max(手动值, 自动值)}，手动值是下限不是替代。</p>
	 */
	private static void leads() {
		// 正/反向各一组：200ms 是用户的实际线路，必须落在 3 tick
		equal("L1 200ms 自动", 3, LeadEstimate.choose(2, 200, true));
		equal("L2 430ms 自动", 6, LeadEstimate.choose(2, 430, true));
		equal("L3 50ms 自动", 2, LeadEstimate.choose(1, 50, true));
		// 0ms（本地）不会把手动值压下去：手动是下限
		equal("L4 0ms 手动 2", 2, LeadEstimate.choose(2, 0, true));
		// 关掉自动：严格按手动值
		equal("L5 关自动用手动值", 2, LeadEstimate.choose(2, 200, false));
		// 手动值高于自动值：听手动的
		equal("L6 手动 5 顶住自动", 5, LeadEstimate.choose(5, 200, true));
		// 极端延迟夹在上限
		equal("L7 3000ms 上限", 8, LeadEstimate.choose(2, 3000, true));
		// 平滑：300 → 100 一次只掉 1/8，不跳变
		equal("L8 平滑 300→100", 275, LeadEstimate.smooth(300, 100));
		// 首次采样直接采用
		equal("L9 平滑首次", 180, LeadEstimate.smooth(0, 180));
		// 同值不漂移
		equal("L10 平滑同值", 200, LeadEstimate.smooth(200, 200));
		// 无效样本（负数）原样返回
		equal("L11 平滑忽略负样本", 200, LeadEstimate.smooth(200, -1));
	}

	/**
	 * 滑翔下砸的保持时长：低头盯着落点要短，平视按 shift 要长。
	 *
	 * <p>这条判据是「飞着按 shift 却不下落」的修复：早先只要俯视不到 60° 就一律不触发，
	 * 而滑翔时按 shift 本来就是原版的加速下降，没人会为此先低头。现在平视按住 8 tick
	 * 一样算数，低头只要 3 tick。</p>
	 */
	private static void glides() {
		equal("G1 正俯视 90°", 3, GlideDive.ticksNeeded(90));
		equal("G2 恰在 60° 门槛", 3, GlideDive.ticksNeeded(60));
		equal("G3 俯视 59°", 8, GlideDive.ticksNeeded(59));
		equal("G4 平视 0°", 8, GlideDive.ticksNeeded(0));
		equal("G5 抬头 -40°", 8, GlideDive.ticksNeeded(-40));
		// 目标短暂滑出柱子时只回退一步，不清零；否则按住 shift 一路飞永远攒不满
		equal("G6 累计 +1", 4, GlideDive.advance(3));
		equal("G7 滑翔中丢失回退", 2, GlideDive.decay(3, true));
		equal("G8 下坠中丢失清零", 0, GlideDive.decay(3, false));
		equal("G9 回退不下溢", 0, GlideDive.decay(0, true));
	}

	private static void equal(String name, int expect, int actual) {
		boolean ok = expect == actual;
		if (ok) {
			passed++;
		} else {
			failed++;
		}
		System.out.printf("%s %-26s 期望=%-3d 实际=%-3d%n",
				ok ? "OK  " : "FAIL", name, expect, actual);
	}

	private static void fire(String name, String hand, String lastHand,
			int slot, int lastSlot, boolean expect) {
		boolean actual = HandSwap.swappedIntoHand(hand, lastHand, slot, lastSlot);
		boolean ok = expect == actual;
		if (ok) {
			passed++;
		} else {
			failed++;
		}
		System.out.printf("%s %-24s 期望=%-4s 实际=%-4s  [%s→%s 槽%d→%d]%n",
				ok ? "OK  " : "FAIL", name, expect ? "换回" : "不管", actual ? "换回" : "不管",
				lastHand.isEmpty() ? "空手" : lastHand, hand, lastSlot, slot);
	}

	private static Packets.Action act(int slot, boolean chest, String item,
			String carried, String hotbar, String clickType) {
		return new Packets.Action("container_click", 0, slot, 0, clickType,
				item, carried, hotbar, chest, chest);
	}

	private static void swapPut(String name, Packets.Action action, boolean expect) {
		boolean actual = ChestSwap.putsElytraInChest(action);
		report(name, expect, actual, action);
	}

	private static void swapOut(String name, Packets.Action action, boolean expect) {
		boolean actual = ChestSwap.takesElytraOut(action);
		report(name, expect, actual, action);
	}

	private static void report(String name, boolean expect, boolean actual, Packets.Action action) {
		boolean ok = expect == actual;
		if (ok) {
			passed++;
		} else {
			failed++;
		}
		System.out.printf("%s %-24s 期望=%-4s 实际=%-4s  [%s]%n",
				ok ? "OK  " : "FAIL", name, expect ? "拦" : "放", actual ? "拦" : "放", action);
	}

	/** 方块集合：用 "x,y,z" 字符串当键，够小也够直观。 */
	private static final class Box {
		final Set<String> solid = new HashSet<>();
		/** 薄方块（地毯 / 雪层）：坐标 → 碰撞高度，属于「有碰撞但不是满格」。 */
		final java.util.Map<String, Double> thin = new java.util.HashMap<>();

		void set(int x, int y, int z) {
			solid.add(x + "," + y + "," + z);
		}

		boolean has(int x, int y, int z) {
			return solid.contains(x + "," + y + "," + z);
		}

		/** 铺一层地毯（碰撞高度 1/16）。 */
		void carpet(int x, int y, int z) {
			thin.put(x + "," + y + "," + z, 0.0625D);
		}

		/** 该坐标的碰撞高度：满格 1.0、地毯 0.0625、空气 0.0。 */
		double height(int x, int y, int z) {
			String key = x + "," + y + "," + z;
			Double h = thin.get(key);
			if (h != null) {
				return h;
			}
			return solid.contains(key) ? 1.0D : 0.0D;
		}

		/** 单块地面。 */
		void floor(int x, int y, int z) {
			set(x, y, z);
		}

		/** 一整层平板。 */
		void slab(int y, int x0, int x1, int z0, int z1) {
			for (int x = x0; x <= x1; x++) {
				for (int z = z0; z <= z1; z++) {
					set(x, y, z);
				}
			}
		}

		/** 竖直薄墙（固定 x）。 */
		void wallX(int x, int y0, int y1, int z0, int z1) {
			for (int y = y0; y <= y1; y++) {
				for (int z = z0; z <= z1; z++) {
					set(x, y, z);
				}
			}
		}

		/** 竖直薄墙（固定 z）。 */
		void wallZ(int z, int y0, int y1, int x0, int x1) {
			for (int y = y0; y <= y1; y++) {
				for (int x = x0; x <= x1; x++) {
					set(x, y, z);
				}
			}
		}
	}

	/** 只回答「玩家在哪、这个坐标是不是固体」。 */
	private static final class FakeWorld implements World.Backend {
		private final Box box;
		private final double x;
		private final double y;
		private final double z;
		private final boolean gliding;

		FakeWorld(Box box, double x, double y, double z) {
			this(box, x, y, z, false);
		}

		FakeWorld(Box box, double x, double y, double z, boolean gliding) {
			this.box = box;
			this.x = x;
			this.y = y;
			this.z = z;
			this.gliding = gliding;
		}

		public boolean available() { return true; }
		public double[] pos() { return new double[] {x, y, z}; }
		public double[] motion() { return new double[] {0, 0, 0}; }
		public boolean onGround() { return true; }
		public float fallDistance() { return 0.0F; }
		public boolean sneaking() { return false; }
		public boolean gliding() { return gliding; }
		public double groundDistance() { return 0.0D; }
		public String chestItem() { return ""; }
		public int hotbarSlot(String itemId) { return -1; }
		public int selectedSlot() { return 0; }
		public void selectSlot(int slot) { }
		public boolean equipChestArmor() { return false; }
		public boolean maceInInventory() { return false; }
		public boolean maceToHotbar() { return false; }
		public boolean isSolid(int bx, int by, int bz) { return box.height(bx, by, bz) > 0.0D; }
		public double blockHeight(int bx, int by, int bz) { return box.height(bx, by, bz); }
		public int nearbyLiving(double radius) { return 0; }
		public boolean attackNearest(double radius) { return false; }
		public boolean containerOpen() { return false; }
	}

	private TerrainCheck() {
	}
}
