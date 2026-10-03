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

import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import net.forbric.kernel.util.ForbricLog;

/**
 * The Fabric half of the {@link TransformPhase#DEOBF_REMAP} stage: a Fabric mod class is compiled against
 * Intermediary ({@code class_2960}, {@code method_1234}), and this game runs under Mojang's names, so every such
 * class must be moved onto named before it links.
 *
 * <p>1.21-era Fabric mods ship already-named, which is why the kernel's mapping resolver is the identity — but
 * 1.20.1 mods (fabric-api 0.92.x and everything built against this game version) are Intermediary, so assuming
 * named-only leaves every {@code class_}/{@code method_} reference unresolved at link time.
 *
 * <p>The join is done here directly rather than through {@link net.forbric.kernel.mapping.ForbricMappings}: the
 * shared helper re-roots the merged tree through mapping-io's source-switch visitors, and on this Tiny v2 +
 * ProGuard pair that drops the named namespace entirely. Reading the two files and joining on the obfuscated
 * (official) column is the whole of what is needed and has no such failure mode; Intermediary names are globally
 * unique, so a name-keyed map is enough and no owner hierarchy is required.
 */
public final class IntermediaryRemapTransformer implements ClassTransformer {
	public static final String INTERMEDIARY_PROPERTY = "forbric.mappings.intermediary";
	public static final String MOJMAP_PROPERTY = "forbric.mappings.mojmap";

	private final Map<String, String> classNames;
	private final Map<String, String> fieldNames;
	private final Map<String, String> methodNames;
	private final Remapper remapper;

	/** The one loaded instance, so other services (Mixin refmaps) can share the same intermediary→named tables. */
	private static volatile IntermediaryRemapTransformer instance;

	/**
	 * Rewrites Intermediary class/member names inside any text (a Fabric {@code refmap.json}'s values, most of all).
	 * A no-op when the mappings were not configured.
	 */
	public static String rewriteReferenceNames(String text) {
		IntermediaryRemapTransformer loaded = instance;
		return loaded == null ? text : loaded.rewriteString(text);
	}

	private IntermediaryRemapTransformer(Map<String, String> classNames, Map<String, String> fieldNames,
			Map<String, String> methodNames) {
		this.classNames = classNames;
		this.fieldNames = fieldNames;
		this.methodNames = methodNames;
		if (classNames.isEmpty()) {
			this.remapper = null;
			return;
		}
		this.remapper = new Remapper() {
			@Override
			public String map(String internalName) {
				return classNames.getOrDefault(internalName, internalName);
			}

			@Override
			public String mapFieldName(String owner, String name, String descriptor) {
				return fieldNames.getOrDefault(name, name);
			}

			@Override
			public String mapMethodName(String owner, String name, String descriptor) {
				return methodNames.getOrDefault(name, name);
			}

			/**
			 * Mixin targets are annotation <b>strings</b> ({@code @At(target = "Lnet/minecraft/class_1309;...")},
			 * {@code @Inject(method = "method_1234")}), and {@code ClassRemapper} rewrites only {@code Type}/{@code Handle}
			 * annotation values, not strings. Without this a Fabric mixin keeps Intermediary member names and Mixin
			 * either cannot resolve the target or weaves from a mismatched descriptor and emits unverifiable code.
			 */
			@Override
			public Object mapValue(Object value) {
				if (value instanceof String text) {
					String rewritten = rewriteString(text);
					if (!rewritten.equals(text)) return rewritten;
				}
				return super.mapValue(value);
			}
		};
	}

	/** Builds the Intermediary→named table, or an inert instance when the mapping files are not configured. */
	public static IntermediaryRemapTransformer fromSystemProperties() {
		Path intermediary = path(INTERMEDIARY_PROPERTY);
		Path mojmap = path(MOJMAP_PROPERTY);
		if (intermediary == null || mojmap == null) return inactive();
		try {
			MemoryMappingTree inter = new MemoryMappingTree();
			MappingReader.read(intermediary, inter);
			MemoryMappingTree moj = new MemoryMappingTree();
			MappingReader.read(mojmap, MappingFormat.PROGUARD_FILE, moj);

			int interObf = nsIndex(inter, "official");
			int interMid = nsIndex(inter, "intermediary");
			int mojNamed = nsIndex(moj, "named", "source");
			int mojObf = nsIndex(moj, "official", "target");

			// obf class -> intermediary / named
			Map<String, String> obfToIntermediary = new HashMap<>();
			Map<String, String> obfToNamed = new HashMap<>();
			Map<String, String> obfFieldToIntermediary = new HashMap<>();
			Map<String, String> obfFieldToNamed = new HashMap<>();
			Map<String, String> obfMethodToIntermediary = new HashMap<>();
			Map<String, String> obfMethodToNamed = new HashMap<>();

			for (MappingTree.ClassMapping cls : inter.getClasses()) {
				String obf = safeName(cls, interObf);
				String mid = safeName(cls, interMid);
				if (obf != null && mid != null) obfToIntermediary.put(obf, mid);
				for (MappingTree.FieldMapping field : cls.getFields()) {
					String fo = safeName(field, interObf);
					String fi = safeName(field, interMid);
					if (obf != null && fo != null && fi != null) obfFieldToIntermediary.put(obf + '\0' + fo, fi);
				}
				for (MappingTree.MethodMapping method : cls.getMethods()) {
					String mo = safeName(method, interObf);
					String mi = safeName(method, interMid);
					String md = "off".equalsIgnoreCase(System.getProperty("forbric.methodDescJoin"))
							? "" : safeDesc(method, interObf);
					// Two overloads of one class can share a Mojang obf name only if their descriptors differ, so the
					// join must carry the descriptor or the last overload wins and every other one is left unmapped.
					if (obf != null && mo != null && mi != null) obfMethodToIntermediary.put(obf + '\0' + mo + '\0' + md, mi);
				}
			}

			for (MappingTree.ClassMapping cls : moj.getClasses()) {
				String obf = safeName(cls, mojObf);
				String named = safeName(cls, mojNamed);
				if (obf != null && named != null) obfToNamed.put(obf, named);
				for (MappingTree.FieldMapping field : cls.getFields()) {
					String fo = safeName(field, mojObf);
					String fn = safeName(field, mojNamed);
					if (obf != null && fo != null && fn != null) obfFieldToNamed.put(obf + '\0' + fo, fn);
				}
				for (MappingTree.MethodMapping method : cls.getMethods()) {
					String mo = safeName(method, mojObf);
					String mn = safeName(method, mojNamed);
					String md = "off".equalsIgnoreCase(System.getProperty("forbric.methodDescJoin"))
							? "" : safeDesc(method, mojObf);
					if (obf != null && mo != null && mn != null) obfMethodToNamed.put(obf + '\0' + mo + '\0' + md, mn);
				}
			}

			Map<String, String> classNames = new HashMap<>();
			obfToIntermediary.forEach((obf, mid) -> {
				String named = obfToNamed.get(obf);
				if (named != null && !mid.equals(named)) classNames.put(mid, named);
			});
			Map<String, String> fieldNames = new HashMap<>();
			obfFieldToIntermediary.forEach((key, mid) -> {
				String named = obfFieldToNamed.get(key);
				if (named != null && !mid.equals(named)) fieldNames.put(mid, named);
			});
			Map<String, String> methodNames = new HashMap<>();
			obfMethodToIntermediary.forEach((key, mid) -> {
				String named = obfMethodToNamed.get(key);
				if (named != null && !mid.equals(named)) methodNames.put(mid, named);
			});

			ForbricLog.info("[Forbric/Remap] intermediary → named: %d classes, %d fields, %d methods",
					classNames.size(), fieldNames.size(), methodNames.size());
			IntermediaryRemapTransformer built = new IntermediaryRemapTransformer(classNames, fieldNames, methodNames);
			instance = built;
			return built;
		} catch (IOException | RuntimeException failed) {
			ForbricLog.error("[Forbric/Remap] intermediary mappings could not be read; Fabric mods stay "
					+ "intermediary: %s", failed);
			return inactive();
		}
	}

	/** Rewrites every Intermediary class/member name inside a mixin target string. */
	private String rewriteString(String text) {
		if (text == null || text.isEmpty()) return text;
		if (!text.contains("class_") && !text.contains("method_") && !text.contains("field_")) return text;
		String out = INTERMEDIARY_CLASS.matcher(text).replaceAll(
				m -> java.util.regex.Matcher.quoteReplacement(classNames.getOrDefault(m.group(1), m.group(1))));
		out = INTERMEDIARY_MEMBER.matcher(out).replaceAll(m -> {
			String token = m.group(1);
			String mapped = token.startsWith("method_") ? methodNames.get(token) : fieldNames.get(token);
			return java.util.regex.Matcher.quoteReplacement(mapped != null ? mapped : token);
		});
		return out;
	}

	private static final java.util.regex.Pattern INTERMEDIARY_CLASS = java.util.regex.Pattern.compile(
			"(?<![/$])(net/minecraft/[\\w/$]*class_\\d+(?:\\$[\\w$]+)*)");
	private static final java.util.regex.Pattern INTERMEDIARY_MEMBER = java.util.regex.Pattern.compile(
			"\\b(method_\\d+|field_\\d+)\\b");

	private static int nsIndex(MemoryMappingTree tree, String... names) {
		// mapping-io convention: the SOURCE namespace has id -1, destinations are 0, 1, 2... (NULL is -2).
		for (String name : names) {
			if (name.equals(tree.getSrcNamespace())) return -1;
			int i = tree.getDstNamespaces().indexOf(name);
			if (i >= 0) return i;
		}
		return -2;
	}

	private static String safeName(MappingTree.ElementMapping element, int ns) {
		if (ns == -2) return null;
		try {
			return element.getName(ns);
		} catch (RuntimeException absent) {
			return null;
		}
	}

	/** The descriptor in namespace {@code ns}, or "" when the tree does not carry one. */
	private static String safeDesc(MappingTree.MethodMapping method, int ns) {
		if (ns == -2) return "";
		try {
			String desc = method.getDesc(ns);
			return desc == null ? "" : desc;
		} catch (RuntimeException absent) {
			return "";
		}
	}

	private static IntermediaryRemapTransformer inactive() {
		return new IntermediaryRemapTransformer(Map.of(), Map.of(), Map.of());
	}

	private static Path path(String property) {
		String value = System.getProperty(property);
		if (value == null || value.isBlank()) return null;
		Path candidate = Path.of(value);
		return Files.isRegularFile(candidate) ? candidate : null;
	}

	public boolean active() {
		return remapper != null;
	}

	@Override
	public String name() {
		return "IntermediaryRemap";
	}

	@Override
	public AnchorSet anchors() {
		return AnchorSet.scanned("intermediary-namespace");
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (remapper == null || !looksIntermediary(classBytes)) return classBytes;
		try {
			ClassReader reader = new ClassReader(classBytes);
			ClassWriter writer = new ClassWriter(0);
			reader.accept(new ClassRemapper(writer, remapper), 0);
			return writer.toByteArray();
		} catch (RuntimeException malformed) {
			ForbricLog.warn("[Forbric/Remap] left %s unremapped (%s)", className, malformed);
			return classBytes;
		}
	}

	/** Whether the constant pool names an Intermediary class or member ({@code class_}/{@code method_}/{@code field_}). */
	private static boolean looksIntermediary(byte[] bytes) {
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
			if (in.readInt() != 0xCAFEBABE) return false;
			in.skipNBytes(4);
			int count = in.readUnsignedShort();
			for (int i = 1; i < count; i++) {
				int tag = in.readUnsignedByte();
				switch (tag) {
					case 1 -> {
						if (isIntermediaryName(in.readUTF())) return true;
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

	/** True for {@code class_123}, {@code method_123}, {@code field_123} or a package-qualified {@code class_123$...}. */
	private static boolean isIntermediaryName(String value) {
		int start = value.lastIndexOf('/') + 1;
		if (start >= value.length()) return false;
		String tail = value.substring(start);
		if (tail.startsWith("class_")) {
			int i = "class_".length();
			if (i >= tail.length() || !Character.isDigit(tail.charAt(i))) return false;
			while (i < tail.length() && (Character.isDigit(tail.charAt(i)) || tail.charAt(i) == '$')) i++;
			return true;
		}
		if (tail.startsWith("method_") || tail.startsWith("field_")) {
			int i = tail.indexOf('_') + 1;
			if (i >= tail.length() || !Character.isDigit(tail.charAt(i))) return false;
			while (i < tail.length() && Character.isDigit(tail.charAt(i))) i++;
			return true;
		}
		return false;
	}
}
