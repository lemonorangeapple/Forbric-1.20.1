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

package net.forbric.kernel.mapping;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.adapter.MappingNsRenamer;
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

/**
 * The Forbric mapping spine: it exposes the game in the namespaces a 1.20.1 mod can reference it in, so a
 * Forge mod's bytecode can be remapped onto the canonical runtime namespace (intermediary) the Fabric
 * substrate runs in.
 *
 * <p>Four namespaces are joined on the shared <b>obfuscated</b> column:
 * <ul>
 *   <li>Fabric <b>intermediary</b> ({@code official → intermediary}) — the runtime namespace;</li>
 *   <li>Mojang's ProGuard <b>named</b> mappings ({@code named → official}) — the deobfuscated class and
 *       member names;</li>
 *   <li>Forge's <b>SRG</b> member names ({@code obf → srg}), read from MCPConfig's {@code joined.tsrg}.</li>
 * </ul>
 *
 * <p>Forge 1.20.1's production namespace is <b>Mojmap class names + SRG member names</b> (verified against the
 * {@code forge-1.20.1-47.4.0-universal.jar}: it references {@code net/minecraft/world/...} classes and
 * {@code m_}/{@code f_} members, and no {@code net/minecraft/src/C_} names). So the {@code srg} namespace this
 * class exposes uses the <b>named</b> class names and the tsrg's member names — which is exactly what a
 * published Forge jar speaks.
 *
 * <p>The {@code srg} namespace is only built when a tsrg is supplied (see the three-argument
 * {@link #load}). SRG cannot be derived from Mojang official + Fabric intermediary alone: the {@code m_}/{@code f_}
 * identifiers are MCPConfig's, so the tsrg is read locally at build/run time and never committed or bundled —
 * the same stance {@code MAPPINGS.md} takes for Minecraft itself.
 */
public final class ForbricMappings {
	/** The namespace a modern Forge/NeoForge mod references the game's <em>classes</em> in (Mojmap). */
	public static final String NAMED = "named";
	/** The Fabric runtime namespace = Forbric's canonical namespace on 1.20.1. */
	public static final String INTERMEDIARY = "intermediary";
	/** The obfuscated namespace shared by every mapping source (the join key). */
	public static final String OFFICIAL = "official";
	/**
	 * Forge's production namespace: Mojang official class names with SRG member names. It is keyed by the same
	 * names as {@link #NAMED} for classes, so it is only meaningful together with the tsrg's member column.
	 */
	public static final String SRG = "srg";

	private final MemoryMappingTree namedKeyed;
	private final int intermediaryNs;
	private final int srgNs;
	// SRG member name -> intermediary, keyed owner + '\0' + srgName. The tree's own member lookup is keyed by
	// the source (named) name, so SRG callers -- Forge access transformers and mixin targets -- need this index.
	private final Map<String, String> srgFields = new HashMap<>();
	private final Map<String, String> srgMethods = new HashMap<>();
	// Global SRG-name -> named, for a mixin @Shadow whose owner is the mixin class, not the target.
	private final Map<String, String> srgFieldNames = new HashMap<>();
	private final Map<String, String> srgMethodNames = new HashMap<>();

	private ForbricMappings(MemoryMappingTree namedKeyed) {
		this.namedKeyed = namedKeyed;
		this.intermediaryNs = namedKeyed.getNamespaceId(INTERMEDIARY);
		this.srgNs = namedKeyed.getNamespaceId(SRG);

		if (intermediaryNs < 0) {
			throw new IllegalStateException("merged mappings are missing the intermediary namespace");
		}
		if (srgNs >= 0) {
			int namedNs = namedKeyed.getNamespaceId(NAMED);
			for (MappingTree.ClassMapping cls : namedKeyed.getClasses()) {
				String named = namedNs < 0 ? null : cls.getName(namedNs);
				String owner = named == null ? cls.getSrcName() : named;
				// The runtime namespace is 'named', so the SRG index resolves to the Mojmap member name.
				for (MappingTree.FieldMapping field : cls.getFields()) {
					String srg = field.getName(srgNs);
					String namedMember = namedNs < 0 ? null : field.getName(namedNs);
					if (srg != null && namedMember != null) srgFields.put(owner + '\0' + srg, namedMember);
				}
				for (MappingTree.MethodMapping method : cls.getMethods()) {
					String srg = method.getName(srgNs);
					String namedMember = namedNs < 0 ? null : method.getName(namedNs);
					if (srg != null && namedMember != null) srgMethods.put(owner + '\0' + srg, namedMember);
				}
			}
		}
	}

	/**
	 * Builds the spine without the SRG namespace: Fabric intermediary + Mojang named only. Enough to remap a
	 * mod that already speaks Mojmap; use the three-argument form for Forge mods, which speak SRG.
	 */
	public static ForbricMappings load(Path intermediaryMappings, Path mojmapProguard) throws IOException {
		return load(intermediaryMappings, mojmapProguard, null);
	}

	/**
	 * Builds the spine from a Fabric intermediary mapping file ({@code official → intermediary}, Tiny v1/v2), a
	 * Mojang ProGuard mapping file ({@code named → official}) and, optionally, MCPConfig's {@code joined.tsrg}
	 * ({@code obf → srg}).
	 *
	 * @param forgeSrgTsrg the {@code config/joined.tsrg} from Forge's {@code mcp_config} zip, or null to skip
	 */
	public static ForbricMappings load(Path intermediaryMappings, Path mojmapProguard, Path forgeSrgTsrg)
			throws IOException {
		// Start keyed by 'official' (obf): intermediary already is.
		MemoryMappingTree officialKeyed = new MemoryMappingTree();
		MappingReader.read(intermediaryMappings, officialKeyed);

		// ProGuard exposes (source=named, target=obf); rename target→official + source→named, then re-root on official.
		MappingReader.read(mojmapProguard, MappingFormat.PROGUARD_FILE,
				new MappingNsRenamer(
						new MappingSourceNsSwitch(officialKeyed, OFFICIAL),
						Map.of("source", NAMED, "target", OFFICIAL)));

		if (forgeSrgTsrg != null) {
			attachSrg(officialKeyed, forgeSrgTsrg);
		}

		// Re-root the merged tree on 'named' so Forge mods (which speak Mojmap) can be looked up directly.
		MemoryMappingTree namedKeyed = new MemoryMappingTree();
		officialKeyed.accept(new MappingSourceNsSwitch(namedKeyed, NAMED));

		return new ForbricMappings(namedKeyed);
	}

	/**
	 * Adds the {@code srg} destination namespace to {@code tree} from MCPConfig's {@code joined.tsrg}.
	 *
	 * <p>The tsrg's own class column is MCPConfig's synthetic {@code net/minecraft/src/C_...}; Forge production
	 * does <em>not</em> use it. So each class's {@code srg} name is set to its {@code named} name, and only the
	 * member column is taken from the tsrg.
	 */
	private static void attachSrg(MemoryMappingTree tree, Path tsrg) throws IOException {
		MemoryMappingTree srgTree = new MemoryMappingTree();
		MappingReader.read(tsrg, MappingFormat.TSRG_2_FILE, srgTree);
		int srgNameNs = srgTree.getNamespaceId("srg");
		if (srgNameNs < 0) {
			throw new IOException("tsrg has no 'srg' namespace: " + tsrg);
		}

		List<String> destinations = new ArrayList<>(tree.getDstNamespaces());
		if (!destinations.contains(SRG)) destinations.add(SRG);
		tree.setDstNamespaces(destinations);
		int srgNs = tree.getNamespaceId(SRG);
		int namedNs = tree.getNamespaceId(NAMED);

		for (MappingTree.ClassMapping srgClass : srgTree.getClasses()) {
			String obf = srgClass.getSrcName();
			MappingTree.ClassMapping cls = tree.getClass(obf);
			if (cls == null) continue; // a class neither intermediary nor Mojmap names: nothing to remap

			String named = namedNs < 0 ? null : cls.getName(namedNs);
			cls.setDstName(named == null ? obf : named, srgNs);

			for (MappingTree.FieldMapping field : srgClass.getFields()) {
				MappingTree.FieldMapping target = cls.getField(field.getSrcName(), field.getSrcDesc());
				if (target != null) target.setDstName(field.getName(srgNameNs), srgNs);
			}
			for (MappingTree.MethodMapping method : srgClass.getMethods()) {
				MappingTree.MethodMapping target = cls.getMethod(method.getSrcName(), method.getSrcDesc());
				if (target != null) target.setDstName(method.getName(srgNameNs), srgNs);
			}
		}
	}

	/** Maps a Mojmap class internal name (e.g. {@code net/minecraft/world/phys/Vec3}) to intermediary, or returns the input if unmapped. */
	public String mapClass(String namedInternalName) {
		MappingTree.ClassMapping cls = namedKeyed.getClass(namedInternalName);
		return cls == null ? namedInternalName : orSelf(cls.getName(intermediaryNs), namedInternalName);
	}

	/** Maps a Mojmap field to its intermediary name. */
	public String mapField(String namedOwner, String namedFieldName, String namedDesc) {
		MappingTree.ClassMapping cls = namedKeyed.getClass(namedOwner);
		if (cls == null) return namedFieldName;

		MappingTree.FieldMapping field = cls.getField(namedFieldName, namedDesc);
		return field == null ? namedFieldName : orSelf(field.getName(intermediaryNs), namedFieldName);
	}

	/** Maps a Mojmap method to its intermediary name. {@code namedDesc} may be {@code null} to match by name only. */
	public String mapMethod(String namedOwner, String namedMethodName, String namedDesc) {
		MappingTree.ClassMapping cls = namedKeyed.getClass(namedOwner);
		if (cls == null) return namedMethodName;

		MappingTree.MethodMapping method = cls.getMethod(namedMethodName, namedDesc);
		return method == null ? namedMethodName : orSelf(method.getName(intermediaryNs), namedMethodName);
	}

	/** Maps a Forge SRG field name to its named (Mojmap) name, or returns the input if unmapped. */
	public String mapSrgField(String namedOwner, String srgFieldName, String namedDesc) {
		String mapped = srgFields.get(namedOwner + '\0' + srgFieldName);
		return mapped == null ? srgFieldName : mapped;
	}

	/** Maps a Forge SRG method name to its named (Mojmap) name, or returns the input if unmapped. */
	public String mapSrgMethod(String namedOwner, String srgMethodName, String namedDesc) {
		String mapped = srgMethods.get(namedOwner + '\0' + srgMethodName);
		return mapped == null ? srgMethodName : mapped;
	}

	/** Global SRG-name -> named (owner-agnostic), for a mixin shadow field/method. */
	public String mapSrgFieldName(String srgFieldName) {
		String mapped = srgFieldNames.get(srgFieldName);
		return mapped == null ? srgFieldName : mapped;
	}

	/** Global SRG-name -> named (owner-agnostic), for a mixin shadow method. */
	public String mapSrgMethodName(String srgMethodName) {
		String mapped = srgMethodNames.get(srgMethodName);
		return mapped == null ? srgMethodName : mapped;
	}

	/** The merged tree (named-keyed), for driving a bytecode remapper (e.g. tiny-remapper) in the DEOBF_REMAP phase. */
	public MappingTree tree() {
		return namedKeyed;
	}

	/** Whether the tsrg was supplied and the {@code srg} namespace is present. */
	public boolean hasSrg() {
		return srgNs >= 0;
	}

	/** The {@code srg} namespace id, or -1 when no tsrg was loaded. */
	public int srgNamespace() {
		return srgNs;
	}

	public int classCount() {
		return namedKeyed.getClasses().size();
	}

	private static String orSelf(String mapped, String fallback) {
		return mapped == null ? fallback : mapped;
	}
}
