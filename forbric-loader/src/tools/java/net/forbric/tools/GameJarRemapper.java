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

package net.forbric.tools;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.adapter.MappingNsRenamer;
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

/**
 * Remaps a Forge-family jar from Forge's production namespace (Mojang official class names + SRG member names)
 * onto the game's runtime namespace, <b>named</b> (Mojang official member names), which is what Forbric's
 * game-side source is compiled against.
 *
 * <p>It is the install-time half of the namespace model. A build-time program in the {@code net.forbric.tools}
 * family: it reads jars, writes a jar, and never runs inside the game. It remaps with a direct ASM
 * {@code ClassRemapper}, not tiny-remapper: the SRG namespace's descriptors are the named ones (the tsrg's class
 * column is synthetic and ignored), and a hand-rolled remapper keys members by owner + SRG name, which is exactly
 * the join the mapping spine holds.
 *
 * <p>Usage:
 * <pre>GameJarRemapper &lt;input.jar&gt; &lt;output.jar&gt; &lt;intermediary.tiny&gt; &lt;mojmap.pro&gt; &lt;joined.tsrg&gt; [classpath.jar]...</pre>
 */
public final class GameJarRemapper {

	private GameJarRemapper() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length < 5) {
			System.err.println("usage: GameJarRemapper <input.jar> <output.jar> <intermediary> <mojmap.pro> "
					+ "<joined.tsrg> [classpath.jar]...");
			System.exit(2);
		}
		Path input = Path.of(args[0]);
		Path output = Path.of(args[1]);
		Path intermediary = Path.of(args[2]);
		Path mojmap = Path.of(args[3]);
		Path tsrg = Path.of(args[4]);

		for (Path required : List.of(input, intermediary, mojmap, tsrg)) {
			if (!Files.isRegularFile(required)) {
				System.err.println("GameJarRemapper: missing input " + required);
				System.exit(2);
			}
		}

		MemoryMappingTree tree = join(intermediary, mojmap, tsrg);
		SrgRemapper remapper = new SrgRemapper(tree);
		System.out.println("[remap] " + tree.getClasses().size() + " classes, " + remapper.members()
				+ " members, namespaces " + tree.getSrcNamespace() + " -> " + tree.getDstNamespaces());
		remap(input, output, remapper);
		System.out.println("[remap] wrote " + output + " (" + (Files.size(output) / (1024 * 1024)) + " MB)");
	}

	// ---- the join: Fabric intermediary + Mojang ProGuard + MCPConfig tsrg, on the obfuscated column ----

	static MemoryMappingTree join(Path intermediary, Path mojmap, Path tsrg) throws IOException {
		MemoryMappingTree officialKeyed = new MemoryMappingTree();
		MappingReader.read(intermediary, officialKeyed);
		MappingReader.read(mojmap, MappingFormat.PROGUARD_FILE,
				new MappingNsRenamer(
						new MappingSourceNsSwitch(officialKeyed, "official"),
						Map.of("source", "named", "target", "official")));

		MemoryMappingTree srgTree = new MemoryMappingTree();
		MappingReader.read(tsrg, MappingFormat.TSRG_2_FILE, srgTree);
		int srgNameNs = srgTree.getNamespaceId("srg");
		if (srgNameNs < 0) throw new IOException("tsrg has no 'srg' namespace: " + tsrg);

		List<String> destinations = new ArrayList<>(officialKeyed.getDstNamespaces());
		if (!destinations.contains("srg")) destinations.add("srg");
		officialKeyed.setDstNamespaces(destinations);
		int srgNs = officialKeyed.getNamespaceId("srg");
		int namedNs = officialKeyed.getNamespaceId("named");

		for (MappingTree.ClassMapping srgClass : srgTree.getClasses()) {
			String obf = srgClass.getSrcName();
			MappingTree.ClassMapping cls = officialKeyed.getClass(obf);
			if (cls == null) continue;
			// Forge production speaks Mojmap class names; the tsrg's synthetic C_ names are not used.
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

		MemoryMappingTree namedKeyed = new MemoryMappingTree();
		officialKeyed.accept(new MappingSourceNsSwitch(namedKeyed, "named"));
		return namedKeyed;
	}

	// ---- the remapper: srg (Mojmap classes + SRG members) -> named ----

	static final class SrgRemapper extends Remapper {
		private final Map<String, String> classes = new HashMap<>();
		private final Map<String, String> fields = new HashMap<>();
		private final Map<String, String> methods = new HashMap<>();
		private final Map<String, String> fieldNames = new HashMap<>();
		private final Map<String, String> methodNames = new HashMap<>();

		SrgRemapper(MappingTree tree) {
			// The spine is re-rooted on 'named', so that is the SOURCE namespace (id -1), not a destination.
			boolean namedIsSrc = "named".equals(tree.getSrcNamespace());
			int srgNs = tree.getNamespaceId("srg");
			int namedNs = namedIsSrc ? -1 : tree.getNamespaceId("named");
			if (srgNs < 0 || (!namedIsSrc && namedNs < 0)) throw new IllegalStateException("tree lacks srg/named");
			for (MappingTree.ClassMapping cls : tree.getClasses()) {
				String srg = cls.getName(srgNs);
				String named = namedIsSrc ? cls.getSrcName() : cls.getName(namedNs);
				if (srg == null || named == null) continue;
				classes.put(srg, named);
				for (MappingTree.FieldMapping field : cls.getFields()) {
					String f = field.getName(srgNs);
					String n = namedIsSrc ? field.getSrcName() : field.getName(namedNs);
					if (f != null && n != null) { fields.put(srg + '\0' + f, n); fieldNames.put(f, n); }
				}
				for (MappingTree.MethodMapping method : cls.getMethods()) {
					String m = method.getName(srgNs);
					String n = namedIsSrc ? method.getSrcName() : method.getName(namedNs);
					if (m != null && n != null) { methods.put(srg + '\0' + m, n); methodNames.put(m, n); }
				}
			}
		}

		int members() {
			return fields.size() + methods.size();
		}

		@Override
		public String map(String internalName) {
			String mapped = classes.get(internalName);
			return mapped == null ? internalName : mapped;
		}

		@Override
		public String mapFieldName(String owner, String name, String descriptor) {
			String mapped = fields.get(owner + '\0' + name);
			if (mapped == null) mapped = fieldNames.get(name);
			return mapped == null ? name : mapped;
		}

		@Override
		public String mapMethodName(String owner, String name, String descriptor) {
			String mapped = methods.get(owner + '\0' + name);
			if (mapped == null) mapped = methodNames.get(name);
			return mapped == null ? name : mapped;
		}

		@Override
		public String mapInvokeDynamicMethodName(String name, String descriptor) {
			// A lambda's invokedynamic name is the functional interface's SAM name; on a Forge class it is SRG.
			String mapped = methodNames.get(name);
			return mapped == null ? name : mapped;
		}
	}

	static void remap(Path input, Path output, SrgRemapper remapper) throws IOException {
		Files.deleteIfExists(output);
		if (output.getParent() != null) Files.createDirectories(output.getParent());
		try (ZipFile in = new ZipFile(input.toFile());
				ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(output))) {
			Enumeration<? extends ZipEntry> entries = in.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				String name = entry.getName();
				// Strip signatures: the re-serialized classes would fail the original per-file digests.
				if (name.endsWith(".SF") || name.endsWith(".DSA") || name.endsWith(".RSA")) continue;
				out.putNextEntry(new ZipEntry(name));
				try (InputStream data = in.getInputStream(entry)) {
					if (name.endsWith(".class")) {
						ClassReader reader = new ClassReader(data);
						ClassWriter writer = new ClassWriter(0);
						reader.accept(new ClassRemapper(writer, remapper), 0);
						out.write(writer.toByteArray());
					} else {
						data.transferTo((OutputStream) out);
					}
				}
				out.closeEntry();
			}
		}
	}
}
