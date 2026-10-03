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

package net.forbric.installer.kernel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Reproduces MinecraftForge's own 1.20.1 install profile in pure Java: produce the Forge-patched, SRG-named
 * Minecraft game jar (Mojang class names + SRG member names) that Forbric then remaps onto intermediary.
 *
 * <p>The pipeline is Forge's, not the old 26.2 dev script's: 1.20.1 does not use {@code mergetool}; it uses
 * {@code installertools MCP_DATA/DOWNLOAD_MOJMAPS/MERGE_MAPPING}, {@code jarsplitter} and
 * {@code ForgeAutoRenamingTool}, then {@code binarypatcher}. The binarypatch is applied to the <b>SRG-renamed</b>
 * jar, which is why doing it to the raw obf jar (the 26.2 shape) fails its checksum. Verified against
 * {@code forge-1.20.1-47.4.0-installer.jar}'s {@code install_profile.json} (client side).
 * <ol>
 *   <li>{@code installertools BUNDLER_EXTRACT} the Mojang server jar (for the common mappings).</li>
 *   <li>{@code MCP_DATA} (mcp_config) and {@code DOWNLOAD_MOJMAPS}, joined by {@code MERGE_MAPPING}.</li>
 *   <li>{@code jarsplitter} (client) then {@code ForgeAutoRenamingTool} → SRG-named common Minecraft.</li>
 *   <li>{@code binarypatcher --apply joined.lzma}.</li>
 *   <li>apply Forge's access transformers; inject the flat-loader {@code self()} shim.</li>
 * </ol>
 * The result embeds Mojang code, so it is built here and never redistributed.
 */
final class PatchedMcBuilder {

	// Forge 1.20.1's install-profile tool coordinates (the userdev config carries binarypatcher itself).
	private static final String INSTALLERTOOLS = "net.minecraftforge:installertools:1.4.1:fatjar";
	private static final String JARSPLITTER = "net.minecraftforge:jarsplitter:1.1.4";
	private static final String FORGE_AUTO_RENAMING = "net.minecraftforge:ForgeAutoRenamingTool:0.1.22:all";
	private static final String LOG4J_API = "org.apache.logging.log4j:log4j-api:2.24.3";
	private static final String LOG4J_CORE = "org.apache.logging.log4j:log4j-core:2.24.3";
	private static final String[] ASM_ARTIFACTS = {"asm", "asm-tree", "asm-commons", "asm-util", "asm-analysis"};

	private final ForgeArtifacts fa;
	private final Http http;
	private final ForgeTool tool;
	private final Path mcDir;
	private final Path workDir;
	private final Path dlDir;
	private final Path outJar;
	private final Consumer<String> log;

	PatchedMcBuilder(ForgeArtifacts fa, Http http, Path mcDir, Path workDir, Path outJar, Consumer<String> log) {
		this.fa = fa;
		this.http = http;
		this.tool = new ForgeTool(log);
		this.mcDir = mcDir;
		this.workDir = workDir;
		this.dlDir = workDir.resolve("dl");
		this.outJar = outJar;
		this.log = log;
	}

	ArtifactResult build(Path userdevJar, ForgeArtifacts.UserdevConfig cfg, Path forgeRuntimeJar) throws IOException {
		String coordinate = fa.patchedMcCoordinate();
		if (BuildStamp.isFresh(outJar)) {
			log.accept("[patched] up-to-date: " + outJar.getFileName());
			return new ArtifactResult(coordinate, outJar, Util.sha1(outJar), Files.size(outJar));
		}
		Files.createDirectories(dlDir);

		// 1) tools
		log.accept("[patched] fetching tools + Forge userdev");
		Path installertools = dl(INSTALLERTOOLS);
		Path binarypatcher = dl(cfg.binpatcherCoordinate);
		Path jarsplitter = dl(JARSPLITTER);
		Path joptSimple = dl("net.sf.jopt-simple:jopt-simple:5.0.4");
		Path srgutils = dl("net.minecraftforge:srgutils:0.4.3");
		Path forgeAutoRenaming = dl(FORGE_AUTO_RENAMING);
		String atCoord = findLib(cfg, "net.minecraftforge", "accesstransformers", "net.minecraftforge:accesstransformers:8.2.2");
		String asmVersion = asmVersion(cfg);
		Path atJar = dl(atCoord);
		List<Path> engineCp = new ArrayList<>();
		engineCp.add(atJar);
		for (String a : ASM_ARTIFACTS) engineCp.add(dlCentral("org.ow2.asm:" + a + ":" + asmVersion));
		engineCp.add(dlCentral(LOG4J_API));
		engineCp.add(dlCentral(LOG4J_CORE));
		// The AT parser's ANTLR runtime and forgespi ride in the Forge carrier, so reuse it instead of pinning
		// each of Forge's runtime libraries by hand.
		if (forgeRuntimeJar != null && Files.isRegularFile(forgeRuntimeJar)) engineCp.add(forgeRuntimeJar);

		// 2) client jar from the user's install
		Path clientJar = mcDir.resolve("versions").resolve(fa.mcVersion).resolve(fa.mcVersion + ".jar");
		if (!Files.isRegularFile(clientJar)) {
			throw new IOException("client jar not found: " + clientJar + " — install/download vanilla "
					+ fa.mcVersion + " first");
		}

		// 3) server jar via <mc>.json downloads.server
		Path serverJar = dlDir.resolve("server.jar");
		downloadServer(serverJar);

		// 4) mappings: MCP_DATA (obf→srg) + DOWNLOAD_MOJMAPS (named→obf) → MERGE_MAPPING (obf→srg, Mojmap classes)
		Path mcpConfig = downloadMcpConfig(cfg);
		Path mappings = workDir.resolve("joined.tsrg");
		tool.runJar(installertools, List.of("--task", "MCP_DATA", "--input", mcpConfig.toString(),
				"--output", mappings.toString(), "--key", "mappings"), "installertools MCP_DATA");
		Path mojmaps = workDir.resolve("client_mappings.tsrg");
		tool.runJar(installertools, List.of("--task", "DOWNLOAD_MOJMAPS", "--version", fa.mcVersion,
				"--side", "client", "--output", mojmaps.toString()), "installertools DOWNLOAD_MOJMAPS");
		Path mergedMappings = workDir.resolve("merged_mappings.tsrg");
		tool.runJar(installertools, List.of("--task", "MERGE_MAPPING", "--left", mappings.toString(),
				"--right", mojmaps.toString(), "--output", mergedMappings.toString(), "--classes", "--reverse-right"),
				"installertools MERGE_MAPPING");

		// 5) jarsplitter (client) → slim common jar, then rename to SRG
		Path slim = workDir.resolve("mc-slim.jar");
		Path extra = workDir.resolve("mc-extra.jar");
		// jarsplitter is not a fat jar: it needs jopt-simple + srgutils beside it.
		tool.runClasspath(List.of(jarsplitter, joptSimple, srgutils), "net.minecraftforge.jarsplitter.ConsoleTool",
				List.of("--input", clientJar.toString(), "--slim", slim.toString(),
						"--extra", extra.toString(), "--srg", mergedMappings.toString()), "jarsplitter");
		Path srg = workDir.resolve("mc-srg.jar");
		tool.runJar(forgeAutoRenaming, List.of("--input", slim.toString(), "--output", srg.toString(),
				"--names", mergedMappings.toString(), "--ann-fix", "--ids-fix", "--src-fix", "--record-fix"),
				"ForgeAutoRenamingTool");

		// 6) extract the CLIENT binpatch from Forge's installer + ATs from the userdev jar, then binarypatch.
		//    The installer's data/client.lzma is generated against exactly the client slim SRG jar built above;
		//    the userdev's joined.lzma is the ForgeGradle joined variant and does not match these checksums.
		Zips.extractEntries(userdevJar, workDir, cfg.ats.get(0));
		Path atCfg = workDir.resolve(cfg.ats.get(0));
		Path installerJar = dl("net.minecraftforge:forge:" + fa.forgeVersion + ":installer");
		Zips.extractEntries(installerJar, workDir, "data/client.lzma");
		Path clientLzma = workDir.resolve("data/client.lzma");
		Path patchedFull = workDir.resolve("patched-full.jar");
		tool.runJar(binarypatcher, binpatcherArgs(cfg, srg, patchedFull, clientLzma),
				"binarypatcher --apply data/client.lzma");

		// 7) binarypatcher writes only the patched classes; overlay them onto the full SRG jar
		Path patchedComplete = workDir.resolve("patched-complete.jar");
		overlay(srg, patchedFull, patchedComplete);

		// 8) apply access transformers
		Path patchedAt = workDir.resolve("patched-at.jar");
		tool.applyAccessTransformers(engineCp, atCfg, patchedComplete, patchedAt);

		// 9) inject a concrete covariant self() into MC classes implementing a public-self() Forge interface
		//    (IForgeLivingEntity/LivingEntity) — Forge's public interface default does not resolve on subclasses
		//    under Forbric's flat Knot classloader (ServerPlayer AbstractMethodError). Reuses the AT step's ASM.
		Files.createDirectories(outJar.getParent());
		int injected = tool.injectCovariantSelf(engineCp, forgeRuntimeJar, patchedAt, outJar);
		log.accept("[patched] injected concrete self() into " + injected + " class(es)");
		// jarsplitter put the client's assets/lang/data in the extra jar; the game needs them (Language.loadDefault
		// NPEs without en_us.json). The AT/self steps rewrite the jar and drop non-class entries, so merge LAST.
		if (Files.isRegularFile(extra)) {
			java.util.LinkedHashMap<String, byte[]> merged = Zips.readAll(outJar);
			Zips.readAll(extra).forEach(merged::putIfAbsent);
			Zips.writeJar(outJar, merged);
			log.accept("[patched] merged the client resources (" + Zips.readAll(extra).size() + " entries)");
		}

		String sha1 = Util.sha1(outJar);
		long size = Files.size(outJar);
		log.accept("[patched] wrote " + outJar.getFileName() + " (" + (size / (1024 * 1024)) + " MB)");
		BuildStamp.write(outJar);
		return new ArtifactResult(coordinate, outJar, sha1, size);
	}

	// ---- steps ----

	private List<String> binpatcherArgs(ForgeArtifacts.UserdevConfig cfg, Path clean, Path output, Path patch) {
		List<String> out = new ArrayList<>();
		for (String tok : cfg.binpatcherArgs) {
			switch (tok) {
				case "{clean}":  out.add(clean.toString()); break;
				case "{output}": out.add(output.toString()); break;
				case "{patch}":  out.add(patch.toString()); break;
				default:         out.add(tok);
			}
		}
		return out;
	}

	@SuppressWarnings("unchecked")
	private void downloadServer(Path dest) throws IOException {
		Path versionJson = mcDir.resolve("versions").resolve(fa.mcVersion).resolve(fa.mcVersion + ".json");
		if (!Files.isRegularFile(versionJson)) {
			throw new IOException("version json not found: " + versionJson + " — download vanilla " + fa.mcVersion + " first");
		}
		Map<String, Object> root;
		try {
			root = (Map<String, Object>) Json.parse(Files.readString(versionJson));
		} catch (RuntimeException e) {
			throw new IOException("malformed " + versionJson.getFileName() + ": " + e.getMessage(), e);
		}
		Object downloads = root.get("downloads");
		Map<String, Object> server = downloads instanceof Map ? (Map<String, Object>) ((Map<String, Object>) downloads).get("server") : null;
		if (server == null || server.get("url") == null) {
			throw new IOException(fa.mcVersion + ".json has no downloads.server.url (a client-only version can't be Forge-patched)");
		}
		String url = (String) server.get("url");
		String sha1 = (String) server.get("sha1");
		if (Files.isRegularFile(dest) && Files.size(dest) > 0) {
			// trust a cached server jar only if its sha1 still matches
			if (sha1 == null || sha1.equalsIgnoreCase(Util.sha1(dest))) return;
		}
		log.accept("[patched] downloading " + fa.mcVersion + " server jar …");
		http.downloadToFile(url, dest);
		ForgeTool.verifySha1(dest, sha1, fa.mcVersion + " server.jar");
	}

	/** Download MCPConfig's {@code @zip} artifact (its {@code config/joined.tsrg} is the obf→srg source). */
	private Path downloadMcpConfig(ForgeArtifacts.UserdevConfig cfg) throws IOException {
		if (cfg.mcpCoordinate == null) {
			throw new IOException("the Forge userdev config.json carries no 'mcp' coordinate");
		}
		String[] parts = cfg.mcpCoordinate.split(":");
		String rel = parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2] + "/"
				+ parts[1] + "-" + parts[2] + ".zip";
		Path dest = dlDir.resolve(parts[1] + "-" + parts[2] + ".zip");
		http.ensureWithFallback(ForgeArtifacts.FORGE_MVN + "/" + rel, ForgeArtifacts.CENTRAL + "/" + rel, dest);
		return dest;
	}

	/** Overlay patched classes onto the clean merge, strip signatures, keep only the manifest main section. */
	static void overlay(Path clean, Path patchedSubset, Path out) throws IOException {
		LinkedHashMap<String, byte[]> pe = Zips.readAll(patchedSubset);
		LinkedHashMap<String, byte[]> cleanAll = Zips.readAll(clean);
		LinkedHashMap<String, byte[]> result = new LinkedHashMap<>();
		for (Map.Entry<String, byte[]> e : cleanAll.entrySet()) {
			String n = e.getKey();
			if (Zips.isSignatureFile(n)) continue;
			if (n.equals("META-INF/MANIFEST.MF")) {
				result.put(n, manifestMainSection(e.getValue()));
				continue;
			}
			result.put(n, pe.containsKey(n) ? pe.get(n) : e.getValue());
		}
		for (Map.Entry<String, byte[]> e : pe.entrySet()) {
			if (!result.containsKey(e.getKey())) result.put(e.getKey(), e.getValue());
		}
		Zips.writeJar(out, result);
	}

	/** Keep only the manifest's main section (re-serialized classes fail the original per-file SHA digests). */
	private static byte[] manifestMainSection(byte[] raw) {
		String text = new String(raw, StandardCharsets.UTF_8);
		String main = text.split("\r\n\r\n", 2)[0];
		main = main.split("\n\n", 2)[0];
		main = rstrip(main) + "\r\n";
		return main.getBytes(StandardCharsets.UTF_8);
	}

	// ---- download helpers ----

	private Path dl(String coordinate) throws IOException {
		String file = fileName(coordinate);
		Path dest = dlDir.resolve(file);
		http.ensureWithFallback(fa.forgeUrl(coordinate), fa.centralUrl(coordinate), dest);
		return dest;
	}

	private Path dlCentral(String coordinate) throws IOException {
		String file = fileName(coordinate);
		Path dest = dlDir.resolve(file);
		http.ensureWithFallback(fa.centralUrl(coordinate), fa.forgeUrl(coordinate), dest);
		return dest;
	}

	private static String fileName(String coordinate) {
		String rel = Util.coordinateToPath(ForgeArtifacts.stripExtension(coordinate));
		return rel.substring(rel.lastIndexOf('/') + 1);
	}

	private static String findLib(ForgeArtifacts.UserdevConfig cfg, String group, String artifact, String dflt) {
		for (String lib : cfg.libraries) {
			String[] p = lib.split(":");
			if (p.length >= 2 && p[0].equals(group) && p[1].equals(artifact)) return lib;
		}
		return dflt;
	}

	private static String asmVersion(ForgeArtifacts.UserdevConfig cfg) {
		for (String lib : cfg.libraries) {
			String[] p = lib.split(":");
			if (p.length >= 3 && p[0].equals("org.ow2.asm") && p[1].equals("asm")) return p[2];
		}
		return "9.9.1";
	}

	private static String rstrip(String s) {
		int end = s.length();
		while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) end--;
		return s.substring(0, end);
	}
}
