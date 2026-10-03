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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Builds the jars a Forbric instance runs on, here, on the machine that will run them.
 *
 * <p>They cannot be shipped. The game base is Minecraft with MinecraftForge's patches applied, and the runtime is
 * assembled from MinecraftForge's own distribution. Both carry code this project has no right to hand out, so an
 * installer that shipped them would be redistributing Mojang's and MinecraftForge's work. Building them from the
 * upstreams' own Mavens, on the user's machine, is the only lawful shape this can take.
 *
 * <p>The pipeline is linear since NeoForge was dropped for 1.20.1 — there is one Forge family, so there is no
 * diamond and no byte-merge:
 *
 * <pre>
 *   forge userdev ─┬→ forge-runtime ────────────────────────┐
 *                  └→ patched-mc-forge (SRG) ──────────────┴→ patched-mc-merged (runtime namespace)
 *   vanilla &lt;mc&gt;.jar ──────────────────────────────────────┘
 * </pre>
 *
 * <p>Everything lands under {@code <mcDir>/.forbric-build/}, one directory that can be deleted wholesale, and
 * each step short-circuits on a finished output so an interrupted install resumes rather than restarts.
 */
final class ArtifactBuilder {

	/** The coordinates {@link Installer} stages and the profile names, without their version suffix. */
	static final String MERGED = "net.forbric:patched-mc-merged";
	static final String FORGE_RUNTIME = "net.forbric:forge-runtime";

	private final Consumer<String> log;

	ArtifactBuilder(Consumer<String> log) {
		this.log = log;
	}

	/**
	 * Produces the game base and the Forge runtime, reusing whatever is already built.
	 *
	 * @param mcDir     the Minecraft directory; its {@code versions/<mc>/<mc>.jar} is the vanilla input and its
	 *                  {@code .forbric-build/} holds every intermediate
	 * @param jvm       the JVM the build tools run under
	 * @return coordinate (without version) to the finished file, in the shape {@link GameArtifacts#all()} returns
	 */
	Map<String, Path> build(Path mcDir, String mcVersion, JdkLocator.Jvm jvm) throws IOException {
		Path build = mcDir.resolve(".forbric-build");
		Path dl = build.resolve("dl");
		Path out = build.resolve("out");
		Files.createDirectories(dl);
		Files.createDirectories(out);

		Path vanilla = mcDir.resolve("versions").resolve(mcVersion).resolve(mcVersion + ".jar");
		if (!Files.isRegularFile(vanilla)) {
			throw new IOException("the vanilla " + mcVersion + " jar is missing: " + vanilla);
		}

		log.accept("");
		log.accept("Building the game artifacts. The first run downloads a few hundred megabytes and takes");
		log.accept("several minutes; afterwards it is cached in " + build + ".");
		log.accept("pins: " + Pins.stamp());

		Http http = new Http(log);

		// ---- MinecraftForge ----
		log.accept("");
		log.accept("== MinecraftForge " + Pins.FORGE + " ==");
		ForgeArtifacts fa = new ForgeArtifacts(mcVersion, Pins.FORGE);
		Path forgeUserdev = dl.resolve("forge-userdev.jar");
		http.ensureWithFallback(fa.forgeUrl(fa.userdevCoordinate()), fa.centralUrl(fa.userdevCoordinate()),
				forgeUserdev);
		ForgeArtifacts.UserdevConfig forgeCfg = ForgeArtifacts.readConfig(forgeUserdev);

		ArtifactResult forgeRuntime = new ForgeRuntimeBuilder(fa, http, build, out.resolve("forge-runtime.jar"), log)
				.build(forgeCfg);
		ArtifactResult forgePatched = new PatchedMcBuilder(fa, http, mcDir, build,
				out.resolve("patched-mc-forge-" + mcVersion + ".jar"), log)
				.build(forgeUserdev, forgeCfg, forgeRuntime.file);

		// ---- the runtime base: remap the SRG Forge base onto intermediary ----
		//
		// Forge's patched jar speaks its production namespace (Mojang class names + SRG member names). The
		// Fabric side of the game runs in intermediary, so the base is converted once, here.
		log.accept("");
		log.accept("== remapping the game base to " + Pins.RUNTIME_NAMESPACE + " ==");
		Path mappings = build.resolve("mappings");
		Files.createDirectories(mappings);
		Path intermediary = intermediaryMappings(http, mappings);
		Path mojmap = mojangMappings(http, mcDir, mcVersion, mappings);
		Path tsrg = forgeSrgMappings(http, forgeCfg, mappings);

		ArtifactResult base = new GameJarRemapper(build.resolve("tools"), log).remap(jvm, forgePatched.file,
				out.resolve("patched-mc-merged-" + mcVersion + ".jar"),
				intermediary, mojmap, tsrg, java.util.List.of(forgeRuntime.file),
				MERGED + ":" + mcVersion);
		ensureVersionJson(base.file, mcVersion, vanilla);

		// The runtime carrier is Forge's own code, compiled against SRG; it must speak the same named namespace as
		// the base or every net.minecraftforge.registries call dies on a m_ NoSuchMethodError inside Bootstrap.
		ArtifactResult carrier = new GameJarRemapper(build.resolve("tools"), log).remap(jvm, forgeRuntime.file,
				out.resolve("forge-runtime-named.jar"), intermediary, mojmap, tsrg,
				java.util.List.of(base.file), FORGE_RUNTIME + ":" + mcVersion);

		Map<String, Path> result = new LinkedHashMap<>();
		result.put(MERGED, base.file);
		result.put(FORGE_RUNTIME, carrier.file);

		log.accept("");
		log.accept("game artifacts ready:");
		for (Map.Entry<String, Path> e : result.entrySet()) {
			log.accept("  " + e.getKey() + "  →  " + e.getValue().getFileName()
					+ " (" + (Files.size(e.getValue()) / (1024 * 1024)) + " MB)");
		}
		return result;
	}

	/**
	 * The game base must carry a {@code version.json}: the kernel reads the game version from it and the dev
	 * launcher stages it as the classpath's metadata-only jar. The Forge-patched jar has none, so add one.
	 */
	private static void ensureVersionJson(Path jar, String mcVersion, Path vanillaJar) throws IOException {
		LinkedHashMap<String, byte[]> entries = Zips.readAll(jar);
		if (entries.containsKey("version.json")) return;
		// Prefer the vanilla jar's own version.json (DetectedVersion requires stable/world_version/pack_version/...).
		byte[] version = vanillaJar != null && Files.isRegularFile(vanillaJar)
				? Zips.readEntry(vanillaJar, "version.json") : null;
		if (version == null) {
			version = ("{\"id\":\"" + mcVersion + "\",\"name\":\"" + mcVersion + "\",\"world_version\":3465,"
					+ "\"series_id\":\"main\",\"protocol_version\":763,"
					+ "\"pack_version\":{\"resource\":15,\"data\":15},"
					+ "\"build_time\":\"2023-06-12T13:23:26+00:00\",\"java_component\":\"java-runtime-gamma\","
					+ "\"java_version\":17,\"stable\":true}").getBytes(StandardCharsets.UTF_8);
		}
		entries.put("version.json", version);
		Zips.writeJar(jar, entries);
	}

	/** Fabric intermediary ({@code official → intermediary}), extracted from the intermediary jar. */
	private static Path intermediaryMappings(Http http, Path mappings) throws IOException {
		Path jar = mappings.resolve("intermediary.jar");
		http.ensure(Pins.INTERMEDIARY_URL, jar);
		byte[] tiny = Zips.readEntry(jar, "mappings/mappings.tiny");
		if (tiny == null) throw new IOException("no mappings/mappings.tiny in " + jar);
		Path out = mappings.resolve("intermediary.tiny");
		Files.write(out, tiny);
		return out;
	}

	/** Mojang's ProGuard mappings ({@code named → official}), whose URL the version JSON carries. */
	private static Path mojangMappings(Http http, Path mcDir, String mcVersion, Path mappings) throws IOException {
		Path versionJson = mcDir.resolve("versions").resolve(mcVersion).resolve(mcVersion + ".json");
		if (!Files.isRegularFile(versionJson)) {
			throw new IOException("the vanilla version JSON is missing: " + versionJson);
		}
		Object parsed = Json.parse(Files.readString(versionJson));
		String url = null;
		if (parsed instanceof Map<?, ?> root && root.get("downloads") instanceof Map<?, ?> downloads
				&& downloads.get("client_mappings") instanceof Map<?, ?> cm && cm.get("url") instanceof String u) {
			url = u;
		}
		if (url == null) throw new IOException("no downloads.client_mappings.url in " + versionJson);
		Path out = mappings.resolve("client.txt");
		http.ensure(url, out);
		return out;
	}

	/** MCPConfig's {@code joined.tsrg} ({@code obf → srg}), the SRG source the Forge userdev config names. */
	private static Path forgeSrgMappings(Http http, ForgeArtifacts.UserdevConfig config, Path mappings)
			throws IOException {
		if (config.mcpCoordinate == null) {
			throw new IOException("the Forge userdev config.json carries no 'mcp' coordinate, so the SRG member "
					+ "names cannot be read (see forbric-loader/MAPPINGS.md)");
		}
		// The artifact is a @zip, so the '.jar' coordinate helper would name the wrong file; build the path here.
		String[] parts = config.mcpCoordinate.split(":");
		String path = parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2] + "/"
				+ parts[1] + "-" + parts[2] + ".zip";
		Path zip = mappings.resolve("mcp_config.zip");
		http.ensureWithFallback(ForgeArtifacts.FORGE_MVN + "/" + path, ForgeArtifacts.CENTRAL + "/" + path, zip);
		byte[] tsrg = Zips.readEntry(zip, Pins.SRG_ENTRY);
		if (tsrg == null) throw new IOException("no " + Pins.SRG_ENTRY + " in " + zip);
		Path out = mappings.resolve("joined.tsrg");
		Files.write(out, tsrg);
		return out;
	}
}
