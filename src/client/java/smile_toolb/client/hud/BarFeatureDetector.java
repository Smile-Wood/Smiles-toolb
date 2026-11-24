package smile_toolb.client.hud;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import smile_libbox.api.GameVersion;
import smile_toolb.SmileSToolb;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 判断当前运行环境里「快捷栏上方那一条」会不会出现定位条，以及由谁提供。
 *
 * <p>不能只看游戏版本：1.21.9 之前没有原版定位条，但整合包里常见把定位条搬到低版本的模组。
 * 本类的结论用于决定配置界面要不要显示那两个状态条开关，以及要不要给出兼容性提示。</p>
 *
 * <p>检测按可靠度从高到低：</p>
 * <ol>
 *   <li>{@link Kind#VANILLA}：游戏版本 &ge; 1.21.9，原版上下文条，主 mixin 完整接管；</li>
 *   <li>{@link Kind#VANILLA_BACKPORT}：VanillaBackport（1.20.1），已做针对性适配，
 *       由 {@link smile_toolb.client.mixin.VanillaBackportBarMixin} 接管；</li>
 *   <li>{@link Kind#API_BACKPORT}：低版本里 {@code Gui} 上出现了返回「四状态枚举」的方法，
 *       说明有别的模组把原版那套上下文条 API 也搬了过来（尚未适配）；</li>
 *   <li>{@link Kind#THIRD_PARTY}：按模组 id / 名称关键词推断出存在自绘定位条模组。
 *       这类模组的条是它们自己渲染的，本模组无法接管。</li>
 * </ol>
 *
 * <p>结果只算一次并缓存；模组增删需要重启游戏，缓存不会过期问题。</p>
 */
public final class BarFeatureDetector {

	public enum Kind {
		VANILLA,
		VANILLA_BACKPORT,
		API_BACKPORT,
		THIRD_PARTY,
		NONE
	}

	public static final class Result {
		public final Kind kind;
		public final String source;

		private Result(Kind kind, String source) {
			this.kind = kind;
			this.source = source;
		}

		/** 是否应该把状态条相关的配置项显示出来。 */
		public boolean present() {
			return this.kind != Kind.NONE;
		}

		/** 本模组当前的 mixin 是否能真正接管这条状态栏。 */
		public boolean manageable() {
			return this.kind == Kind.VANILLA || this.kind == Kind.VANILLA_BACKPORT;
		}
	}

	private static final Result NONE = new Result(Kind.NONE, "");

	/** 名称关键词：覆盖绝大多数定位条 / 路径点类模组。 */
	private static final String[] KEYWORDS = {"locator", "waypoint"};

	/** 不含以上关键词、但确实提供定位条的已知模组 id。 */
	private static final String[] EXTRA_IDS = {"wayfarer"};

	/** 内置模组，不参与推断。 */
	private static final String[] BUILTIN_IDS = {"minecraft", "java", "smile_toolb", "vanillabackport"};

	private static volatile Result cached;

	private BarFeatureDetector() {
	}

	public static Result get() {
		Result value = cached;
		if (value == null) {
			value = detect();
			cached = value;
			// 这条日志是「某个版本为什么没有状态条开关」的唯一排查入口，别降级成 debug。
			// 版本判断走库的纯 Java API，不引用任何 MC 类，两代命名空间都能打印。
			SmileSToolb.LOGGER.info("状态条特性检测：游戏版本={}，atLeast(1.21.9)={}，结论={}，来源={}，"
							+ "显示开关={}，静态判定可接管={}，上下文枚举可用={}",
					GameVersion.display(), GameVersion.atLeast("1.21.9"), value.kind,
					value.source.isEmpty() ? "无" : value.source, value.present(), value.manageable(),
					ContextualBarController.states() != null);
			if (value.kind == Kind.VANILLA_BACKPORT) {
				SmileSToolb.LOGGER.info("检测到 VanillaBackport 定位条（{}），已启用针对性接管", value.source);
			} else if (value.present() && !value.manageable()) {
				SmileSToolb.LOGGER.info("检测到外部定位条特性（{}，来源 {}）；状态条开关会显示，"
						+ "但当前构建只能接管原版上下文条与 VanillaBackport", value.kind, value.source);
			}
		}
		return value;
	}

	private static Result detect() {
		if (GameVersion.atLeast("1.21.9")) {
			return new Result(Kind.VANILLA, "vanilla");
		}
		// 先精确识别：这个模组 id / 名字里不含 locator、waypoint，关键词推断抓不到它
		if (BackportBridge.available()) {
			return new Result(Kind.VANILLA_BACKPORT, BackportBridge.displayName());
		}
		if (hasContextualBarApi()) {
			return new Result(Kind.API_BACKPORT, "Gui contextual bar backport");
		}
		List<String> mods = findLocatorMods();
		if (!mods.isEmpty()) {
			return new Result(Kind.THIRD_PARTY, String.join("、", mods));
		}
		return NONE;
	}

	/**
	 * 低版本里是否真的被搬来了原版那套上下文条。
	 *
	 * <p>判据是：{@code Gui} 里存在一个方法，返回值类型正好是那个有 4 个常量的状态枚举。
	 * 按类型而不是按方法名找，可以避免 backport 模组改名或换 mappings 导致漏检。</p>
	 */
	private static boolean hasContextualBarApi() {
		// 库按命名空间提供这套枚举常量；低版本里拿不到就说明没有被 backport 进来。
		Object[] states = ContextualBarController.states();
		return states != null && states.length == 4 && states[0] != null;
	}

	/** 按 id / 名称推断哪些模组可能提供了定位条；这是启发式判断，可能误报。 */
	private static List<String> findLocatorMods() {
		List<String> found = new ArrayList<>();
		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			String id = mod.getMetadata().getId().toLowerCase(Locale.ROOT);
			if (isBuiltin(id)) {
				continue;
			}
			String name = mod.getMetadata().getName();
			String haystack = id + " " + name.toLowerCase(Locale.ROOT);
			boolean matched = matchesKeyword(haystack);
			if (!matched) {
				for (String extra : EXTRA_IDS) {
					if (id.equals(extra)) {
						matched = true;
						break;
					}
				}
			}
			if (matched) {
				found.add(name);
			}
		}
		return found;
	}

	private static boolean matchesKeyword(String haystack) {
		for (String keyword : KEYWORDS) {
			if (haystack.contains(keyword)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isBuiltin(String id) {
		for (String builtin : BUILTIN_IDS) {
			if (id.equals(builtin) || id.startsWith("fabric")) {
				return true;
			}
		}
		return false;
	}
}
