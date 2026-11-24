package smile_toolb.client.combat;

import smile_libbox.api.world.World;

/**
 * 鞘翅安全判定：这个位置适合不适合穿鞘翅起飞 / 滑翔。
 *
 * <p>判定口径（与需求一致）：</p>
 * <ul>
 *   <li><b>净空不足</b>：脚部往上的连续空气高度 {@code <= 3}，穿鞘翅会直接撞头；</li>
 *   <li><b>落脚面太窄</b>：露天地面（头顶 64 格内无固体）的可站立宽度 {@code <= 3}，
 *       且两侧下方 5 格内找不到 5×5 的连续固体平台——摔下去没有落脚点。</li>
 * </ul>
 *
 * <p><b>1×1 竖井是例外</b>：四面是墙、往上是一条通道的 1 格宽竖井，照样判安全。
 * 「宽 1」有两种截然不同的地形——半空单方块小路飞出去就是坠落，竖井里往上是一条通道、
 * 可以顺着它爬升。两者的区别是<b>四面有没有墙</b>（见 {@link #enclosed}），
 * 只要四面夹住，就按「可以起飞」处理。</p>
 *
 * <p>四个基准场景的预期结果：半空单方块小路、地下 2 格高 1 格宽小道、地下 3×3 通道
 * → 危险；地下 4×4 通道 → 安全。每次判定翻转都会打一行 {@code 护甲锁：} 日志，
 * 方便对着场景走查。</p>
 *
 * <p>判定基准是「脚下最近那个面」而不是脚部坐标本身：人在空中（起跳、下落、滑翔）时
 * 脚下一片空气，若直接按脚部所在高度量宽度会恒为 1，把空旷平地也判成窄道。</p>
 *
 * <p>扫描结果同时带上「脚部离落脚面多高」（{@link Scan#drop}）：上层据此区分
 * 「在窄道里跑跳」（距离不到一次跳跃，照常守卫）与「真的从高处坠落」（让位，
 * 那枚自动换上的鞘翅可能是唯一的自救手段）。判据集中在 {@link Scan#inside()}。</p>
 */
final class Terrain {

	/** 头顶净空扫描上限：数到这么高还没挡住，就认为是露天。 */
	private static final int SKY_SCAN = 64;

	/** 可站立宽度的最大统计格数。 */
	private static final int WIDTH_SCAN = 8;

	/** 平台检测的边长：5×5 连续固体才算「大面积安全方块」。 */
	private static final int PLATFORM_SIZE = 5;

	/** 两侧的水平扫描距离（格）。 */
	private static final int SIDE_SCAN = 5;

	/**
	 * 往下找「真正会踩到的那个面」的最大深度（格）。
	 *
	 * <p>站在地上时它就在脚下一格；跳起来 / 飞行时脚下是空气，
	 * 这时要往下找落点，否则宽度会被算成 1 → 平地起跳也判成窄道。
	 * 超过这个深度还没地面，就当作「悬空」，宽度规则不适用。</p>
	 */
	private static final int FLOOR_SCAN = 12;

	/** 找不到落脚面时的哨兵值。 */
	private static final double NO_FLOOR = -1.0E9D;

	private Terrain() {
	}

	/**
	 * 一次跳跃（含跳跃提升）能达到的高度上限（格）。
	 *
	 * <p>离落脚面不超过这个距离，说明人还「在这一格里」——跑跳、擦地跳、跳起来马上落回去。
	 * 这时护甲锁照常生效，不能因为 {@code onGround} 是 false 就整体让位，
	 * 否则在 3 格高的通道里跑两下，模组换上的鞘翅就会被放行。</p>
	 */
	static final double HOP_HEIGHT = 3.0D;

	/** 头顶有实心天花板的最大净空（格）：这个高度以内跳起来也飞不出去。 */
	static final double CEILING_MAX = 3.0D;

	/** 「满格」的判定阈值：碰撞高度到这个数就算挡头（地毯 0.0625、雪层 0.125 都不算）。 */
	private static final double FULL = 0.99D;

	/**
	 * 竖井的最小竖直净空（格）：1×1 的竖直通道至少这么高，才算「能起飞」。
	 *
	 * <p>两格、三格高的 1 格宽地道不在此列——那种地方张开鞘翅就是撞头，
	 * 兜底仍由 {@link #CEILING_MAX} 那条守着。</p>
	 */
	static final double SHAFT_MIN = 4.0D;

	/** 连续量比较的容差（格）：3.0000001 不该算成「超过 3 格」。 */
	private static final double EPS = 1e-6D;

	/**
	 * 「人已经在空中」的离地高度（格）：达到它就允许穿鞘翅自救。
	 *
	 * <p>取 1.75 是因为普通跳跃顶点只有 1.25 格——低于它的离地一定是跳起来的那一刻，
	 * 那时穿上的鞘翅只会撞头，正是窄道保护要挡的东西。高过它说明人不是「跳起来」，
	 * 而是被击退/爆炸/别的模组抛了起来，此时鞘翅是唯一的救命手段。</p>
	 *
	 * <p>这条与 {@link #HOP_HEIGHT} 的区别：后者是「彻底离开这一格、完全不管」，
	 * 前者只是「允许把鞘翅穿上」。无天花板时两者都让位；有天花板时旧逻辑会一直锁到落地，
	 * 于是「被怪弹飞、想飞却切不出鞘翅」就发生在那两三格里。</p>
	 */
	static final double AIR_ESCAPE = 1.75D;

	/** 「站在面上」的容差（格）：雪层、地毯、台阶踩上去时脚部坐标会浮起不到一格。 */
	static final double STAND_SLACK = 0.6D;

	/** 一次扫描的结果。 */
	static final class Scan {
		/** 头顶可用净空（格，连续值）：落脚面到天花板底面的距离。 */
		final double clearance;
		/** 地面宽度（能站住的格数）。 */
		final int width;
		final boolean openSky;
		final boolean platform;
		/** 平台规则是否真的算过（已由净空/宽度判定危险时就不算了）。 */
		final boolean platformChecked;
		/** 脚部到落脚面的高度差（格）；悬空时是 {@link Double#MAX_VALUE}。 */
		final double drop;
		/** 扫描这一刻是不是正在滑翔。 */
		final boolean gliding;
		/** 是不是 1×1 的竖直通道（四面夹住、往上够高）：这种地方允许起飞。 */
		final boolean shaft;
		final boolean dangerous;

		Scan(double clearance, int width, boolean openSky, boolean platform,
				boolean platformChecked, double drop, boolean gliding, boolean shaft) {
			this.clearance = clearance;
			this.width = width;
			this.openSky = openSky;
			this.platform = platform;
			this.platformChecked = platformChecked;
			this.drop = drop;
			this.gliding = gliding;
			this.shaft = shaft;
			this.dangerous = !shaft && (clearance <= CEILING_MAX + EPS
					|| (openSky && width <= 3 && !platform));
		}

		/**
		 * 人是不是还待在「这个危险格子」里，也就是「空中还要不要继续锁甲」。
		 *
		 * <p>四层：</p>
		 * <ul>
		 *   <li><b>正在滑翔</b> → 一直锁。滑翔不是坠落：贴地低飞（离地五六格）本来就是
		 *       躲怪、避地形的常态飞法，按「离地太高」让位等于把整段飞行交给别的模组处理；</li>
		 *   <li><b>离落脚面超过一次跳跃</b> → 让位。那枚自动换上的鞘翅可能是摔死前唯一的
		 *       自救手段；</li>
		 *   <li><b>已经在空中</b>（离地 ≥ {@link #AIR_ESCAPE}，比跳跃顶点高）→ 让位。
		 *       有天花板的地方旧逻辑会一直锁到落地，于是「被怪弹飞、想飞却切不出鞘翅」
		 *       正好发生在离地两三格那一段；</li>
		 *   <li><b>头顶有天花板（净空 ≤ 3）</b> → 跳起来也飞不出去，照常锁；
		 *       <b>露天的窄道</b> → 只在真的站在面上时才锁。</li>
		 * </ul>
		 *
		 * <p>最后一条是「刚起飞就被拽下来」的根因修复：露天窄道上玩家起跳想滑翔，脚才离地
		 * 一两格，若照样把刚穿上的鞘翅扒掉，体感就是把「想飞」判成「违规」，人直接被拉下来。</p>
		 *
		 * <p>1×1 竖井（{@link #shaft}）根本不会走到这里：它在扫描阶段就判成安全，
		 * 竖井里张开鞘翅往上飞是正常玩法，锁了等于把矿洞里的直井封死。</p>
		 */
		boolean inside() {
			if (!dangerous) {
				return false;
			}
			if (gliding) {
				return true;
			}
			if (drop > HOP_HEIGHT) {
				return false;
			}
			if (drop >= AIR_ESCAPE) {
				// 已经在空中（不是跳起来的那一刻）：被击退、被弹飞、被别的模组抛起来时，
				// 鞘翅是唯一能救命的装备，这一刻拦着等于让人摔死。
				return false;
			}
			return clearance <= CEILING_MAX + EPS || drop <= STAND_SLACK;
		}

		String describe() {
			return "净空=" + String.format("%.1f", clearance)
					+ "，地面宽=" + width + "，露天=" + (openSky ? "是" : "否")
					+ "，安全平台=" + (platformChecked ? (platform ? "有" : "无") : "-")
					+ "，离地=" + (drop == Double.MAX_VALUE ? "悬空" : String.format("%.1f", drop))
					+ (shaft ? "，竖井" : "")
					+ (gliding ? "，滑翔中" : "");
		}
	}

	static Scan scan() {
		double[] pos = World.pos();
		boolean gliding = World.gliding();
		if (pos.length < 3) {
			return new Scan(SKY_SCAN, WIDTH_SCAN, true, true, false, Double.MAX_VALUE, gliding, false);
		}
		int x = (int) Math.floor(pos[0]);
		int z = (int) Math.floor(pos[2]);

		// 落脚面：脚下第一个「有碰撞」的方块顶面，用连续坐标算。
		// 地毯 0.0625、雪层 0.125、台阶 0.5 都是真实的脚下高度 —— 早先按整格判，
		// 站在双地毯上会把脚下方块当成「天花板」，净空被吃掉一格甚至两格，
		// 四格高的走廊量成一到三格，于是站着不动也被判成窄道（日志里 净空=1、离地=0.1）。
		double surface = surfaceY(x, pos[1], z);
		if (surface == NO_FLOOR) {
			// 脚下 FLOOR_SCAN 格内没有落脚面：悬空，宽度与平台都无从谈起
			return new Scan(SKY_SCAN, WIDTH_SCAN, true, true, false, Double.MAX_VALUE, gliding, false);
		}
		// 站立格 = 落脚面所在的那一格：满格地板顶面 45.0 与地毯顶面 45.0625 都落在 45 格。
		// 以它为准往上看天花板，人跳起来（脚部浮到上一格）时净空也不会跟着缩水。
		int standY = (int) Math.floor(surface);
		double ceiling = ceilingY(x, standY, z);
		double clearance = Math.max(0.0D, ceiling - surface);
		boolean openSky = ceiling - standY >= SKY_SCAN;

		int width = Math.min(run(x, standY, z, 1, 0) + run(x, standY, z, -1, 0) + 1,
				run(x, standY, z, 0, 1) + run(x, standY, z, 0, -1) + 1);
		// 1×1 竖井：宽 1、四面夹住、往上够高 —— 那是竖直通道，不是「飞出去就掉」的窄道。
		// 半空单方块小路同样是宽 1，但四面临空，所以必须靠「四面有墙」把两者分开。
		boolean shaft = width <= 1 && clearance >= SHAFT_MIN - EPS && enclosed(x, standY, z);
		// 平台规则只在「净空够、但宽度只有 3 格以内」时才可能左右结论，别的时候不算：
		// 这是每 tick 都跑的判定，省掉最贵的那段扫描。竖井已经有结论了，也不用跑。
		boolean needPlatform = openSky && width <= 3 && !shaft;
		boolean platform = needPlatform && hasPlatform(x, standY, z);
		double drop = Math.max(0.0D, pos[1] - surface);
		return new Scan(clearance, width, openSky, platform, needPlatform, drop, gliding, shaft);
	}

	/**
	 * 四面是不是都夹着「满格墙」——1×1 竖直通道的截面。
	 *
	 * <p>宽 1 有两种地形：<b>半空单方块小路</b>飞出去就是坠落，
	 * <b>1×1 竖井</b>往上是一条通道、可以顺着它爬升。区别就在这里：
	 * 竖井四面是墙，小路四面临空。</p>
	 *
	 * <p>脚部与头部的两格都得挡：只有半截矮墙（台阶、地毯）算不上井壁。</p>
	 */
	private static boolean enclosed(int x, int standY, int z) {
		return isWall(x + 1, standY, z) && isWall(x - 1, standY, z)
				&& isWall(x, standY, z + 1) && isWall(x, standY, z - 1);
	}

	private static boolean isWall(int x, int y, int z) {
		return World.blockHeight(x, y, z) >= FULL && World.blockHeight(x, y + 1, z) >= FULL;
	}

	/**
	 * 脚下方块的上表面高度（连续坐标）；找不到返回 {@link #NO_FLOOR}。
	 *
	 * <p>薄的（地毯、雪层、台阶）按各自碰撞高度算，所以站在双地毯上得到的是
	 * {@code 45.0625} 而不是「45 格整」。这就是净空不再被薄方块吃掉一格的原因。</p>
	 */
	private static double surfaceY(int x, double feetY, int z) {
		int cell = (int) Math.floor(feetY + 0.001D);
		for (int dy = 0; dy <= FLOOR_SCAN; dy++) {
			double h = World.blockHeight(x, cell - dy, z);
			if (h > 0.0D) {
				return (cell - dy) + h;
			}
		}
		return NO_FLOOR;
	}

	/**
	 * 从站立格往上，第一个「满格」方块的底面 y；{@value #SKY_SCAN} 格内没有就返回
	 * {@code standY + SKY_SCAN}（当作露天）。
	 *
	 * <p>只认满格：地毯、雪层、台阶这类碰撞高度不到一格的方块不挡头，
	 * 否则站在铺了地毯的走廊里，自己脚下那一格会被当成天花板。</p>
	 */
	private static double ceilingY(int x, int standY, int z) {
		for (int dy = 0; dy < SKY_SCAN; dy++) {
			if (World.blockHeight(x, standY + dy, z) >= FULL) {
				return standY + dy;
			}
		}
		return standY + SKY_SCAN;
	}

	/** 沿一个方向能站住多少格（有地面、且脚下两格能过人）。 */
	private static int run(int x, int standY, int z, int dx, int dz) {
		int n = 0;
		for (int i = 1; i <= WIDTH_SCAN; i++) {
			int bx = x + dx * i;
			int bz = z + dz * i;
			boolean floor = World.blockHeight(bx, standY - 1, bz) > 0.0D;
			boolean clearLow = World.blockHeight(bx, standY, bz) < FULL;
			boolean clearHigh = World.blockHeight(bx, standY + 1, bz) < FULL;
			if (!floor || !clearLow || !clearHigh) {
				break;
			}
			n++;
		}
		return n;
	}

	/** 两侧下方 5 格内有没有 5×5 的连续固体平面。 */
	private static boolean hasPlatform(int x, int footY, int z) {
		for (int side = -1; side <= 1; side += 2) {
			for (int i = 2; i <= SIDE_SCAN; i++) {
				int cx = x + side * i;
				for (int dy = 1; dy <= 5; dy++) {
					if (isPlatform(cx, footY - dy, z)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/** 以 (cx, y, cz) 为中心，{@code PLATFORM_SIZE}² 是否全是固体（只认同一层）。 */
	private static boolean isPlatform(int cx, int y, int cz) {
		int half = PLATFORM_SIZE / 2;
		for (int dx = -half; dx <= half; dx++) {
			for (int dz = -half; dz <= half; dz++) {
				if (!World.isSolid(cx + dx, y, cz + dz)) {
					return false;
				}
			}
		}
		return true;
	}
}
