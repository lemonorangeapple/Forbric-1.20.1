/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import java.lang.reflect.*;
import java.util.*;
import net.forbric.kernel.util.ForbricLog;
import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;

/**
 * Replays NewRegistryEvent only to listeners added by client-window Forge constructors.
 *
 * <p>1.20.1 runs EventBus 6: there is no {@code NewRegistryEvent.BUS} static and no {@code BusGroup}, so this
 * 26.2 (EventBus 7) replay has no shape to drive. It degrades to a no-op; the client's Forge mods are constructed
 * in the kernel's own registration window, which already fires NewRegistryEvent on every mod's bus.
 */
final class LateForgeRegistryDeclarations {
	private LateForgeRegistryDeclarations() { }

	/** True when the EventBus 7 carrier this replay was written for is present. */
	private static boolean available(ClassLoader loader) {
		try {
			Class.forName("net.minecraftforge.eventbus.api.bus.BusGroup", false, loader);
			Class.forName("net.minecraftforge.registries.NewRegistryEvent", false, loader).getField("BUS");
			return true;
		} catch (ReflectiveOperationException | LinkageError absent) {
			return false;
		}
	}

	static List<Object> snapshot(ClassLoader loader) throws ReflectiveOperationException {
		if (!available(loader)) return List.of();
		Object bus=Class.forName("net.minecraftforge.registries.NewRegistryEvent",false,loader).getField("BUS").get(null);
		List<Object> result=new ArrayList<>();collect(bus,result,Collections.newSetFromMap(new IdentityHashMap<>()));return result;
	}
	private static void collect(Object bus,List<Object> result,Set<Object> seen)throws ReflectiveOperationException{
		if(!seen.add(bus))return;
		for(String field:List.of("backingList","monitorBackingList"))result.addAll((List<?>)bus.getClass().getMethod(field).invoke(bus));
		for(Object child:(List<?>)bus.getClass().getMethod("children").invoke(bus))collect(child,result,seen);
	}
	static List<Object> added(List<Object> before,List<Object> after){
		Set<Object> seen=Collections.newSetFromMap(new IdentityHashMap<>());seen.addAll(before);
		return after.stream().filter(seen::add).toList();
	}
	static void fire(ClassLoader loader,List<Object> before)throws ReflectiveOperationException{
		if (!available(loader)) return;
		List<Object> listeners=added(before,snapshot(loader));if(listeners.isEmpty())return;
		Class<?> eventClass=Class.forName("net.minecraftforge.registries.NewRegistryEvent",false,loader);
		Class<?> groupClass=Class.forName("net.minecraftforge.eventbus.api.bus.BusGroup",false,loader);
		Class<?> busClass=Class.forName(ForeignType.EVENT_BUS.binary(Ecosystem.FORGE),false,loader);
		Object group=groupClass.getMethod("create",String.class).invoke(null,"forbric-late-registry-declarations");
		Object bus=busClass.getMethod("create",groupClass,Class.class).invoke(null,group,eventClass);
		try{
			Method add=busClass.getMethod("addListener",Class.forName(ForeignType.EVENT_LISTENER.binary(Ecosystem.FORGE),false,loader));
			for(Object listener:listeners)add.invoke(bus,listener);
			groupClass.getMethod("startup").invoke(group);
			Object event=eventClass.getDeclaredConstructor().newInstance();
			busClass.getMethod("post",Class.forName(ForeignType.EVENT.binary(Ecosystem.FORGE),false,loader)).invoke(bus,event);
			Method fill=eventClass.getDeclaredMethod("fill");fill.setAccessible(true);fill.invoke(event);
			ForbricLog.info("[Forbric/Forge] delivered NewRegistryEvent to %d newly added client-window listener(s) "
					+ "without replaying the baseline declarations",listeners.size());
		}finally{groupClass.getMethod("dispose").invoke(group);}
	}
}
