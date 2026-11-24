package smile_toolb.client.combat;

/**
 * 滑翔中「下砸意图」的保持时长判定。
 *
 * <h2>为什么不是一个固定值</h2>
 * <p>滑翔时按住潜行键是原版自带的<b>加速下降</b>，飞过任何生物上方都可能按着它。
 * 只看「按了没有」会把路过当成要砸，鞘翅当场被摘、人被拽下来；只看「低头多少度」
 * 又要求玩家先摆姿势——而按 shift 的人往往正平视着往前飞，于是怎么按都不触发。</p>
 *
 * <p>所以拆成两条通路，按视角决定「要按多久才算数」：</p>
 * <ul>
 *   <li><b>低头盯着落点</b>（俯视 ≥ {@value #DIVE_PITCH}°）：意图明确，
 *       {@value #HOLD_QUICK} tick 就动手，鼠标扫过不会误触；</li>
 *   <li><b>平视飞着按 shift</b>：按住本身就是「想快速下降」，
 *       连续 {@value #HOLD_FLAT} tick（约 0.4 秒）后同样算数。松一下重新计数，
 *       所以贴着低空飞时点一下加速不会被拽下来。</li>
 * </ul>
 *
 * <p>纯 Java、只吃数字，可以脱机跑自检。</p>
 */
public final class GlideDive {

	/** 视为「盯着落点」的俯视角（度）。 */
	public static final double DIVE_PITCH = 60.0D;

	/** 低头时的保持时长（tick）。 */
	public static final int HOLD_QUICK = 3;

	/** 平视时的保持时长（tick）。 */
	public static final int HOLD_FLAT = 8;

	private GlideDive() {
	}

	/**
	 * 当前视角下需要连续按住多少 tick 才允许下砸。
	 *
	 * @param pitch 视线俯仰角，{@code -90} 向上、{@code 0} 平视、{@code +90} 向下
	 */
	public static int ticksNeeded(double pitch) {
		return pitch >= DIVE_PITCH ? HOLD_QUICK : HOLD_FLAT;
	}

	/** 这一 tick 条件仍然成立：累计 +1。 */
	public static int advance(int hold) {
		return hold + 1;
	}

	/**
	 * 这一 tick 条件不成立时怎么处理已有的计数。
	 *
	 * <p>滑翔中目标会在几 tick 内滑出手长柱（水平速度 0.4~0.7 格/tick，柱子两侧各手长宽），
	 * 当场清零会让「按住 shift 一路飞过去」这段永远攒不满——这正是「按着也不下落」的第二个成因。
	 * 所以滑翔中只回退一步，连续丢够久自然归零；下坠流程里条件不成立就是真的不成立，直接清空。</p>
	 */
	public static int decay(int hold, boolean gliding) {
		return gliding ? Math.max(0, hold - 1) : 0;
	}
}
