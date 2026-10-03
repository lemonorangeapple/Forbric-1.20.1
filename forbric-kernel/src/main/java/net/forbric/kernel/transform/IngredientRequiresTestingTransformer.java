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
 * Answers {@code Ingredient.requiresTesting()} when fabric-recipe-api-v1's {@code IngredientMixin} cannot apply.
 *
 * <p>The mixin adds the method and its API calls it unconditionally, so a suppressed mixin turns the datapack
 * reload into a {@code NoSuchMethodError}. The mixin also makes {@code Ingredient} carry a {@code requiresTesting}
 * flag; the base has no such state, so the honest answer is {@code false} — the ingredient may use a plain
 * item-list fast path.
 */
public final class IngredientRequiresTestingTransformer implements ClassTransformer {
	static final String OWNER = "net.minecraft.world.item.crafting.Ingredient";
	static final String METHOD = "requiresTesting";
	static final String DESC = "()Z";

	@Override
	public String name() {
		return "forbric:ingredient-requires-testing";
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || !className.equals(OWNER)) return classBytes;
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		for (MethodNode m : node.methods) {
			if (m.name.equals(METHOD) && m.desc.equals(DESC)) return classBytes; // the mixin applied after all
		}
		MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, METHOD, DESC, null, null);
		method.instructions.add(new InsnNode(Opcodes.ICONST_0));
		method.instructions.add(new InsnNode(Opcodes.IRETURN));
		method.maxStack = 1;
		method.maxLocals = 1;
		node.methods.add(method);
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}
}
