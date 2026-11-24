package smile_toolb.client.tts;

import java.nio.charset.StandardCharsets;

/**
 * Edge 在线合成（read-aloud 服务）的访问令牌 {@code Sec-MS-GEC}。
 *
 * <p>该令牌是服务端用来拒绝非 Edge 客户端的校验值，随版本演进过：
 * <ul>
 *   <li>v1：{@code SHA-256(Windows 文件时间 + 客户端令牌)}，现已返回 403；</li>
 *   <li>v2：{@code "<CS64>-<SHA256>"}，两个摘要都基于改造过的初值与轮常量，
 *       并把前 7 字节当作预压缩块（用于让实现难以被替换）。</li>
 * </ul>
 * 本类移植的是 v2。它**不是通用哈希**，只能用于生成该令牌；算法在上游
 * {@code edge-tts} 的 {@code drm.py} 中定义，服务端一旦调整就可能失效，
 * 此时表现为连接返回 403，模组会回退到本地语音引擎。</p>
 */
public final class EdgeToken {

	/** Microsoft Edge 的公开客户端令牌。 */
	static final String TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";

	/** 令牌版本号里跟随的 Edge 版本；需要与服务端接受的版本形态一致。 */
	static final String CHROMIUM_FULL_VERSION = "143.0.3650.75";

	public static final String SEC_MS_GEC_VERSION = "2-" + CHROMIUM_FULL_VERSION;

	private static final int MASK = 0xFFFFFFFF;

	/** 32 位掩码的 long 形式；混入 long 运算时必须用它，否则 int 会被符号扩展成 -1。 */
	private static final long MASK32 = 0xFFFFFFFFL;

	private static final int[] SHA256_INITIAL = {
			0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A,
			0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19
	};

	private static final int[] SHA256_STATE = {
			0xFD9FBEA2, 0x91243CE7, 0xBE499094, 0xCD2E1E0A,
			0x6C006F49, 0x04BD35AB, 0xFEB0A7E4, 0x03EFFBD2
	};

	private static final int[] SHA256_SCHEDULE = {
			0xEB24BF9E, 0x1558691F, 0xA0C09EE2, 0x0CFA32BA,
			0xA622EE8C, 0xD1DD9491, 0xC0DD25A0, 0xF9E70AB6,
			0xF0C31573, 0x7058CE4E, 0x7188E606, 0x00E5AB33,
			0xD258B060, 0x72766564
	};

	private static final int[] ROUND_CONSTANTS = {
			0x428A2F98, 0x71374491, 0xB5C0FBCF, 0xE9B5DBA5,
			0x3956C25B, 0x59F111F1, 0x923F82A4, 0xAB1C5ED5,
			0xD807AA98, 0x12835B01, 0x243185BE, 0x550C7DC3,
			0x72BE5D74, 0x80DEB1FE, 0x9BDC06A7, 0xC19BF174,
			0xE49B69C1, 0xEFBE4786, 0x0FC19DC6, 0x240CA1CC,
			0x2DE92C6F, 0x4A7484AA, 0x5CB0A9DC, 0x76F988DA,
			0x983E5152, 0xA831C66D, 0xB00327C8, 0xBF597FC7,
			0xC6E00BF3, 0xD5A79147, 0x06CA6351, 0x14292967,
			0x27B70A85, 0x2E1B2138, 0x4D2C6DFC, 0x53380D13,
			0x650A7354, 0x766A0ABB, 0x81C2C92E, 0x92722C85,
			0xA2BFE8A1, 0xA81A664B, 0xC24B8B70, 0xC76C51A3,
			0xD192E819, 0xD6990624, 0xF40E3585, 0x106AA070,
			0x19A4C116, 0x1E376C08, 0x2748774C, 0x34B0BCB5,
			0x391C0CB3, 0x4ED8AA4A, 0x5B9CCA4F, 0x682E6FF3,
			0x748F82EE, 0x78A5636F, 0x84C87814, 0x8CC70208,
			0x90BEFFFA, 0xA4506CEB, 0xBEF9A3F7, 0xC67178F2
	};

	private static final int SHA256_ADJUSTMENT = 0xC9DC06A7;
	private static final int SHA256_SIGMA0_ADJUSTMENT = 0x059C0B80;
	private static final int SHA256_SIGMA1_ADJUSTMENT = 0x000B92C0;

	/** 首块用的轮常量：第 14 项被替换成调整值。 */
	private static final int[] BLOCK_CONSTANTS = new int[64];

	static {
		System.arraycopy(ROUND_CONSTANTS, 0, BLOCK_CONSTANTS, 0, 64);
		BLOCK_CONSTANTS[14] = SHA256_ADJUSTMENT;
	}

	private static final long CS64_FACTOR = 0x82893933L;
	private static final long CS64_ADJUSTMENT0 = 0xF2F90B1AL;
	private static final long CS64_ADJUSTMENT1 = 0x0B1A0000L;

	private static final long[] CS64_COEFFICIENTS = {
			0xE82E91B1L, 0x44DE24C6L, 0x3BEB65BCL, 0xC89C5933L, 0x8D1EC211L,
			0x314EF5D5L, 0xE92491B1L, 0xF0A5B9D5L, 0x06064CF3L, 0xDA79FA60L,
			0xBED20560L, 0x89F9C9F8L, 0x35FA38C6L, 0xCB49E3C6L
	};

	private static final long[] CS64_OFFSETS = {
			0xB32700D0L, 0x88DFF51DL, 0x29514038L, 0xB328CBDFL, 0xD405B4D5L,
			0x26E55F3CL, 0x607801C6L, 0x82B2C1FBL, 0xFA51A3DAL, 0x207CC464L,
			0x3C3C74F3L, 0xD2D14B61L, 0xBB04A2C1L, 0xC5626167L
	};

	private EdgeToken() {
	}

	/**
	 * 生成 {@code Sec-MS-GEC} 令牌。
	 *
	 * @param unixSeconds 当前 Unix 秒（服务端按 5 分钟取整，允许一定时钟偏差）
	 */
	public static String secMsGec(long unixSeconds) {
		// Windows FILETIME：自 1601-01-01 起的 100ns 计数，按 300s 取整。
		// 这里刻意用 double 复现参考实现的浮点路径：该值超过 2^53，定点与浮点
		// 生成的十进制串会差几位，而服务端校验的是字符串本身。
		double ticks = unixSeconds + 11644473600.0;
		ticks -= ticks % 300.0;
		ticks *= 1e9 / 100.0;
		byte[] data = (String.format(java.util.Locale.ROOT, "%.0f", ticks)
				+ TRUSTED_CLIENT_TOKEN).getBytes(StandardCharsets.US_ASCII);

		byte[] digest = sha256Digest(data);
		long[] cs64 = cs64Digest(data, digest);
		// 两个 32 位字按小端序拼成 16 位十六进制
		byte[] cs64Bytes = new byte[8];
		for (int i = 0; i < 4; i++) {
			cs64Bytes[i] = (byte) (cs64[0] >>> (8 * i));
			cs64Bytes[4 + i] = (byte) (cs64[1] >>> (8 * i));
		}
		return hexUpper(cs64Bytes) + "-" + hexUpper(digest);
	}

	// ------------------------------------------------------------------
	// 改造过的 SHA-256
	// ------------------------------------------------------------------

	private static byte[] sha256Digest(byte[] data) {
		if (data.length < 7) {
			throw new IllegalArgumentException("Token salt must contain at least seven bytes");
		}
		int length = 57 + data.length;
		int zeros = Math.floorMod(55 - length, 64);

		byte[] padded = new byte[data.length - 7 + 1 + zeros + 8];
		System.arraycopy(data, 7, padded, 0, data.length - 7);
		padded[data.length - 7] = (byte) 0x80;
		long bits = (long) length * 8L;
		for (int i = 0; i < 8; i++) {
			padded[padded.length - 1 - i] = (byte) (bits >>> (8 * i));
		}

		int[] state = sha256Init(data);
		int[] w = new int[64];
		for (int offset = 0; offset < padded.length; offset += 64) {
			for (int i = 0; i < 16; i++) {
				int p = offset + i * 4;
				w[i] = ((padded[p] & 0xFF) << 24)
						| ((padded[p + 1] & 0xFF) << 16)
						| ((padded[p + 2] & 0xFF) << 8)
						| (padded[p + 3] & 0xFF);
			}
			expand(w, 16);
			int[] working = compress(state, w, ROUND_CONSTANTS, 0);
			for (int i = 0; i < 8; i++) {
				state[i] += working[i];
			}
		}

		byte[] out = new byte[32];
		for (int i = 0; i < 8; i++) {
			out[i * 4] = (byte) (state[i] >>> 24);
			out[i * 4 + 1] = (byte) (state[i] >>> 16);
			out[i * 4 + 2] = (byte) (state[i] >>> 8);
			out[i * 4 + 3] = (byte) state[i];
		}
		return out;
	}

	/** 由令牌的前 7 字节推出压缩完首个（57 字节）块之后的中间状态。 */
	private static int[] sha256Init(byte[] data) {
		int[] words = new int[64];
		words[14] = ((data[0] & 0xFF) << 16) | ((data[1] & 0xFF) << 8) | (data[2] & 0xFF);
		words[15] = ((data[3] & 0xFF) << 24) | ((data[4] & 0xFF) << 16)
				| ((data[5] & 0xFF) << 8) | (data[6] & 0xFF);

		for (int i = 16; i < 30; i++) {
			int value = SHA256_SCHEDULE[i - 16];
			value += (i == 16)
					? (sigma1(words[i - 2]) ^ SHA256_SIGMA1_ADJUSTMENT)
					: sigma1(words[i - 2]);
			if (i - 7 >= 14) {
				value += words[i - 7];
			}
			if (i - 15 >= 14) {
				value += (i == 29)
						? (sigma0(words[i - 15]) ^ SHA256_SIGMA0_ADJUSTMENT)
						: sigma0(words[i - 15]);
			}
			words[i] = value;
		}
		words[30] = sigma1(words[28]) + words[23] + sigma0(words[15]) + words[14]
				+ SHA256_ADJUSTMENT - ROUND_CONSTANTS[14];
		expand(words, 31);

		int[] working = compress(SHA256_STATE, words, BLOCK_CONSTANTS, 14);
		int[] state = new int[8];
		for (int i = 0; i < 8; i++) {
			state[i] = SHA256_INITIAL[i] + working[i];
		}
		return state;
	}

	private static void expand(int[] words, int first) {
		for (int i = first; i < 64; i++) {
			words[i] = sigma1(words[i - 2]) + words[i - 7]
					+ sigma0(words[i - 15]) + words[i - 16];
		}
	}

	private static int[] compress(int[] state, int[] words, int[] constants, int firstRound) {
		int a = state[0];
		int b = state[1];
		int c = state[2];
		int d = state[3];
		int e = state[4];
		int f = state[5];
		int g = state[6];
		int h = state[7];
		for (int i = firstRound; i < 64; i++) {
			int s1 = Integer.rotateRight(e, 6) ^ Integer.rotateRight(e, 11) ^ Integer.rotateRight(e, 25);
			int ch = g ^ (e & (f ^ g));
			int t1 = h + s1 + ch + constants[i] + words[i];
			int s0 = Integer.rotateRight(a, 2) ^ Integer.rotateRight(a, 13) ^ Integer.rotateRight(a, 22);
			int maj = (a & b) | (c & (a | b));
			int t2 = s0 + maj;
			h = g;
			g = f;
			f = e;
			e = d + t1;
			d = c;
			c = b;
			b = a;
			a = t1 + t2;
		}
		return new int[]{a, b, c, d, e, f, g, h};
	}

	private static int sigma0(int value) {
		return Integer.rotateRight(value, 7) ^ Integer.rotateRight(value, 18) ^ (value >>> 3);
	}

	private static int sigma1(int value) {
		return Integer.rotateRight(value, 17) ^ Integer.rotateRight(value, 19) ^ (value >>> 10);
	}

	// ------------------------------------------------------------------
	// CS64 混合摘要
	// ------------------------------------------------------------------

	private static long[] cs64Digest(byte[] data, byte[] digest) {
		long keyA = readLE32(digest, 0) | 1L;
		long keyB = readLE32(digest, 4) | 1L;
		long[] factors = {(keyA * CS64_FACTOR) & MASK32, (keyB * CS64_FACTOR) & MASK32};

		int wordCount = wordCountEven(data);
		long[] terms = new long[14 + wordCount];
		long[] offsets = new long[14 + wordCount];
		for (int i = 0; i < 14; i++) {
			terms[i] = (CS64_COEFFICIENTS[i] * factors[i % 2]) & MASK32;
			offsets[i] = CS64_OFFSETS[i];
		}

		byte[] prefixed = new byte[data.length + 1];
		System.arraycopy(data, 0, prefixed, 1, data.length);
		int[] words = toWordsLE(prefixed, wordCount);
		for (int i = 0; i < wordCount; i++) {
			long word = words[i] & MASK32;
			long term = word * ((i % 2 == 0) ? keyA : keyB);
			long offset = (rol16(word) * ((i % 2 == 0) ? 0xEF0569FBL : 0xC31713DBL));
			if (i == 0) {
				term += CS64_ADJUSTMENT0 * factors[0];
				offset += CS64_ADJUSTMENT1;
			}
			terms[14 + i] = term & MASK32;
			offsets[14 + i] = offset & MASK32;
		}

		long swapAcc = 0;
		long swapLast = 0;
		long state = 0;
		long acc = 0;
		for (int i = 0; i < terms.length; i += 2) {
			long x = terms[i];
			long y = terms[i + 1];
			long t = rol16((offsets[i] + x) & MASK32);
			long q = (t * 0x76F50F34L) & MASK32;
			swapAcc = (swapAcc + ((rol16(q) * 0xC3EFEA97L + q) & MASK32)) & MASK32;
			t = rol16((offsets[i + 1] + y) & MASK32);
			q = (t * 0x1881CE3CL) & MASK32;
			swapLast = (rol16(q) * 0x35BD1EC9L + q) & MASK32;
			swapAcc = (swapAcc + swapLast) & MASK32;

			state = (state * keyA + x) & MASK32;
			state = (rol16(state) * 0xCF98B111L) & MASK32;
			state = (rol16(state) * 0x87085B9FL) & MASK32;
			state = (rol16(state) * 0x12CEB96DL) & MASK32;
			state = (rol16(state) * 0x257E1D83L) & MASK32;
			acc += state;
			t = (state * keyB + y) & MASK32;
			t = (rol16(t) * 0xA27416F5L) & MASK32;
			t = (rol16(t) * 0xD38396FFL) & MASK32;
			state = (rol16((rol16(t) * 0x7C932B89L) & MASK32) * 0xBFA49F69L) & MASK32;
			acc = (acc + state) & MASK32;
		}
		return new long[]{swapLast ^ state, swapAcc ^ acc};
	}

	private static int wordCountEven(byte[] prefixed) {
		int padded = (prefixed.length + 3) / 4 * 4;
		int count = padded / 4;
		return (count % 2 == 0) ? count : count + 1;
	}

	private static int[] toWordsLE(byte[] data, int count) {
		int[] words = new int[count];
		for (int i = 0; i < count; i++) {
			int p = i * 4;
			int b0 = p < data.length ? data[p] & 0xFF : 0;
			int b1 = p + 1 < data.length ? data[p + 1] & 0xFF : 0;
			int b2 = p + 2 < data.length ? data[p + 2] & 0xFF : 0;
			int b3 = p + 3 < data.length ? data[p + 3] & 0xFF : 0;
			words[i] = b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
		}
		return words;
	}

	private static long readLE32(byte[] data, int offset) {
		return (data[offset] & 0xFFL)
				| ((data[offset + 1] & 0xFFL) << 8)
				| ((data[offset + 2] & 0xFFL) << 16)
				| ((data[offset + 3] & 0xFFL) << 24);
	}

	private static long rol16(long value) {
		value &= MASK32;
		return ((value << 16) | (value >>> 16)) & MASK32;
	}

	private static String hexUpper(byte[] bytes) {
		StringBuilder sb = new StringBuilder(bytes.length * 2);
		for (byte b : bytes) {
			sb.append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)));
			sb.append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
		}
		return sb.toString();
	}
}
