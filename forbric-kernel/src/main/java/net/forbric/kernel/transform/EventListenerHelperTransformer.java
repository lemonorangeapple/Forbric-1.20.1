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

package net.forbric.kernel.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Stops EventBus 6 from instantiating an event class to read its listener list.
 *
 * <p>{@code EventListenerHelper.computeListenerList} reflects a no-arg constructor and news an event instance to
 * ask it for {@code getListenerList()}. Forge's own {@code NetworkEvent} (and others) declare no such constructor,
 * so on a carrier assembled outside ModLauncher the network bus dies with {@code NoSuchMethodException} while
 * {@code TierSortingRegistry} builds its channel. Every event instance's list is itself derived from the
 * superclass list, so reading that list directly is exactly equivalent — and it cannot fail.
 */
public final class EventListenerHelperTransformer implements ClassTransformer {
	static final String OWNER = "net.minecraftforge.eventbus.api.EventListenerHelper";
	static final String OWNER_INTERNAL = "net/minecraftforge/eventbus/api/EventListenerHelper";
	static final String METHOD = "computeListenerList";
	static final String DESC = "(Ljava/lang/Class;Z)Lnet/minecraftforge/eventbus/ListenerList;";

	@Override
	public String name() {
		return "forbric:event-listener-helper";
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || !className.equals(OWNER)) return classBytes;
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		boolean changed = false;
		for (MethodNode method : node.methods) {
			if (!METHOD.equals(method.name) || !DESC.equals(method.desc)) continue;
			method.instructions.clear();
			if (method.tryCatchBlocks != null) method.tryCatchBlocks.clear();
			method.localVariables = null;
			// return new ListenerList(getListenerList(eventClass.getSuperclass()));
			method.instructions.add(new TypeInsnNode(Opcodes.NEW, "net/minecraftforge/eventbus/ListenerList"));
			method.instructions.add(new InsnNode(Opcodes.DUP));
			method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
			method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getSuperclass",
					"()Ljava/lang/Class;", false));
			method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OWNER_INTERNAL, "getListenerList",
					"(Ljava/lang/Class;)Lnet/minecraftforge/eventbus/ListenerList;", false));
			method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "net/minecraftforge/eventbus/ListenerList",
					"<init>", "(Lnet/minecraftforge/eventbus/ListenerList;)V", false));
			method.instructions.add(new InsnNode(Opcodes.ARETURN));
			method.maxStack = 4;
			method.maxLocals = 2;
			changed = true;
		}
		if (!changed) return classBytes;
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}
}
