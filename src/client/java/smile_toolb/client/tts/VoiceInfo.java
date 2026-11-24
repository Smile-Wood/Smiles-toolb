package smile_toolb.client.tts;

/**
 * 一条系统语音（音色）描述。
 *
 * <p>字段刻意保持为公有可变字段，便于 Gson 直接读写缓存文件。</p>
 */
public final class VoiceInfo {

	/** OneCore 语音库（WinRT 合成），含 Natural 系列里的非讲述人专用音色。 */
	public static final String ENGINE_ONECORE = "onecore";

	/** 传统 SAPI5 桌面音色（System.Speech 合成）。 */
	public static final String ENGINE_SAPI = "sapi";

	/** Edge 在线合成（云端返回 MP3，需联网）。 */
	public static final String ENGINE_EDGE = "edge";

	/** 音色标识，带引擎前缀：{@code onecore:HKEY_LOCAL_MACHINE\...} 或 {@code sapi:Microsoft Zira Desktop}。 */
	public String id = "";

	/** 展示名称，例如 {@code Microsoft Yaoyao}。 */
	public String name = "";

	/** 性别：Female / Male / NotSet。 */
	public String gender = "";

	/** BCP-47 语言标记，例如 {@code zh-CN}。 */
	public String language = "";

	/** 需要联网合成的音色（名称或 Id 含 Online），默认隐藏。 */
	public boolean online = false;

	/** 提供该音色的引擎，见 {@link #ENGINE_ONECORE} / {@link #ENGINE_SAPI}。 */
	public String engine = ENGINE_ONECORE;

	public VoiceInfo() {
	}

	public VoiceInfo(String id, String name, String gender, String language, boolean online, String engine) {
		this.id = id;
		this.name = name;
		this.gender = gender;
		this.language = language;
		this.online = online;
		this.engine = engine;
	}

	public String getId() {
		return this.id;
	}

	public String getName() {
		return this.name;
	}

	public String getLanguage() {
		return this.language;
	}

	public boolean isOnline() {
		return this.online;
	}

	/** 引擎名；旧缓存文件没有该字段，按 OneCore 处理。 */
	public String getEngine() {
		return this.engine == null || this.engine.isBlank() ? ENGINE_ONECORE : this.engine;
	}

	public boolean isSapi() {
		return ENGINE_SAPI.equals(this.getEngine());
	}

	public boolean isEdge() {
		return ENGINE_EDGE.equals(this.getEngine());
	}

	/** 下拉列表中显示的文本。 */
	public String label() {
		StringBuilder sb = new StringBuilder(this.name);
		if (this.language != null && !this.language.isBlank()) {
			sb.append(" [").append(this.language).append(']');
		}
		if (this.online) {
			sb.append(" (在线)");
		}
		return sb.toString();
	}

	@Override
	public String toString() {
		return "VoiceInfo[" + this.name + ", " + this.language + ", engine=" + this.getEngine()
				+ ", online=" + this.online + "]";
	}
}
