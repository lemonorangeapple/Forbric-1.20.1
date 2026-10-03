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
import java.util.Properties;

/**
 * The upstream versions this installer builds against.
 *
 * <p>There is exactly one place these are declared: the repository-root
 * {@code VERSIONS.properties}. {@code bundleVersions} copies it into this jar as
 * {@code /forbric-versions.properties} at build time, and this class reads that resource. So the
 * installer cannot be built against one version and describe another, and a version bump is a change
 * to one file rather than to this class.
 *
 * <p>There are deliberately no compiled-in defaults: a missing resource or key fails loudly, because
 * a quiet fallback would be a second source of truth wearing a hard-coded hat.
 */
final class Pins {

	private static final String RESOURCE = "/forbric-versions.properties";

	private static final Properties VERSIONS = load();

	private Pins() {
	}

	private static Properties load() {
		Properties properties = new Properties();
		try (InputStream in = Pins.class.getResourceAsStream(RESOURCE)) {
			if (in == null) {
				throw new IllegalStateException("Pins: " + RESOURCE + " is missing from the installer jar; "
						+ "VERSIONS.properties did not reach the build resources (see bundleVersions)");
			}
			properties.load(in);
		} catch (IOException error) {
			throw new IllegalStateException("Pins: could not read " + RESOURCE, error);
		}
		return properties;
	}

	private static String version(String key) {
		String value = VERSIONS.getProperty("forbric." + key);
		if (value == null || value.trim().isEmpty()) {
			throw new IllegalStateException("VERSIONS.properties is missing forbric." + key);
		}
		return value.trim();
	}

	/** The only Minecraft version this generation supports. */
	static final String MINECRAFT = version("minecraft.version");

	/** MinecraftForge, in its own {@code <mc>-<fml>} coordinate form. */
	static final String FORGE = version("forge.version");

	/** The launcher profile id the installer writes ({@code versions/<profile>/<profile>.json}). */
	static final String PROFILE = version("profile.name");

	/**
	 * jline for the dedicated-server console. 1.20.1's version JSON does not list it, so the installer fetches it
	 * itself — the same jars the dev runner adds by hand.
	 */
	static final String JLINE = version("console.jline.version");

	/**
	 * The canonical runtime namespace of the merged game.
	 *
	 * <p>On an obfuscated version this is Fabric's {@code intermediary}: Fabric mods run unmodified and
	 * every Forge (SRG) artifact -- carrier and guest mods -- is remapped onto it.
	 */
	static final String RUNTIME_NAMESPACE = version("runtime.namespace");

	/**
	 * The Fabric intermediary mapping URL for {@link #MINECRAFT}, with the {@code {version}} placeholder the
	 * descriptor carries already substituted. Intermediary is the runtime namespace the base is remapped onto.
	 */
	static final String INTERMEDIARY_URL = version("intermediary.url").replace("{version}", MINECRAFT);

	/** The tsrg entry inside MCPConfig that carries Forge's SRG member names. */
	static final String SRG_ENTRY = version("forge.srg.entry");

	/** A one-line summary for the build stamp, so a cached artifact records what produced it. */
	static String stamp() {
		return "mc=" + MINECRAFT + " forge=" + FORGE + " namespace=" + RUNTIME_NAMESPACE;
	}
}
