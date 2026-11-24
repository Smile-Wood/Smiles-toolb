package smile_toolb.client.chat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import smile_libbox.api.event.ChatDraftEvents;
import smile_libbox.api.ui.Ui;
import smile_toolb.SmileSToolb;
import smile_toolb.client.config.SmileConfig;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * 聊天框草稿的持久化。
 *
 * <p>库侧 {@code LibChatDraftMixin} 负责在 {@code ChatScreen#init} / {@code removed} /
 * {@code handleChatInput} 里把文本交给 {@link ChatDraftEvents}；这里只做纯 Java 的存取，
 * 因此本 jar 的常量池里没有任何 {@code net.minecraft} 描述符。</p>
 *
 * <p><b>判定一律以连接地址为准</b>（{@link Ui#serverAddress()}，即玩家填写的
 * {@code ServerData.ip}），不用玩家名 / 世界名 / 客户端实例。文件刻意放在用户主目录而不是
 * {@code gameDir/config}：另一个客户端实例（不同游戏目录、不同启动器）顶号重启时仍要能
 * 读到同一份草稿，这正是「切换客户端保留」的实现方式。</p>
 *
 * <p>每次读写都重新读盘，让同时运行的两个客户端尽量看到彼此的更新；写盘用临时文件 +
 * 原子替换，避免读到写了一半的 JSON。任何 IO 异常都只记 debug，绝不让聊天框崩溃。</p>
 */
public final class ChatDraftStore {

	private static final Logger LOGGER = SmileSToolb.LOGGER;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = resolveFile();
	private static final Path TMP = FILE.resolveSibling(FILE.getFileName() + ".tmp");
	private static final String DEFAULT_PORT_SUFFIX = ":25565";

	/**
	 * 「清空」要晚于最近一次保存这么多毫秒才作数。
	 *
	 * <p>关闭聊天框时库先后给了 {@code onClose} 与 {@code removed} 两次回调，
	 * 后一次读到的输入框可能已被原版清空。玩家自己删字再关不会这么快，
	 * 所以这个窗口只挡得住重复回调，不会挡住真的清空。</p>
	 */
	private static final long CLEAR_GRACE_MILLIS = 400L;

	private ChatDraftStore() {
	}

	private static Path resolveFile() {
		String home = System.getProperty("user.home");
		Path root = (home == null || home.isBlank())
				? FabricLoader.getInstance().getGameDir()
				: Paths.get(home);
		return root.resolve(".smile_toolb").resolve("chat-drafts.json");
	}

	/** 把两个回调交给库侧总线；client 入口调用一次即可。 */
	public static void register() {
		ChatDraftEvents.onCapture(ChatDraftStore::capture);
		ChatDraftEvents.onRestore(ChatDraftStore::restore);
		LOGGER.info("聊天草稿：{}，判定=连接地址，保留={}s，文件={}",
				ChatDraftEvents.describe(), SmileConfig.get().chatDraftTtlSeconds, FILE);
		if (Boolean.getBoolean("smile_toolb.selftest.chat_draft")) {
			runSelfTest();
		}
	}

	/** 无人值守验证用：确认「写盘 → 换地址隔离 → 清空」这条纯 Java 链路真的通。 */
	private static void runSelfTest() {
		String addr = "__smile_selftest__";
		String payload = "顶号重启草稿";
		capture(addr, payload);
		String same = restore(addr);
		String other = restore("__smile_selftest_other__");
		capture(addr, "");
		String cleared = restore(addr);
		LOGGER.info("聊天草稿自检：写入→读回={}，其它地址={}，清空后={}，文件={}",
				payload.equals(same) ? "一致" : "不一致(" + same + ")",
				other == null ? "不串" : "串了(" + other + ")",
				cleared == null ? "已清除" : "仍存在",
				Files.exists(FILE) ? "保留" : "已删除");
	}

	/** 关闭聊天框 / 断开服务器 / 提交消息时由库调用；空串表示清除该地址的草稿。 */
	public static synchronized void capture(String address, String text) {
		if (!SmileConfig.get().keepChatDraft) {
			return;
		}
		try {
			Data data = read();
			purgeExpired(data);
			String key = key(address);
			if (text == null || text.isEmpty()) {
				Entry existing = data.entries.get(key);
				if (existing != null && !existing.text.isEmpty()
						&& System.currentTimeMillis() - existing.updated < CLEAR_GRACE_MILLIS) {
					// 刚存进去就被「清空」——关闭流程里 onClose / removed 各回调一次，
					// 后一次读到的输入框可能已经被原版清过，不能拿它把刚存的草稿抹掉
					LOGGER.debug("聊天草稿：忽略紧跟在保存之后的清空（{}）", key);
					return;
				}
				if (data.entries.remove(key) != null) {
					LOGGER.debug("聊天草稿：清除（{}）", key);
				}
			} else {
				Entry entry = new Entry();
				entry.text = text;
				entry.updated = System.currentTimeMillis();
				data.entries.put(key, entry);
				LOGGER.info("聊天草稿：保存 {} 字（{}）", text.length(), key);
			}
			write(data);
		} catch (Throwable t) {
			LOGGER.debug("保存聊天草稿失败: {}", t.toString());
		}
	}

	/** 打开聊天框时由库调用；返回 {@code null} 表示不干涉原版。 */
	public static synchronized String restore(String address) {
		if (!SmileConfig.get().keepChatDraft) {
			return null;
		}
		try {
			Data data = read();
			String key = key(address);
			Entry entry = data.entries.get(key);
			if (entry == null) {
				return null;
			}
			if (expired(entry)) {
				purgeExpired(data);
				write(data);
				return null;
			}
			return entry.text == null || entry.text.isEmpty() ? null : entry.text;
		} catch (Throwable t) {
			LOGGER.debug("读取聊天草稿失败: {}", t.toString());
			return null;
		}
	}

	/** 配置界面状态行用：当前仍然有效的草稿条数。 */
	public static synchronized int count() {
		try {
			Data data = read();
			purgeExpired(data);
			return data.entries.size();
		} catch (Throwable t) {
			return 0;
		}
	}

	/** 地址归一化：大小写不敏感，带不带默认端口视为同一台服务器。 */
	private static String key(String address) {
		String value = address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
		if (value.endsWith(DEFAULT_PORT_SUFFIX)) {
			value = value.substring(0, value.length() - DEFAULT_PORT_SUFFIX.length());
		}
		return value.isEmpty() ? "local" : value;
	}

	private static long ttlMillis() {
		return Math.max(60, SmileConfig.get().chatDraftTtlSeconds) * 1000L;
	}

	private static boolean expired(Entry entry) {
		return entry == null || entry.text == null || entry.text.isEmpty()
				|| entry.updated <= 0L
				|| System.currentTimeMillis() - entry.updated > ttlMillis();
	}

	private static void purgeExpired(Data data) {
		Iterator<Map.Entry<String, Entry>> it = data.entries.entrySet().iterator();
		while (it.hasNext()) {
			if (expired(it.next().getValue())) {
				it.remove();
			}
		}
	}

	private static Data read() {
		if (!Files.isRegularFile(FILE)) {
			return new Data();
		}
		try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
			Data data = GSON.fromJson(reader, Data.class);
			if (data == null) {
				return new Data();
			}
			if (data.entries == null) {
				data.entries = new HashMap<>();
			}
			return data;
		} catch (IOException | JsonSyntaxException e) {
			LOGGER.debug("聊天草稿文件不可读，按空缓存处理: {}", e.toString());
			return new Data();
		}
	}

	private static void write(Data data) throws IOException {
		if (data.entries.isEmpty()) {
			// 草稿清空后不留下空文件：自检与日常使用都不会在用户主目录堆垃圾。
			try {
				Files.deleteIfExists(FILE);
			} catch (IOException ignored) {
				// 另一个客户端可能正持有文件，忽略即可
			}
			try {
				Files.deleteIfExists(TMP);
			} catch (IOException ignored) {
				// 同上
			}
			return;
		}
		Files.createDirectories(FILE.getParent());
		try (Writer writer = Files.newBufferedWriter(TMP, StandardCharsets.UTF_8)) {
			GSON.toJson(data, writer);
		}
		try {
			Files.move(TMP, FILE, StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(TMP, FILE, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static final class Data {
		int version = 1;
		Map<String, Entry> entries = new HashMap<>();
	}

	private static final class Entry {
		String text = "";
		long updated;
	}
}
