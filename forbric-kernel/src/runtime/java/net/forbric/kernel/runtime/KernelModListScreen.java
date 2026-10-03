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

import java.util.List;
import java.util.Locale;

import net.forbric.api.Ecosystem;
import net.forbric.api.ModCatalog;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The Forbric mods list. The 26.2 original was built on that version's {@code GuiGraphicsExtractor} screen
 * pipeline, which 1.20.1 does not have; this is the same screen on 1.20.1's {@code GuiGraphics}/widget API — the
 * one list of everything the kernel loaded, with its ecosystem and status.
 *
 * <p>{@code ModsButtonRedirector} re-points the pause menu's Mods button here, and {@code ModCatalog} is the one
 * list the player sees, so this stays a real screen rather than a placeholder.
 */
public final class KernelModListScreen extends Screen {
	private static volatile int framesDrawn;
	private static volatile int rowsBuilt;

	private final Screen parent;

	public KernelModListScreen(Screen parent) {
		super(Component.literal("Forbric Mods"));
		this.parent = parent;
	}

	public static KernelModListScreen create(Screen parent) {
		return new KernelModListScreen(parent);
	}

	public static int framesDrawn() {
		return framesDrawn;
	}

	public static int rowsBuilt() {
		return rowsBuilt;
	}

	@Override
	protected void init() {
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
				.bounds(this.width / 2 - 100, this.height - 26, 200, 20).build());
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		this.renderBackground(graphics);
		framesDrawn++;
		List<ModCatalog.Entry> entries = ModCatalog.all();
		rowsBuilt = entries.size();
		graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);
		int y = 30;
		for (ModCatalog.Entry entry : entries) {
			if (y > this.height - 40) break;
			String line = entry.name() + "  [" + label(entry.ecosystem()) + "]  " + entry.version()
					+ "  " + entry.status();
			graphics.drawString(this.font, line, 12, y, 0xFFFFFF);
			y += 12;
		}
		super.render(graphics, mouseX, mouseY, partialTick);
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) this.minecraft.setScreen(this.parent);
	}

	private static String label(Ecosystem ecosystem) {
		return ecosystem == null ? "?" : ecosystem.name().toLowerCase(Locale.ROOT);
	}
}
