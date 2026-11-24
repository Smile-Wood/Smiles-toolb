package smile_toolb.client.hud;

import smile_libbox.api.ui.Ui;

/**
 * 快捷栏上方那条「上下文条」的状态机。
 *
 * <p>1.21.9 起这一条不是固定的经验条了，原版会在经验条 / 玩家追踪条 / 跳跃蓄力条之间切换。
 * 本类负责两件事，都刻意与游戏版本解耦：</p>
 *
 * <ul>
 *   <li>跟踪跳跃键的按下与松开，给蓄力条算一个 2.5 秒的“松手后继续显示”窗口；</li>
 *   <li>通过反射拿到 {@code Gui.ContextualInfo} 的四个常量——这个枚举是包私有的，
 *       从 {@code smile_toolb} 包里没法直接引用它的类型，只能按实例用。</li>
 * </ul>
 *
 * <p>反射只在第一次调用时做一次（遍历 {@code Gui} 的嵌套类找那个恰好有 4 个常量的枚举），
 * 之后就是普通数组访问。正式环境里枚举常量的名字是 intermediary 名（{@code field_xxxxx}），
 * 名字对不上时退回按声明顺序取，顺序为 EMPTY / EXPERIENCE / LOCATOR / JUMPABLE_VEHICLE。</p>
 */
public final class ContextualBarController {

	public static final int EMPTY = 0;
	public static final int EXPERIENCE = 1;
	public static final int LOCATOR = 2;
	public static final int JUMPABLE_VEHICLE = 3;

	/** 松开跳跃键后，蓄力条继续显示的时长。 */
	private static final long JUMP_GRACE_MS = 2500L;

	private static final ContextualBarController INSTANCE = new ContextualBarController();

	/** {@code Gui$ContextualInfo} 的四个常量，下标与上面的常量一致。 */
	private static Object[] states;

	private boolean jumpWasDown;
	private long jumpReleasedAt = Long.MIN_VALUE;

	private ContextualBarController() {
	}

	public static ContextualBarController get() {
		return INSTANCE;
	}

	/** 客户端 tick 调用（20 Hz）：只记录跳跃键的按下 / 松开，用于计算 2.5 秒宽限窗口。 */
	public void tick() {
		boolean down = Ui.jumpDown();
		if (down) {
			jumpReleasedAt = Long.MIN_VALUE;
		} else if (this.jumpWasDown) {
			this.jumpReleasedAt = System.currentTimeMillis();
		}
		this.jumpWasDown = down;
	}

	/** 蓄力条是否该显示：按住跳跃键，或者松开还没超过 2.5 秒。 */
	public boolean jumpBarActive() {
		if (Ui.jumpDown()) {
			return true;
		}
		return this.jumpReleasedAt != Long.MIN_VALUE
				&& System.currentTimeMillis() - this.jumpReleasedAt < JUMP_GRACE_MS;
	}

	/**
	 * 取 {@code Gui$ContextualInfo} 的常量数组（顺序固定）。
	 *
	 * @return 四个常量；拿不到时返回 {@code null}，调用方应放弃干预、退回原版行为
	 */
	public static Object[] states() {
		// 枚举常量由库按命名空间反射取得（正式环境常量名可能是 intermediary 名）。
		return Ui.contextualStates();
	}
}
