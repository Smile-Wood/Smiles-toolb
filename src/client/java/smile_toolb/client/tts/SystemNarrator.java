package smile_toolb.client.tts;

import com.mojang.text2speech.Narrator;
import org.slf4j.Logger;
import smile_libbox.client.compat.VersionedNarrator;
import smile_toolb.SmileSToolb;
import smile_toolb.client.config.SmileConfig;

import java.util.Locale;

/**
 * 用系统语音（Windows WinRT 音色，含 Natural HD）替换原版讲述人。
 *
 * <p>原版讲述人最终走的是 {@code com.mojang.text2speech} 的 SAPI 实现，
 * 只能使用少量 SAPI5 音色、且音量/语速不可控。这里实现同一个
 * {@link Narrator} 接口，由 {@code NarratorFactoryMixin} 在工厂方法返回时
 * 把它包上去，于是聊天朗读、界面朗读、叙述者状态等原版逻辑全部保持不变，
 * 只有“发声”这一步换成了系统语音引擎。</p>
 *
 * <p>1.21.5 前后 {@code say} 差一个音量参数，这段签名差异由
 * {@link VersionedNarrator} 按游戏版本编译，本类只实现 {@link #onSay} 回调。</p>
 *
 * <p>设计上保证“绝不比原版更差”：引擎不可用、被关闭或合成失败时，
 * 一律退回被包装的原版讲述人。</p>
 */
public final class SystemNarrator extends VersionedNarrator {

	private static final Logger LOGGER = SmileSToolb.LOGGER;

	private static SystemNarrator instance;

	private SystemNarrator(Narrator fallback) {
		super(fallback);
	}

	/**
	 * 包装原版讲述人。仅在 Windows 且原版实例可用时生效。
	 *
	 * @param original {@code Narrator.getNarrator()} 的原返回值
	 * @return 替换实例；返回 {@code null} 表示不做替换
	 */
	public static synchronized Narrator wrap(Narrator original) {
		if (original == null || original instanceof SystemNarrator) {
			return null;
		}
		if (!isWindows()) {
			return null;
		}
		if (instance == null) {
			instance = new SystemNarrator(original);
			LOGGER.info("讲述人已切换到系统语音引擎");
		}
		return instance;
	}

	@Override
	protected void onSay(String msg, boolean interrupt, float volume) {
		SmileConfig config = SmileConfig.get();
		if (!config.enabled) {
			this.fallbackSay(msg, interrupt, volume);
			return;
		}
		String text = sanitize(msg, config.maxChars);
		if (text.isEmpty()) {
			return;
		}
		// 引擎未就绪时顺手拉起一次（带冷却），这样在游戏内重新启用后无需重启
		TtsEngine.get().ensureAvailable();
		if (!TtsEngine.get().speak(text, interrupt)) {
			// 引擎未就绪 / 已失效：退回原版，避免出现“静默”
			this.fallbackSay(msg, interrupt, volume);
		}
	}

	@Override
	public void clear() {
		if (SmileConfig.get().enabled) {
			TtsEngine.get().stop();
		} else {
			this.fallback().clear();
		}
	}

	@Override
	public boolean active() {
		return SmileConfig.get().enabled || this.fallback().active();
	}

	@Override
	public void destroy() {
		TtsEngine.get().shutdown();
		this.fallback().destroy();
	}

	/**
	 * 清理待朗读文本：去掉 Minecraft 格式化符号、控制字符，压缩空白并截断，
	 * 避免把 {@code §c} 这类符号念出来，也避免超长聊天记录让语音引擎长时间占用。
	 */
	static String sanitize(String raw, int maxChars) {
		if (raw == null) {
			return "";
		}
		String text = raw.replaceAll("\u00a7.", " ");
		text = text.replaceAll("[\\p{Cntrl}&&[^\t]]", " ");
		text = text.replaceAll("\\s+", " ").trim();
		if (maxChars > 0 && text.length() > maxChars) {
			text = text.substring(0, maxChars);
		}
		return text;
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
	}
}
