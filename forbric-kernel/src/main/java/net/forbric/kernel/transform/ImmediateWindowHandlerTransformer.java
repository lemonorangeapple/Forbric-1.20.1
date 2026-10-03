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
 * Stands in for ModLauncher's immediate (early) window, which the kernel does not have.
 *
 * <p>Forge patches vanilla {@code Window.<init>} to create the GLFW window through
 * {@code ImmediateWindowHandler.setupMinecraftWindow} instead of calling {@code GLFW.glfwCreateWindow}
 * directly, so a loading window can exist before Minecraft does. {@code ImmediateWindowHandler.provider} is only
 * set by ModLauncher, so on this carrier the call NPEs and the client never opens a window. The body is rewritten
 * to the GLFW call it replaced, and the purely cosmetic early-window methods become no-ops.
 */
public final class ImmediateWindowHandlerTransformer implements ClassTransformer {
	static final String OWNER = "net.minecraftforge.fml.loading.ImmediateWindowHandler";
	static final String SETUP = "setupMinecraftWindow";
	static final String SETUP_DESC = "(Ljava/util/function/IntSupplier;Ljava/util/function/IntSupplier;"
			+ "Ljava/util/function/Supplier;Ljava/util/function/LongSupplier;)J";

	@Override
	public String name() {
		return "forbric:immediate-window-handler";
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || !className.equals(OWNER)) return classBytes;
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		boolean changed = false;
		boolean needsHelper = false;
		for (MethodNode method : node.methods) {
			if (method.name.equals(SETUP) && method.desc.equals(SETUP_DESC)) {
				rewriteSetup(method);
				changed = true;
				continue;
			}
			if (method.name.equals("positionWindow") && method.desc.endsWith(")Z")) {
				simple(method, Opcodes.ICONST_0, Opcodes.IRETURN);
				changed = true;
			} else if (method.name.equals("updateFBSize") || method.name.equals("renderTick")
					|| method.name.equals("acceptGameLayer") || method.name.equals("updateProgress")) {
				simple(method, -1, Opcodes.RETURN);
				changed = true;
			} else if (method.name.equals("getGLVersion") && method.desc.equals("()Ljava/lang/String;")) {
				method.instructions.clear();
				if (method.tryCatchBlocks != null) method.tryCatchBlocks.clear();
				method.localVariables = null;
				method.instructions.add(new org.objectweb.asm.tree.LdcInsnNode(""));
				method.instructions.add(new InsnNode(Opcodes.ARETURN));
				method.maxStack = 1;
				method.maxLocals = 0;
				changed = true;
			} else if (method.name.equals("loadingOverlay")) {
				rewriteLoadingOverlay(method);
				needsHelper = true;
				changed = true;
			}
		}
		if (!changed) return classBytes;
		if (needsHelper) node.methods.add(overlayHelper());
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** {@code return GLFW.glfwCreateWindow(width.getAsInt(), height.getAsInt(), title.get(), monitor.getAsLong(), 0L); } */
	private static void rewriteSetup(MethodNode method) {
		method.instructions.clear();
		if (method.tryCatchBlocks != null) method.tryCatchBlocks.clear();
		method.localVariables = null;
		method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/function/IntSupplier",
				"getAsInt", "()I", true));
		method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
		method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/function/IntSupplier",
				"getAsInt", "()I", true));
		method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
		method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/function/Supplier",
				"get", "()Ljava/lang/Object;", true));
		method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/CharSequence"));
		method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
		method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/function/LongSupplier",
				"getAsLong", "()J", true));
		method.instructions.add(new InsnNode(Opcodes.LCONST_0));
		method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/lwjgl/glfw/GLFW", "glfwCreateWindow",
				"(IILjava/lang/CharSequence;JJ)J", false));
		method.instructions.add(new InsnNode(Opcodes.LRETURN));
		method.maxStack = 8;
		method.maxLocals = 4;
	}

	/**
	 * Rewrites {@code loadingOverlay} to return {@code () -> new LoadingOverlay((Minecraft) mc.get(),
	 * (ReloadInstance) reload.get(), errorConsumer, showSmallLabel)}, via a lambda over {@link #overlayHelper()}.
	 * There is no early window, so the overlay is the ordinary one; only the provider hook is gone.
	 */
	private static void rewriteLoadingOverlay(MethodNode method) {
		method.instructions.clear();
		if (method.tryCatchBlocks != null) method.tryCatchBlocks.clear();
		method.localVariables = null;
		var insns = method.instructions;
		insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
		insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
		insns.add(new VarInsnNode(Opcodes.ALOAD, 2));
		insns.add(new VarInsnNode(Opcodes.ILOAD, 3));
		org.objectweb.asm.Handle bsm = new org.objectweb.asm.Handle(org.objectweb.asm.Opcodes.H_INVOKESTATIC,
				"java/lang/invoke/LambdaMetafactory", "metafactory",
				"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
						+ "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
						+ "Ljava/lang/invoke/CallSite;", false);
		org.objectweb.asm.Handle impl = new org.objectweb.asm.Handle(org.objectweb.asm.Opcodes.H_INVOKESTATIC,
				OWNER.replace('.', '/'), OVERLAY_HELPER,
				"(Ljava/util/function/Supplier;Ljava/util/function/Supplier;Ljava/util/function/Consumer;Z)"
						+ "Ljava/lang/Object;", false);
		insns.add(new org.objectweb.asm.tree.InvokeDynamicInsnNode("get",
				"(Ljava/util/function/Supplier;Ljava/util/function/Supplier;Ljava/util/function/Consumer;Z)"
						+ "Ljava/util/function/Supplier;", bsm,
				org.objectweb.asm.Type.getType("()Ljava/lang/Object;"), impl,
				org.objectweb.asm.Type.getType("()Ljava/lang/Object;")));
		insns.add(new InsnNode(Opcodes.ARETURN));
		method.maxStack = 4;
		method.maxLocals = 4;
	}

	private static final String OVERLAY_HELPER = "forbric$overlay";

	/** The lambda body {@link #rewriteLoadingOverlay} points the invokedynamic at. */
	private static MethodNode overlayHelper() {
		MethodNode m = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
				OVERLAY_HELPER,
				"(Ljava/util/function/Supplier;Ljava/util/function/Supplier;Ljava/util/function/Consumer;Z)"
						+ "Ljava/lang/Object;", null, null);
		var insns = m.instructions;
		insns.add(new TypeInsnNode(Opcodes.NEW, "net/minecraft/client/gui/screens/LoadingOverlay"));
		insns.add(new InsnNode(Opcodes.DUP));
		insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
		insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/function/Supplier", "get",
				"()Ljava/lang/Object;", true));
		insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/client/Minecraft"));
		insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
		insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/function/Supplier", "get",
				"()Ljava/lang/Object;", true));
		insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/server/packs/resources/ReloadInstance"));
		insns.add(new VarInsnNode(Opcodes.ALOAD, 2));
		insns.add(new VarInsnNode(Opcodes.ILOAD, 3));
		insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "net/minecraft/client/gui/screens/LoadingOverlay", "<init>",
				"(Lnet/minecraft/client/Minecraft;Lnet/minecraft/server/packs/resources/ReloadInstance;"
						+ "Ljava/util/function/Consumer;Z)V", false));
		insns.add(new InsnNode(Opcodes.ARETURN));
		m.maxStack = 6;
		m.maxLocals = 4;
		return m;
	}

	/** Replaces the body with a single constant return (or a bare return when {@code push < 0}). */
	private static void simple(MethodNode method, int push, int returnOpcode) {
		method.instructions.clear();
		if (method.tryCatchBlocks != null) method.tryCatchBlocks.clear();
		method.localVariables = null;
		if (push >= 0) method.instructions.add(new InsnNode(push));
		method.instructions.add(new InsnNode(returnOpcode));
		method.maxStack = push >= 0 ? 1 : 0;
	}
}
