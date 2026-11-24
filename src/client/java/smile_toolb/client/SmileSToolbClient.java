package smile_toolb.client;

import net.fabricmc.api.ClientModInitializer;
import org.lwjgl.glfw.GLFW;
import smile_libbox.api.event.ChatDraftEvents;
import smile_libbox.api.link.ModLinks;
import smile_libbox.api.ui.ConfigSpec;
import smile_libbox.api.ui.Ui;
import smile_toolb.SmileSToolb;
import smile_toolb.client.chat.ChatDraftStore;
import smile_toolb.client.combat.ArmorLock;
import smile_toolb.client.combat.MaceSlam;
import smile_toolb.client.compat.BarSelection;
import smile_toolb.client.compat.ExitKeys;
import smile_toolb.client.config.SmileConfig;
import smile_toolb.client.config.SmileToolbConfig;
import smile_toolb.client.dummy.DummySpawner;
import smile_toolb.client.hud.BackportBarLink;
import smile_toolb.client.hud.BarFeatureDetector;
import smile_toolb.client.hud.ContextualBarController;
import smile_toolb.client.tts.TtsEngine;
import smile_toolb.client.tts.VoiceRegistry;

import java.util.concurrent.atomic.AtomicBoolean;

public class SmileSToolbClient implements ClientModInitializer {

	private static Object openConfigKey;
	private static Object previewVoiceKey;
	private static final AtomicBoolean DIAGNOSED = new AtomicBoolean();
	private static final AtomicBoolean INITIALIZED = new AtomicBoolean();

	@Override
	public void onInitializeClient() {
		// Fabric 不保证前置库的客户端入口先执行：实测同一份整合包两次启动，
		// 一次 Ui.ready()=true、一次 false。未就绪时 Ui.key / Ui.onTick 会落到占位实现，
		// 键位与每帧回调被静默丢掉（表现为 F8 打不开配置、假人与状态条全失效）。
		// 因此整段注册都排到后端就绪之后再跑。
		Ui.whenReady(SmileSToolbClient::initialize);
	}

	private static void initialize() {
		// 配置必须在讲述人初始化之前可用，因此这里先做一次预热读取
		SmileConfig.get();
		VoiceRegistry.get().loadCache();

		// 版本相关的输入注入与上下文条选择都在库里按版本编译，这里只注册固定回调
		ExitKeys.register();
		BarSelection.register();
		// 对第三方模组的注入点留在 toolb 的软目标 mixin 里（目标类名不随 MC 命名空间变化），
		// 但业务规则注册到库的联动总线，注入类只做一次派发。
		BackportBarLink.register();
		// 聊天框草稿：注入点在库侧按版本编译，这里只注册「按连接地址持久化」的回调。
		ChatDraftStore.register();
		// 假人：全部走服务端 Carpet 命令，纯发包，不涉及任何注入点。
		DummySpawner.register();
		// 战斗辅助：护甲锁先注册（拦住往胸甲槽放鞘翅的容器操作），重锤流程依赖它锁甲
		ArmorLock.register();
		MaceSlam.register();

		openConfigKey = Ui.key(SmileSToolb.MOD_ID, "key.smile_toolb.open_config", GLFW.GLFW_KEY_F8);
		previewVoiceKey = Ui.key(SmileSToolb.MOD_ID, "key.smile_toolb.preview_voice", GLFW.GLFW_KEY_F9);

		Ui.onTick(() -> {
			ContextualBarController.get().tick();
			// 假人命令排队发送：按间隔一条一条发，避免触发服务端反刷屏踢人
			DummySpawner.tick();
			initializeRuntime();
			while (Ui.consumeClick(openConfigKey)) {
				openConfigScreen(Ui.currentScreen());
			}
			while (Ui.consumeClick(previewVoiceKey)) {
				// 随时试听当前音色，不必重开配置界面
				TtsEngine.get().playSample(Ui.tr("config.smile_toolb.sample"));
			}
		});
		Ui.onStarted(SmileSToolbClient::initializeRuntime);

		// client 入口是两代命名空间里唯一必然触发的时机：某些旧版 Fabric API 环境
		// 的 CLIENT_STARTED / END_CLIENT_TICK 不可靠，版本门控结论不能挂在那里。
		runDiagnostics();
	}

	/**
	 * 版本门控诊断：只走纯 Java 数据，不碰任何 MC 类，也不会切 Screen。
	 *
	 * <p>{@link Ui#ready()} 用来确认库的 client 入口确实装上了后端。
	 * 配置界面的自检放到运行时再做：client 入口阶段 {@code Minecraft.options}
	 * 还是 null，构造界面或碰讲述人状态会直接把 entrypoint 打崩。</p>
	 */
	private static void runDiagnostics() {
		if (!DIAGNOSED.compareAndSet(false, true)) {
			return;
		}
		SmileSToolb.LOGGER.info("Ui 后端：ready={}", Ui.ready());
		SmileSToolb.LOGGER.info("联动总线：{}", ModLinks.describe());
		SmileSToolb.LOGGER.info("聊天草稿总线：{}，有效草稿={} 条",
				ChatDraftEvents.describe(), ChatDraftStore.count());
		// 这一段同时验证库侧新门面：未连接时 sendCommand 必须返回 offline 而不是抛异常，
		// hasCommand 必须安静地返回 false（主菜单没有服务端命令树）。
		SmileSToolb.LOGGER.info("假人：sendCommand 探测={}，/player 可用={}",
				Ui.sendCommand("player __smile_probe__ spawn"), DummySpawner.available());
		BarFeatureDetector.get();
	}

	/**
	 * 自检 Mod Menu 有没有拿到本模组的配置界面工厂。
	 *
	 * <p>两个大版本的判据不一样：Mod Menu 17.x（1.21.9+）有公开的
	 * {@code hasConfigScreen(String)}；7.x / 11.x 只有私有表
	 * {@code configScreenFactories}，ModsScreen 就是拿这张表里的工厂去建按钮的。
	 * 两条路都走一遍，读到的工厂就是我们 {@code ModMenuIntegration} 里那个代理，
	 * 说明 entrypoint 已经被成功解析并接受了。</p>
	 *
	 * <p>全程反射：没装 Mod Menu 时只是 {@link ClassNotFoundException}，
	 * 不会因为缺类拖垮入口。</p>
	 */
	private static String modMenuStatus() {
		try {
			Class<?> menu = Class.forName("com.terraformersmc.modmenu.ModMenu");
			String base;
			try {
				Object has = menu.getMethod("hasConfigScreen", String.class)
						.invoke(null, SmileSToolb.MOD_ID);
				base = "已安装，hasConfigScreen=" + has;
			} catch (NoSuchMethodException olderApi) {
				java.lang.reflect.Field table = menu.getDeclaredField("configScreenFactories");
				table.setAccessible(true);
				Object factories = table.get(null);
				Object factory = ((java.util.Map<?, ?>) factories).get(SmileSToolb.MOD_ID);
				base = "已安装（7.x 私有表），登记工厂="
						+ (factory == null ? "无" : factory.getClass().getName());
			}
			return base + modMenuProbe(menu);
		} catch (ClassNotFoundException notInstalled) {
			return "未安装";
		} catch (Throwable error) {
			return "检测失败：" + error;
		}
	}

	/**
	 * 自检模式下走一遍 Mod Menu 真正建界面的路径。
	 *
	 * <p>{@code ModMenu.getConfigScreen(id, parent)} 就是列表里点「配置」时调的方法：
	 * 它从工厂建出 Screen，返回 null 就等于按钮点了没反应。这里返回了非 null 的
	 * Screen，说明 entrypoint、代理工厂、库的界面构造三层都通。</p>
	 *
	 * <p>会真正构造一次界面（内容预热有几百毫秒开销），所以只在自检模式跑。</p>
	 */
	private static String modMenuProbe(Class<?> menu) {
		if (!selfTestOpenConfig()) {
			return "";
		}
		try {
			java.lang.reflect.Method probe = null;
			for (java.lang.reflect.Method candidate : menu.getMethods()) {
				if ("getConfigScreen".equals(candidate.getName()) && candidate.getParameterCount() == 2) {
					probe = candidate;
					break;
				}
			}
			if (probe == null) {
				return "，自检建界面=找不到 getConfigScreen";
			}
			Object screen = probe.invoke(null, SmileSToolb.MOD_ID, null);
			return "，自检建界面="
					+ (screen == null ? "null（按钮点了不会有反应）" : screen.getClass().getSimpleName());
		} catch (Throwable error) {
			return "，自检建界面失败：" + error;
		}
	}

	/** 运行时初始化；两条触发路径谁先到都只执行一次。 */
	private static void initializeRuntime() {
		if (!INITIALIZED.compareAndSet(false, true)) {
			return;
		}
		SmileSToolb.LOGGER.info("运行时初始化：开始");
		SmileConfig config = SmileConfig.get();
		if (config.enabled) {
			TtsEngine.get().startAsync();
		}
		// 在线音色需要联网，放到后台拉取，避免拖慢启动
		if (config.enabled && config.includeOnlineVoices) {
			TtsEngine.get().refreshEdgeVoicesAsync();
		}
		if (config.autoEnableNarrator) {
			Ui.narratorOn();
		}
		SmileSToolb.LOGGER.info("系统语音讲述人已初始化（启用={}，在线音色={}）",
				config.enabled, config.includeOnlineVoices);

		runDiagnostics();
		// Mod Menu 的 entrypoint 收集发生在更早的客户端初始化阶段，
		// 放到这里查才准（在 client 入口里查会读到还没填表的空结果）。
		SmileSToolb.LOGGER.info("Mod Menu 集成：{}", modMenuStatus());
		// 到这里 Minecraft 已构造完成，options / 语言管理器可用，才能安全地构造界面数据
		SmileSToolb.LOGGER.info("配置界面自检：{}", configScreenSelfCheck());
		if (selfTestOpenConfig()) {
			// 实机验证用：排到下一帧打开界面，检验库内预设界面的渲染链路。
			Ui.runOnMain(() -> openConfigScreen(Ui.currentScreen()));
		}
	}

	/**
	 * 报告配置界面会画出多少项。
	 *
	 * <p>界面本体在库里，这里只验证「预设能建出来、页签与行数对得上」，
	 * 重点确认状态条那两个开关在这台机器上是否应当出现。</p>
	 */
	private static String configScreenSelfCheck() {
		ConfigSpec spec = SmileToolbConfig.build(null);
		int rows = 0;
		StringBuilder perPage = new StringBuilder();

		for (ConfigSpec.Page page : spec.pages) {
			rows += page.rows.size();
			if (perPage.length() > 0) {
				perPage.append('，');
			}
			perPage.append(page.title).append('=').append(page.rows.size());
		}

		return String.format("页签=%d（%s），共 %d 行；追踪条开关=%s，聊天草稿=%d 条，假人=%s（每次 %d 个，在线 %d 个）",
				spec.pages.size(), perPage, rows,
				BarFeatureDetector.get().present() ? "显示" : "隐藏", ChatDraftStore.count(),
				DummySpawner.available() ? "可用" : "不可用（无 /player 命令）",
				SmileConfig.get().dummyCount, DummySpawner.count());
	}

	/**
	 * 打开配置界面：预设交给库，界面由库按版本渲染。
	 *
	 * <p>父界面句柄来自库门面（{@code Object}），主菜单阶段没有父界面就传 {@code null}。</p>
	 */
	private static void openConfigScreen(Object parent) {
		Ui.open(Ui.config(parent, SmileToolbConfig.build(parent)));
	}

	/** 让隔离验证实例自动打开配置界面；正常游玩时默认关闭。 */
	private static boolean selfTestOpenConfig() {
		return Boolean.getBoolean("smile_toolb.selftest.open_config")
				|| "1".equals(System.getenv("SMILE_TOOLB_SELFTEST_OPEN_CONFIG"));
	}
}
