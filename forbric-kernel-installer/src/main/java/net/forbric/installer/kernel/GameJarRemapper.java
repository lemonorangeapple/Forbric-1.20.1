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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Runs the tools jar's {@code net.forbric.tools.GameJarRemapper} as a subprocess: it converts the Forge-patched
 * Minecraft base, which speaks Forge's production namespace (Mojang class names + SRG member names), onto
 * Fabric's intermediary namespace — the namespace the game runs in once Fabric mods are in the mix.
 *
 * <p>The remap stack (tiny-remapper + mapping-io) lives shaded inside {@code forbric-merge-tools.jar}, which
 * rides in the installer as a resource, so the installer itself stays pure JDK. The remap is given a 4 GB heap
 * because it rewrites ~10k classes; a process cannot raise its own maximum heap after it starts, so it must be a
 * child JVM.
 */
final class GameJarRemapper {

	/** Where the tools jar rides inside the installer jar. */
	private static final String TOOLS_RESOURCE = "/forbric/tools/forbric-merge-tools.jar";
	private static final String REMAP_MAIN = "net.forbric.tools.GameJarRemapper";
	private static final String REMAP_HEAP = "-Xmx4g";

	private final Path toolsDir;
	private final Consumer<String> log;

	GameJarRemapper(Path toolsDir, Consumer<String> log) {
		this.toolsDir = toolsDir;
		this.log = log;
	}

	/**
	 * Remaps {@code input} (Forge production namespace) to {@code output} (intermediary), reusing a finished
	 * output the way the rest of the build does.
	 *
	 * @param classpath extra jars tiny-remapper may read for inheritance resolution (the carrier, vanilla)
	 */
	ArtifactResult remap(JdkLocator.Jvm jvm, Path input, Path output, Path intermediary, Path mojmap, Path tsrg,
	                     List<Path> classpath, String coordinate) throws IOException {
		if (BuildStamp.isFresh(output)) {
			log.accept("[remap] up-to-date: " + output.getFileName());
			return new ArtifactResult(coordinate, output, Util.sha1(output), Files.size(output));
		}
		Path tools = unpackTools();
		Files.createDirectories(output.getParent());

		List<String> cmd = new ArrayList<>();
		cmd.add(jvm.javaBin().toString());
		cmd.add(REMAP_HEAP);
		cmd.add("-cp");
		cmd.add(tools.toString());
		cmd.add(REMAP_MAIN);
		cmd.add(input.toString());
		cmd.add(output.toString());
		cmd.add(intermediary.toString());
		cmd.add(mojmap.toString());
		cmd.add(tsrg.toString());
		for (Path cp : classpath) cmd.add(cp.toString());

		log.accept("[remap] " + input.getFileName() + " (SRG) → " + output.getFileName() + " (intermediary) …");
		new ForgeTool(log).runProcess(cmd, "game-jar remap");

		if (!Files.isRegularFile(output) || Files.size(output) == 0) {
			throw new IOException("the remap did not produce " + output);
		}
		long size = Files.size(output);
		log.accept("[remap] wrote " + output.getFileName() + " (" + (size / (1024 * 1024)) + " MB)");
		BuildStamp.write(output);
		return new ArtifactResult(coordinate, output, Util.sha1(output), size);
	}

	private Path unpackTools() throws IOException {
		Files.createDirectories(toolsDir);
		Path tools = toolsDir.resolve("forbric-merge-tools.jar");
		try (InputStream in = GameJarRemapper.class.getResourceAsStream(TOOLS_RESOURCE)) {
			if (in == null) {
				throw new IOException("the build tools are missing from the installer jar (" + TOOLS_RESOURCE
						+ "); the installer was built without them");
			}
			Files.copy(in, tools, StandardCopyOption.REPLACE_EXISTING);
		}
		return tools;
	}
}
