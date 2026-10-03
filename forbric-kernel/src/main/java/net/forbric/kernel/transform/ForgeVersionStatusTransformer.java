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
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Answers {@code ForgeVersion.getStatus()} without Forge's own mod file being in the ModList.
 *
 * <p>{@code ForgeHooksClient.renderMainMenu} calls it on the title screen, and it dereferences
 * {@code ModList.get().getModFileById("forge")} — null here, because the kernel owns the mod list and Forge is not
 * one of its entries. The value is only the "Forge is up to date" line; {@code UP_TO_DATE} is the truthful idle answer.
 */
public final class ForgeVersionStatusTransformer implements ClassTransformer {
	static final String OWNER = "net.minecraftforge.versions.forge.ForgeVersion";
	static final String METHOD = "getStatus";
	static final String DESC = "()Lnet/minecraftforge/fml/VersionChecker$Status;";

	@Override
	public String name() {
		return "forbric:forge-version-status";
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
			method.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC,
					"net/minecraftforge/fml/VersionChecker$Status", "UP_TO_DATE",
					"Lnet/minecraftforge/fml/VersionChecker$Status;"));
			method.instructions.add(new InsnNode(Opcodes.ARETURN));
			method.maxStack = 1;
			method.maxLocals = 0;
			changed = true;
		}
		if (!changed) return classBytes;
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}
}
