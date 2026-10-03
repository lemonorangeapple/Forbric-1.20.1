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
import org.objectweb.asm.tree.MethodNode;

/**
 * Stops traditional Forge's {@code ClientModLoader} from driving mod loading the kernel already owns.
 *
 * <p>On 1.20.1 Forge's patched {@code Minecraft.<init>} calls {@code ClientModLoader.begin}, which runs Forge's own
 * discovery/sorting/registration — everything the kernel replaces — and NPEs on ModLauncher state that does not
 * exist here. The kernel's client lifecycle is emitted into the same constructor by
 * {@code ClientEntrypointHookInjector}, so {@code begin} only needs to get out of the way.
 */
public final class ClientModLoaderTransformer implements ClassTransformer {
	static final String OWNER = "net.minecraftforge.client.loading.ClientModLoader";

	@Override
	public String name() {
		return "forbric:client-mod-loader";
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || !className.equals(OWNER)) return classBytes;
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		boolean changed = false;
		for (MethodNode method : node.methods) {
			if (method.name.equals("begin") && method.desc.endsWith(")V")) {
				simple(method, Opcodes.RETURN, 0);
				changed = true;
			} else if (method.name.equals("completeModLoading") && method.desc.equals("()Z")) {
				simple(method, Opcodes.IRETURN, 1);
				changed = true;
			} else if (method.name.equals("isLoading") && method.desc.equals("()Z")) {
				simple(method, Opcodes.IRETURN, 0);
				changed = true;
			} else if (method.name.equals("checkForUpdates") && method.desc.endsWith(")Lnet/minecraftforge/fml/VersionChecker$Status;")) {
				simple(method, Opcodes.ARETURN, 1);
				changed = true;
			}
		}
		if (!changed) return classBytes;
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** Replace the body with a bare return of {@code push} (the opcode constant), or of {@code null} when negative. */
	private static void simple(MethodNode method, int returnOpcode, int push) {
		method.instructions.clear();
		if (method.tryCatchBlocks != null) method.tryCatchBlocks.clear();
		method.localVariables = null;
		if (returnOpcode == Opcodes.ARETURN) method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
		else if (returnOpcode == Opcodes.IRETURN) {
			method.instructions.add(new InsnNode(push == 1 ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
		}
		method.instructions.add(new InsnNode(returnOpcode));
		method.maxStack = returnOpcode == Opcodes.RETURN ? 0 : 1;
	}
}
