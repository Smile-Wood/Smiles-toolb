package smile_toolb.client.combat;

/**
 * 把「到服务端的延迟」换算成「要提前几 tick 发包」。
 *
 * <h2>为什么需要</h2>
 * <p>攻击包在路上要飞半程。低延迟时这一程可以忽略，本地还剩 1 tick 落地再发，服务端
 * 处理时人还在空中，猛击判定成立；延迟到 200ms 这一程就是 2 tick，包到的时候本地早已
 * 落地，服务端那边看到的是「已经站在地上的人」，猛击不成立，白锁一次甲、白换一次锤。</p>
 *
 * <p>所以提前量要跟着延迟走，而不是写死一个数：固定 2 tick 对本地玩家是浪费
 * （提前太多会少吃一点坠落距离），对 200ms 玩家又不够。</p>
 *
 * <h2>公式</h2>
 * <p>往返 {@code ping} 毫秒 = 单程 {@code ping / 2} 毫秒 = {@code ping / 100} tick，
 * 向上取整后再加 <b>1 tick 缓冲</b>：客户端预测本身按 tick 离散、发包落在 tick 边界、
 * 服务端对齐自己的 tick，这半拍到一拍的量不能省。</p>
 *
 * <pre>
 *   lead = clamp(ceil(ping / 100) + 1, 1, 8)
 *
 *   0 ms   → 1 tick      100 ms → 2 tick
 *   200 ms → 3 tick      400 ms → 5 tick
 * </pre>
 *
 * <p>上限 8 tick（400ms 单程）之后不再增加：再提前就是「人还在半空很高就开始挥」，
 * 坠落距离被砍掉一大截，伤害反而降。200ms 玩家落在 3 tick，正是这条曲线的常用段。</p>
 *
 * <h2>边界情况</h2>
 * <ul>
 *   <li>单人本地游戏读到的通常是 {@code 0}，按无延迟算（1 tick），手动值仍然兜底；</li>
 *   <li>tab 列表每秒才刷新一次，读数会在几十毫秒内上下跳，所以这里做指数平滑
 *       （新样本占 1/8），避免提前量每 tick 变一个数；</li>
 *   <li>平滑是单向偏保守的：延迟突然从 300 掉到 30 时，值要几秒才降下来，
 *       这段时间多提前一点无害；反过来突然升高时也能较快追上。</li>
 * </ul>
 */
public final class LeadEstimate {

	/** 一 tick 的毫秒数。 */
	public static final int TICK_MS = 50;

	/** 提前量下限（tick）：本地还剩 1 tick 才落地，至少要给发包留出这一 tick。 */
	public static final int MIN_LEAD = 1;

	/** 提前量上限（tick）：再早就是「人还在半空很高就挥」，坠落距离损失大于收益。 */
	public static final int MAX_LEAD = 8;

	/** 采样上限（毫秒）：超过这个值当作异常读数，避免极端卡顿时把提前量顶到上限。 */
	public static final int MAX_PING = 4000;

	/** 平滑权重分母：新样本占 {@code 1/8}。 */
	private static final int SMOOTH_DIVISOR = 8;

	private LeadEstimate() {
	}

	/**
	 * 指数平滑一次延迟采样。
	 *
	 * <p>整数除法会吃掉 1 毫秒级的抖动，这正好——那本来就是要压掉的东西。
	 * 但差值不为零时强制走 1，否则延迟从 300 掉到 100 会卡在 299 不动。</p>
	 *
	 * @param previous 上一次的平滑值；{@code <= 0} 表示还没有样本
	 * @param sample   本次采样（毫秒）；负数视为无效样本，原样返回
	 */
	public static int smooth(int previous, int sample) {
		if (sample < 0) {
			return previous;
		}
		int capped = Math.min(sample, MAX_PING);
		if (previous <= 0) {
			return capped;
		}
		int delta = capped - previous;
		if (delta == 0) {
			return previous;
		}
		int step = delta / SMOOTH_DIVISOR;
		if (step == 0) {
			step = delta > 0 ? 1 : -1;
		}
		return previous + step;
	}

	/** 纯按延迟算的提前量（tick），已夹在 {@link #MIN_LEAD} ~ {@link #MAX_LEAD}。 */
	public static int autoTicks(int pingMs) {
		if (pingMs <= 0) {
			return MIN_LEAD;
		}
		// 单程 tick 数 = ping / 100，向上取整；再加 1 tick 缓冲
		int oneWay = (Math.min(pingMs, MAX_PING) + 99) / 100;
		return clamp(oneWay + 1);
	}

	/**
	 * 最终采用的提前量。
	 *
	 * <p>自动模式下取 {@code max(手动值, 自动值)}：手动值是用户明确设的下限，
	 * 自动值只会把它顶高，不会把用户设的 2 tick 压回 1。</p>
	 *
	 * @param manualTicks 配置里的手动提前量
	 * @param pingMs      平滑后的往返延迟
	 * @param auto        是否开启自动计算
	 */
	public static int choose(int manualTicks, int pingMs, boolean auto) {
		int manual = clamp(manualTicks);
		if (!auto) {
			return manual;
		}
		return Math.max(manual, autoTicks(pingMs));
	}

	/** 夹到 {@link #MIN_LEAD} ~ {@link #MAX_LEAD}。 */
	public static int clamp(int ticks) {
		return Math.max(MIN_LEAD, Math.min(MAX_LEAD, ticks));
	}
}
