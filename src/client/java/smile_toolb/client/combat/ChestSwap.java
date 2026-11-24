package smile_toolb.client.combat;

import smile_libbox.api.world.Packets;

/**
 * 「这一下容器操作会把鞘翅怎么着」的纯函数判定。
 *
 * <p>单独拎出来是为了能脱离 Minecraft 跑自检（{@code tools/selftest-terrain}）——
 * 这段判据是整个护甲锁最容易漏的地方，必须有用例盯着。</p>
 *
 * <h2>为什么不能只看被点槽位里的物品</h2>
 * <p>换装的实际形态是「先点背包里的鞘翅拿起、再点胸甲槽放下」。第二步点的是胸甲槽，
 * 那一刻槽里还是旧胸甲，{@code itemId} 是胸甲——光看它认不出「这一下要把鞘翅装进去」。
 * 所以要看光标 {@code carriedId}，以及数字键交换时的 {@code hotbarId}。</p>
 */
final class ChestSwap {

	/** 与鞘翅自动换装对照的物品 id。 */
	static final String ELYTRA = "minecraft:elytra";

	private ChestSwap() {
	}

	/**
	 * 这一下点击会不会<b>把鞘翅装进胸甲槽</b>（地形危险时要拦）。
	 *
	 * <p>三种形态：</p>
	 * <ul>
	 *   <li>手上拿着鞘翅点胸甲槽（绝大多数自动换装模组的做法）；</li>
	 *   <li>按数字键把热键栏里的鞘翅换到胸甲槽（{@code SWAP}，不走光标）；</li>
	 *   <li>shift 点击背包里的鞘翅（{@code QUICK_MOVE}，原版优先送进空的 / 可替换的胸甲槽）。</li>
	 * </ul>
	 */
	static boolean putsElytraInChest(Packets.Action action) {
		if (action.chestSlot) {
			return ELYTRA.equals(action.carriedId) || ELYTRA.equals(action.hotbarId);
		}
		return ELYTRA.equals(action.itemId) && "QUICK_MOVE".equals(action.clickType);
	}

	/**
	 * 这一下点击会不会<b>把鞘翅从胸甲槽拿走</b>（滑翔时要拦）。
	 *
	 * <p>槽里现在是鞘翅、光标上拿的不是鞘翅，就说明这一下要把它换掉。
	 * 光标上（或数字键那格）也是鞘翅的不算——翅膀还在。</p>
	 */
	static boolean takesElytraOut(Packets.Action action) {
		return action.chestSlot && ELYTRA.equals(action.itemId)
				&& !ELYTRA.equals(action.carriedId)
				&& !ELYTRA.equals(action.hotbarId);
	}
}
