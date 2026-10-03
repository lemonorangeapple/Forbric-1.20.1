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

/**
 * Makes {@code net.minecraftforge.common.ForgeHooks.getModPacks()} return an empty list.
 *
 * <p>1.20.1's {@code MinecraftServer.configurePackRepository} calls it, and Forge's implementation throws
 * {@code "Attempted to retrieve mod packs before they were loaded in!"} unless {@code ModLoader} has run its
 * lifecycle — which the kernel replaces. The kernel hands the game its own resource packs through the pack
 * providers, so the Forge mod-pack list is empty here, not an error.
 */
public final class ForgeModPacksInjector implements ClassTransformer {
	static final String OWNER = "net.minecraftforge/common/ForgeHooks";
	static final String METHOD = "getModPacks";
	static final String DESC = "()Ljava/util/List;";

	@Override
	public String name() {
		return "forbric:forge-mod-packs";
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || !className.equals(OWNER.replace('/', '.'))) return classBytes;
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		boolean changed = false;
		for (MethodNode method : node.methods) {
			if (METHOD.equals(method.name) && DESC.equals(method.desc)) {
				method.instructions.clear();
				method.tryCatchBlocks.clear();
				method.localVariables = null;
				method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/List", "of",
						"()Ljava/util/List;", true));
				method.instructions.add(new InsnNode(Opcodes.ARETURN));
				changed = true;
			}
		}
		if (!changed) return classBytes;
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}
}
