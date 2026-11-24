package smile_toolb;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public class SmileSToolb implements ModInitializer {
	public static final String MOD_ID = "smile_toolb";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// This code runs as soon as Minecraft is in a mod-load-ready state.
		// However, some things (like resources) may still be uninitialized.
		// Proceed with mild caution.
		LOGGER.info("Smile's toolb 已加载");
	}

	/** 模组私有配置目录：{@code .minecraft/config/smile_toolb}。 */
	public static Path configDir() {
		return FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
	}
}
