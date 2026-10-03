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

package net.forbric.kernel.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import cpw.mods.jarhandling.SecureJar;
import net.forbric.kernel.discovery.ModFileScanner;
import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.forgespi.language.IModLanguageProvider;
import net.minecraftforge.forgespi.language.ModFileScanData;
import net.minecraftforge.forgespi.locating.IModFile;
import net.minecraftforge.forgespi.locating.IModProvider;

/**
 * The {@code IModFile} behind a kernel-constructed mod, ported to the 1.20.1 FML SPI.
 *
 * <p>The 26.2 carrier's {@code IModFile} carried a {@code JarContents}/{@code getContents()} member that 1.20.1
 * does not have; 1.20.1 instead exposes the {@code SecureJar}. This class answers the 1.20.1 surface: the mod's
 * real jar when it has one, a placeholder when it does not.
 */
public final class KernelModFile implements IModFile {
	private final String modId;
	private final Path path;
	private final Path jar;

	/** The info that owns this file. Set by {@link KernelModFileInfo}'s constructor. */
	private IModFileInfo modFileInfo;

	/** Memoised: walking a hundred jars for nobody is pure boot cost. */
	private ModFileScanData scanResult;

	public KernelModFile(String modId, Path jar) {
		this.modId = modId;
		this.jar = jar;
		this.path = jar != null ? jar : Path.of("forbric-kernel", modId + ".jar");
	}

	@Override
	public Path getFilePath() {
		return path;
	}

	@Override
	public SecureJar getSecureJar() {
		try {
			return jar == null ? null : SecureJar.from(jar);
		} catch (Throwable unavailable) {
			return null;
		}
	}

	@Override
	public void setSecurityStatus(SecureJar.Status status) {
		// The kernel does not run Forge's secure-jar verification; nothing to record.
	}

	@Override
	public synchronized ModFileScanData getScanResult() {
		if (scanResult == null) {
			Object real = jar == null ? null : ModFileScanner.scanShared(jar, getClass().getClassLoader());
			scanResult = real instanceof ModFileScanData data ? data : new ModFileScanData();
		}
		return scanResult;
	}

	/** The jar's file name, or the placeholder path's for a mod that has no jar. Never null. */
	@Override
	public String getFileName() {
		Path name = path.getFileName();
		return name == null ? modId + ".jar" : name.toString();
	}

	@Override
	public IModFile.Type getType() {
		return IModFile.Type.MOD;
	}

	@Override
	public IModFileInfo getModFileInfo() {
		return modFileInfo;
	}

	/** Called once by {@link KernelModFileInfo}'s constructor. */
	void setModFileInfo(IModFileInfo info) {
		this.modFileInfo = info;
	}

	@Override
	public List<IModInfo> getModInfos() {
		return List.of();
	}

	@Override
	public List<IModLanguageProvider> getLoaders() {
		// The kernel constructs its own mod containers; FML's language providers never run.
		return List.of();
	}

	@Override
	public Supplier<Map<String, Object>> getSubstitutionMap() {
		return Map::of;
	}

	@Override
	public Path findResource(String... pathName) {
		if (jar == null) return null;
		Path candidate = jar.getFileSystem().getPath(String.join("/", pathName));
		return candidate;
	}

	/** The kernel does not discover mod files through FML's locators; the provider is a placeholder identity. */
	@Override
	public IModProvider getProvider() {
		return KernelModProvider.INSTANCE;
	}

	@Override
	public String toString() {
		return "KernelModFile[" + modId + "]";
	}

	/** A no-op {@link IModProvider}: the kernel owns discovery, so nothing is ever scanned through this. */
	static final class KernelModProvider implements IModProvider {
		static final IModProvider INSTANCE = new KernelModProvider();

		@Override
		public String name() {
			return "forbric-kernel";
		}

		@Override
		public void scanFile(IModFile file, Consumer<Path> pathConsumer) {
		}

		@Override
		public void initArguments(Map<String, ?> arguments) {
		}

		@Override
		public boolean isValid(IModFile file) {
			return true;
		}
	}
}
