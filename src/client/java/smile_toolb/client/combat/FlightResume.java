package smile_toolb.client.combat;

/**
 * 一次猛击收工之后要不要把飞行装备穿回去（鞘翅 + 烟花火箭）。
 *
 * <h2>为什么需要这个判断</h2>
 * <p>砸人的动作是一条链：把鞘翅换成胸甲（打断滑翔）→ 切锤 → 落地砸 → 换回原武器。
 * 砸完站在地上时这条链没有问题，但实际战斗里常常是「砸完立刻被弹飞」——
 * 风暴、爆炸、击退、怪物的弹射技能都会把人抛起来。那一刻手上是剑、胸甲槽是护甲，
 * 飞不起来，只能看着自己往下掉。</p>
 *
 * <p>所以收工之后留一段观察窗口：只要人真的在空中、下方又没有可以砸的东西，
 * 就把飞行装备装回去——这正好是「无目标就恢复飞行」的那条规则。</p>
 *
 * <h2>四个条件缺一不可</h2>
 * <ul>
 *   <li><b>功能开着</b>：配置里可以关掉，关掉就退回「只换回武器」的老行为；</li>
 *   <li><b>不在方块上</b>：站着时穿鞘翅没有意义，窄道保护也正该管着；</li>
 *   <li><b>离地至少 {@link #MIN_DROP} 格</b>：普通跳跃顶点只有 1.25 格，
 *       用一个台阶的高度把它挡在外面，跑跳不会被当成「被弹飞」；</li>
 *   <li><b>下方没有可猛击的实体</b>：有东西可砸时该继续砸，不是该飞。</li>
 * </ul>
 *
 * <p>全是纯布尔与数值比较，可以直接用假数据跑自检（见 {@code tools/selftest-terrain}）。</p>
 */
public final class FlightResume {

	/**
	 * 允许恢复飞行的最小离地高度（格）。
	 *
	 * <p>与 {@link Terrain#AIR_ESCAPE} 同源：普通跳跃顶点 1.25 格，低于它的一定是
	 * 「刚起跳」；高过它说明人是被外力抛起来的。</p>
	 */
	public static final double MIN_DROP = Terrain.AIR_ESCAPE;

	private FlightResume() {
	}

	/**
	 * 收工观察期里「这一刻该不该把飞行装备装回去」。
	 *
	 * @param enabled     配置里的「无目标时恢复飞行」
	 * @param onGround    是否站在方块上
	 * @param drop        脚部离落脚面的高度（格）；悬空时是 {@link Double#MAX_VALUE}
	 * @param targetBelow 下方手长柱里有没有可以砸的实体
	 */
	public static boolean wanted(boolean enabled, boolean onGround, double drop, boolean targetBelow) {
		if (!enabled || onGround) {
			return false;
		}
		if (targetBelow) {
			return false;
		}
		return drop >= MIN_DROP;
	}
}
