package smile_toolb.client.config;

import smile_libbox.api.ui.ConfigSpec;
import smile_libbox.api.ui.Ui;
import smile_toolb.client.combat.ArmorLock;
import smile_toolb.client.combat.MaceSlam;
import smile_toolb.client.dummy.DummySpawner;
import smile_toolb.client.hud.BarFeatureDetector;
import smile_toolb.client.tts.TtsEngine;
import smile_toolb.client.tts.VoiceRegistry;
import smile_toolb.client.ui.ExitKeyScreen;
import smile_toolb.client.ui.VoiceSelectScreen;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 配置界面的预设定义：只描述「有哪些页、每页有哪些项、值怎么读写」，
 * 渲染全部交给库里那套原版材质的 {@link ConfigSpec} 界面。
 *
 * <p>以前这里是 {@code Configs}（把字段映射成 MaLiLib 的 {@code IConfigBase}）
 * 加一个 {@code GuiConfigsBase} 子类，两边都要跟着 MaLiLib 的版本走。现在界面只有
 * 一份实现、位于库内，本类只负责数据 —— 换 GUI 库、换游戏版本都不用动它。</p>
 */
public final class SmileToolbConfig {

	private SmileToolbConfig() {
	}

	/**
	 * 建一份可直接交给 {@code Ui.config(parent, spec)} 的描述。
	 *
	 * @param parent 父界面句柄，可为 {@code null}
	 */
	public static ConfigSpec build(Object parent) {
		SmileConfig config = SmileConfig.get();
		ConfigSpec spec = new ConfigSpec(Ui.tr("smile_toolb.gui.title.configs"), parent,
				SmileConfig::save);

		narratorPage(spec, config);
		combatPage(spec, config);
		miscPage(spec, config);
		return spec;
	}

	/** 讲述人页。 */
	private static void narratorPage(ConfigSpec spec, SmileConfig config) {
		ConfigSpec.Page page = spec.page(Ui.tr("smile_toolb.gui.tab.narrator"));

		page.toggle("enabled", Ui.tr("smile_toolb.config.enabled"),
				tip("enabled"),
				config.enabled, true, bool((c, v) -> c.enabled = v));
		// 系统语音关掉后，音色 / 语速 / 音量 / 单条长度 / 自动开启都没有意义，跟着置灰
		ConfigSpec.Row enabledRow = page.last();

		page.slider("rate", Ui.tr("smile_toolb.config.rate"),
				tip("rate"),
				config.rate, 50, 200, 100, integer((c, v) -> c.rate = v))
				.last().requires(enabledRow);
		page.slider("volume", Ui.tr("smile_toolb.config.volume"),
				tip("volume"),
				config.volume, 0, 100, 100, integer((c, v) -> c.volume = v))
				.last().requires(enabledRow);
		// 0~1000 的跨度用滑条一格 5 个字，拖不准，所以只要输入框（保存时写回）
		page.number("max_chars", Ui.tr("smile_toolb.config.max_chars"),
				tip("max_chars"),
				config.maxChars, 0, 1000, 300, integer((c, v) -> c.maxChars = v))
				.last().requires(enabledRow);
		page.toggle("include_online_voices", Ui.tr("smile_toolb.config.include_online_voices"),
				tip("include_online_voices"),
				config.includeOnlineVoices, true, bool((c, v) -> c.includeOnlineVoices = v));
		page.toggle("auto_enable_narrator", Ui.tr("smile_toolb.config.auto_enable_narrator"),
				tip("auto_enable"),
				config.autoEnableNarrator, false, bool((c, v) -> c.autoEnableNarrator = v))
				.last().requires(enabledRow);

		page.footer(() -> Ui.tr("smile_toolb.gui.button.select_voice",
						VoiceRegistry.get().labelOf(VoiceRegistry.get().resolveSelectedId())),
				() -> true, SmileToolbConfig::openVoiceList);
		page.footer(() -> Ui.tr("smile_toolb.gui.button.preview"), () -> true,
				() -> TtsEngine.get().playSample(Ui.tr("config.smile_toolb.sample")));
	}

	/** 战斗辅助页。 */
	private static void combatPage(ConfigSpec spec, SmileConfig config) {
		ConfigSpec.Page page = spec.page(Ui.tr("smile_toolb.gui.tab.combat"));

		// 护甲锁：地形不适合鞘翅时，禁止盔甲栏被自动换成鞘翅
		page.toggle("elytra_guard", Ui.tr("smile_toolb.config.elytra_guard"),
				tip("elytra_guard"),
				config.elytraGuardEnabled, true, bool((c, v) -> c.elytraGuardEnabled = v));

		// 重锤猛击：锁定护甲 → 切重锤 → 落地前挥击 → 解锁
		page.toggle("mace_slam", Ui.tr("smile_toolb.config.mace_slam"),
				tip("mace_slam"),
				config.maceSlamEnabled, true, bool((c, v) -> c.maceSlamEnabled = v));
		ConfigSpec.Row slamRow = page.last();

		page.toggle("mace_slam_sneak", Ui.tr("smile_toolb.config.mace_slam_sneak"),
				tip("mace_slam_sneak"),
				config.maceSlamRequireSneak, true, bool((c, v) -> c.maceSlamRequireSneak = v))
				.last().requires(slamRow);
		page.toggle("mace_slam_dive", Ui.tr("smile_toolb.config.mace_slam_dive"),
				tip("mace_slam_dive"),
				config.maceSlamDive, true, bool((c, v) -> c.maceSlamDive = v))
				.last().requires(slamRow);
		page.slider("mace_slam_reach", Ui.tr("smile_toolb.config.mace_slam_reach"),
				tip("mace_slam_reach"),
				config.maceSlamReach, 2, 6, 3, integer((c, v) -> c.maceSlamReach = v))
				.last().requires(slamRow);
		page.toggle("mace_slam_lead_auto", Ui.tr("smile_toolb.config.mace_slam_lead_auto"),
				tip("mace_slam_lead_auto"),
				config.maceSlamLeadAuto, true, bool((c, v) -> c.maceSlamLeadAuto = v))
				.last().requires(slamRow);
		page.slider("mace_slam_lead", Ui.tr("smile_toolb.config.mace_slam_lead"),
				tip("mace_slam_lead"),
				config.maceSlamLeadTicks, 1, 8, 2, integer((c, v) -> c.maceSlamLeadTicks = v))
				.last().requires(slamRow);
		page.toggle("mace_slam_resume", Ui.tr("smile_toolb.config.mace_slam_resume"),
				tip("mace_slam_resume"),
				config.maceSlamResumeFlight, true, bool((c, v) -> c.maceSlamResumeFlight = v))
				.last().requires(slamRow);

		// 底栏常驻一行状态：护甲锁当前状态 + 地形扫描结果，走查四个基准场景时直接看这里
		page.bottom.text(ArmorLock::statusLine, 0xFFFFAA00);
		// 右边补上延迟与提前量：高延迟玩家要能直接看到「为什么这一锤提前了 3 tick」
		page.bottom.text(ConfigSpec.Bottom.Align.RIGHT, MaceSlam::leadLine, 0xFFAAAAAA);
	}

	/** 杂项页。 */
	private static void miscPage(ConfigSpec spec, SmileConfig config) {
		ConfigSpec.Page page = spec.page(Ui.tr("smile_toolb.gui.tab.misc"));

		// 1.20.x 没有玩家追踪条，这两项显示出来只会让人以为坏了：
		// 按运行时探测决定，而不是编译期版本条件（VanillaBackport 会改变能力）。
		if (BarFeatureDetector.get().present()) {
			page.toggle("force_experience_bar", Ui.tr("smile_toolb.config.force_experience_bar"),
					tip("force_experience_bar"),
					config.forceExperienceBar, true, bool((c, v) -> c.forceExperienceBar = v));
			ConfigSpec.Row expBarRow = page.last();
			page.toggle("advanced_bar_control", Ui.tr("smile_toolb.config.advanced_bar_control"),
					tip("advanced_bar_control"),
					config.advancedBarControl, false, bool((c, v) -> c.advancedBarControl = v))
					.last().requires(expBarRow);
		}

		page.toggle("keep_chat_draft", Ui.tr("smile_toolb.config.keep_chat_draft"),
				tip("keep_chat_draft"),
				config.keepChatDraft, true, bool((c, v) -> c.keepChatDraft = v));
		ConfigSpec.Row draftRow = page.last();
		// 60~3600 秒：滑条一格 59 秒，只有输入框能填准
		page.number("chat_draft_ttl", Ui.tr("smile_toolb.config.chat_draft_ttl"),
				tip("chat_draft_ttl"),
				config.chatDraftTtlSeconds, 60, 3600, 600,
				integer((c, v) -> c.chatDraftTtlSeconds = v))
				.last().requires(draftRow);
		page.slider("dummy_count", Ui.tr("smile_toolb.config.dummy_count"),
				tip("dummy_count"),
				config.dummyCount, 1, 50, 20, integer((c, v) -> c.dummyCount = v));
		page.slider("dummy_interval", Ui.tr("smile_toolb.config.dummy_interval"),
				tip("dummy_interval"),
				config.dummyIntervalTicks, 1, 100, 10,
				integer((c, v) -> c.dummyIntervalTicks = v));
		page.toggle("exit_key_enabled", Ui.tr("smile_toolb.config.exit_key_enabled"),
				tip("exit_key_enabled"),
				config.exitKeyEnabled, true, bool((c, v) -> c.exitKeyEnabled = v));
		ConfigSpec.Row exitKeyRow = page.last();
		page.action("exit_keys", Ui.tr("smile_toolb.config.exit_keys"),
				tip("exit_keys"),
				() -> Ui.tr("smile_toolb.gui.button.edit_keys",
						String.valueOf(SmileConfig.get().exitKeyNames().size())),
				() -> Ui.open(Ui.screen(new ExitKeyScreen(Ui.currentScreen()))))
				.last().requires(exitKeyRow);

		// 底部那一行（就是「全部重置 / 完成」那行）：两颗批量按钮 + 红字状态。
		// 宽度由库按文字统一算，按钮从左往右自动续排，状态文字接在按钮后面。
		page.bottom.button(
				() -> Ui.tr("smile_toolb.gui.button.spawn_dummies"),
				() -> DummySpawner.available() && !DummySpawner.busy(),
				() -> DummySpawner.spawn(SmileConfig.get().dummyCount));
		page.bottom.button(
				() -> Ui.tr("smile_toolb.gui.button.remove_dummies",
						String.valueOf(DummySpawner.count())),
				() -> DummySpawner.available() && !DummySpawner.busy(),
				DummySpawner::removeAll);
		// 有批量任务在跑时两个按钮一起禁用，红字说明原因与剩余条数
		page.bottom.text(
				() -> DummySpawner.busy()
						? Ui.tr("smile_toolb.gui.status.dummy_busy",
								String.valueOf(DummySpawner.queued()))
						: "",
				0xFFFF5555);
	}

	private static void openVoiceList() {
		Ui.open(Ui.screen(new VoiceSelectScreen(Ui.currentScreen())));
	}

	/**
	 * 配置项说明：语言键统一前缀 {@code config.smile_toolb.tooltip.<key>}。
	 *
	 * <p>界面把这些文字当行 tooltip，鼠标停在标签文字区时由原版 tooltip 显示。</p>
	 */
	private static String tip(String key) {
		return Ui.tr("config.smile_toolb.tooltip." + key);
	}

	// ------------------------------------------------------------------ 值同步

	private static Consumer<Boolean> bool(BiConsumer<SmileConfig, Boolean> setter) {
		return value -> {
			setter.accept(SmileConfig.get(), value);
			commit();
		};
	}

	private static Consumer<Integer> integer(BiConsumer<SmileConfig, Integer> setter) {
		return value -> {
			setter.accept(SmileConfig.get(), value);
			commit();
		};
	}

	/** 改动即时落盘并推给语音引擎 —— 与原来 MaLiLib 的 {@code onConfigsChanged} 同频。 */
	private static void commit() {
		SmileConfig.save();
		try {
			TtsEngine.get().applyConfig();
		} catch (Throwable error) {
			// 语音引擎还没起来时忽略：下次启动会重新读一遍配置
		}
	}
}
