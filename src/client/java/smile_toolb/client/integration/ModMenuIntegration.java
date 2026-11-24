package smile_toolb.client.integration;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import smile_libbox.api.ui.Ui;
import smile_toolb.client.config.SmileToolbConfig;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Mod Menu 的配置入口：只做「报名」，界面由库按预设渲染。
 *
 * <p><b>为什么用 {@link Proxy} 而不是匿名类</b>：{@code ConfigScreenFactory} 的方法签名是
 * {@code Screen create(Screen parent)}，直接实现它会把 {@code Screen} 写进常量池。而
 * {@code Screen} 在两个命名空间里名字不同 —— intermediary 是 {@code net/minecraft/class_437}，
 * 26.2 的 named 是 {@code net/minecraft/client/gui/screens/Screen}。一个类文件只能写一种，
 * 这就是过去必须按版本各出一个 jar 的**唯一**原因。</p>
 *
 * <p>改成运行时代理后，本类只引用 Mod Menu 自己的接口（包名跨版本一致），
 * {@code Screen} 只出现在接口的方法描述符里、由代理机制负责装箱拆箱，
 * 我们自己一行都不碰。于是整个模组从「9 个 jar」变成「1 个 jar」：
 * 1.20.1 ~ 26.2 共用同一份字节码，界面差异全部由前置库的按版本适配层吸收。</p>
 *
 * <p>这个类只在装了 Mod Menu 时才会被加载：Loader 的 entrypoint 是惰性解析的，
 * 没装 Mod Menu 就没有任何代码会来取 {@code modmenu} 入口。因此 Mod Menu 只写在
 * {@code suggests} 里，不是必需依赖。</p>
 */
public final class ModMenuIntegration implements ModMenuApi {

	/** 工厂实例无状态，建一次就够（Mod Menu 可能反复取）。 */
	private static final ConfigScreenFactory<?> FACTORY = createFactory();

	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return FACTORY;
	}

	private static ConfigScreenFactory<?> createFactory() {
		return (ConfigScreenFactory<?>) Proxy.newProxyInstance(
				ConfigScreenFactory.class.getClassLoader(),
				new Class<?>[]{ConfigScreenFactory.class},
				new ScreenFactory());
	}

	/**
	 * 只认 {@code create}：参数是当前版本的父界面句柄（我们当 {@code Object} 收），
	 * 返回值是库的界面宿主 —— 它本身就是那个版本的 {@code Screen} 子类，
	 * 代理会按接口声明类型做一次 checkcast，类型一定对得上。
	 */
	private static final class ScreenFactory implements InvocationHandler {

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) {
			switch (method.getName()) {
				case "create":
					Object parent = args != null && args.length > 0 ? args[0] : null;
					return Ui.config(parent, SmileToolbConfig.build(parent));
				case "equals":
					return Boolean.valueOf(proxy == args[0]);
				case "hashCode":
					return Integer.valueOf(System.identityHashCode(proxy));
				case "toString":
					return "SmileToolbConfigScreenFactory";
				default:
					throw new UnsupportedOperationException(method.toString());
			}
		}
	}
}
