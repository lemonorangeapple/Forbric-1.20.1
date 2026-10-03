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

package net.forbric.kernel.boot;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;

/**
 * Constructs the traditional-MinecraftForge baseline mod ({@code net.minecraftforge.common.ForgeMod}) and fires
 * its {@code RegisterEvent}s natively — the Forge-family twin of the NeoForge baseline in {@link KernelLifecycle}.
 *
 * <p>The merged base's Forge-patched vanilla code reads traditional-Forge {@code RegistryObject}s (e.g.
 * {@code ForgeMod.EMPTY_TYPE}, the empty {@code forge:fluid_type}, dereferenced by {@code EntityFluidInteraction}
 * when a chest minecart spawns). Those only bind if {@code ForgeMod}'s {@code DeferredRegister}s register and the
 * ForgeRegistries bake.
 *
 * <p>The context/bus/registry-event mechanics live in {@link KernelForgeModContext}, shared with the real
 * third-party Forge {@code @Mod}s that {@link KernelModLoader} constructs — the baseline is just its first client.
 */
public final class KernelForgeBaseline {
	private static final String FORGE_MOD = "net.minecraftforge.common.ForgeMod";

	private KernelForgeBaseline() {
	}

	/**
	 * Constructs ForgeMod, creates Forge's custom registries, then fires the Forge {@code RegisterEvent} stream for
	 * the baseline <em>and</em> for {@code modHandles} — the real traditional-Forge {@code @Mod}s
	 * {@link KernelModLoader} already constructed. They ride the same pass because {@code NewRegistryEvent} must have
	 * created the custom registries (forge:fluid_type et al.) before anything enumerates them, and every
	 * {@code DeferredRegister} — baseline or mod — flushes off the same event.
	 */
	public static void register(ClassLoader cl, List<KernelForgeModContext.Handle> modHandles, boolean client) {
		try {
			Class.forName(FORGE_MOD, false, cl);
		} catch (ClassNotFoundException absent) {
			ForbricLog.debug("[Forbric/Forge] traditional-Forge ForgeMod not present — skipping");
			return;
		}
		// Forge's own version classes read FMLLoader.versionInfo()/getDist(), which only ModLauncher would have
		// populated. Bookkeeping NPEs there used to take ForgeMod (and every forge: registry it declares) down.
		try {
			KernelForgeModContext.seedLoaderStatics(cl,
					KernelBoot.descriptor("forge.version", "47.4.0"),
					KernelBoot.descriptor("minecraft.version", "1.20.1"),
					"20230612.114412", client);
		} catch (Throwable seedFailure) {
			ForbricLog.debug("[Forbric/Forge] could not seed FMLLoader statics: %s", Reflect.unwrap(seedFailure));
		}
		// The baseline's own bring-up and the RegisterEvent pass are separated deliberately. They used to share one
		// try, so a ForgeMod that failed to construct took the whole pass with it — including every real mod's
		// RegisterEvent, which has nothing to do with ForgeMod. The mods can register without the baseline; what
		// they lose is forge:fluid_type and friends, which is a smaller loss than registering nothing at all.
		KernelForgeModContext.Handle baseline = null;
		try {
			baseline = KernelForgeModContext.create(cl, "forge");
			// create() no longer makes its container active — it is called for every mod long before any of them
			// constructs now — so the constructor's window is opened and closed here, the way the mod path does it.
			KernelForgeModContext.setActiveContainer(cl, baseline.container());
			Object mod;
			try {
				mod = KernelForgeModContext.constructMod(cl, FORGE_MOD, baseline);
			} finally {
				KernelForgeModContext.setActiveContainer(cl, null);
			}
			ForbricLog.info("[Forbric/Forge] constructed traditional-Forge baseline mod ForgeMod -> %s", mod);
			KernelForgeModContext.startup(cl, baseline.busGroup());
		} catch (Throwable t) {
			baseline = null;
			ForbricLog.warn("[Forbric/Forge] the traditional-Forge baseline mod (ForgeMod) did not come up — its own "
					+ "content (forge:fluid_type and the rest) will be missing, but every real MinecraftForge mod "
					+ "still gets its RegisterEvent", Reflect.unwrap(t));
		}

		try {
			// Forge's CUSTOM registries (forge:fluid_type, holder_set_type, biome/structure_modifier_serializers, …)
			// are created by ForgeMod's DeferredRegister subscribers to NewRegistryEvent — never by GameData.init(),
			// which only wraps the vanilla BuiltInRegistries. The kernel fires RegisterEvent but never NewRegistryEvent,
			// so those registries never exist and RegistryObjects like ForgeMod.EMPTY_TYPE (minecraft:empty fluid type,
			// read by EntityFluidInteraction when a chest minecart spawns during worldgen) stay unbound. Fire it FIRST
			// so the registries exist before the RegisterEvent pass enumerates + populates them.
			List<KernelForgeModContext.Handle> all = new java.util.ArrayList<>();
			if (baseline != null) all.add(baseline);
			all.addAll(modHandles);
			int created = fireNewRegistryEvent(cl, all);
			int n = KernelForgeModContext.fireRegisterEvents(cl, all);
			ForbricLog.info("[Forbric/Forge] created %d custom registr(ies) via NewRegistryEvent + fired Forge "
					+ "RegisterEvent x%d on %d bus(es) [%s + %d mod(s)]", created, n, all.size(),
					baseline == null ? "NO baseline" : "baseline", modHandles.size());
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Forge] the traditional-Forge RegisterEvent pass failed as a whole — this is "
					+ "not one mod's listener throwing (those are isolated and named individually); nothing a "
					+ "MinecraftForge mod declares through DeferredRegister has registered", Reflect.unwrap(t));
		}
	}

	/**
	 * 1.20.1's {@code NewRegistryEvent} implements {@code IModBusEvent} and has no static bus: Forge delivers it to
	 * each mod's own bus. Post it on every handle's bus (ForgeMod's {@code DeferredRegister} subscribers call
	 * {@code event.create(builder)}), then {@code fill()} it to build + register those registries into
	 * {@code RegistryManager.ACTIVE}. Returns the count of registries that came into being. Best-effort.
	 */
	private static int fireNewRegistryEvent(ClassLoader cl, List<KernelForgeModContext.Handle> handles) {
		try {
			Class<?> newRegCls = Class.forName(ForeignType.NEW_REGISTRY_EVENT.binary(Ecosystem.FORGE), false, cl);
			Class<?> regManager = Class.forName(ForeignType.REGISTRY_MANAGER.binary(Ecosystem.FORGE), false, cl);
			Object active = regManager.getField("ACTIVE").get(null);
			Field rf = regManager.getDeclaredField("registries");
			rf.setAccessible(true);
			int before = ((java.util.Map<?, ?>) rf.get(active)).size();

			Object event = newRegCls.getDeclaredConstructor().newInstance();
			Class<?> eventBase = Class.forName("net.minecraftforge.eventbus.api.Event", false, cl);

			// The post and the fill are separated on purpose. One mod's listener must not take every other mod's
			// registry down with it, so each bus is posted to in its own try and a thrower costs only its own mod's
			// entries. fill() then still runs, keeping whatever the listeners before it created.
			boolean posted = true;
			for (KernelForgeModContext.Handle handle : handles) {
				try {
					Method post = handle.busGroup().getClass().getMethod("post", eventBase);
					post.invoke(handle.busGroup(), event);
				} catch (Throwable t) {
					posted = false;
					ForbricLog.warn("[Forbric/Forge] %s's NewRegistryEvent listener failed — the registries collected "
							+ "before it are still created below, the ones after it are not", handle.modId(),
							Reflect.unwrap(t));
				}
			}

			Method fill = newRegCls.getDeclaredMethod("fill");
			fill.setAccessible(true);
			fill.invoke(event);

			int created = ((java.util.Map<?, ?>) rf.get(active)).size() - before;
			if (!posted) {
				ForbricLog.warn("[Forbric/Forge] %d Forge custom registr(ies) survived that failure", created);
			}
			return created;
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Forge] NewRegistryEvent failed — Forge custom registries (fluid_type etc.) "
					+ "will not exist", Reflect.unwrap(t));
			return 0;
		}
	}
}
