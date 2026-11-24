package smile_toolb.client.mixin;

import com.mojang.text2speech.Narrator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import smile_toolb.client.tts.SystemNarrator;

/**
 * 在 {@code Narrator.getNarrator()} 返回时把原版讲述人替换为系统语音实现。
 *
 * <p>选择注入这个工厂方法而不是游戏内的 {@code GameNarrator}：
 * 后者在 1.20.x 内部实现变动较频繁，而 {@code com.mojang.text2speech.Narrator}
 * 接口自 1.13 起保持稳定，只有 4 个方法。这样原版的讲述人状态机
 * （关闭/全部/聊天/系统）无需复制一份。</p>
 */
@Mixin(Narrator.class)
public interface NarratorFactoryMixin {

	@Inject(method = "getNarrator", at = @At("RETURN"), cancellable = true)
	private static void smile_toolb$useSystemVoice(CallbackInfoReturnable<Narrator> cir) {
		Narrator replacement = SystemNarrator.wrap(cir.getReturnValue());
		if (replacement != null) {
			cir.setReturnValue(replacement);
		}
	}
}
