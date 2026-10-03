/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.ArrayList;
import java.util.List;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Lava placed next to water becomes obsidian again, flowing lava that reaches water becomes cobblestone, and lava over
 * soul soil beside blue ice becomes basalt — with no mods installed, as in vanilla — and each family's mods' fluid rules
 * run where their own game runs them.
 *
 * <p>Vanilla runs those rules from {@code LiquidBlock.shouldSpreadLiquid}, at both of its callers: {@code onPlace} (the
 * liquid was just set — a bucket, a command, or a flowing block the fluid tick just spread) and {@code neighborChanged}
 * (something next to it changed — water arriving). Each carrier patched its own callers: MinecraftForge's
 * {@code onPlace} and {@code neighborChanged} both ask its {@code FluidInteractionRegistry.canInteract}; NeoForge's
 * {@code neighborChanged} asks its own, while its {@code onPlace} still runs vanilla's {@code shouldSpreadLiquid}, so on
 * NeoForge a mod's rule fires when a neighbour changes and never on placement. Both registries hold vanilla's two rules
 * first and then the ones mods add, and walk the neighbours in vanilla's order, every rule at one neighbour before the
 * next neighbour. The merge kept MinecraftForge's {@code onPlace} and NeoForge's {@code neighborChanged}, and the kernel
 * neutered MinecraftForge's {@code canInteract} to {@code return false} (its first lookup once ended in an
 * AbstractMethodError, before the merged fluids had MinecraftForge's {@code getFluidType()}). So only water arriving next
 * to lava reacted, and no MinecraftForge mod's rule ever ran.
 *
 * <p>Each entry point now runs what its own family runs there:
 * <ul>
 *   <li>{@code onPlace} asks MinecraftForge's registry, unedited — its {@code canInteract} is no longer neutered (the
 *       merged {@code FluidState} implements {@code IForgeFluidState}, and every merged fluid answers MinecraftForge's
 *       {@code getFluidType()}: the per-class bridge and {@code ForeignFluidTypeInjector}). That is MinecraftForge's own
 *       placement: vanilla's rules and its mods', in its order. It is also NeoForge's, whose placement runs vanilla's
 *       rules alone; a fluid that is no MinecraftForge mod's gets its MinecraftForge type from its fluid tags, as
 *       vanilla's {@code shouldSpreadLiquid} decides.</li>
 *   <li>{@code neighborChanged} asks NeoForge's registry, as merged. Where NeoForge's walk has tried every rule of its
 *       own at one neighbour and is about to move to the next, it now asks the kernel for a MinecraftForge mod's rule at
 *       that same neighbour ({@code KernelFluidInteractions.minecraftForge}). A MinecraftForge mod's rule therefore beats
 *       vanilla's at a later neighbour, as on MinecraftForge, and NeoForge's own match still returns at once, so one
 *       liquid never reacts twice. MinecraftForge's copies of vanilla's rules are not asked there: NeoForge's identical
 *       ones just were.</li>
 *   <li>MinecraftForge's initializer hands the kernel its map once vanilla's two rules are in, and its
 *       {@code addInteraction} reports in. Until a MinecraftForge mod adds a rule, {@code neighborChanged} never asks.</li>
 * </ul>
 * Each edit is made on the reviewed shape only, and is not made twice. {@code -Dforbric.fluidInteractions=off} leaves
 * both registries as merged and KernelBoot puts the neuter back, which reproduces the bug.
 */
public final class FluidInteractionsInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.fluidInteractions";
	static final String NEO = ForeignType.FLUID_INTERACTION_REGISTRY.binary(Ecosystem.NEOFORGE);
	static final String FORGE = ForeignType.FLUID_INTERACTION_REGISTRY.binary(Ecosystem.FORGE);
	static final String NEO_INTERNAL = ForeignType.FLUID_INTERACTION_REGISTRY.internal(Ecosystem.NEOFORGE);
	static final String FORGE_INTERNAL = ForeignType.FLUID_INTERACTION_REGISTRY.internal(Ecosystem.FORGE);
	static final String CAN_INTERACT = "canInteract";
	static final String INTERACT_DESC = "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Z";
	static final String ADD_INTERACTION = "addInteraction";
	static final String INTERACTIONS = "INTERACTIONS";
	static final String RUNTIME = "net/forbric/kernel/runtime/KernelFluidInteractions";
	/** Asked in NeoForge's canInteract with (level, pos, neighbour) when none of NeoForge's rules matched that neighbour. */
	static final String FORGE_LEG = "minecraftForge";
	static final String FORGE_LEG_DESC = "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)Z";
	/** Called last in MinecraftForge's initializer, with its map. */
	static final String REGISTRY = "minecraftForgeRegistry";
	/** Called first thing in MinecraftForge's addInteraction. */
	static final String IN_USE = "minecraftForgeInUse";
	private static final String POS = "net/minecraft/core/BlockPos";
	private static final String ITERATOR = "java/util/Iterator";

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override public String name() { return "forbric-fluid-interactions"; }

	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("liquid placement left asking MinecraftForge's neutered registry with -D" + PROPERTY + "=off");
		return AnchorSet.of(
				new AnchorSet.Anchor(NEO, AnchorSet.Severity.REQUIRED,
						"a MinecraftForge mod's fluid interactions never run when a block next to the liquid changes"),
				new AnchorSet.Anchor(FORGE, AnchorSet.Severity.REQUIRED,
						"the kernel never learns that a MinecraftForge mod added a fluid interaction, so a neighbour change never runs it"));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || bytes == null || bytes.length == 0) return bytes;
		boolean neo = NEO.equals(className);
		if (!neo && !FORGE.equals(className)) return bytes;
		ClassNode node = new ClassNode();
		// NeoForge's edit adds a branch target inside a loop, whose frame is a copy of the loop head's: EXPAND_FRAMES
		// keeps every frame absolute (F_NEW), so the copy is exact and ClassWriter re-serialises all of them.
		new ClassReader(bytes).accept(node, neo ? ClassReader.EXPAND_FRAMES : 0);
		int changed = neo ? neoForge(node) : minecraftForge(node);
		if (changed <= 0) return bytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		if (neo) {
			ForbricLog.info("[Forbric/Fluid] NeoForge's FluidInteractionRegistry.canInteract asks MinecraftForge mods' interactions at "
					+ "each neighbour after its own, once a MinecraftForge mod adds one — neighborChanged ran NeoForge's alone");
		} else {
			ForbricLog.info("[Forbric/Fluid] MinecraftForge's FluidInteractionRegistry hands its rules to the kernel and reports "
					+ "a mod's addInteraction; its canInteract is no longer neutered, so a liquid placed asks it as on MinecraftForge "
					+ "(%d edit(s))", changed);
		}
		return writer.toByteArray();
	}

	/**
	 * Where NeoForge's inner walk (its rules, at one neighbour) runs out, ask the kernel about that neighbour before the
	 * outer walk moves on: the inner loop's exit jump is retargeted to the question, which falls back to the old target.
	 */
	static int neoForge(ClassNode registry) {
		MethodNode canInteract = staticMethod(registry, CAN_INTERACT, INTERACT_DESC);
		if (canInteract == null) return declined("NeoForge's FluidInteractionRegistry has no static canInteract(Level, BlockPos)");
		if (callsRuntime(canInteract)) return 0;   // already done
		VarInsnNode neighbour = null;
		int relatives = 0, unmatched = 0;
		List<MethodInsnNode> hasNext = new ArrayList<>(), interact = new ArrayList<>();
		for (AbstractInsnNode insn : canInteract.instructions) {
			if (insn instanceof MethodInsnNode call) {
				if (call.owner.equals(POS) && call.name.equals("relative") && call.desc.equals("(Lnet/minecraft/core/Direction;)L" + POS + ";")) {
					relatives++;
					if (next(call) instanceof VarInsnNode store && store.getOpcode() == Opcodes.ASTORE) neighbour = store;
				}
				if (call.owner.equals(ITERATOR) && call.name.equals("hasNext")) hasNext.add(call);
				if (call.owner.equals(NEO_INTERNAL + "$FluidInteraction") && call.name.equals("interact")) interact.add(call);
			}
			if (insn.getOpcode() == Opcodes.ICONST_0 && next(insn) != null && next(insn).getOpcode() == Opcodes.IRETURN) unmatched++;
		}
		if (relatives != 1 || neighbour == null || hasNext.size() != 2 || interact.size() != 1 || unmatched != 1) {
			return declined("NeoForge's canInteract has " + relatives + " neighbour(s), " + hasNext.size() + " loop(s), "
					+ interact.size() + " interact call(s) and " + unmatched + " `return false`");
		}
		InsnList code = canInteract.instructions;
		MethodInsnNode inner = hasNext.get(1);
		// Its own first match returns at once: interact, then `return true`.
		AbstractInsnNode afterInteract = next(interact.get(0));
		boolean returnsAtOnce = afterInteract != null && afterInteract.getOpcode() == Opcodes.ICONST_1
				&& next(afterInteract) != null && next(afterInteract).getOpcode() == Opcodes.IRETURN;
		if (!returnsAtOnce || code.indexOf(neighbour) > code.indexOf(inner) || code.indexOf(inner) > code.indexOf(interact.get(0))
				|| !(next(inner) instanceof JumpInsnNode exit) || exit.getOpcode() != Opcodes.IFEQ) {
			return declined("NeoForge's canInteract does not walk its rules per neighbour, returning at the first match");
		}
		// The exit lands on the outer loop's back-edge (back above the neighbour), and nothing falls through to it.
		AbstractInsnNode back = next(exit.label), before = previous(exit.label);
		if (!(back instanceof JumpInsnNode outer) || outer.getOpcode() != Opcodes.GOTO || code.indexOf(outer.label) > code.indexOf(neighbour)
				|| before == null || before.getOpcode() != Opcodes.GOTO) {
			return declined("NeoForge's canInteract does not move to the next neighbour where its rules run out");
		}
		// The inner loop's head: the target of the inner back-edge, with the frame every local is live in.
		FrameNode head = frameAt(((JumpInsnNode) before).label);
		if (head == null || head.type != Opcodes.F_NEW || head.local == null || head.local.size() <= neighbour.var) {
			return declined("NeoForge's canInteract has no frame at its inner loop that holds the neighbour");
		}
		LabelNode ask = new LabelNode();
		InsnList leg = new InsnList();
		leg.add(ask);
		leg.add(new FrameNode(Opcodes.F_NEW, head.local.size(), head.local.toArray(), 0, new Object[0]));
		leg.add(new VarInsnNode(Opcodes.ALOAD, 0));
		leg.add(new VarInsnNode(Opcodes.ALOAD, 1));
		leg.add(new VarInsnNode(Opcodes.ALOAD, neighbour.var));
		leg.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, FORGE_LEG, FORGE_LEG_DESC, false));
		leg.add(new JumpInsnNode(Opcodes.IFEQ, exit.label));
		leg.add(new InsnNode(Opcodes.ICONST_1));
		leg.add(new InsnNode(Opcodes.IRETURN));
		code.insertBefore(exit.label, leg);
		exit.label = ask;
		return 1;
	}

	/** MinecraftForge's initializer hands over its map at the end; its addInteraction reports in before it adds. */
	static int minecraftForge(ClassNode registry) {
		MethodNode add = null, clinit = null;
		for (MethodNode m : registry.methods) {
			if (m.name.equals(ADD_INTERACTION) && (m.access & Opcodes.ACC_STATIC) != 0 && m.desc.endsWith(")V")) {
				if (add != null) return declined("MinecraftForge's FluidInteractionRegistry has more than one addInteraction");
				add = m;
			}
			if (m.name.equals("<clinit>")) clinit = m;
		}
		boolean map = false;
		for (FieldNode f : registry.fields) {
			if (f.name.equals(INTERACTIONS) && f.desc.equals("Ljava/util/Map;") && (f.access & Opcodes.ACC_STATIC) != 0) map = true;
		}
		if (add == null || clinit == null || !map || staticMethod(registry, CAN_INTERACT, INTERACT_DESC) == null) {
			return declined("MinecraftForge's FluidInteractionRegistry has no static addInteraction, initializer, INTERACTIONS map or canInteract");
		}
		List<AbstractInsnNode> returns = new ArrayList<>();
		boolean stored = false;
		for (AbstractInsnNode insn : clinit.instructions) {
			if (insn.getOpcode() == Opcodes.RETURN) returns.add(insn);
			if (insn instanceof FieldInsnNode put && put.getOpcode() == Opcodes.PUTSTATIC && put.owner.equals(registry.name) && put.name.equals(INTERACTIONS)) stored = true;
		}
		if (returns.size() != 1 || !stored) {
			return declined("MinecraftForge's FluidInteractionRegistry initializer has " + returns.size() + " return(s) and "
					+ (stored ? "stores" : "does not store") + " its map");
		}
		int changed = 0;
		if (!callsRuntime(clinit)) {
			InsnList handOver = new InsnList();
			handOver.add(new FieldInsnNode(Opcodes.GETSTATIC, registry.name, INTERACTIONS, "Ljava/util/Map;"));
			handOver.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, REGISTRY, "(Ljava/util/Map;)V", false));
			clinit.instructions.insertBefore(returns.get(0), handOver);
			changed++;
		}
		if (!callsRuntime(add)) {
			add.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, IN_USE, "()V", false));
			changed++;
		}
		return changed;
	}

	private static MethodNode staticMethod(ClassNode node, String name, String desc) {
		for (MethodNode m : node.methods) {
			if (m.name.equals(name) && m.desc.equals(desc) && (m.access & Opcodes.ACC_STATIC) != 0) return m;
		}
		return null;
	}

	private static boolean callsRuntime(MethodNode method) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(RUNTIME)) return true;
		}
		return false;
	}

	/** The frame recorded at {@code label}, skipping line numbers; null when it has none. */
	private static FrameNode frameAt(LabelNode label) {
		for (AbstractInsnNode at = label.getNext(); at != null && at.getOpcode() < 0; at = at.getNext()) {
			if (at instanceof FrameNode frame) return frame;
		}
		return null;
	}

	private static AbstractInsnNode next(AbstractInsnNode insn) {
		AbstractInsnNode next = insn.getNext();
		while (next != null && next.getOpcode() < 0) next = next.getNext();
		return next;
	}

	private static AbstractInsnNode previous(AbstractInsnNode insn) {
		AbstractInsnNode previous = insn.getPrevious();
		while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
		return previous;
	}

	private static int declined(String reason) {
		ForbricLog.warn("[Forbric/Fluid] left the fluid interactions as merged: %s — a MinecraftForge mod's fluid interactions may "
				+ "not run when a block next to the liquid changes", reason);
		return -1;
	}
}
