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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Gives {@code LootTable} the two duck interfaces Fabric's loot mixins cannot install.
 *
 * <p>{@code LootTableMixin} shadows {@code LootTable.pools} as a {@code LootPool[]}, but MinecraftForge's patch turns
 * that field into a {@code List} — so the mixin's {@code @Shadow} never resolves, the mixin is dropped, and then
 * {@code BufferingLootTableBuilder} / {@code FabricLootTableBuilder.copyOf} die casting a {@code LootTable} to
 * {@code FabricLootSupplier} / {@code LootTableAccessor} during the datapack reload. Adding both interfaces and their
 * getters directly produces the shape the mixin would have, reading the field the base actually has.
 */
public final class LootTableFabricSupplierTransformer implements ClassTransformer {
	static final String OWNER = "net.minecraft.world.level.storage.loot.LootTable";
	static final String OWNER_INTERNAL = "net/minecraft/world/level/storage/loot/LootTable";
	static final String SUPPLIER = "net/fabricmc/fabric/api/loot/v1/FabricLootSupplier";
	static final String ACCESSOR = "net/fabricmc/fabric/mixin/loot/LootTableAccessor";
	static final String LOOT_POOL = "net/minecraft/world/level/storage/loot/LootPool";
	static final String LOOT_FUNCTION = "net/minecraft/world/level/storage/loot/functions/LootItemFunction";
	static final String RESOURCE_LOCATION = "net/minecraft/resources/ResourceLocation";

	@Override
	public String name() {
		return "forbric:loot-table-fabric-supplier";
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || !className.equals(OWNER)) return classBytes;
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		if (node.interfaces.contains(SUPPLIER) && node.interfaces.contains(ACCESSOR)) return classBytes;
		// Only when the carrier field really is a List; a vanilla array base already satisfies the mixin.
		boolean listPools = node.fields.stream().anyMatch(f -> f.name.equals("pools") && f.desc.equals("Ljava/util/List;"));
		if (!listPools) return classBytes;

		boolean arrayFunctions = node.fields.stream()
				.anyMatch(f -> f.name.equals("functions") && f.desc.equals("[L" + LOOT_FUNCTION + ";"));
		boolean randomSequence = node.fields.stream()
				.anyMatch(f -> f.name.equals("randomSequence") && f.desc.equals("L" + RESOURCE_LOCATION + ";"));

		if (!node.interfaces.contains(SUPPLIER)) {
			node.interfaces.add(SUPPLIER);
			// List<LootPool> getPools() -> this.pools
			MethodNode getPools = new MethodNode(Opcodes.ACC_PUBLIC, "getPools", "()Ljava/util/List;", null, null);
			getPools.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
			getPools.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, OWNER_INTERNAL, "pools", "Ljava/util/List;"));
			getPools.instructions.add(new InsnNode(Opcodes.ARETURN));
			getPools.maxStack = 1;
			getPools.maxLocals = 1;
			node.methods.add(getPools);
			if (arrayFunctions) {
				// List<LootItemFunction> getFunctions() -> Arrays.asList(this.functions)
				MethodNode getFunctions = new MethodNode(Opcodes.ACC_PUBLIC, "getFunctions", "()Ljava/util/List;", null, null);
				getFunctions.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
				getFunctions.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, OWNER_INTERNAL, "functions",
						"[L" + LOOT_FUNCTION + ";"));
				getFunctions.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Arrays", "asList",
						"([Ljava/lang/Object;)Ljava/util/List;", false));
				getFunctions.instructions.add(new InsnNode(Opcodes.ARETURN));
				getFunctions.maxStack = 1;
				getFunctions.maxLocals = 1;
				node.methods.add(getFunctions);
			}
		}

		if (!node.interfaces.contains(ACCESSOR)) {
			node.interfaces.add(ACCESSOR);
			// LootPool[] fabric_getPools() -> this.pools.toArray(new LootPool[0])
			MethodNode accessorPools = new MethodNode(Opcodes.ACC_PUBLIC, "fabric_getPools", "()[L" + LOOT_POOL + ";", null, null);
			accessorPools.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
			accessorPools.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, OWNER_INTERNAL, "pools", "Ljava/util/List;"));
			accessorPools.instructions.add(new InsnNode(Opcodes.ICONST_0));
			accessorPools.instructions.add(new TypeInsnNode(Opcodes.ANEWARRAY, LOOT_POOL));
			accessorPools.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "toArray",
					"([Ljava/lang/Object;)[Ljava/lang/Object;", true));
			accessorPools.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "[L" + LOOT_POOL + ";"));
			accessorPools.instructions.add(new InsnNode(Opcodes.ARETURN));
			accessorPools.maxStack = 2;
			accessorPools.maxLocals = 1;
			node.methods.add(accessorPools);
			if (arrayFunctions) {
				MethodNode accessorFunctions = new MethodNode(Opcodes.ACC_PUBLIC, "fabric_getFunctions",
						"()[L" + LOOT_FUNCTION + ";", null, null);
				accessorFunctions.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
				accessorFunctions.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, OWNER_INTERNAL, "functions",
						"[L" + LOOT_FUNCTION + ";"));
				accessorFunctions.instructions.add(new InsnNode(Opcodes.ARETURN));
				accessorFunctions.maxStack = 1;
				accessorFunctions.maxLocals = 1;
				node.methods.add(accessorFunctions);
			}
			if (randomSequence) {
				MethodNode accessorRandom = new MethodNode(Opcodes.ACC_PUBLIC, "fabric_getRandomSequenceId",
						"()L" + RESOURCE_LOCATION + ";", null, null);
				accessorRandom.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
				accessorRandom.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, OWNER_INTERNAL, "randomSequence",
						"L" + RESOURCE_LOCATION + ";"));
				accessorRandom.instructions.add(new InsnNode(Opcodes.ARETURN));
				accessorRandom.maxStack = 1;
				accessorRandom.maxLocals = 1;
				node.methods.add(accessorRandom);
			}
		}

		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}
}
