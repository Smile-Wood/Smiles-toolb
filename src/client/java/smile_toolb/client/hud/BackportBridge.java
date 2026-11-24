package smile_toolb.client.hud;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import smile_toolb.SmileSToolb;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 与 VanillaBackport（1.20.1 的定位条 backport）之间的只读软桥接。
 *
 * <p>这个模组把 1.21.9 的 waypoint 体系整套搬到了 1.20.1，但它的定位条是
 * <b>叠加覆盖式</b>的：{@code LocatorBarRenderer#shouldSkipRendering()} 是唯一总闸门，
 * 返回 false 时才会在经验条 / 跳跃条上面盖一层定位条背景与路径点图标。
 * 本模组通过软目标 mixin 改写这个闸门的返回值，因此需要在运行时读取它的两个状态：</p>
 *
 * <ul>
 *   <li>{@code ClientWaypointManager#hasWaypoints()}：没有路径点时不要强行画空条；</li>
 *   <li>{@code ClientConfig#enableLocatorBar}：模组自己的总开关必须被尊重。</li>
 * </ul>
 *
 * <p>全部走反射，不引入编译期依赖；任何反射失败都返回「不干预」的安全值，
 * 这样 backport 换版本改了内部 API 也只会让本功能失效，不会让游戏崩。结果只解析一次。</p>
 */
public final class BackportBridge {

	public static final String MOD_ID = "vanillabackport";

	private static final Logger LOGGER = SmileSToolb.LOGGER;

	private static final String RENDERER =
			"com.blackgear.vanillabackport.client.api.modules.waypoints.LocatorBarRenderer";
	private static final String MANAGER =
			"com.blackgear.vanillabackport.client.api.modules.waypoints.ClientWaypointManager";
	private static final String CORE = "com.blackgear.vanillabackport.core.VanillaBackport";
	private static final String CLIENT_CONFIG = "com.blackgear.vanillabackport.client.ClientConfig";

	private static volatile Boolean available;
	private static boolean resolved;
	private static Method hasWaypoints;
	private static Object manager;
	private static Method enableGet;
	private static Method infoBarGet;
	private static Object enableLocatorBar;
	private static Object locatorDisplayInfoBar;

	private BackportBridge() {
	}

	/**
	 * 是否确实装了带定位条渲染器的 VanillaBackport。
	 *
	 * <p>既看 mod id，也看渲染器类是否存在：这个 backport 有很多「特性模块」，
	 * 光看 id 不够，得确认定位条这套类真的在。</p>
	 */
	public static boolean available() {
		Boolean value = available;
		if (value == null) {
			value = checkAvailability();
			available = value;
		}
		return value;
	}

	private static boolean checkAvailability() {
		try {
			if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) {
				return false;
			}
			Class.forName(RENDERER, false, BackportBridge.class.getClassLoader());
			return true;
		} catch (Throwable ignored) {
			return false;
		}
	}

	/** 显示名，用于配置界面的一行提示。 */
	public static String displayName() {
		try {
			return FabricLoader.getInstance().getModContainer(MOD_ID)
					.map(container -> {
						String name = container.getMetadata().getName();
						String version = container.getMetadata().getVersion().getFriendlyString();
						return name + " " + version;
					})
					.orElse("VanillaBackport");
		} catch (Throwable ignored) {
			return "VanillaBackport";
		}
	}

	/** 附近是否存在可显示的路径点；拿不到时返回 false（保持原版行为）。 */
	public static boolean hasWaypoints() {
		resolve();
		if (hasWaypoints == null || manager == null) {
			return false;
		}
		try {
			Object value = hasWaypoints.invoke(manager);
			return value instanceof Boolean && (Boolean) value;
		} catch (Throwable ignored) {
			return false;
		}
	}

	/** backport 自己的定位条总开关是否开启；拿不到时返回 false（不接管）。 */
	public static boolean locatorEnabled() {
		resolve();
		return readBoolean(enableGet, enableLocatorBar);
	}

	/**
	 * backport 的「保留经验条 / 跳跃条」选项是否被打开。
	 *
	 * <p>这个选项为 true 时，它自己的 {@code renderBackground()} 会提前返回，
	 * 导致定位条背景画不出来（只剩路径点图标叠在经验条上）。配置界面据此提示用户。</p>
	 */
	public static boolean infoBarMode() {
		resolve();
		return readBoolean(infoBarGet, locatorDisplayInfoBar);
	}

	private static boolean readBoolean(Method getter, Object configEntry) {
		if (getter == null || configEntry == null) {
			return false;
		}
		try {
			Object value = getter.invoke(configEntry);
			return value instanceof Boolean && (Boolean) value;
		} catch (Throwable ignored) {
			return false;
		}
	}

	private static synchronized void resolve() {
		if (resolved) {
			return;
		}
		resolved = true;
		try {
			Class<?> managerClass = Class.forName(MANAGER);
			manager = managerClass.getField("INSTANCE").get(null);
			hasWaypoints = managerClass.getMethod("hasWaypoints");

			Class<?> coreClass = Class.forName(CORE);
			Object clientConfig = coreClass.getField("CLIENT_CONFIG").get(null);
			Class<?> configClass = Class.forName(CLIENT_CONFIG);
			Field enableField = configClass.getField("enableLocatorBar");
			Field infoBarField = configClass.getField("locatorDisplayInfoBar");
			enableLocatorBar = enableField.get(clientConfig);
			locatorDisplayInfoBar = infoBarField.get(clientConfig);
			// 两个配置项可能是不同的实现类，各取各的 get()，避免 invoke 时类型不匹配
			enableGet = enableLocatorBar.getClass().getMethod("get");
			infoBarGet = locatorDisplayInfoBar.getClass().getMethod("get");
			LOGGER.info("VanillaBackport 定位条接口解析成功，状态条开关已接管");
		} catch (Throwable error) {
			LOGGER.warn("VanillaBackport 定位条接口解析失败，状态条开关将不干预它的渲染：{}",
					error.toString());
			hasWaypoints = null;
			manager = null;
			enableGet = null;
			infoBarGet = null;
			enableLocatorBar = null;
			locatorDisplayInfoBar = null;
		}
	}
}
