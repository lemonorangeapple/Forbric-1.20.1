/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.installer.kernel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/** Reuses the installer artifact pipeline without installing a player profile or release payload. */
public final class DevPrepare {

	public static void main(String[] args) throws Exception {
		if (args.length != 3) throw new IllegalArgumentException("usage: DevPrepare <mc-dir> <staged-run-dir> <java>");
		Path mc = Path.of(args[0]).toAbsolutePath();
		Path stage = Path.of(args[1]).toAbsolutePath();
		JdkLocator.Jvm jvm = JdkLocator.locate(mc, Path.of(args[2]), System.out::println);
		if (jvm.feature() < 17) throw new IOException("Minecraft 1.20.1 development needs JDK 17 or newer");
		Path version = mc.resolve("versions").resolve(Pins.MINECRAFT);
		if (!Files.isRegularFile(version.resolve(Pins.MINECRAFT + ".jar"))
				|| !Files.isRegularFile(version.resolve(Pins.MINECRAFT + ".json"))) {
			new MojangDownloader(System.out::println).downloadClient(Pins.MINECRAFT, version);
		}
		Map<String, Path> artifacts = new ArtifactBuilder(System.out::println).build(mc, Pins.MINECRAFT, jvm);
		copy(artifacts.get(ArtifactBuilder.MERGED),
				stage.resolve("merged-base/patched-mc-merged-" + Pins.MINECRAFT + ".jar"));
		copy(artifacts.get(ArtifactBuilder.FORGE_RUNTIME), stage.resolve("forge-runtime/forge-runtime.jar"));
		// The Forge-patched jar the base is built from: the bytecode tests compare against it.
		copy(mc.resolve(".forbric-build/out/patched-mc-forge-" + Pins.MINECRAFT + ".jar"),
				stage.resolve("forge-patched/patched-mc-forge-" + Pins.MINECRAFT + ".jar"));
		System.out.println("Development artifacts staged under " + stage);
	}

	private static void copy(Path source, Path target) throws IOException {
		Files.createDirectories(target.getParent());
		if (!Files.isRegularFile(target) || Files.mismatch(source, target) != -1) {
			Path temporary = target.resolveSibling(target.getFileName() + ".part");
			Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
			Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
