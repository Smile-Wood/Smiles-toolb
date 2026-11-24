package smile_toolb.client.tts;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import smile_toolb.SmileSToolb;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * Microsoft Edge 在线语音合成（read-aloud 服务）客户端。
 *
 * <h2>为什么在 Java 侧做</h2>
 * 服务端只提供 WebSocket + MP3，PCM/RIFF 一律不返回；而 Java 可以原生跑
 * {@code java.net.http.WebSocket}，不必把协议栈搬进 PowerShell。
 * 合成得到的 MP3 交给辅助进程播放（它已有 WinRT 播放器），
 * 于是“联网合成”和“本地播放”各自待在擅长的进程里。
 *
 * <h2>协议要点</h2>
 * <ul>
 *   <li>握手 URL 需要 {@code Sec-MS-GEC} 令牌（见 {@link EdgeToken}），否则 403；</li>
 *   <li>先发 {@code speech.config} 再发 {@code ssml}，音频以二进制帧返回，
 *       帧头前 2 字节是头部长度，跳过它才是音频数据；</li>
 *   <li>收到 {@code Path:turn.end} 即本次合成结束。</li>
 * </ul>
 *
 * <p>该服务属非公开接口，微软随时可能调整；调用失败时上层会退回本地语音引擎。</p>
 */
public final class EdgeTtsClient {

	private static final Logger LOGGER = SmileSToolb.LOGGER;

	private static final String BASE = "speech.platform.bing.com/consumer/speech/synthesize/readaloud";
	private static final String WSS_URL = "wss://" + BASE + "/edge/v1";
	private static final String VOICE_LIST_URL = "https://" + BASE + "/voices/list";

	private static final String USER_AGENT =
			"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
					+ "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0";

	private static final String ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold";

	private static final DateTimeFormatter TIMESTAMP =
			DateTimeFormatter.ofPattern("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",
					Locale.US);

	private static final HttpClient CLIENT = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	private static volatile WebSocket socket;
	private static volatile CompletableFuture<byte[]> pendingTurn;
	private static volatile ByteArrayOutputStream pendingAudio;
	private static volatile boolean broken = true;

	private EdgeTtsClient() {
	}

	// ------------------------------------------------------------------
	// 音色列表
	// ------------------------------------------------------------------

	/**
	 * 拉取在线音色。
	 *
	 * <p>服务端会返回三百多个音色，全部塞进选择器不现实，因此只保留与游戏
	 * 主语言一致的那些（中文环境下是 14 个，涵盖 Xiaoxiao / Yunxi 等）。</p>
	 *
	 * @param primaryLanguage 语言主标签，例如 {@code zh}
	 */
	public static List<VoiceInfo> fetchVoices(String primaryLanguage) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create(
						VOICE_LIST_URL + "?trustedclienttoken=" + EdgeToken.TRUSTED_CLIENT_TOKEN))
				.timeout(Duration.ofSeconds(15))
				.header("User-Agent", USER_AGENT)
				.header("Accept", "*/*")
				.header("Accept-Language", "en-US,en;q=0.9")
				.GET()
				.build();

		HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			throw new IllegalStateException("voice list HTTP " + response.statusCode());
		}

		JsonArray array = JsonParser.parseString(response.body()).getAsJsonArray();
		String wanted = primaryLanguage == null ? "" : primaryLanguage.toLowerCase(Locale.ROOT);
		List<VoiceInfo> voices = new ArrayList<>();
		for (JsonElement element : array) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject object = element.getAsJsonObject();
			String shortName = string(object, "ShortName");
			String locale = string(object, "Locale");
			if (shortName.isEmpty() || locale.isEmpty()) {
				continue;
			}
			String primary = locale.contains("-") ? locale.substring(0, locale.indexOf('-')) : locale;
			if (!wanted.isEmpty() && !primary.equalsIgnoreCase(wanted)) {
				continue;
			}
			voices.add(new VoiceInfo(
					VoiceInfo.ENGINE_EDGE + ":" + shortName,
					"Microsoft " + displayName(shortName),
					string(object, "Gender"),
					locale,
					true,
					VoiceInfo.ENGINE_EDGE));
		}
		LOGGER.info("在线音色：主语言 {} 命中 {} 个", wanted, voices.size());
		return voices;
	}

	/** {@code zh-CN-liaoning-XiaobeiNeural} → {@code Xiaobei}。 */
	private static String displayName(String shortName) {
		String name = shortName;
		int dash = name.lastIndexOf('-');
		if (dash >= 0) {
			name = name.substring(dash + 1);
		}
		if (name.endsWith("Neural")) {
			name = name.substring(0, name.length() - "Neural".length());
		}
		return name;
	}

	private static String string(JsonObject object, String key) {
		JsonElement element = object.get(key);
		return element == null || element.isJsonNull() ? "" : element.getAsString();
	}

	// ------------------------------------------------------------------
	// 合成
	// ------------------------------------------------------------------

	/**
	 * 合成一段语音。
	 *
	 * <p>音量不在这里处理：合成结果以原始音量返回，由播放端调节，
	 * 这样改音量不需要重新联网合成。</p>
	 *
	 * <p>连接是长连的：握手（含令牌校验）约 1 秒，而单次合成只要 0.3 秒左右，
	 * 每条语音都重连会让朗读明显滞后。连接被服务端关闭或出现异常时会自动重建。</p>
	 *
	 * @param shortName 形如 {@code zh-CN-XiaoxiaoNeural}
	 * @param rate      百分比，100 为正常语速
	 * @return MP3 数据；失败抛异常，由调用方决定回退
	 */
	public static byte[] synthesize(String shortName, String text, int rate, long timeoutMs)
			throws Exception {
		// 播放端是单线程的，这里再加一把锁把“一条连接一次只能一个回合”的约束固定下来
		synchronized (TURN_LOCK) {
			WebSocket socket = ensureSocket();
			ByteArrayOutputStream audio = new ByteArrayOutputStream();
			CompletableFuture<byte[]> turn = new CompletableFuture<>();
			pendingAudio = audio;
			pendingTurn = turn;
			try {
				socket.sendText(configMessage(), true).join();
				socket.sendText(ssmlMessage(shortName, text, rate), true).join();
				byte[] result = turn.get(timeoutMs, TimeUnit.MILLISECONDS);
				if (result.length == 0) {
					throw new IllegalStateException("服务端未返回音频");
				}
				return result;
			} catch (Exception e) {
				// 超时或异常后连接状态不可信，直接丢弃，下次重连
				closeSocket();
				throw e;
			} finally {
				pendingTurn = null;
				pendingAudio = null;
			}
		}
	}

	private static WebSocket ensureSocket() throws Exception {
		WebSocket current = socket;
		if (current != null && !broken && !current.isOutputClosed()) {
			return current;
		}
		closeSocket();
		String url = WSS_URL
				+ "?TrustedClientToken=" + EdgeToken.TRUSTED_CLIENT_TOKEN
				+ "&ConnectionId=" + UUID.randomUUID().toString().replace("-", "")
				+ "&Sec-MS-GEC=" + EdgeToken.secMsGec(System.currentTimeMillis() / 1000L)
				+ "&Sec-MS-GEC-Version=" + EdgeToken.SEC_MS_GEC_VERSION;
		long start = System.currentTimeMillis();
		WebSocket created = CLIENT.newWebSocketBuilder()
				.header("User-Agent", USER_AGENT)
				.header("Origin", ORIGIN)
				.header("Pragma", "no-cache")
				.header("Cache-Control", "no-cache")
				.header("Accept-Language", "en-US,en;q=0.9")
				.connectTimeout(Duration.ofSeconds(10))
				.buildAsync(URI.create(url), LISTENER)
				.get(15, TimeUnit.SECONDS);
		broken = false;
		socket = created;
		LOGGER.info("Edge 语音连接已建立（{} ms）", System.currentTimeMillis() - start);
		return created;
	}

	/** 主动断开长连接（关掉模组/退出游戏时用）。 */
	public static void shutdown() {
		synchronized (TURN_LOCK) {
			closeSocket();
		}
	}

	private static void closeSocket() {
		WebSocket current = socket;
		socket = null;
		broken = true;
		if (current == null) {
			return;
		}
		try {
			current.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(2, TimeUnit.SECONDS);
		} catch (Exception e) {
			current.abort();
		}
	}

	/** 单条连接上的回合状态：由监听器写入，由调用线程等待。 */
	private static final Object TURN_LOCK = new Object();

	private static final WebSocket.Listener LISTENER = new WebSocket.Listener() {
		@Override
		public void onOpen(WebSocket webSocket) {
			webSocket.request(1);
		}

		@Override
		public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
			if (data.toString().contains("Path:turn.end")) {
				CompletableFuture<byte[]> turn = pendingTurn;
				ByteArrayOutputStream audio = pendingAudio;
				if (turn != null) {
					turn.complete(audio == null ? new byte[0] : audio.toByteArray());
				}
			}
			webSocket.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
			ByteArrayOutputStream audio = pendingAudio;
			byte[] frame = new byte[data.remaining()];
			data.get(frame);
			if (audio != null && frame.length > 2) {
				// 前 2 字节是大端的头部长度，音频紧随其后
				int headerLength = ((frame[0] & 0xFF) << 8) | (frame[1] & 0xFF);
				int offset = 2 + headerLength;
				if (offset < frame.length) {
					audio.write(frame, offset, frame.length - offset);
				}
			}
			webSocket.request(1);
			return null;
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error) {
			broken = true;
			CompletableFuture<byte[]> turn = pendingTurn;
			if (turn != null) {
				turn.completeExceptionally(error);
			}
		}

		@Override
		public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
			broken = true;
			CompletableFuture<byte[]> turn = pendingTurn;
			if (turn != null) {
				turn.completeExceptionally(new java.io.IOException("连接已关闭（" + statusCode + "）"));
			}
			return null;
		}
	};

	private static String configMessage() {
		return "X-Timestamp:" + timestamp() + "\r\n"
				+ "Content-Type:application/json; charset=utf-8\r\n"
				+ "Path:speech.config\r\n\r\n"
				+ "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{"
				+ "\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},"
				+ "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}";
	}

	private static String ssmlMessage(String shortName, String text, int rate) {
		String escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
		String ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>"
				+ "<voice name='" + fullVoiceName(shortName) + "'>"
				+ "<prosody pitch='+0Hz' rate='" + rateOffset(rate) + "' volume='+0%'>"
				+ escaped
				+ "</prosody></voice></speak>";
		return "X-RequestId:" + UUID.randomUUID().toString().replace("-", "") + "\r\n"
				+ "Content-Type:application/ssml+xml\r\n"
				+ "X-Timestamp:" + timestamp() + "Z\r\n"
				+ "Path:ssml\r\n\r\n" + ssml;
	}

	/**
	 * SSML 的 voice 名必须是完整形式，短名会被服务端静默忽略（收到 response 但没有音频）。
	 */
	private static String fullVoiceName(String shortName) {
		String[] parts = shortName.split("-", 3);
		if (parts.length < 3) {
			return shortName;
		}
		String language = parts[0];
		String region = parts[1];
		String name = parts[2];
		int dash = name.indexOf('-');
		if (dash >= 0) {
			region = region + "-" + name.substring(0, dash);
			name = name.substring(dash + 1);
		}
		return "Microsoft Server Speech Text to Speech Voice (" + language + "-" + region + ", " + name + ")";
	}

	private static String rateOffset(int rate) {
		int delta = clamp(rate, 50, 200) - 100;
		return (delta >= 0 ? "+" : "") + delta + "%";
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private static String timestamp() {
		return TIMESTAMP.format(ZonedDateTime.now(ZoneOffset.UTC));
	}
}
