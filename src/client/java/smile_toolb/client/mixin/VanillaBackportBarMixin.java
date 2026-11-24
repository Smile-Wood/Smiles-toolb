package smile_toolb.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import smile_libbox.api.link.ModLinks;

/**
 * VanillaBackport（1.20.1 的定位条 backport）的<b>薄注入点</b>。
 *
 * <p>{@code LocatorBarRenderer#shouldSkipRendering()} 是它叠加绘制定位条的唯一总闸门，
 * 同时被 {@code renderBackground()} 与 {@code renderWaypoints()} 调用。这里不再写任何
 * 业务判断，只把原版算出的返回值交给 {@link ModLinks} 总线；真正的规则在
 * {@code smile_toolb.client.hud.BackportBarLink} 里以回调形式注册。</p>
 *
 * <p>这是软目标 mixin（字符串 {@code targets} + {@code remap = false} +
 * {@code require = 0}），放在独立的 {@code required: false} 配置里，由
 * {@link BackportMixinPlugin} 按模组是否加载门控。目标类是第三方模组的类而非
 * {@code net.minecraft.*}，且注入方法 {@code shouldSkipRendering()Z} 的签名里
 * 不含任何 MC 类型，所以这份 class 能跨 intermediary / named 两代命名空间共用，
 * 不必按游戏版本重编 —— 这正是「使用方保持单 jar」的关键。</p>
 *
 * <p>{@code HOOK} 字符串必须与 {@code BackportBarLink.HOOK} 完全一致；它是跨 jar
 * 的唯一契约，编译期不产生任何符号引用。</p>
 */
@Mixin(targets = "com.blackgear.vanillabackport.client.api.modules.waypoints.LocatorBarRenderer", remap = false)
public abstract class VanillaBackportBarMixin {

	private static final String HOOK = "vanillabackport.locator_bar.skip";

	@Inject(method = "shouldSkipRendering()Z", at = @At("RETURN"), cancellable = true,
			remap = false, require = 0)
	private void smile_toolb$chooseBar(CallbackInfoReturnable<Boolean> cir) {
		Boolean value = ModLinks.dispatchBoolean(HOOK, cir.getReturnValue());
		if (value != null) {
			cir.setReturnValue(value);
		}
	}
}
