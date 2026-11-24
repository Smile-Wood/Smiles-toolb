package smile_toolb.client.combat;

/**
 * 「手里这一格是怎么变成烟花的」的纯函数判定。
 *
 * <p>和 {@link ChestSwap} 一样单独拎出来，是为了能脱离 Minecraft 跑自检
 * （{@code tools/selftest-terrain}）——这段判据决定了「模组自动切换」与「玩家自己选择」
 * 能不能分开，判错了就是把人手里的武器抢走。</p>
 *
 * <h2>两种完全不同的变化</h2>
 * <ul>
 *   <li><b>换槽</b>：选中的格子变了。玩家滚轮、按 1~9 都是这样，模组切换武器也是——分不出
 *       是谁按的，但那是「有人在选别的格子」，属于正常操作，不该干预；</li>
 *   <li><b>同槽换物</b>：选中的格子没变，格子里的东西却变成了烟花。玩家没有任何按键能做
 *       出这种变化（切槽只会改选中格），所以只能是别的模组把火箭<b>换进了当前这一格</b>——
 *       tweakeroo 的「自动切换烟花火箭」正是这种形态。</li>
 * </ul>
 *
 * <p>这就是「守卫中被模组切烟花了就换回去」与「不许烟花出现在手里」的区别所在。</p>
 */
final class HandSwap {

	/** 烟花火箭的注册名。 */
	static final String FIREWORK = "minecraft:firework_rocket";

	private HandSwap() {
	}

	/**
	 * 这一 tick 手上是不是「被别的模组换进来的烟花」。
	 *
	 * @param hand     这一 tick 主手的物品 id
	 * @param lastHand 上一 tick 主手的物品 id
	 * @param slot     这一 tick 选中的热键栏槽位（0~8）
	 * @param lastSlot 上一 tick 选中的热键栏槽位
	 */
	static boolean swappedIntoHand(String hand, String lastHand, int slot, int lastSlot) {
		return FIREWORK.equals(hand) && slot == lastSlot && !FIREWORK.equals(lastHand);
	}

	/**
	 * 重锤猛击收工时的准入判定：这一格现在由谁说了算。
	 *
	 * <p>正常情况手上一直是锤，所以「手上还是这把锤」就等于「这一格还是我在管」；
	 * 玩家自己中途换了别的东西，就不该抢他的槽。</p>
	 *
	 * <p>但「锤被别的模组换成了烟花」这种异常，从外观上和「玩家自己换了烟花」完全一样，
	 * 只看当前物品分不开。所以另带一个「本次流程里发现过锤被换走」的标志：
	 * 置上之后即使手上不是锤也照样收回原武器——这就是「落地后人事不知地攥着烟花」的修复。</p>
	 *
	 * @param now      手上现在的物品 id（空串 = 空手）
	 * @param mace     重锤的注册名
	 * @param lost     本次流程中是否发现过重锤被换走
	 */
	static boolean shouldRestore(String now, String mace, boolean lost) {
		return mace.equals(now) || lost;
	}
}
