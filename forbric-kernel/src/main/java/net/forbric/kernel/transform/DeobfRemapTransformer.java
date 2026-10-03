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

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import net.fabricmc.mappingio.tree.MappingTree;

import net.forbric.kernel.mapping.ForbricMappings;
import net.forbric.kernel.util.ForbricLog;

/**
 * The {@link TransformPhase#DEOBF_REMAP} stage: remaps a Forge-family class from Forge's production namespace
 * (Mojang official class names + SRG member names) onto the canonical runtime namespace (intermediary).
 *
 * <p>This is the runtime half of the namespace model. The game base is converted once by the installer's
 * {@code GameJarRemapper}; the <b>carrier</b> and every <b>guest Forge mod</b> that a player drops into
 * {@code mods/} are still SRG when the game loads them, and they cannot link against an intermediary game until
 * this runs. A guest class is recognised by the SRG member names it references ({@code m_}/{@code f_}); the
 * check is a single constant-pool scan, and a class that carries none is returned untouched.
 *
 * <p>It is inert unless the three mapping files are pointed at by system properties
 * ({@link #INTERMEDIARY_PROPERTY}, {@link #MOJMAP_PROPERTY}, {@link #SRG_PROPERTY}) — a kernel with no mappings
 * still boots and runs the namespaces it already speaks. {@code MAPPINGS.md} records where the SRG tsrg comes
 * from and why it is never bundled.
 */
public final class DeobfRemapTransformer implements ClassTransformer {

	public static final String INTERMEDIARY_PROPERTY = "forbric.mappings.intermediary";
	public static final String MOJMAP_PROPERTY = "forbric.mappings.mojmap";
	public static final String SRG_PROPERTY = "forbric.mappings.srg";

	private final ForbricMappings mappings;
	private final Remapper remapper;
	private final Map<String, String> fields = new HashMap<>();
	private final Map<String, String> methods = new HashMap<>();

	private DeobfRemapTransformer(ForbricMappings mappings) {
		this.mappings = mappings;
		if (mappings == null) {
			this.remapper = null;
			return;
		}
		MappingTree tree = mappings.tree();
		int srgNs = mappings.srgNamespace();
		int namedNs = tree.getNamespaceId(ForbricMappings.NAMED);
		for (MappingTree.ClassMapping cls : tree.getClasses()) {
			String named = namedNs < 0 ? null : cls.getName(namedNs);
			String owner = named == null ? cls.getSrcName() : named;
			for (MappingTree.FieldMapping field : cls.getFields()) {
				String srg = field.getName(srgNs);
				String target = namedNs < 0 ? null : field.getName(namedNs);
				if (srg != null && target != null) fields.put(owner + '\0' + srg, target);
			}
			for (MappingTree.MethodMapping method : cls.getMethods()) {
				String srg = method.getName(srgNs);
				String target = namedNs < 0 ? null : method.getName(namedNs);
				if (srg != null && target != null) methods.put(owner + '\0' + srg, target);
			}
		}
		this.remapper = new Remapper() {
			@Override
			public String map(String internalName) {
				// The runtime namespace is named: SRG class names already equal the Mojmap class names.
				return internalName;
			}

			@Override
			public String mapFieldName(String owner, String name, String descriptor) {
				return srgField(owner, name);
			}

			@Override
			public String mapMethodName(String owner, String name, String descriptor) {
				return srgMethod(owner, name);
			}

			/**
			 * Mixin targets are annotation <b>strings</b> -- {@code @At(target = "Lnet/minecraft/...;m_1_(...)V")},
			 * {@code @Inject(method = "...")} -- and {@code ClassRemapper} rewrites only {@code Type}/{@code Handle}
			 * annotation values, not strings. So a Forge mixin's SRG target survives the class remap and Mixin then
			 * fails to find it against the intermediary game. Rewrite any string that names an SRG member here.
			 */
			@Override
			public Object mapValue(Object value) {
				if (value instanceof String text) {
					String rewritten = rewriteTarget(text);
					if (!rewritten.equals(text)) return rewritten;
				}
				return super.mapValue(value);
			}
		};
	}

	/** A transformer that never edits anything: the state when no mapping files were configured. */
	public static DeobfRemapTransformer inactive() {
		return new DeobfRemapTransformer(null);
	}

	/**
	 * Loads the SRG-aware spine from the files named by the three system properties, or returns
	 * {@link #inactive} when any is missing. Never throws: a bad mapping file must not stop the boot.
	 */
	public static DeobfRemapTransformer fromSystemProperties() {
		Path intermediary = path(INTERMEDIARY_PROPERTY);
		Path mojmap = path(MOJMAP_PROPERTY);
		Path srg = path(SRG_PROPERTY);
		if (intermediary == null || mojmap == null || srg == null) return inactive();
		try {
			ForbricMappings loaded = ForbricMappings.load(intermediary, mojmap, srg);
			ForbricLog.info("[Forbric/Remap] %d classes joined, SRG → intermediary", loaded.classCount());
			return new DeobfRemapTransformer(loaded);
		} catch (IOException | RuntimeException failed) {
			ForbricLog.error("[Forbric/Remap] mappings could not be read; Forge classes stay SRG: %s", failed);
			return inactive();
		}
	}

	private static Path path(String property) {
		String value = System.getProperty(property);
		if (value == null || value.isBlank()) return null;
		Path candidate = Path.of(value);
		return Files.isRegularFile(candidate) ? candidate : null;
	}

	/** Whether the SRG spine was loaded; false means this transformer is a no-op. */
	public boolean active() {
		return mappings != null;
	}

	@Override
	public String name() {
		return "DeobfRemap";
	}

	@Override
	public AnchorSet anchors() {
		return AnchorSet.scanned("srg-namespace");
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (mappings == null || !looksSrg(classBytes)) return classBytes;
		try {
			ClassReader reader = new ClassReader(classBytes);
			ClassWriter writer = new ClassWriter(reader, 0);
			reader.accept(new ClassRemapper(writer, remapper), 0);
			return writer.toByteArray();
		} catch (RuntimeException malformed) {
			ForbricLog.warn("[Forbric/Remap] left %s unremapped (%s)", className, malformed);
			return classBytes;
		}
	}

	/**
	 * Whether a class file references an SRG member name. Reads the constant pool's UTF8 entries and looks for one
	 * shaped {@code m_<digits>_} or {@code f_<digits>_}; a Fabric/intermediary class names members {@code method_}/
	 * {@code field_} instead, so it is left alone.
	 */
	private static boolean looksSrg(byte[] bytes) {
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
			if (in.readInt() != 0xCAFEBABE) return false;
			in.skipNBytes(4); // minor, major
			int count = in.readUnsignedShort();
			for (int i = 1; i < count; i++) {
				int tag = in.readUnsignedByte();
				switch (tag) {
					case 1 -> {
						if (isSrgName(in.readUTF())) return true;
					}
					case 7, 8, 16, 19, 20 -> in.skipNBytes(2);
					case 15 -> in.skipNBytes(3);
					case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipNBytes(4);
					case 5, 6 -> {
						in.skipNBytes(8);
						i++;
					}
					default -> {
						return false;
					}
				}
			}
		} catch (IOException | RuntimeException malformed) {
			return false;
		}
		return false;
	}

	private String srgField(String owner, String name) {
		String mapped = fields.get(owner + '\0' + name);
		return mapped == null ? name : mapped;
	}

	private String srgMethod(String owner, String name) {
		String mapped = methods.get(owner + '\0' + name);
		return mapped == null ? name : mapped;
	}

	/**
	 * Rewrites one mixin target string. Handles the three shapes Mixin accepts: the JVM form
	 * {@code Lowner;name(desc)ret} (methods) and {@code Lowner;name:Ldesc;} (fields), the selector form
	 * {@code owner.name(desc)}, and a bare member name (left alone -- with no owner there is nothing to key on).
	 */
	String rewriteTarget(String target) {
		if (target == null || target.isEmpty() || !hasSrgName(target)) return target;
		int semicolon = target.startsWith("L") ? target.indexOf(';') : -1;
		if (semicolon > 1) {
			String owner = target.substring(1, semicolon);
			String rest = target.substring(semicolon + 1);
			String mappedOwner = owner;
			int paren = rest.indexOf('(');
			int colon = rest.indexOf(':');
			if (paren >= 0) {
				String member = srgMethod(owner, rest.substring(0, paren));
				return "L" + mappedOwner + ";" + member + remapper.mapDesc(rest.substring(paren));
			}
			if (colon >= 0) {
				String member = srgField(owner, rest.substring(0, colon));
				return "L" + mappedOwner + ";" + member + ":" + remapper.mapDesc(rest.substring(colon + 1));
			}
			return "L" + mappedOwner + ";" + srgMethod(owner, rest);
		}
		int paren = target.indexOf('(');
		int dot = target.lastIndexOf('.', paren < 0 ? target.length() : paren);
		if (dot > 0) {
			String owner = target.substring(0, dot);
			String rest = target.substring(dot + 1);
			String mappedOwner = owner;
			if (paren >= 0) {
				return mappedOwner + "." + srgMethod(owner, rest.substring(0, paren))
						+ remapper.mapDesc(rest.substring(paren));
			}
			return mappedOwner + "." + srgMethod(owner, rest);
		}
		return target;
	}

	private static boolean hasSrgName(String value) {
		for (int i = 0; i + 4 <= value.length(); i++) {
			if ((value.charAt(i) == 'm' || value.charAt(i) == 'f') && value.charAt(i + 1) == '_') {
				int j = i + 2;
				while (j < value.length() && Character.isDigit(value.charAt(j))) j++;
				if (j > i + 2 && j < value.length() && value.charAt(j) == '_') return true;
			}
		}
		return false;
	}

	private static boolean isSrgName(String value) {
		int n = value.length();
		if (n < 4) return false;
		if (value.charAt(0) != 'm' && value.charAt(0) != 'f') return false;
		if (value.charAt(1) != '_' || value.charAt(n - 1) != '_') return false;
		for (int i = 2; i < n - 1; i++) {
			if (!Character.isDigit(value.charAt(i))) return false;
		}
		return true;
	}
}
