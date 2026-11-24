package smile_toolb.client.compat;

import smile_libbox.api.event.BarEvents;
import smile_libbox.api.ui.Ui;
import smile_toolb.client.config.SmileConfig;
import smile_toolb.client.hud.ContextualBarController;

/**
 * 快捷栏上方「上下文条」的业务逻辑。
 *
 * <p>原实现是 {@code @Mixin(Gui)} 注入 1.21.9 才有的 {@code nextContextualInfoState()}，
 * 方法体里还直接调用了 {@code jumpableVehicle()} / {@code getWaypointManager()} 这些
 * 低版本不存在的 API，只能整体条件编译掉。现在注入点由库按版本编译，本类只接收
 * 原版枚举实例并返回替换值；高版本独有的 API 改成按形状反射，低版本自动降级。</p>
 */
public final class BarSelection {

	private BarSelection() {
	}

	public static void register() {
		BarEvents.onContextualInfo(BarSelection::choose);
	}

	static Object choose(Object vanilla) {
		SmileConfig config = SmileConfig.get();
		if (!config.forceExperienceBar) {
			return null;
		}
		Object[] states = ContextualBarController.states();
		if (states == null) {
			// 低版本没有这套枚举，整段逻辑不生效
			return null;
		}
		if (!config.advancedBarControl) {
			// 旧行为：定位条让位给经验条，跳跃条 / 空条保持原版
			return vanilla == states[ContextualBarController.LOCATOR]
					? states[ContextualBarController.EXPERIENCE] : null;
		}

		boolean hasExperience = Ui.hasExperience();
		boolean hasWaypoints = Ui.hasWaypoints();
		boolean playerListHeld = Ui.playerListDown();

		// 跳跃蓄力条只能沿用原版判定，绝不主动制造。
		// 26.2 的 Hud 一看到 JUMPABLE_VEHICLE 就 new JumpableVehicleBar(...)，
		// 构造函数里对 LocalPlayer.jumpableVehicle() 做 Objects.requireNonNull。
		// 椅子 / 船 / 矿车只是「有载具」，这个值恒为 null → Render Frame NPE 崩溃。
		// 原版自己算出的 vanilla 只在真正可跳跃坐骑上才是这个状态，再叠一道
		// Ui.hasJumpableVehicle() 做双保险；库侧 BarEvents.jumpStateAllowed 还有第三道。
		if (vanilla == states[ContextualBarController.JUMPABLE_VEHICLE]
				&& Ui.hasJumpableVehicle()) {
			return vanilla;
		}
		if (playerListHeld && hasWaypoints) {
			return states[ContextualBarController.LOCATOR];
		}
		if (hasExperience) {
			return states[ContextualBarController.EXPERIENCE];
		}
		return hasWaypoints ? states[ContextualBarController.LOCATOR]
				: states[ContextualBarController.EMPTY];
	}
}
