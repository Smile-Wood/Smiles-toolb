package smile_toolb.client.ui;

import org.slf4j.Logger;
import smile_libbox.api.ui.KeyRef;
import smile_libbox.api.ui.Ui;
import smile_libbox.api.ui.UiCanvas;
import smile_libbox.api.ui.UiHost;
import smile_libbox.api.ui.UiScreen;
import smile_toolb.SmileSToolb;
import smile_toolb.client.config.SmileConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * 「退出按键映射」设置界面。
 *
 * <p>实现 {@link UiScreen}（纯 Java 接口），不继承任何 Minecraft 类：
 * 宿主 {@code Screen} 由库按命名空间提供，因此本类常量池零 MC 引用。</p>
 */
public final class ExitKeyScreen implements UiScreen {

	private static final Logger LOGGER = SmileSToolb.LOGGER;

	private static final int ROW_HEIGHT = 20;
	private static final int ROW_STEP = ROW_HEIGHT + 2;
	private static final int REMOVE_ZONE = 52;
	private static final int KEY_ESCAPE = 256;
	private static final int MOUSE_LEFT = 0;

	private final Object parent;
	private final List<String> keys = new ArrayList<>();

	private UiHost host;
	private int width;
	private int height;
	private int enableButton;
	private int addButton;
	private int doneButton;

	private boolean capturing;
	private String notice;
	private int scroll;
	private int listLeft;
	private int listRight;
	private int listTop;
	private int listBottom;

	public ExitKeyScreen(Object parent) {
		this.parent = parent;
		this.keys.addAll(SmileConfig.get().exitKeyNames());
	}

	// ------------------------------------------------------------------ UiScreen

	@Override
	public String title() {
		return Ui.tr("config.smile_toolb.exit_keys.title");
	}

	@Override
	public Object parent() {
		return this.parent;
	}

	@Override
	public boolean pause() {
		return false;
	}

	@Override
	public void init(UiHost h) {
		this.host = h;
		this.width = h.width();
		this.height = h.height();
		h.clearButtons();
		int centerX = this.width / 2;
		this.listLeft = Math.max(20, centerX - 170);
		this.listRight = Math.min(this.width - 20, centerX + 170);
		this.listTop = 84;
		this.listBottom = Math.max(this.listTop + ROW_STEP * 3, this.height - 62);

		this.enableButton = h.button(centerX - 110, 34, 220, 20,
				this.enableLabel(), this::toggleEnabled);

		int buttonY = this.height - 28;
		int w = Math.min(100, (this.listRight - this.listLeft - 4) / 2);
		this.addButton = h.button(this.listRight - w * 2 - 4, buttonY, w, 20,
				Ui.tr("config.smile_toolb.exit_keys.add"), this::startCapture);
		this.doneButton = h.button(this.listRight - w, buttonY, w, 20,
				Ui.tr("config.smile_toolb.exit_keys.done"), h::close);

		LOGGER.info("打开退出按键界面：已配置 {} 个按键 {}，启用={}", this.keys.size(), this.keys,
				SmileConfig.get().exitKeyEnabled);
	}

	@Override
	public void render(UiCanvas c, UiHost h, int mouseX, int mouseY, float partialTick) {
		c.centered(this.title(), this.width / 2, 14, 0xFFFFFF);
		this.renderHint(c);
		this.renderList(c, mouseX, mouseY);
	}

	@Override
	public boolean key(int keyCode, int scanCode, int modifiers) {
		if (this.capturing) {
			if (keyCode == KEY_ESCAPE) {
				this.capturing = false;
				this.notice = Ui.tr("config.smile_toolb.exit_keys.cancelled");
			} else {
				this.addKey(Ui.keyFromCode(0, keyCode));
			}
			return true;
		}
		return false;
	}

	@Override
	public boolean click(double mouseX, double mouseY, int button) {
		if (this.capturing) {
			if (button == MOUSE_LEFT) {
				this.notice = Ui.tr("config.smile_toolb.exit_keys.left_blocked");
				return true;
			}
			this.addKey(Ui.keyFromCode(1, button));
			return true;
		}
		if (button != MOUSE_LEFT || mouseX < this.listLeft || mouseX > this.listRight
				|| mouseY < this.listTop || mouseY > this.listBottom || this.keys.isEmpty()) {
			return false;
		}
		if (mouseX < this.listRight - REMOVE_ZONE) {
			return false;
		}
		int index = (int) ((mouseY - this.listTop + this.scroll) / ROW_STEP);
		if (index < 0 || index >= this.keys.size()) {
			return false;
		}
		this.removeKey(index);
		return true;
	}

	@Override
	public boolean scroll(double mouseX, double mouseY, double delta) {
		return this.scrollList(mouseX, mouseY, delta);
	}

	@Override
	public void close() {
	}

	// ------------------------------------------------------------------ 状态

	private String enableLabel() {
		boolean on = SmileConfig.get().exitKeyEnabled;
		return Ui.tr("config.smile_toolb.exit_keys.enable",
				Ui.tr(on ? "config.smile_toolb.exit_keys.on" : "config.smile_toolb.exit_keys.off"));
	}

	private void toggleEnabled() {
		SmileConfig config = SmileConfig.get();
		config.exitKeyEnabled = !config.exitKeyEnabled;
		SmileConfig.save();
		this.host.buttonText(this.enableButton, this.enableLabel());
		this.notice = Ui.tr(config.exitKeyEnabled
				? "config.smile_toolb.exit_keys.notice_on"
				: "config.smile_toolb.exit_keys.notice_off");
	}

	private void startCapture() {
		this.capturing = true;
		this.notice = null;
	}

	private void addKey(KeyRef key) {
		if (key == null) {
			this.capturing = false;
			return;
		}
		this.capturing = false;
		String name = key.name;
		if (this.keys.contains(name)) {
			this.notice = Ui.tr("config.smile_toolb.exit_keys.duplicate", displayName(key));
			return;
		}
		this.keys.add(name);
		this.persist();
		this.notice = Ui.tr("config.smile_toolb.exit_keys.added", displayName(key));
		LOGGER.info("新增退出按键 {}（{}），共 {} 个", displayName(key), name, this.keys.size());
	}

	private void removeKey(int index) {
		if (index < 0 || index >= this.keys.size()) {
			return;
		}
		String removed = this.keys.remove(index);
		this.persist();
		this.notice = Ui.tr("config.smile_toolb.exit_keys.removed", displayName(removed));
		this.scroll = clampScroll(this.scroll);
		LOGGER.info("移除退出按键 {}（{}），剩余 {} 个", displayName(removed), removed, this.keys.size());
	}

	private void persist() {
		SmileConfig.get().exitKeys = new ArrayList<>(this.keys);
		SmileConfig.save();
	}

	// ------------------------------------------------------------------ 渲染

	private void renderHint(UiCanvas c) {
		int centerX = this.width / 2;
		List<String> lines;
		if (this.capturing) {
			lines = c.wrap(Ui.tr("config.smile_toolb.exit_keys.capturing"), 340);
		} else if (this.notice != null) {
			lines = c.wrap(this.notice, 340);
		} else {
			lines = c.wrap(Ui.tr("config.smile_toolb.exit_keys.hint"), 340);
		}
		int y = 60;
		for (String line : lines) {
			if (y > this.listTop - 10) {
				break;
			}
			c.text(line, centerX - 170, y, this.capturing ? 0xFFFFD060 : 0xFFA0A0A0, false);
			y += 10;
		}
	}

	private void renderList(UiCanvas c, int mouseX, int mouseY) {
		c.fill(this.listLeft - 2, this.listTop - 2, this.listRight + 2, this.listBottom + 2, 0x66000000);
		if (this.keys.isEmpty()) {
			c.centered(Ui.tr("config.smile_toolb.exit_keys.empty"),
					(this.listLeft + this.listRight) / 2,
					(this.listTop + this.listBottom) / 2 - 4, 0xFFA0A0A0);
			return;
		}

		int removeX = this.listRight - REMOVE_ZONE;
		c.scissor(this.listLeft, this.listTop, this.listRight, this.listBottom);
		int rowTop = this.listTop - this.scroll;
		for (int i = 0; i < this.keys.size(); i++) {
			int top = rowTop + i * ROW_STEP;
			if (top + ROW_HEIGHT < this.listTop || top > this.listBottom) {
				continue;
			}
			boolean hoverRow = mouseX >= this.listLeft && mouseX <= this.listRight
					&& mouseY >= top && mouseY < top + ROW_HEIGHT;
			if (hoverRow) {
				c.fill(this.listLeft, top, this.listRight, top + ROW_HEIGHT, 0x33FFFFFF);
			}
			int textY = top + (ROW_HEIGHT - 8) / 2;
			String name = c.fit(displayName(this.keys.get(i)), removeX - this.listLeft - 18);
			c.text(name, this.listLeft + 10, textY, 0xFFE6E6E6, false);

			boolean hoverRemove = hoverRow && mouseX >= removeX;
			c.text(Ui.tr("config.smile_toolb.exit_keys.remove"), removeX + 6, textY,
					hoverRemove ? 0xFFFF8080 : 0xFF8A8A8A, false);
		}
		c.endScissor();
		this.renderScrollbar(c);
	}

	private void renderScrollbar(UiCanvas c) {
		int content = this.keys.size() * ROW_STEP;
		int view = this.listBottom - this.listTop;
		if (content <= view) {
			return;
		}
		int trackX = this.listRight - 4;
		c.fill(trackX, this.listTop, this.listRight, this.listBottom, 0x33FFFFFF);
		int thumbHeight = Math.max(12, view * view / content);
		int offset = (view - thumbHeight) * this.scroll / Math.max(1, content - view);
		c.fill(trackX, this.listTop + offset, this.listRight,
				this.listTop + offset + thumbHeight, 0xFFB0B0B0);
	}

	// ------------------------------------------------------------------ 键名显示

	public static String displayName(KeyRef key) {
		return key == null ? "?" : key.displayName;
	}

	public static String displayName(String name) {
		KeyRef key = Ui.keyFromName(name);
		return key == null ? Ui.tr("config.smile_toolb.exit_keys.raw", name) : displayName(key);
	}

	// ------------------------------------------------------------------ 滚动

	private boolean scrollList(double mouseX, double mouseY, double delta) {
		if (this.keys.size() * ROW_STEP <= this.listBottom - this.listTop) {
			return false;
		}
		if (mouseX < this.listLeft || mouseX > this.listRight
				|| mouseY < this.listTop || mouseY > this.listBottom) {
			return false;
		}
		this.scroll = clampScroll(this.scroll - (int) (delta * ROW_STEP * 2));
		return true;
	}

	private int clampScroll(int value) {
		int max = Math.max(0, this.keys.size() * ROW_STEP - (this.listBottom - this.listTop));
		return Math.max(0, Math.min(value, max));
	}
}
