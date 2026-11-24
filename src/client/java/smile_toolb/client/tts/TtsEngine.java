package smile_toolb.client.tts;

import org.slf4j.Logger;
import smile_toolb.SmileSToolb;
import smile_toolb.client.config.SmileConfig;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 系统语音引擎：管理一个常驻的 PowerShell 辅助进程。
 *
 * <h2>为什么必须用子进程</h2>
 * 需求是“离线使用系统音色”。Windows 上这些音色分散在两套互不相通的语音栈里：
 * OneCore（WinRT {@code Windows.Media.SpeechSynthesis}，Natural 系列与 Huihui/Yaoyao 等在此）
 * 和 SAPI5（{@code System.Speech} / 传统桌面音色）。JVM 内既没有 WinRT 绑定，
 * 也没有 SAPI 绑定，因此由一个常驻的 PowerShell 进程同时驱动两套引擎，
 * JVM 只负责把文本与参数通过标准输入推过去。整个过程不产生任何网络请求。
 *
 * <h2>热重载</h2>
 * 语速、音量、截断长度随每条 SPEAK 下发；音色、引擎开关、总开关通过
 * {@link #applyConfig()} 即时生效——不需要重启游戏，也不需要重启辅助进程。
 *
 * <h2>协议</h2>
 * 行式、制表符分隔、所有自由文本走 Base64，避免编码与换行歧义。
 * <pre>
 * Java -&gt; 辅助进程： SPEAK &lt;seq&gt; &lt;voiceIdB64&gt; &lt;rate&gt; &lt;volume&gt; &lt;textB64&gt;
 *                    STOP | LIST | PING | QUIT
 * 辅助进程 -&gt; Java： READY
 *                    ENGINE &lt;name&gt; &lt;ok&gt; &lt;messageB64&gt;
 *                    HINT &lt;messageB64&gt;
 *                    VOICE &lt;n&gt; &lt;idB64&gt; &lt;nameB64&gt; &lt;gender&gt; &lt;language&gt; &lt;online&gt; &lt;engine&gt;
 *                    VOICES_END &lt;count&gt; | SPOKEN &lt;seq&gt; | PONG | ERR &lt;messageB64&gt;
 * </pre>
 */
public final class TtsEngine {

	private static final Logger LOGGER = SmileSToolb.LOGGER;
	private static final String SCRIPT_NAME = "narrator_helper.ps1";
	private static final String SCRIPT_RESOURCE = "/assets/smile_toolb/tts/" + SCRIPT_NAME;
	private static final long START_TIMEOUT_MS = 20_000L;
	private static final long RETRY_COOLDOWN_MS = 8_000L;
	private static final int FAILURE_THRESHOLD = 3;

	private static final TtsEngine INSTANCE = new TtsEngine();

	private final AtomicLong sequence = new AtomicLong();
	private final AtomicInteger failures = new AtomicInteger();
	private final List<VoiceInfo> incomingVoices = new ArrayList<>();
	private final Map<String, Boolean> engines = new java.util.concurrent.ConcurrentHashMap<>();
	private final List<String> hints = new CopyOnWriteArrayList<>();

	private volatile Process process;
	private volatile BufferedWriter toHelper;
	private volatile boolean ready;
	private volatile boolean starting;
	private volatile boolean unavailable;
	private volatile long lastStartAttempt;
	private volatile CompletableFuture<Void> pendingList;
	private volatile String lastError = "";
	private boolean shutdownHookInstalled;

	/** Edge 在线合成：同一时刻只保留最新一条待合成文本，模拟“打断”。 */
	private final AtomicReference<EdgeJob> edgeJob = new AtomicReference<>();
	private final AtomicBoolean edgeWorkerRunning = new AtomicBoolean();
	private final java.util.concurrent.atomic.AtomicLong edgeFileSequence = new java.util.concurrent.atomic.AtomicLong();

	/** 在线合成是否可用；{@code null} 表示尚未探测。 */
	private volatile Boolean edgeAvailable;

	private TtsEngine() {
	}

	public static TtsEngine get() {
		return INSTANCE;
	}

	// ------------------------------------------------------------------
	// 生命周期
	// ------------------------------------------------------------------

	/** 异步启动辅助进程；重复调用无副作用。 */
	public synchronized void startAsync() {
		if (this.starting || this.process != null || this.unavailable) {
			return;
		}
		if (!isWindows()) {
			this.unavailable = true;
			LOGGER.info("非 Windows 平台，系统语音引擎不启用");
			return;
		}
		this.starting = true;
		this.lastStartAttempt = System.currentTimeMillis();
		Thread boot = new Thread(this::startBlocking, "SmileTTS-Bootstrap");
		boot.setDaemon(true);
		boot.start();
	}

	/**
	 * 确保引擎处于“可用”状态，用于热重载：配置保存、重新启用、或进程意外退出后，
	 * 都会在这里按冷却时间重试，而不是要求玩家重启游戏。
	 */
	public void ensureAvailable() {
		if (this.ready || this.starting || !SmileConfig.get().enabled) {
			return;
		}
		if (System.currentTimeMillis() - this.lastStartAttempt < RETRY_COOLDOWN_MS) {
			return;
		}
		this.unavailable = false;
		this.startAsync();
	}

	/**
	 * 立刻重试启动，忽略冷却时间。用于玩家显式打开配置界面的场景——
	 * 此时玩家就是在等音色列表，不该被自动重试的冷却拦住。
	 */
	public synchronized void startNow() {
		if (this.ready || this.starting || !SmileConfig.get().enabled) {
			return;
		}
		this.unavailable = false;
		this.failures.set(0);
		this.lastStartAttempt = 0L;
		this.startAsync();
	}

	/** 等待引擎就绪；不触发启动，只等待已经在进行的启动流程。 */
	public boolean awaitReady(long timeoutMs) {
		long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
		while (!this.ready) {
			if (System.currentTimeMillis() >= deadline) {
				break;
			}
			if (!this.starting && this.process == null) {
				break;
			}
			try {
				Thread.sleep(40L);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		return this.ready;
	}

	private void startBlocking() {
		try {
			Path script = this.extractScript();
			ProcessBuilder builder = new ProcessBuilder(
					"powershell.exe",
					"-NoProfile",
					"-NonInteractive",
					"-ExecutionPolicy", "Bypass",
					"-File", script.toAbsolutePath().toString());
			builder.redirectErrorStream(false);

			Process started = builder.start();
			this.process = started;
			this.toHelper = new BufferedWriter(
					new OutputStreamWriter(started.getOutputStream(), StandardCharsets.UTF_8));

			Thread stdout = new Thread(() -> this.readLoop(started), "SmileTTS-Out");
			stdout.setDaemon(true);
			stdout.start();

			Thread stderr = new Thread(() -> this.errorLoop(started), "SmileTTS-Err");
			stderr.setDaemon(true);
			stderr.start();

			this.installShutdownHook();

			long deadline = System.currentTimeMillis() + START_TIMEOUT_MS;
			while (!this.ready && System.currentTimeMillis() < deadline) {
				if (!started.isAlive()) {
					break;
				}
				Thread.sleep(50L);
			}

			if (this.ready) {
				this.unavailable = false;
				this.lastError = "";
				LOGGER.info("系统语音引擎已就绪");
			} else {
				this.unavailable = true;
				if (this.lastError.isBlank()) {
					this.lastError = "启动超时：辅助进程未在 " + (START_TIMEOUT_MS / 1000) + " 秒内报告就绪";
				}
				LOGGER.warn("系统语音引擎启动超时或异常，已退回原版讲述人（配置界面保存可再次尝试）：{}", this.lastError);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (Throwable t) {
			this.unavailable = true;
			this.lastError = String.valueOf(t);
			LOGGER.error("启动系统语音引擎失败", t);
		} finally {
			this.starting = false;
		}
	}

	private Path extractScript() throws IOException {
		Path target = SmileSToolb.configDir().resolve(SCRIPT_NAME);
		try (InputStream in = TtsEngine.class.getResourceAsStream(SCRIPT_RESOURCE)) {
			if (in == null) {
				throw new IOException("模组内缺少辅助脚本资源: " + SCRIPT_RESOURCE);
			}
			byte[] data = in.readAllBytes();
			Files.createDirectories(target.getParent());
			Files.write(target, data);
		}
		return target;
	}

	private void installShutdownHook() {
		if (this.shutdownHookInstalled) {
			return;
		}
		this.shutdownHookInstalled = true;
		Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "SmileTTS-Shutdown"));
	}

	/**
	 * 配置保存后调用：即时应用开关状态，并刷新音色列表。
	 *
	 * <p>停止操作放到后台线程执行，避免关闭引擎时的进程等待卡住渲染线程。</p>
	 */
	public void applyConfig() {
		if (!SmileConfig.get().enabled) {
			this.stopAsync();
			return;
		}
		// 启用在线音色但列表还空着（首次启用或上次离线）时补一次拉取
		if (SmileConfig.get().includeOnlineVoices && VoiceRegistry.get().onlineVoices().isEmpty()) {
			this.refreshEdgeVoicesAsync();
		}
		if (this.ready) {
			this.refreshVoicesAsync();
			return;
		}
		// 保存配置是显式动作，跳过自动重试的冷却时间，立刻拉起
		this.unavailable = false;
		this.lastStartAttempt = 0L;
		this.startAsync();
	}

	/** 后台停止辅助进程（配置里关闭总开关时用）；若期间又被重新启用，会自动拉回来。 */
	public void stopAsync() {
		Thread worker = new Thread(() -> {
			this.shutdown();
			if (SmileConfig.get().enabled) {
				this.startAsync();
			}
		}, "SmileTTS-Stop");
		worker.setDaemon(true);
		worker.start();
	}

	public synchronized void shutdown() {
		Process current = this.process;
		this.ready = false;
		this.send("QUIT");
		Writer writer = this.toHelper;
		if (writer != null) {
			try {
				writer.close();
			} catch (IOException ignored) {
			}
		}
		if (current != null) {
			try {
				if (!current.waitFor(1500L, TimeUnit.MILLISECONDS)) {
					current.destroy();
					if (!current.waitFor(1000L, TimeUnit.MILLISECONDS)) {
						current.destroyForcibly();
					}
				}
			} catch (InterruptedException e) {
				current.destroyForcibly();
				Thread.currentThread().interrupt();
			}
		}
		this.process = null;
		this.toHelper = null;
		this.engines.clear();
		// 关闭在线合成的长连接；放到后台是因为可能正有一条合成在进行，不能卡住调用方
		Thread closer = new Thread(EdgeTtsClient::shutdown, "SmileTTS-EdgeClose");
		closer.setDaemon(true);
		closer.start();
	}

	// ------------------------------------------------------------------
	// 朗读
	// ------------------------------------------------------------------

	/**
	 * 请求朗读一段文本。
	 *
	 * @return {@code true} 表示请求已交给引擎；{@code false} 表示引擎不可用，
	 *         调用方应退回原版讲述人
	 */
	public boolean speak(String text, boolean interrupt) {
		return this.speakAs(VoiceRegistry.get().resolveSelectedId(), text, interrupt);
	}

	/**
	 * 用指定音色朗读一段文本，不修改配置（音色选择界面的即时试听用）。
	 *
	 * <p>引擎可能还在冷启动，因此在后台等一小段时间；等不到就静默放弃，
	 * 不能让渲染线程卡住。</p>
	 */
	public void preview(String voiceId, String text) {
		if (voiceId == null || voiceId.isBlank() || text == null || text.isBlank()) {
			return;
		}
		Thread worker = new Thread(() -> {
			for (int i = 0; i < 50; i++) {
				if (this.isUsable()) {
					this.speakAs(voiceId, text, true);
					return;
				}
				this.ensureAvailable();
				try {
					Thread.sleep(100L);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
			}
			LOGGER.debug("试听跳过：语音引擎未能就绪");
		}, "SmileTTS-Preview");
		worker.setDaemon(true);
		worker.start();
	}

	private boolean speakAs(String voiceId, String text, boolean interrupt) {
		if (!this.isUsable() || voiceId == null || voiceId.isBlank() || text == null || text.isBlank()) {
			return false;
		}
		SmileConfig config = SmileConfig.get();

		if (voiceId.startsWith(VoiceInfo.ENGINE_EDGE + ":")) {
			if (interrupt) {
				this.send("STOP");
				// 被打断时丢弃尚未开始合成的文本，避免“旧消息”排在后面念出来
				this.edgeJob.set(null);
			}
			this.edgeJob.set(new EdgeJob(
					voiceId.substring(VoiceInfo.ENGINE_EDGE.length() + 1),
					text, config.rate, config.volume));
			this.startEdgeWorker();
			return true;
		}

		if (interrupt) {
			this.send("STOP");
		}
		long seq = this.sequence.incrementAndGet();
		return this.send("SPEAK\t" + seq
				+ '\t' + base64(voiceId)
				+ '\t' + config.rate
				+ '\t' + config.volume
				+ '\t' + base64(text));
	}

	// ------------------------------------------------------------------
	// Edge 在线合成
	// ------------------------------------------------------------------

	/** 一条待合成的在线语音。 */
	private record EdgeJob(String shortName, String text, int rate, int volume) {
	}

	/** 在线音色是否可用（探测成功过）。 */
	public boolean isEdgeAvailable() {
		return Boolean.TRUE.equals(this.edgeAvailable);
	}

	/** 在线音色探测过的结果；{@code null} 表示还没探测。 */
	public Boolean edgeStatus() {
		return this.edgeAvailable;
	}

	/**
	 * 启动合成线程：只处理“最新一条”，旧任务直接被覆盖。
	 *
	 * <p>在线合成单次耗时数百毫秒，若按队列逐条处理，聊天频繁时声音会越拖越旧；
	 * 讲述人场景下丢弃过期消息比补念更合理。</p>
	 */
	private void startEdgeWorker() {
		if (!this.edgeWorkerRunning.compareAndSet(false, true)) {
			return;
		}
		Thread worker = new Thread(() -> {
			try {
				EdgeJob job;
				while ((job = this.edgeJob.getAndSet(null)) != null) {
					this.runEdgeJob(job);
				}
			} finally {
				this.edgeWorkerRunning.set(false);
				// 收尾竞争：刚放进去的任务可能没被循环看到
				if (this.edgeJob.get() != null) {
					this.startEdgeWorker();
				}
			}
		}, "SmileTTS-Edge");
		worker.setDaemon(true);
		worker.start();
	}

	private void runEdgeJob(EdgeJob job) {
		try {
			byte[] audio = EdgeTtsClient.synthesize(
					job.shortName(), job.text(), job.rate(), 30_000L);
			Path file = this.writeEdgeAudio(audio);
			this.edgeAvailable = true;
			this.failures.set(0);
			// 播放交给辅助进程：WinRT 播放器在那边，STOP 也能一起停掉
			this.send("EDGE\t" + base64(file.toString()) + '\t' + job.volume());
		} catch (Exception e) {
			this.edgeAvailable = false;
			this.lastError = "Edge 在线合成失败: " + e;
			LOGGER.warn("Edge 在线合成失败，改用本地音色: {}", e.toString());
			// 不让玩家听到静默：用本地引擎念同一句
			SmileConfig config = SmileConfig.get();
			long seq = this.sequence.incrementAndGet();
			this.send("SPEAK\t" + seq
					+ '\t' + base64("")
					+ '\t' + config.rate
					+ '\t' + config.volume
					+ '\t' + base64(job.text()));
		}
	}

	private Path writeEdgeAudio(byte[] audio) throws IOException {
		Path dir = SmileSToolb.configDir().resolve("cache");
		Files.createDirectories(dir);
		long sequence = this.edgeFileSequence.incrementAndGet();
		Path file = dir.resolve("edge_" + sequence + ".mp3");
		Files.write(file, audio);
		// 播放器可能还握着更早的文件，删不掉就留到下次
		long stale = sequence - 3;
		if (stale > 0) {
			try {
				Files.deleteIfExists(dir.resolve("edge_" + stale + ".mp3"));
			} catch (IOException ignored) {
			}
		}
		return file;
	}

	/** 后台拉取在线音色列表。 */
	public void refreshEdgeVoicesAsync() {
		Thread worker = new Thread(this::fetchEdgeVoices, "SmileTTS-EdgeVoices");
		worker.setDaemon(true);
		worker.start();
	}

	/** 同步拉取在线音色（配置界面打开时用，超时即放弃，不阻塞太久）。 */
	public boolean refreshEdgeVoicesBlocking(long timeoutMs) {
		Thread worker = new Thread(this::fetchEdgeVoices, "SmileTTS-EdgeVoices");
		worker.setDaemon(true);
		worker.start();
		try {
			worker.join(timeoutMs);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		return this.isEdgeAvailable() && !VoiceRegistry.get().onlineVoices().isEmpty();
	}

	private void fetchEdgeVoices() {
		try {
			List<VoiceInfo> voices = EdgeTtsClient.fetchVoices(primaryLanguage());
			if (voices.isEmpty()) {
				this.edgeAvailable = false;
				LOGGER.warn("在线音色列表为空，保留本地音色");
				return;
			}
			VoiceRegistry.get().updateOnline(voices);
			this.edgeAvailable = true;
		} catch (Exception e) {
			this.edgeAvailable = false;
			this.lastError = "获取在线音色失败: " + e;
			LOGGER.warn("获取在线音色失败: {}", e.toString());
		}
	}

	/** 当前游戏界面语言的主标签（{@code zh_cn} → {@code zh}）。 */
	private static String primaryLanguage() {
		try {
			String selected = smile_libbox.api.ui.Ui.language();
			if (selected != null && !selected.isBlank()) {
				int underscore = selected.indexOf('_');
				return underscore > 0 ? selected.substring(0, underscore) : selected;
			}
		} catch (RuntimeException e) {
			LOGGER.debug("读取游戏语言失败，按 en 处理: {}", e.toString());
		}
		return "en";
	}

	/**
	 * 试听当前选中的音色（F9 与保存配置后调用）。
	 */
	public void playSample(String text) {
		this.preview(VoiceRegistry.get().resolveSelectedId(), text);
	}

	public void stop() {
		// 在线合成可能还在路上，先丢弃待合成任务，再让辅助进程停掉正在播放的音频
		this.edgeJob.set(null);
		if (this.process != null) {
			this.send("STOP");
		}
	}

	/** 同步刷新音色列表（配置界面打开时用，超时即放弃）。 */
	public boolean refreshVoicesBlocking(long timeoutMs) {
		if (!this.isUsable()) {
			return false;
		}
		CompletableFuture<Void> future = new CompletableFuture<>();
		this.pendingList = future;
		if (!this.send("LIST")) {
			return false;
		}
		try {
			future.get(timeoutMs, TimeUnit.MILLISECONDS);
			return true;
		} catch (Exception e) {
			return false;
		} finally {
			this.pendingList = null;
		}
	}

	public void refreshVoicesAsync() {
		if (this.isUsable()) {
			this.send("LIST");
		}
	}

	public boolean isUsable() {
		return this.ready && this.failures.get() < FAILURE_THRESHOLD;
	}

	public boolean isReady() {
		return this.ready;
	}

	public boolean isUnavailable() {
		return this.unavailable && !this.starting && !this.ready;
	}

	/** 各语音引擎的可用状态，键为 {@code onecore} / {@code sapi}。 */
	public Map<String, Boolean> engineAvailability() {
		return Map.copyOf(this.engines);
	}

	/** 辅助进程上报的提示（例如检测到讲述人专用的 Natural HD 音色）。 */
	public List<String> hints() {
		return List.copyOf(this.hints);
	}

	/** 最近一次引擎错误，供配置界面直接展示，便于定位“为什么没有音色”。 */
	public String lastError() {
		return this.lastError;
	}

	// ------------------------------------------------------------------
	// 进程 I/O
	// ------------------------------------------------------------------

	private boolean send(String line) {
		BufferedWriter writer = this.toHelper;
		if (writer == null) {
			return false;
		}
		synchronized (this) {
			try {
				writer.write(line);
				writer.write('\n');
				writer.flush();
				return true;
			} catch (IOException e) {
				this.failures.incrementAndGet();
				LOGGER.warn("向语音引擎发送指令失败: {}", e.toString());
				return false;
			}
		}
	}

	private void readLoop(Process source) {
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(source.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				this.handleLine(line);
			}
		} catch (IOException e) {
			if (this.process != null) {
				LOGGER.debug("语音引擎输出流关闭: {}", e.toString());
			}
		} finally {
			this.ready = false;
			// 进程已退出：清空句柄，让 ensureAvailable() 能重新拉起
			if (source == this.process) {
				this.process = null;
				this.toHelper = null;
				if (this.lastError.isBlank()) {
					this.lastError = "辅助进程意外退出";
				}
			}
		}
	}

	private void errorLoop(Process source) {
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(source.getErrorStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (!line.isBlank()) {
					LOGGER.warn("[语音辅助进程] {}", line);
					// PowerShell 把未捕获异常、执行策略拦截等都写到 stderr，
					// 保留最后一条作为界面上的诊断信息
					this.lastError = line.length() > 300 ? line.substring(0, 300) : line;
				}
			}
		} catch (IOException ignored) {
		}
	}

	private void handleLine(String line) {
		if (line.isEmpty()) {
			return;
		}
		// 单行解析失败绝不能带走整个读线程，否则引擎会“看着就绪”却再也收不到音色
		try {
			this.dispatch(line);
		} catch (RuntimeException e) {
			LOGGER.warn("处理语音引擎消息失败: {} ({})", line, e.toString());
		}
	}

	private void dispatch(String line) {
		String[] parts = line.split("\t", -1);
		switch (parts[0]) {
			case "READY" -> {
				this.ready = true;
				this.failures.set(0);
			}
			case "ENGINE" -> {
				// ENGINE <name> <ok> <messageB64>
				if (parts.length >= 4) {
					boolean ok = "1".equals(parts[2]);
					this.engines.put(parts[1], ok);
					if (!ok) {
						LOGGER.warn("语音引擎 {} 不可用: {}", parts[1], unb64(parts[3]));
					}
				}
			}
			case "HINT" -> {
				String hint = parts.length > 1 ? unb64(parts[1]) : "";
				if (!hint.isBlank() && !this.hints.contains(hint)) {
					this.hints.add(hint);
					LOGGER.info("语音引擎提示: {}", hint);
				}
			}
			case "VOICE" -> this.collectVoice(parts);
			case "VOICES_END" -> {
				VoiceRegistry.get().updateLocal(List.copyOf(this.incomingVoices));
				this.incomingVoices.clear();
				CompletableFuture<Void> pending = this.pendingList;
				if (pending != null) {
					pending.complete(null);
				}
			}
			case "SPOKEN" -> this.failures.set(0);
			case "PLAYING" -> this.failures.set(0);
			case "ERR" -> {
				this.failures.incrementAndGet();
				String message = parts.length > 1 ? unb64(parts[1]) : "未知错误";
				this.lastError = message;
				LOGGER.warn("语音引擎报错: {}", message);
			}
			default -> LOGGER.debug("语音引擎未知消息: {}", line);
		}
	}

	private void collectVoice(String[] parts) {
		// VOICE <n> <idB64> <nameB64> <gender> <language> <online> <engine>
		if (parts.length < 7) {
			return;
		}
		// 序号回到 1 说明这是一轮新的枚举；清掉上一轮可能残留的半截数据
		if ("1".equals(parts[1]) && !this.incomingVoices.isEmpty()) {
			this.incomingVoices.clear();
		}
		String engine = parts.length > 7 && !parts[7].isBlank() ? parts[7] : VoiceInfo.ENGINE_ONECORE;
		String id = unb64(parts[2]);
		if (id.isBlank()) {
			return;
		}
		this.incomingVoices.add(new VoiceInfo(
				id,
				unb64(parts[3]),
				parts[4],
				parts[5],
				"1".equals(parts[6]),
				engine));
	}

	private static String base64(String value) {
		if (value == null) {
			return "";
		}
		return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

	private static String unb64(String value) {
		if (value == null || value.isEmpty()) {
			return "";
		}
		try {
			return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
		} catch (IllegalArgumentException e) {
			return "";
		}
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("windows");
	}
}
