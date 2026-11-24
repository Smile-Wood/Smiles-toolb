package smile_toolb.client.ui;

import org.slf4j.Logger;
import smile_libbox.api.ui.Ui;
import smile_libbox.api.ui.UiCanvas;
import smile_libbox.api.ui.UiHost;
import smile_libbox.api.ui.UiScreen;
import smile_toolb.SmileSToolb;
import smile_toolb.client.config.SmileConfig;
import smile_toolb.client.tts.TtsEngine;
import smile_toolb.client.tts.VoiceInfo;
import smile_toolb.client.tts.VoiceRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 独立的音色选择界面：顶部搜索、筛选、中间可滚动列表、底部试听与完成。
 *
 * <p>实现 {@link UiScreen}（纯 Java 接口），绘制与输入全部走库的固定回调，
 * 常量池零 MC 引用，因此同一个 jar 能装到两种命名空间。</p>
 */
public final class VoiceSelectScreen implements UiScreen {

	private static final Logger LOGGER = SmileSToolb.LOGGER;

	private static final int ROW_HEIGHT = 20;
	private static final int ROW_STEP = ROW_HEIGHT + 2;
	private static final int SEARCH_MAX_WIDTH = 220;

	private final Object parent;
	private final List<VoiceInfo> shown = new ArrayList<>();

	private UiHost host;
	private int width;
	private int height;
	private int searchBox = -1;
	private int onlineToggle = -1;
	private int previewButtonId = -1;
	private int doneButtonId = -1;
	private int filterAll = -1;
	private int filterLocal = -1;
	private int filterOnline = -1;

	/** 0 = 全部，1 = 仅本地，2 = 仅在线。 */
	private int filter;
	private String selectedId;
	private int scroll;
	private int hovered = -1;
	private boolean refreshScheduled;

	private int listLeft;
	private int listRight;
	private int listTop;
	private int listBottom;

	public VoiceSelectScreen(Object parent) {
		this.parent = parent;
		this.selectedId = VoiceRegistry.get().resolveSelectedId();
	}

	// ------------------------------------------------------------------ UiScreen

	@Override
	public String title() {
		return Ui.tr("config.smile_toolb.picker.title");
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
		this.listTop = 80;
		this.listBottom = Math.max(this.listTop + ROW_STEP * 3, this.height - 78);

		int searchWidth = Math.min(SEARCH_MAX_WIDTH, this.width - 60);
		this.searchBox = h.textBox(centerX - searchWidth / 2, 30, searchWidth, 18,
				Ui.tr("config.smile_toolb.picker.search"), "", value -> {
					this.scroll = 0;
					this.rebuild();
				});

		int filterY = 54;
		int filterWidth = 58;
		int gap = 4;
		int totalWidth = filterWidth * 3 + gap * 2;
		int filterX = centerX - totalWidth / 2;
		this.filterAll = h.button(filterX, filterY, filterWidth, 18,
				Ui.tr("config.smile_toolb.picker.filter.all"), () -> this.setFilter(0));
		this.filterLocal = h.button(filterX + filterWidth + gap, filterY, filterWidth, 18,
				Ui.tr("config.smile_toolb.picker.filter.local"), () -> this.setFilter(1));
		this.filterOnline = h.button(filterX + (filterWidth + gap) * 2, filterY, filterWidth, 18,
				Ui.tr("config.smile_toolb.picker.filter.online"), () -> this.setFilter(2));

		int buttonY = this.height - 28;
		int listWidth = this.listRight - this.listLeft;
		int toggleWidth = 96;
		int previewWidth = 88;
		int doneWidth = 96;
		int needed = toggleWidth + previewWidth + doneWidth + gap * 2;
		if (needed > listWidth) {
			double scale = (double) listWidth / needed;
			toggleWidth = (int) (toggleWidth * scale);
			previewWidth = (int) (previewWidth * scale);
			doneWidth = (int) (doneWidth * scale);
		}
		int previewX = this.listLeft + toggleWidth + gap;
		int doneX = this.listRight - doneWidth;
		this.onlineToggle = h.button(this.listLeft, buttonY, toggleWidth, 20,
				this.onlineLabel(), this::toggleOnline);
		this.previewButtonId = h.button(previewX, buttonY, previewWidth, 20,
				Ui.tr("config.smile_toolb.picker.preview"), this::preview);
		this.doneButtonId = h.button(doneX, buttonY, doneWidth, 20,
				Ui.tr("config.smile_toolb.picker.done"), h::close);

		this.rebuild();
		this.updateFilterButtons();
		this.scheduleLateRefresh();
		LOGGER.info("打开音色列表：可见 {} 个（本地 {}，在线 {}），列表区域 x[{},{}] y[{},{}]",
				this.shown.size(),
				this.shown.stream().filter(v -> !v.isEdge() && !v.isOnline()).count(),
				this.shown.stream().filter(v -> v.isEdge() || v.isOnline()).count(),
				this.listLeft, this.listRight, this.listTop, this.listBottom);
	}

	@Override
	public void render(UiCanvas c, UiHost h, int mouseX, int mouseY, float partialTick) {
		c.centered(this.title(), this.width / 2, 14, 0xFFFFFF);
		this.renderList(c, mouseX, mouseY);
		this.renderFooter(c);
	}

	@Override
	public boolean click(double mouseX, double mouseY, int button) {
		if (button != 0 || mouseX < this.listLeft || mouseX > this.listRight
				|| mouseY < this.listTop || mouseY > this.listBottom || this.shown.isEmpty()) {
			return false;
		}
		int index = (int) ((mouseY - this.listTop + this.scroll) / ROW_STEP);
		if (index < 0 || index >= this.shown.size()) {
			return false;
		}
		this.select(this.shown.get(index));
		return true;
	}

	@Override
	public boolean scroll(double mouseX, double mouseY, double delta) {
		if (this.shown.size() * ROW_STEP <= this.viewHeight()) {
			return false;
		}
		if (mouseX < this.listLeft || mouseX > this.listRight
				|| mouseY < this.listTop || mouseY > this.listBottom) {
			return false;
		}
		this.scroll = this.clampScroll(this.scroll - (int) (delta * ROW_STEP * 2));
		return true;
	}

	@Override
	public boolean key(int keyCode, int scanCode, int modifiers) {
		if ((this.searchBox < 0 || !this.host.textBoxFocused(this.searchBox))
				&& !this.shown.isEmpty()) {
			int current = this.indexOf(this.selectedId);
			if (keyCode == 265) {
				this.moveSelection(current <= 0 ? 0 : current - 1);
				return true;
			}
			if (keyCode == 264) {
				this.moveSelection(current < 0 ? 0 : Math.min(this.shown.size() - 1, current + 1));
				return true;
			}
			if (keyCode == 257 || keyCode == 335) {
				this.preview();
				return true;
			}
		}
		return false;
	}

	@Override
	public void close() {
	}

	// ------------------------------------------------------------------ 列表构建与滚动

	private void rebuild() {
		String query = this.searchBox < 0 ? ""
				: this.host.textBoxValue(this.searchBox).trim().toLowerCase(Locale.ROOT);
		List<VoiceInfo> result = new ArrayList<>();
		for (VoiceInfo voice : VoiceRegistry.get().visible()) {
			if (this.filter == 1 && (voice.isEdge() || voice.isOnline())) {
				continue;
			}
			if (this.filter == 2 && !voice.isEdge() && !voice.isOnline()) {
				continue;
			}
			if (!query.isEmpty() && !this.matches(voice, query)) {
				continue;
			}
			result.add(voice);
		}
		this.shown.clear();
		this.shown.addAll(result);

		String resolved = VoiceRegistry.get().resolveSelectedId();
		if (resolved != null && !resolved.isBlank()) {
			this.selectedId = resolved;
		}
		this.scroll = this.clampScroll(this.scroll);
		this.hovered = -1;
	}

	private boolean matches(VoiceInfo voice, String query) {
		return contains(voice.getName(), query)
				|| contains(voice.getLanguage(), query)
				|| contains(this.engineLabel(voice), query);
	}

	private static boolean contains(String value, String query) {
		return value != null && value.toLowerCase(Locale.ROOT).contains(query);
	}

	private static String displayName(VoiceInfo voice) {
		String name = voice.getName();
		return name == null || name.isBlank() ? voice.getId() : name;
	}

	private void setFilter(int value) {
		if (this.filter == value) {
			return;
		}
		this.filter = value;
		this.scroll = 0;
		this.rebuild();
		this.updateFilterButtons();
	}

	private void updateFilterButtons() {
		if (this.host == null || this.filterAll < 0) {
			return;
		}
		this.host.buttonText(this.filterAll,
				mark(0, Ui.tr("config.smile_toolb.picker.filter.all")));
		this.host.buttonText(this.filterLocal,
				mark(1, Ui.tr("config.smile_toolb.picker.filter.local")));
		this.host.buttonText(this.filterOnline,
				mark(2, Ui.tr("config.smile_toolb.picker.filter.online")));
	}

	private String mark(int value, String label) {
		return this.filter == value ? "[ " + label + " ]" : label;
	}

	private int contentHeight() {
		return this.shown.size() * ROW_STEP;
	}

	private int viewHeight() {
		return this.listBottom - this.listTop;
	}

	private int clampScroll(int value) {
		int max = Math.max(0, this.contentHeight() - this.viewHeight());
		return Math.max(0, Math.min(value, max));
	}

	private void ensureVisible(int index) {
		int rowTop = index * ROW_STEP;
		int rowBottom = rowTop + ROW_HEIGHT;
		if (rowTop < this.scroll) {
			this.scroll = rowTop;
		} else if (rowBottom > this.scroll + this.viewHeight()) {
			this.scroll = rowBottom - this.viewHeight();
		}
		this.scroll = this.clampScroll(this.scroll);
	}

	// ------------------------------------------------------------------ 选择与试听

	private void select(VoiceInfo voice) {
		if (voice == null) {
			return;
		}
		this.selectedId = voice.getId();
		SmileConfig config = SmileConfig.get();
		config.voiceId = this.selectedId;
		SmileConfig.save();
		LOGGER.info("音色切换为 {}", this.selectedId);
		TtsEngine.get().preview(this.selectedId, Ui.tr("config.smile_toolb.sample"));
	}

	private void preview() {
		VoiceInfo voice = this.byId(this.selectedId);
		String text = Ui.tr("config.smile_toolb.sample");
		if (voice == null) {
			TtsEngine.get().playSample(text);
			return;
		}
		TtsEngine.get().preview(voice.getId(), text);
	}

	private void toggleOnline() {
		SmileConfig config = SmileConfig.get();
		config.includeOnlineVoices = !config.includeOnlineVoices;
		SmileConfig.save();
		this.host.buttonText(this.onlineToggle, this.onlineLabel());
		this.scroll = 0;
		this.rebuild();
		if (!config.includeOnlineVoices) {
			return;
		}
		TtsEngine engine = TtsEngine.get();
		engine.startAsync();
		Thread worker = new Thread(() -> {
			engine.refreshEdgeVoicesBlocking(8_000L);
			Ui.runOnMain(this::rebuild);
		}, "SmileTTS-PickerEdge");
		worker.setDaemon(true);
		worker.start();
	}

	private String onlineLabel() {
		boolean on = SmileConfig.get().includeOnlineVoices;
		return Ui.tr("config.smile_toolb.picker.online",
				Ui.tr(on ? "config.smile_toolb.picker.on" : "config.smile_toolb.picker.off"));
	}

	private VoiceInfo byId(String id) {
		if (id == null) {
			return null;
		}
		for (VoiceInfo voice : this.shown) {
			if (id.equals(voice.getId())) {
				return voice;
			}
		}
		return VoiceRegistry.get().byId(id);
	}

	private void scheduleLateRefresh() {
		if (this.refreshScheduled || !this.shown.isEmpty()) {
			return;
		}
		TtsEngine engine = TtsEngine.get();
		if (engine.isReady()) {
			return;
		}
		this.refreshScheduled = true;
		Thread worker = new Thread(() -> {
			if (engine.awaitReady(15_000L)) {
				engine.refreshVoicesBlocking(2_000L);
			}
			Ui.runOnMain(() -> {
				if (Ui.screenSpec(Ui.currentScreen()) == this) {
					this.rebuild();
				}
				this.refreshScheduled = false;
			});
		}, "SmileTTS-PickerRefresh");
		worker.setDaemon(true);
		worker.start();
	}

	// ------------------------------------------------------------------ 渲染

	private void renderList(UiCanvas c, int mouseX, int mouseY) {
		c.fill(this.listLeft - 2, this.listTop - 2, this.listRight + 2, this.listBottom + 2, 0x66000000);
		if (this.shown.isEmpty()) {
			c.centered(Ui.tr(this.emptyKey()), (this.listLeft + this.listRight) / 2,
					(this.listTop + this.listBottom) / 2 - 4, 0xFFA0A0A0);
			return;
		}

		c.scissor(this.listLeft, this.listTop, this.listRight, this.listBottom);
		int rowTop = this.listTop - this.scroll;
		for (int i = 0; i < this.shown.size(); i++) {
			int top = rowTop + i * ROW_STEP;
			if (top + ROW_HEIGHT < this.listTop || top > this.listBottom) {
				continue;
			}
			VoiceInfo voice = this.shown.get(i);
			boolean selected = voice.getId().equals(this.selectedId);
			boolean hoveredRow = mouseX >= this.listLeft && mouseX <= this.listRight
					&& mouseY >= top && mouseY < top + ROW_HEIGHT;
			if (hoveredRow) {
				this.hovered = i;
			}
			if (selected) {
				c.fill(this.listLeft, top, this.listRight, top + ROW_HEIGHT, 0x884C8F4C);
				c.fill(this.listLeft, top, this.listLeft + 2, top + ROW_HEIGHT, 0xFF9BE09B);
			} else if (hoveredRow) {
				c.fill(this.listLeft, top, this.listRight, top + ROW_HEIGHT, 0x33FFFFFF);
			}

			int textY = top + (ROW_HEIGHT - 8) / 2;
			String tag = this.tagText(voice);
			int tagWidth = Ui.textWidth(tag);
			int tagX = this.listRight - 8 - tagWidth;
			c.text(tag, tagX, textY, 0xFF9AA5B1, false);

			int nameMax = tagX - 8 - (this.listLeft + 10);
			String name = Ui.fit(displayName(voice), Math.max(20, nameMax));
			c.text(name, this.listLeft + 10, textY, selected ? 0xFFFFFFFF : 0xFFE6E6E6, false);
		}
		c.endScissor();
		this.renderScrollbar(c);
	}

	private void renderScrollbar(UiCanvas c) {
		int content = this.contentHeight();
		int view = this.viewHeight();
		if (content <= view) {
			return;
		}
		int trackX = this.listRight - 4;
		c.fill(trackX, this.listTop, this.listRight, this.listBottom, 0x33FFFFFF);
		int thumbHeight = Math.max(12, view * view / content);
		int travel = view - thumbHeight;
		int offset = travel * this.scroll / Math.max(1, content - view);
		c.fill(trackX, this.listTop + offset, this.listRight,
				this.listTop + offset + thumbHeight, 0xFFB0B0B0);
	}

	private void renderFooter(UiCanvas c) {
		int centerX = this.width / 2;
		int y = this.listBottom + 8;
		List<String> lines = new ArrayList<>(c.wrap(Ui.tr("config.smile_toolb.picker.count",
				this.shown.size(),
				this.shown.stream().filter(v -> !v.isEdge() && !v.isOnline()).count(),
				this.shown.stream().filter(v -> v.isEdge() || v.isOnline()).count()), 320));

		if (!SmileConfig.get().includeOnlineVoices && VoiceRegistry.get().hasOnlineVoices()) {
			lines.addAll(c.wrap(Ui.tr("config.smile_toolb.picker.online_hidden"), 320));
		} else if (TtsEngine.get().isUnavailable()) {
			lines.addAll(c.wrap(Ui.tr("config.smile_toolb.status.unavailable"), 320));
		} else if (!TtsEngine.get().isReady() && this.shown.isEmpty()) {
			lines.addAll(c.wrap(Ui.tr("config.smile_toolb.status.starting"), 320));
		}
		if (!TtsEngine.get().hints().isEmpty() && this.shown.size() < 40) {
			lines.addAll(c.wrap(Ui.tr("config.smile_toolb.picker.hint"), 320));
		}

		int drawY = y;
		for (String line : lines) {
			if (drawY > this.height - 36) {
				break;
			}
			c.text(line, centerX - 160, drawY, 0xFFA0A0A0, false);
			drawY += 10;
		}
	}

	private String emptyKey() {
		if (this.filter == 2 && !SmileConfig.get().includeOnlineVoices) {
			return "config.smile_toolb.picker.online_off";
		}
		return this.searchBox >= 0 && !this.host.textBoxValue(this.searchBox).isBlank()
				? "config.smile_toolb.picker.no_match"
				: "config.smile_toolb.status.no_voices";
	}

	private String tagText(VoiceInfo voice) {
		String engine = this.engineLabel(voice);
		String language = voice.getLanguage();
		return language == null || language.isBlank() ? engine : engine + " · " + language;
	}

	private String engineLabel(VoiceInfo voice) {
		if (voice.isEdge() || voice.isOnline()) {
			return Ui.tr("config.smile_toolb.picker.tag.online");
		}
		return voice.isSapi() ? "SAPI5" : "OneCore";
	}

	private void moveSelection(int index) {
		if (index < 0 || index >= this.shown.size()) {
			return;
		}
		this.ensureVisible(index);
		this.select(this.shown.get(index));
	}

	private int indexOf(String id) {
		if (id == null) {
			return -1;
		}
		for (int i = 0; i < this.shown.size(); i++) {
			if (id.equals(this.shown.get(i).getId())) {
				return i;
			}
		}
		return -1;
	}
}
