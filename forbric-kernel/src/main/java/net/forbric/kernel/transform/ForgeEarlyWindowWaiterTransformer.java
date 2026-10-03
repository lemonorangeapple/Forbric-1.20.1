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
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Runs Forge's {@code BackgroundWaiter.runAndTick} background task synchronously, without the early window.
 *
 * <p>Forge patches vanilla's client {@code Main.main} to call it, and it ticks
 * {@code ImmediateWindowHandler.updateProgress}, which NPEs because {@code earlyProgress} is only set by
 * ModLauncher's immediate window — which the kernel does not have. The work it wraps still has to happen; only the
 * progress tick is dropped.
 */
public final class ForgeEarlyWindowWaiterTransformer implements ClassTransformer {
	static final String OWNER = "net.minecraftforge.fml.loading.BackgroundWaiter";
	static final String METHOD = "runAndTick";
	static final String DESC = "(Ljava/lang/Runnable;Ljava/lang/Runnable;)V";

	@Override
	public String name() {
		return "forbric:forge-early-window-waiter";
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
			// arg0.run(); return;
			method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
			method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/lang/Runnable", "run", "()V",
					true));
			method.instructions.add(new InsnNode(Opcodes.RETURN));
			method.maxStack = 1;
			method.maxLocals = 2;
			changed = true;
		}
		if (!changed) return classBytes;
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}
}
