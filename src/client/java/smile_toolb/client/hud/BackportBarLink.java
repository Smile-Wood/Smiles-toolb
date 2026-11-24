package smile_toolb.client.hud;

import org.slf4j.Logger;
import smile_libbox.api.link.ModLinks;
import smile_libbox.api.ui.Ui;
import smile_toolb.SmileSToolb;
import smile_toolb.client.config.SmileConfig;

/**
 * VanillaBackport 定位条的<b>业务回调</b>：从原来的 mixin 类里搬出来，注册到库的
 * {@link ModLinks} 总线。
 *
 * <p>这样 {@link smile_toolb.client.mixin.VanillaBackportBarMixin} 只剩一次派发，
 * 注入类不 import 配置 / 桥接 / 控制器，改规则不必再动 mixin 描述符；业务逻辑
 * 是普通 Java 类，可以放进使用方的单 jar，跨 MC 命名空间代际也不需要重编。</p>
 *
 * <p>映射规则与高版本状态条一致：</p>
 * <ul>
 *   <li>「使用旧版经验条动作」关闭：不干预，完全按 backport 自己的规则；</li>
 *   <li>开启、高级模式关闭：恒跳过定位条，固定显示经验条；</li>
 *   <li>再开启「高级状态条控制」：默认经验条；按住玩家列表键（默认 Tab）显示定位条；
 *       骑乘可跳跃坐骑时按住跳跃键、以及松开后 2.5 秒内让蓄力条露出来。</li>
 * </ul>
 */
public final class BackportBarLink {

	/** 必须与 {@code VanillaBackportBarMixin} 里的 HOOK 字面量一致。 */
	public static final String HOOK = "vanillabackport.locator_bar.skip";

	private static final Logger LOGGER = SmileSToolb.LOGGER;

	private BackportBarLink() {
	}

	public static void register() {
		ModLinks.onOverride(HOOK, BackportBarLink::choose);
		LOGGER.info("第三方联动已注册：hook={}，目标模组可用={}", HOOK,
				BackportBridge.available());
	}

	/**
	 * @param original backport 原本的 {@code shouldSkipRendering()} 结果
	 * @return {@code Boolean} 覆盖值；{@code null} 表示不干预
	 */
	private static Object choose(String hook, Object original) {
		SmileConfig config = SmileConfig.get();
		if (!config.forceExperienceBar) {
			return null;
		}
		// 模组总开关关闭、或附近没有路径点时，不强行画空条，也不覆盖它自己的判断
		if (!BackportBridge.locatorEnabled() || !BackportBridge.hasWaypoints()) {
			return null;
		}
		if (Ui.playerName().isEmpty()) {
			return null;
		}

		if (!config.advancedBarControl) {
			return Boolean.TRUE;
		}

		// 只有真正可跳跃的坐骑才让蓄力条优先；船 / 矿车 / 家具座椅只是「有载具」，
		// 不该为了它们把定位条藏起来。库按版本用 jumpableVehicle() / 接口形状判定。
		boolean jumpable = Ui.hasJumpableVehicle();
		if (jumpable && ContextualBarController.get().jumpBarActive()) {
			// 蓄力条优先：让原版的 renderJumpMeter 露出来，别被定位条盖住
			return Boolean.TRUE;
		}
		return !Ui.playerListDown();
	}
}
