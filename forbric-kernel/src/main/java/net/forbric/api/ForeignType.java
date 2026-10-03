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

package net.forbric.api;

/**
 * The Forge-family types the kernel names by string, as ONE row per concept instead of two literals per call site.
 *
 * <p>The two Forge-family ecosystems ship the same concept under different names, and the kernel has to name both
 * because it drives both. Written inline that is two string constants sitting next to each other at every site --
 * these concepts span transform/, boot/ and interop/ -- and each pair is an invitation to handle one family and forget
 * the other. {@code ClientPackHookInjector} and {@code ForeignModPresenceInjector} both carry exactly that shape.
 *
 * <h2>Why a table of pairs and not a prefix rule</h2>
 *
 * <p>Because there is no prefix rule. NeoForge splits across THREE roots: what descends from FML keeps
 * {@code net.minecraftforge.} ({@code fml.*}, {@code bus.*}, {@code api.distmarker.*}); the mod-facing game API sits
 * under {@code net.minecraftforge.} ({@code registries.*}, {@code client.*}, {@code common.*}, {@code event.*});
 * and the loader SPI the two families share by shape sits under {@code net.minecraftforge.forgespi.}
 * ({@code language.IModInfo}, {@code language.IConfigurable}) against MinecraftForge's {@code forgespi.*}.
 * MinecraftForge has one root for all three. So {@code fml.ModList} pairs
 * {@code net.minecraftforge.fml.ModList} with {@code net.minecraftforge.fml.ModList}, while {@code registries.GameData}
 * pairs {@code net.minecraftforge.registries.GameData} with {@code net.minecraftforge.registries.GameData}.
 * The package path does not have to match either: {@code NetworkRegistry} is {@code network.NetworkRegistry} on
 * one side and {@code network.registration.NetworkRegistry} on the other.
 * A swap-the-prefix helper gets these wrong, silently, and a name that does not resolve here does not
 * throw -- it just means a transform never fires.
 *
 * <h2>What this deliberately does NOT unify</h2>
 *
 * <p>Only the NAME. Where the two families' members differ in shape, that difference stays at the call site as
 * data. {@code LifecycleHookInjector}'s two server triggers are the standing example: same concept
 * ({@code ServerModLoader.load}), but NeoForge's is {@code (Z)V} and MinecraftForge's is {@code ()V}, and they
 * redirect to different kernel hooks. Folding descriptors in here would be the same mistake that cost
 * {@code KernelEventSubscribers} three simultaneous bugs -- a hub carries per-family divergence as data, it does
 * not average it away.
 */
public enum ForeignType {
	CLIENT_HOOKS("net.minecraftforge.client.ForgeHooksClient",
			"net.minecraftforge.client.ClientHooks"),
	CLIENT_MOD_LOADER("net.minecraftforge.client.loading.ClientModLoader",
			"net.minecraftforge.client.loading.ClientModLoader"),
	CLIENT_TOOLTIP_COMPONENT_MANAGER("net.minecraftforge.client.gui.ClientTooltipComponentManager",
			"net.minecraftforge.client.gui.ClientTooltipComponentManager"),
	COLOR_RESOLVER_MANAGER("net.minecraftforge.client.ColorResolverManager",
			"net.minecraftforge.client.ColorResolverManager"),
	CONFIG_TRACKER("net.minecraftforge.fml.config.ConfigTracker",
			"net.minecraftforge.fml.config.ConfigTracker"),
	CONFIGURABLE("net.minecraftforge.forgespi.language.IConfigurable",
			"net.minecraftforge.forgespi.language.IConfigurable"),
	DIST("net.minecraftforge.api.distmarker.Dist",
			"net.minecraftforge.api.distmarker.Dist"),
	EVENT_HOOKS("net.minecraftforge.common.ForgeHooks",
			"net.minecraftforge.event.EventHooks"),
	/** The base of every event each family's bus dispatches (EventChainAuditInjector wraps both dispatches). */
	EVENT("net.minecraftforge.eventbus.internal.Event",
			"net.minecraftforge.eventbus.api.Event"),
	EVENT_BUS("net.minecraftforge.eventbus.api.bus.EventBus", "net.minecraftforge.bus.EventBus"),
	EVENT_LISTENER("net.minecraftforge.eventbus.api.listener.EventListener", "net.minecraftforge.eventbus.api.EventListener"),
	DATAPACK_NEW_REGISTRY_EVENT("net.minecraftforge.registries.DataPackRegistryEvent$NewRegistry",
			"net.minecraftforge.registries.DataPackRegistryEvent$NewRegistry"),
	DATAPACK_REGISTRY_DATA("net.minecraftforge.registries.DataPackRegistryEvent$DataPackRegistryData",
			"net.minecraftforge.registries.DataPackRegistryEvent$DataPackRegistryData"),
	FLUID_INTERACTION_REGISTRY("net.minecraftforge.fluids.FluidInteractionRegistry",
			"net.minecraftforge.fluids.FluidInteractionRegistry"),
	BLOCK_TINT_EVENT("net.minecraftforge.client.event.RegisterColorHandlersEvent$Block",
			"net.minecraftforge.client.event.RegisterColorHandlersEvent$BlockTintSources"),
	// The mod-lifecycle phases. Paired because the kernel posts each one at BOTH families and the two events are
	// different classes on different bus shapes -- naming either half inline is how one family silently stops
	// receiving a phase, which is exactly what happened to traditional MinecraftForge until 2026-09-13.
	/**
	 * The earliest mod-bus phase there is: genuine FML posts it at each container the moment it is built.
	 *
	 * <p>Pairing it is what showed that only NeoForge mods were getting it — the kernel named NeoForge's class
	 * inline and there was no second half for anyone to notice was missing.
	 */
	FML_CONSTRUCT_MOD_EVENT("net.minecraftforge.fml.event.lifecycle.FMLConstructModEvent",
			"net.minecraftforge.fml.event.lifecycle.FMLConstructModEvent"),
	FML_CLIENT_SETUP_EVENT("net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent",
			"net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent"),
	FML_COMMON_SETUP_EVENT("net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent",
			"net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent"),
	FML_DEDICATED_SERVER_SETUP_EVENT("net.minecraftforge.fml.event.lifecycle.FMLDedicatedServerSetupEvent",
			"net.minecraftforge.fml.event.lifecycle.FMLDedicatedServerSetupEvent"),
	FML_LOAD_COMPLETE_EVENT("net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent",
			"net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent"),
	/**
	 * The one-shot cache of {@link #FML_LOADER}'s answers. MinecraftForge's is four {@code public static final}
	 * fields decided by a {@code <clinit>} that cannot throw, so whoever touches it first decides them forever;
	 * NeoForge's is stateless. The kernel reads these fields back to check its own seeding took.
	 */
	FML_ENVIRONMENT("net.minecraftforge.fml.loading.FMLEnvironment",
			"net.minecraftforge.fml.loading.FMLEnvironment"),
	FML_LOADER("net.minecraftforge.fml.loading.FMLLoader",
			"net.minecraftforge.fml.loading.FMLLoader"),
	FML_MOD_CONTAINER("net.minecraftforge.fml.javafmlmod.FMLModContainer",
			"net.minecraftforge.fml.javafmlmod.FMLModContainer"),
	FML_MOD_LOADER("net.minecraftforge.fml.ModLoader",
			"net.minecraftforge.fml.ModLoader"),
	FML_PATHS("net.minecraftforge.fml.loading.FMLPaths",
			"net.minecraftforge.fml.loading.FMLPaths"),
	GAME_DATA("net.minecraftforge.registries.GameData",
			"net.minecraftforge.registries.GameData"),
	KEY_MAPPING_LOOKUP("net.minecraftforge.client.settings.KeyMappingLookup",
			"net.minecraftforge.client.settings.KeyMappingLookup"),
	/**
	 * Each family's own built-in translations — the table its screens read before any resource pack exists.
	 *
	 * <p>Paired because the loader on it is called from the client mod loader the kernel replaces, so BOTH halves
	 * need calling and a half named inline is a family whose screens quietly render raw keys.
	 */
	LANGUAGE_HOOK("net.minecraftforge.server.LanguageHook",
			"net.minecraftforge.server.LanguageHook"),
	LOADING_MOD_LIST("net.minecraftforge.fml.loading.LoadingModList",
			"net.minecraftforge.fml.loading.LoadingModList"),
	INTER_MOD_ENQUEUE_EVENT("net.minecraftforge.fml.event.lifecycle.InterModEnqueueEvent",
			"net.minecraftforge.fml.event.lifecycle.InterModEnqueueEvent"),
	INTER_MOD_PROCESS_EVENT("net.minecraftforge.fml.event.lifecycle.InterModProcessEvent",
			"net.minecraftforge.fml.event.lifecycle.InterModProcessEvent"),
	// The two families' Mods screens. Paired because the kernel REPLACES both: which one the merged pause menu's
	// button is bound to is a byte-merge outcome, and naming only the winner would make the replacement quietly
	// conditional on a merge detail that has changed before.
	MOD_LIST_SCREEN("net.minecraftforge.client.gui.ModListScreen",
			"net.minecraftforge.client.gui.modlist.ModListScreen"),
	MOD_BUS_EVENT("net.minecraftforge.fml.event.IModBusEvent",
			"net.minecraftforge.fml.event.IModBusEvent"),
	MOD_CONFIG_TYPE("net.minecraftforge.fml.config.ModConfig$Type",
			"net.minecraftforge.fml.config.ModConfig$Type"),
	MOD_CONTAINER("net.minecraftforge.fml.ModContainer",
			"net.minecraftforge.fml.ModContainer"),
	MOD_FILE("net.minecraftforge.fml.loading.moddiscovery.ModFile",
			"net.minecraftforge.fml.loading.moddiscovery.ModFile"),
	MOD_FILE_TYPE("net.minecraftforge.forgespi.locating.IModFile$Type",
			"net.minecraftforge.forgespi.locating.IModFile$Type"),
	/**
	 * Each family's annotation index for one mod file. Paired because the kernel seeds BOTH loading lists with
	 * files that must answer {@code getScanResult()}, and a half named inline is a family whose annotation walkers
	 * find nothing — or, on NeoForge, throw "Scanning of this mod file has not started yet." (RollingGate).
	 */
	MOD_FILE_SCAN_DATA("net.minecraftforge.forgespi.language.ModFileScanData",
			"net.minecraftforge.forgespi.language.ModFileScanData"),
	/**
	 * Each family's rewriter for enums a mod may add constants to. Same job, and the two are reached the same
	 * way — the kernel supplies the class node and their compiled processor does the rewrite — but they sit in
	 * different packages and take different arguments, so both injectors name this and neither may drift alone.
	 */
	RUNTIME_ENUM_EXTENDER("net.minecraftforge.fml.common.asm.RuntimeEnumExtender",
			"net.minecraftforge.fml.common.asm.enumextension.RuntimeEnumExtender"),
	MOD_FILE_INFO("net.minecraftforge.fml.loading.moddiscovery.ModFileInfo",
			"net.minecraftforge.fml.loading.moddiscovery.ModFileInfo"),
	MOD_INFO("net.minecraftforge.fml.loading.moddiscovery.ModInfo",
			"net.minecraftforge.fml.loading.moddiscovery.ModInfo"),
	MOD_INFO_SPI("net.minecraftforge.forgespi.language.IModInfo",
			"net.minecraftforge.forgespi.language.IModInfo"),
	MOD_LIST("net.minecraftforge.fml.ModList",
			"net.minecraftforge.fml.ModList"),
	MOD_LOADING_CONTEXT("net.minecraftforge.fml.ModLoadingContext",
			"net.minecraftforge.fml.ModLoadingContext"),
	NEW_REGISTRY_EVENT("net.minecraftforge.registries.NewRegistryEvent",
			"net.minecraftforge.registries.NewRegistryEvent"),
	/**
	 * The resource-condition type both families dispatch datapack elements through. They are separate registries
	 * with separate dialects, and the merged base runs BOTH evaluators over every element from every pack — so a
	 * condition one family cannot resolve used to fail the whole registry load, and the world with it.
	 */
	ICONDITION("net.minecraftforge.common.crafting.conditions.ICondition",
			"net.minecraftforge.common.conditions.ICondition"),
	NETWORK_REGISTRY("net.minecraftforge.network.NetworkRegistry",
			"net.minecraftforge.network.registration.NetworkRegistry"),
	PRESET_EDITOR_MANAGER("net.minecraftforge.client.PresetEditorManager",
			"net.minecraftforge.client.PresetEditorManager"),
	REGISTER_EVENT("net.minecraftforge.registries.RegisterEvent",
			"net.minecraftforge.registries.RegisterEvent"),
	REGISTRY_MANAGER("net.minecraftforge.registries.RegistryManager",
			"net.minecraftforge.registries.RegistryManager"),
	SERVER_LIFECYCLE_HOOKS("net.minecraftforge.server.ServerLifecycleHooks",
			"net.minecraftforge.server.ServerLifecycleHooks"),
	SERVER_MOD_LOADER("net.minecraftforge.server.loading.ServerModLoader",
			"net.minecraftforge.server.loading.ServerModLoader"),
	SPAWN_PLACEMENT_EVENT("net.minecraftforge.event.entity.SpawnPlacementRegisterEvent",
			"net.minecraftforge.event.entity.RegisterSpawnPlacementsEvent"),
	BIOME_MODIFIER("net.minecraftforge.common.world.BiomeModifier",
			"net.minecraftforge.common.world.BiomeModifier"),
	STRUCTURE_MODIFIER("net.minecraftforge.common.world.StructureModifier",
			"net.minecraftforge.common.world.StructureModifier"),
	MODIFIER_REGISTRY_KEYS("net.minecraftforge.registries.ForgeRegistries$Keys",
			"net.minecraftforge.registries.NeoForgeRegistries$Keys"),
	MOB_SPAWN_SETTINGS_BUILDER("net.minecraftforge.common.world.MobSpawnSettingsBuilder",
			"net.minecraftforge.common.world.MobSpawnSettingsBuilder"),
	REMOVE_SPAWNS_BIOME_MODIFIER("net.minecraftforge.common.world.ForgeBiomeModifiers$RemoveSpawnsBiomeModifier",
			"net.minecraftforge.common.world.BiomeModifiers$RemoveSpawnsBiomeModifier"),
	/** The static hook class each family's patched game calls to post its events. */
	EVENT_FACTORY("net.minecraftforge.event.ForgeEventFactory", "net.minecraftforge.event.EventHooks"),
	/**
	 * Each family's fluid type: the merged Fluid answers NeoForge's (ForeignFluidTypeInjector gives a fluid without one
	 * the type its tags imply) and a vanilla fluid is bridged to MinecraftForge's (ForbricMergedBaseCompatTransformer).
	 */
	FLUID_TYPE("net.minecraftforge.fluids.FluidType", "net.minecraftforge.fluids.FluidType"),
	/**
	 * Each family's multipart-entity part: the Ender Dragon's parts are one or the other, and every consumer in the
	 * merged game casts to NeoForge's (DragonPartsInjector, and the frame recomputer that follows its rebase).
	 */
	PART_ENTITY("net.minecraftforge.entity.PartEntity", "net.minecraftforge.entity.PartEntity"),
	/**
	 * Each family's client-command registration event, both handing out the one {@code CommandDispatcher} type: the
	 * merged game runs NeoForge's, and MinecraftForge's is posted with that dispatcher (KernelGameClientNetworkEvents).
	 */
	CLIENT_COMMANDS_EVENT("net.minecraftforge.client.event.RegisterClientCommandsEvent",
			"net.minecraftforge.client.event.RegisterClientCommandsEvent"),
	/** Each family's global-loot-modifier reload listener: same directory, two ideas of what a list file is. */
	LOOT_MODIFIER_MANAGER("net.minecraftforge.common.loot.LootModifierManager",
			"net.minecraftforge.common.loot.LootModifierManager");

	private final String forge;
	private final String neoforge;

	ForeignType(String forge, String neoforge) {
		this.forge = forge;
		this.neoforge = neoforge;
	}

	/** The binary (dotted) name in {@code ecosystem}, or {@code null} for {@link Ecosystem#FABRIC}. */
	public String binary(Ecosystem ecosystem) {
		return switch (ecosystem) {
			case FORGE -> forge;
			case NEOFORGE -> neoforge;
			case FABRIC -> null;
		};
	}

	/** The internal (slash) name in {@code ecosystem}, for ASM. */
	public String internal(Ecosystem ecosystem) {
		String binary = binary(ecosystem);
		return binary == null ? null : binary.replace('.', '/');
	}

	/** Whether {@code binaryName} is this concept in EITHER Forge-family ecosystem. */
	public boolean matches(String binaryName) {
		return forge.equals(binaryName) || neoforge.equals(binaryName);
	}
}
