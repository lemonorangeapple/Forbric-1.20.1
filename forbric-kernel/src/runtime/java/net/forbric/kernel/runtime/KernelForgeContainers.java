/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.runtime;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import net.forbric.kernel.boot.KernelForgeModContext.Handle;
import net.minecraftforge.eventbus.api.BusBuilder;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.IModBusEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.javafmlmod.FMLModContainer;
import net.minecraftforge.fml.lowcodemod.LowCodeModContainer;
import net.minecraftforge.forgespi.language.ModFileScanData;

/**
 * The game side of the kernel's traditional-MinecraftForge loading context: an EventBus-6 {@code IEventBus} plus an
 * {@code FMLModContainer} plus an {@code FMLJavaModLoadingContext}, manufactured without ModLauncher, module layer,
 * or FML discovery/sorting.
 *
 * <p>1.20.1's {@code FMLModContainer} constructor eagerly {@code Class.forName}s the mod class, but the kernel
 * publishes a container for every mod <em>before</em> it knows the {@code @Mod} class (discovery and construction
 * are separate stages). So the container is allocated around its constructor and the fields the constructor would
 * have set are filled in, the way the previous generation did.
 */
public final class KernelForgeContainers {
	private KernelForgeContainers() {
	}

	public static Handle create(String modId) throws Exception {
		FMLModContainer container = KernelUnsafe.newInstance(FMLModContainer.class);
		Object bus = BusBuilder.builder()
				.setTrackPhases(true)
				.markerType(IModBusEvent.class)
				.build();
		FMLJavaModLoadingContext jctx = KernelUnsafe.newInstance(FMLJavaModLoadingContext.class);
		uset(FMLJavaModLoadingContext.class, "container", jctx, container);

		// ModContainer's constructor (skipped) initialises these; addConfig / registerExtensionPoint / the
		// activity + dependency maps all NPE on a null.
		uset(ModContainer.class, "modId", container, modId);
		uset(ModContainer.class, "namespace", container, modId);
		uset(ModContainer.class, "modInfo", container, new KernelForgeModInfo(modId));
		uset(ModContainer.class, "configs", container, new EnumMap<ModConfig.Type, Object>(ModConfig.Type.class));
		uset(ModContainer.class, "extensionPoints", container, new ConcurrentHashMap<>());
		usetIfPresent(ModContainer.class, "activityMap", container, new HashMap<>());
		uset(ModContainer.class, "contextExtension", container, (Supplier<Object>) () -> jctx);
		// ModContainer's constructor defaults configHandler to empty and the stage to CONSTRUCT; skipping it left
		// both null, and ConfigTracker.openConfig dereferences configHandler on every config it opens.
		uset(ModContainer.class, "configHandler", container, java.util.Optional.empty());
		usetIfPresent(ModContainer.class, "modLoadingStage", container,
				net.minecraftforge.fml.ModLoadingStage.CONSTRUCT);
		usetIfPresent(FMLModContainer.class, "scanResults", container, new ModFileScanData());
		uset(FMLModContainer.class, "eventBus", container, bus);
		usetIfPresent(FMLModContainer.class, "context", container, jctx);

		return new Handle(modId, bus, container, jctx);
	}

	/**
	 * The container MinecraftForge builds for a {@code lowcodefml} mod, through its own public constructor.
	 */
	public static Object lowCode(String modId, java.nio.file.Path jar) {
		return new LowCodeModContainer(new KernelForgeModInfo(modId, jar), new ModFileScanData(), null);
	}

	/** Makes {@code container} the active {@code ModLoadingContext} — what every {@code *.get()} reads. */
	@SuppressWarnings("removal")
	public static void setActiveContainer(Object container) throws Exception {
		ModLoadingContext mlc = ModLoadingContext.get();
		Method setActive = ModLoadingContext.class.getDeclaredMethod("setActiveContainer", ModContainer.class);
		setActive.setAccessible(true);
		setActive.invoke(mlc, container);
	}

	/**
	 * Constructs {@code modClassName} against {@code handle}, preferring the {@code (FMLJavaModLoadingContext)}
	 * constructor traditional-Forge mods declare, and stores the instance on the container.
	 */
	public static Object constructMod(String modClassName, Handle handle) throws Exception {
		Class<?> modCls = Class.forName(modClassName, true, KernelForgeContainers.class.getClassLoader());
		Object mod;
		try {
			Constructor<?> c = modCls.getDeclaredConstructor(FMLJavaModLoadingContext.class);
			c.setAccessible(true);
			mod = c.newInstance(handle.jctx());
		} catch (NoSuchMethodException noCtxCtor) {
			Constructor<?> c = modCls.getDeclaredConstructor();
			c.setAccessible(true);
			mod = c.newInstance();
		}
		usetIfPresent(FMLModContainer.class, "modInstance", handle.container(), mod);
		usetIfPresent(FMLModContainer.class, "modClass", handle.container(), modCls);
		return mod;
	}

	/** EventBus 6 has no startup gate; nothing to open. */
	public static void startup(Object busGroup) {
	}

	/**
	 * Seeds the {@code FMLLoader} statics Forge reads everywhere but ModLauncher would have set: the
	 * {@code VersionInfo} (whose absence NPEs {@code ForgeVersion.<clinit>}) and the current {@code Dist}.
	 * Without this, constructing {@code ForgeMod} dies before its {@code DeferredRegister}s ever register.
	 */
	public static void seedLoaderStatics(String forgeVersion, String mcVersion, String mcpVersion, boolean client)
			throws Exception {
		ClassLoader cl = KernelForgeContainers.class.getClassLoader();
		Class<?> fmlLoader = Class.forName("net.minecraftforge.fml.loading.FMLLoader", false, cl);
		Object versionInfo = Class.forName("net.minecraftforge.fml.loading.VersionInfo", false, cl)
				.getConstructor(String.class, String.class, String.class, String.class)
				.newInstance(forgeVersion, mcVersion, mcpVersion, "net.minecraftforge");
		setStatic(fmlLoader, "versionInfo", versionInfo);
		// Forge's patched Block.initClient asks the launch handler isData(); ModLauncher would have set it.
		try {
			Class<?> handlerType = Class.forName(client
					? "net.minecraftforge.fml.loading.targets.ForgeClientLaunchHandler"
					: "net.minecraftforge.fml.loading.targets.ForgeServerLaunchHandler", true, cl);
			setStatic(fmlLoader, "commonLaunchHandler", KernelUnsafe.newInstance(handlerType));
		} catch (Throwable absent) {
			net.forbric.kernel.util.ForbricLog.debug("[Forbric/ForgeCtx] could not seed the Forge launch handler: %s",
					absent);
		}
		Class<?> distType = Class.forName("net.minecraftforge.api.distmarker.Dist", true, cl);
		setStatic(fmlLoader, "dist", distType.getField(client ? "CLIENT" : "DEDICATED_SERVER").get(null));
		// FMLConfig reads its own fml.toml through FMLPaths, which ModLauncher would have rooted at the game dir.
		// The game is launched with cwd == game dir, so user.dir is that root. Without this, a config value read
		// during config registration NPEs inside FMLConfig$ConfigValue.
		java.nio.file.Path gameDir = java.nio.file.Path.of(System.getProperty("user.dir", ".")).toAbsolutePath();
		Class<?> fmlPaths = Class.forName("net.minecraftforge.fml.loading.FMLPaths", true, cl);
		fmlPaths.getMethod("loadAbsolutePaths", java.nio.file.Path.class).invoke(null, gameDir);
		Class<?> fmlConfig = Class.forName("net.minecraftforge.fml.loading.FMLConfig", true, cl);
		fmlConfig.getMethod("load").invoke(null);
	}

	private static void setStatic(Class<?> owner, String name, Object value) throws Exception {
		Field field = owner.getDeclaredField(name);
		field.setAccessible(true);
		field.set(null, value);
	}

	/** Writes a field that MUST exist; a missing one is a real change in the carrier and has to be loud. */
	private static void uset(Class<?> owner, String fieldName, Object target, Object value) throws Exception {
		Field field = owner.getDeclaredField(fieldName);
		KernelUnsafe.setField(field, target, value);
	}

	/** Writes a field only some Forge revisions declare. */
	private static void usetIfPresent(Class<?> owner, String fieldName, Object target, Object value) {
		try {
			uset(owner, fieldName, target, value);
		} catch (Throwable absent) {
			net.forbric.kernel.util.ForbricLog.debug("[Forbric/ForgeCtx] %s has no %s field — skipped: %s",
					owner.getSimpleName(), fieldName, String.valueOf(absent));
		}
	}
}
