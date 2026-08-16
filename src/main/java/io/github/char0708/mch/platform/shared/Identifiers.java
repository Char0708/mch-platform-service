package io.github.char0708.mch.platform.shared;

import java.util.UUID;

public final class Identifiers {

	private Identifiers() {
	}

	public static String newId() {
		return UUID.randomUUID().toString().replace("-", "");
	}
}
