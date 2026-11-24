package smile_toolb.client.combat;

import smile_libbox.api.world.EntityView;

/**
 * 重锤猛击的落点判定。
 *
 * <h2>两条规则（任一命中即算「下方有实体」）</h2>
 * <ol>
 *   <li><b>手长柱</b>：以玩家为中心、水平半宽 = {@code reach}（原版手长 3 格），
 *       从脚下一直延伸到当前脚下的地面。柱子里碰到任何实体的碰撞箱就算命中——
 *       这就是「玩家周围的手长向下延申」。</li>
 *   <li><b>运动预判</b>：在 {@code 2 × reach} 范围内，把每个实体的当前速度外推
 *       「从当前位置立刻落到地面需要的 tick 数」；外推后的位置只要落进
 *       「玩家落点 ± 手长」的那一段，也算命中。人在下坠、船在水面漂、怪在走，
 *       都会因此被算进来。</li>
 * </ol>
 *
 * <p>全部是纯 Java 数学，入参只有 {@code double[]} 与 {@link EntityView}，
 * 所以可以直接用构造出来的假实体跑自检（见 {@code tools/selftest-terrain}）。</p>
 */
public final class SlamTargets {

	/** 原版每 tick 的重力加速度（格/tick²）。 */
	public static final double GRAVITY = 0.08D;

	/** 原版每 tick 的速度阻尼。 */
	public static final double DRAG = 0.98D;

	/** 外推的最大 tick 数；超过就是没地面可落，当作不算。 */
	public static final int MAX_TICKS = 200;

	/** 玩家碰撞箱高度，用来给手长柱封顶。 */
	public static final double PLAYER_HEIGHT = 1.8D;

	/** 命中原因。 */
	public enum Rule {
		/** 没命中。 */
		NONE,
		/** 手长柱里有实体。 */
		COLUMN,
		/** 两倍手长内，实体的运动预判与落点重叠。 */
		PREDICTED
	}

	/** 一次判定的结果。 */
	public static final class Hit {
		public final Rule rule;
		public final EntityView entity;
		/** 从当前位置落到地面需要的 tick 数；算不出来是 {@code -1}。 */
		public final int ticksToImpact;

		private Hit(Rule rule, EntityView entity, int ticksToImpact) {
			this.rule = rule;
			this.entity = entity;
			this.ticksToImpact = ticksToImpact;
		}

		public boolean found() {
			return this.rule != Rule.NONE;
		}

		/** 日志用的一行说明。 */
		public String describe() {
			if (!found()) {
				return "无";
			}
			String how = this.rule == Rule.COLUMN ? "手长柱内" : "运动预判命中";
			return how + " " + this.entity.id
					+ String.format("（%d tick 后落地）", this.ticksToImpact);
		}
	}

	private static final Hit NONE = new Hit(Rule.NONE, null, -1);

	private SlamTargets() {
	}

	/**
	 * 从当前状态立刻松手直落，落到地面要几 tick。
	 *
	 * <p>照着原版的 {@code motion.y -= 0.08; motion.y *= 0.98} 递推，
	 * 不解析求解——阻尼让解析式很丑，而逐 tick 累加只有几十次循环。</p>
	 *
	 * @param verticalMotion 当前竖直速度（向下为负）
	 * @param groundDistance 到地面顶面的高度（格）
	 * @return tick 数；落不到地面或本来就在地上返回 {@code -1}
	 */
	public static int ticksToImpact(double verticalMotion, double groundDistance) {
		if (!(groundDistance > 0.0D) || groundDistance == Double.MAX_VALUE) {
			return -1;
		}
		double y = 0.0D;
		double v = verticalMotion;
		for (int t = 1; t <= MAX_TICKS; t++) {
			v = (v - GRAVITY) * DRAG;
			y += v;
			if (-y >= groundDistance) {
				return t;
			}
		}
		return -1;
	}

	/**
	 * 扫描实体，找「下方有东西可以砸」。
	 *
	 * @param pos           玩家脚部坐标 {@code {x, y, z}}
	 * @param motion        玩家速度 {@code {dx, dy, dz}}
	 * @param groundDistance 到脚下地面的高度
	 * @param reach         手长（格），2 倍就是预判半径
	 * @param entities      附近实体快照
	 */
	public static Hit find(double[] pos, double[] motion, double groundDistance,
			double reach, EntityView[] entities) {
		if (pos == null || motion == null || entities == null
				|| pos.length < 3 || motion.length < 3) {
			return NONE;
		}
		if (!(reach > 0.0D) || !(groundDistance > 0.0D)
				|| groundDistance == Double.MAX_VALUE) {
			return NONE;
		}
		double px = pos[0];
		double py = pos[1];
		double pz = pos[2];
		double bottom = py - groundDistance;
		double top = py + PLAYER_HEIGHT;
		int ticks = ticksToImpact(motion[1], groundDistance);

		// 规则一：手长柱
		for (EntityView e : entities) {
			if (e == null) {
				continue;
			}
			if (Math.abs(e.x - px) <= reach + e.halfWidth()
					&& Math.abs(e.z - pz) <= reach + e.halfWidth()
					&& e.y + e.height >= bottom
					&& e.y <= top) {
				return new Hit(Rule.COLUMN, e, ticks);
			}
		}

		// 规则二：两倍手长内的运动预判
		if (ticks > 0) {
			double horizon = reach * 2.0D;
			for (EntityView e : entities) {
				if (e == null) {
					continue;
				}
				double hw = e.halfWidth();
				// 粗筛：当前就在两倍手长之外、且速度也带不回来的，直接跳过
				if (Math.abs(e.x - px) > horizon + hw + Math.abs(e.dx) * ticks
						|| Math.abs(e.z - pz) > horizon + hw + Math.abs(e.dz) * ticks) {
					continue;
				}
				double tx = e.x + e.dx * ticks;
				double ty = e.y + e.dy * ticks;
				double tz = e.z + e.dz * ticks;
				if (Math.abs(tx - px) <= reach + hw
						&& Math.abs(tz - pz) <= reach + hw
						&& ty + e.height >= bottom - reach
						&& ty <= top + reach) {
					return new Hit(Rule.PREDICTED, e, ticks);
				}
			}
		}
		return NONE;
	}
}
