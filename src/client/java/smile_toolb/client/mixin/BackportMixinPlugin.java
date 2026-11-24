package smile_toolb.client.mixin;

import smile_libbox.api.SoftTargetMixinPlugin;

/**
 * VanillaBackport 联动的门控。
 *
 * <p>门控逻辑（版本表达式、mod id、标记类、Loader 未就绪时的资源探测兜底）
 * 全部由库的 {@link SoftTargetMixinPlugin} 提供，这里只声明「要联动谁」和
 * 「目标类必须真的存在」。没装 backport 的版本里，整个 mixin 类在解析阶段前
 * 就被跳过，8 个版本节点可以共用同一份源码。</p>
 */
public final class BackportMixinPlugin extends SoftTargetMixinPlugin {

	private static final String MOD_ID = "vanillabackport";

	private static final String RENDERER =
			"com.blackgear.vanillabackport.client.api.modules.waypoints.LocatorBarRenderer";

	@Override
	protected String modId() {
		return MOD_ID;
	}

	@Override
	protected String[] markerClasses() {
		return new String[]{RENDERER};
	}
}
