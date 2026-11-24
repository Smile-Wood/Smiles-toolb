package smile_toolb.client.compat;

import org.lwjgl.glfw.GLFW;
import smile_libbox.api.event.InputEvents;
import smile_libbox.api.ui.Ui;
import smile_toolb.SmileSToolb;
import smile_toolb.client.config.SmileConfig;
import smile_toolb.client.ui.ExitKeyScreen;

/**
 * 「自定义退出键」的业务逻辑。
 *
 * <p>以前这段写在 {@code @Mixin(Screen)} / {@code @Mixin(MouseHandler)} 里，而这两个注入点
 * 在 1.21.9 前后签名完全不同，只能靠条件编译各出一份。现在注入点由 Lib Box 按版本编译，
 * 本模组只注册固定回调，因此不需要任何 mixin，也就没有 refmap 跨版本的问题。</p>
 */
public final class ExitKeys {

	private ExitKeys() {
	}

	public static void register() {
		InputEvents.onScreenKey((target, keyCode, scanCode, modifiers) -> {
			// 原生 Esc 走原版分支
			if (keyCode == 256 || target == null) {
				return false;
			}
			// 录制界面要接收这些键完成增删
			if (Ui.screenSpec(target) instanceof ExitKeyScreen
					|| !SmileConfig.get().isExitKey(keyCode)) {
				return false;
			}
			if (!Ui.shouldCloseOnEsc(target)) {
				return false;
			}
			SmileSToolb.LOGGER.debug("退出按键触发：键码 {} 关闭 {}",
					keyCode, Ui.screenName(target));
			Ui.closeScreen(target);
			return true;
		});

		InputEvents.onMouseButton((window, button, action) -> {
			if (action != GLFW.GLFW_PRESS) {
				return false;
			}
			Object screen = Ui.currentScreen();
			if (screen == null || Ui.screenSpec(screen) instanceof ExitKeyScreen) {
				return false;
			}
			if (window != Ui.windowHandle()) {
				return false;
			}
			if (!SmileConfig.get().isExitMouseKey(button)) {
				return false;
			}
			if (!Ui.shouldCloseOnEsc(screen)) {
				return false;
			}
			SmileSToolb.LOGGER.debug("退出按键触发：鼠标键 {} 关闭 {}",
					button, Ui.screenName(screen));
			Ui.closeScreen(screen);
			return true;
		});
	}
}
