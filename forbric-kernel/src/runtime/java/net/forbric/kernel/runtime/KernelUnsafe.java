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

import java.lang.reflect.Field;

/**
 * The two {@code Unsafe} operations the kernel needs to stand where a genuine loader stands: allocate a class
 * without running a constructor it cannot reach, and write a private/package field Forge keeps to itself.
 *
 * <p>The 26.2 carrier shipped a {@code net.minecraftforge.unsafe.UnsafeHacks} for exactly this; 1.20.1 does not,
 * so the kernel carries its own over {@code sun.misc.Unsafe} (present on every supported JDK).
 */
final class KernelUnsafe {
	private KernelUnsafe() {
	}

	private static final Object UNSAFE = lookup();

	private static Object lookup() {
		try {
			Field field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
			field.setAccessible(true);
			return field.get(null);
		} catch (Throwable unavailable) {
			return null;
		}
	}

	/** Allocates an instance without running any constructor. */
	@SuppressWarnings("unchecked")
	static <T> T newInstance(Class<T> type) {
		try {
			return (T) UNSAFE.getClass().getMethod("allocateInstance", Class.class).invoke(UNSAFE, type);
		} catch (Throwable failed) {
			throw new IllegalStateException("forbric: cannot allocate " + type.getName(), failed);
		}
	}

	/** Writes a field even when it is private and final. */
	static void setField(Field field, Object target, Object value) {
		try {
			field.setAccessible(true);
			long offset = (long) UNSAFE.getClass().getMethod("objectFieldOffset", Field.class).invoke(UNSAFE, field);
			if (value instanceof Integer i) {
				UNSAFE.getClass().getMethod("putInt", Object.class, long.class, int.class).invoke(UNSAFE, target, offset, i);
			} else if (value instanceof Boolean b) {
				UNSAFE.getClass().getMethod("putBoolean", Object.class, long.class, boolean.class).invoke(UNSAFE, target, offset, b);
			} else {
				UNSAFE.getClass().getMethod("putObject", Object.class, long.class, Object.class).invoke(UNSAFE, target, offset, value);
			}
		} catch (Throwable failed) {
			throw new IllegalStateException("forbric: cannot set " + field, failed);
		}
	}
}
