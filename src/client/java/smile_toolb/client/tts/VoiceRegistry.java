package smile_toolb.client.tts;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import smile_toolb.SmileSToolb;
import smile_toolb.client.config.SmileConfig;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 系统音色注册表。
 *
 * <p>音色有两个来源：本地引擎（辅助进程枚举的 OneCore + SAPI5）与在线引擎
 * （Edge 云端语音列表，由 Java 侧直接拉取）。两者分别维护、合并后对外提供，
 * 这样一个来源刷新失败不会影响另一个。</p>
 *
 * <p>列表整体替换而非原地清空重填：渲染线程随时可能读取，不能让它看到“中间态空列表”，
 * 否则配置界面会偶发地显示成没有音色。两类音色都缓存到同一个文件，
 * 离线启动时界面也能立即列出上次的结果。</p>
 */
public final class VoiceRegistry {

	private static final Logger LOGGER = SmileSToolb.LOGGER;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type LIST_TYPE = new TypeToken<List<VoiceInfo>>() {
	}.getType();
	private static final Path CACHE =
			FabricLoader.getInstance().getConfigDir().resolve("smile_toolb").resolve("voices.json");

	private static final VoiceRegistry INSTANCE = new VoiceRegistry();

	/** 中文优先，其次按语言、引擎（本地在前）、名称排序。 */
	private static final Comparator<VoiceInfo> ORDER = Comparator
			.comparing((VoiceInfo v) -> !v.getLanguage().toLowerCase(Locale.ROOT).startsWith("zh"))
			.thenComparing(VoiceInfo::getLanguage)
			.thenComparingInt(VoiceRegistry::engineRank)
			.thenComparing(VoiceInfo::getName);

	private static int engineRank(VoiceInfo voice) {
		if (voice.isEdge()) {
			return 2;
		}
		return voice.isSapi() ? 1 : 0;
	}

	private volatile List<VoiceInfo> local = List.of();
	private volatile List<VoiceInfo> online = List.of();
	/** {@link #local} 与 {@link #online} 的合并快照。 */
	private volatile List<VoiceInfo> merged = List.of();

	private VoiceRegistry() {
	}

	public static VoiceRegistry get() {
		return INSTANCE;
	}

	/** 从磁盘缓存载入音色列表（客户端初始化时调用），按引擎拆回两个来源。 */
	public void loadCache() {
		if (!Files.isRegularFile(CACHE)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(CACHE, StandardCharsets.UTF_8)) {
			List<VoiceInfo> cached = GSON.fromJson(reader, LIST_TYPE);
			if (cached == null || cached.isEmpty()) {
				return;
			}
			List<VoiceInfo> localCache = new ArrayList<>();
			List<VoiceInfo> onlineCache = new ArrayList<>();
			for (VoiceInfo voice : cached) {
				(voice.isEdge() ? onlineCache : localCache).add(voice);
			}
			this.local = List.copyOf(localCache);
			this.online = List.copyOf(onlineCache);
			remerge();
			LOGGER.info("已从缓存载入 {} 个音色（本地 {}，在线 {}）",
					cached.size(), localCache.size(), onlineCache.size());
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("读取音色缓存失败: {}", e.toString());
		}
	}

	/** 由引擎线程写入本地（辅助进程）枚举结果。 */
	public void updateLocal(List<VoiceInfo> list) {
		this.local = sane(list);
		remerge();
		LOGGER.info("本地音色 {} 个（OneCore {}，SAPI5 {}），在线 {} 个",
				this.local.size(),
				this.local.stream().filter(v -> !v.isSapi()).count(),
				this.local.stream().filter(VoiceInfo::isSapi).count(),
				this.online.size());
		saveCache();
	}

	/** 写入 Edge 在线语音列表（Java 侧拉取）。 */
	public void updateOnline(List<VoiceInfo> list) {
		this.online = sane(list);
		remerge();
		LOGGER.info("在线音色 {} 个，合计 {} 个", this.online.size(), this.merged.size());
		saveCache();
	}

	private static List<VoiceInfo> sane(List<VoiceInfo> list) {
		// 不同引擎可能报出同名音色，按 Id 去重，保持枚举顺序
		Map<String, VoiceInfo> unique = new LinkedHashMap<>();
		if (list != null) {
			for (VoiceInfo voice : list) {
				if (voice != null && voice.getId() != null && !voice.getId().isBlank()) {
					unique.put(voice.getId(), voice);
				}
			}
		}
		return List.copyOf(unique.values());
	}

	private void remerge() {
		List<VoiceInfo> all = new ArrayList<>(this.local);
		all.addAll(this.online);
		this.merged = List.copyOf(all);
	}

	private void saveCache() {
		// 空结果不写缓存，避免一次失败的枚举把上次可用的列表覆盖掉
		if (this.merged.isEmpty()) {
			return;
		}
		try {
			Files.createDirectories(CACHE.getParent());
			try (Writer writer = Files.newBufferedWriter(CACHE, StandardCharsets.UTF_8)) {
				GSON.toJson(new ArrayList<>(this.merged), LIST_TYPE, writer);
			}
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("写入音色缓存失败: {}", e.toString());
		}
	}

	public List<VoiceInfo> all() {
		return this.merged;
	}

	public List<VoiceInfo> localVoices() {
		return this.local;
	}

	public List<VoiceInfo> onlineVoices() {
		return this.online;
	}

	/** 配置界面里可选的音色（是否包含在线音色受配置控制）。 */
	public List<VoiceInfo> visible() {
		boolean includeOnline = SmileConfig.get().includeOnlineVoices;
		List<VoiceInfo> result = new ArrayList<>();
		for (VoiceInfo voice : this.merged) {
			if (includeOnline || !voice.isOnline()) {
				result.add(voice);
			}
		}
		result.sort(ORDER);
		return result;
	}

	/** 系统里是否存在需要联网的在线音色；一个都没有时配置界面不显示该开关。 */
	public boolean hasOnlineVoices() {
		for (VoiceInfo voice : this.merged) {
			if (voice.isOnline()) {
				return true;
			}
		}
		return false;
	}

	public VoiceInfo byId(String id) {
		if (id == null || id.isBlank()) {
			return null;
		}
		for (VoiceInfo voice : this.merged) {
			if (id.equals(voice.getId())) {
				return voice;
			}
		}
		return null;
	}

	/**
	 * 解析当前应使用的音色 Id：配置里的音色若已失效（例如被系统卸载），
	 * 则退回第一个可用音色。
	 */
	public String resolveSelectedId() {
		String configured = SmileConfig.get().voiceId;
		VoiceInfo selected = byId(configured);
		if (selected == null && configured != null && configured.startsWith("HKEY_")) {
			// 旧版本保存的是不带引擎前缀的 OneCore 路径，这里做一次平滑迁移
			selected = byId(VoiceInfo.ENGINE_ONECORE + ":" + configured);
		}
		if (selected != null) {
			return selected.getId();
		}
		List<VoiceInfo> candidates = this.visible();
		if (candidates.isEmpty()) {
			return "";
		}
		return candidates.get(0).getId();
	}

	public String labelOf(String id) {
		VoiceInfo voice = this.byId(id);
		return voice == null ? id : voice.label();
	}

	public boolean isEmpty() {
		return this.merged.isEmpty();
	}
}
