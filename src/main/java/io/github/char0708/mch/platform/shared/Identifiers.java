package io.github.char0708.mch.platform.shared;

import java.util.Locale;
import java.util.UUID;

public final class Identifiers {

	private Identifiers() {
	}

	public static String newId() {
		return UUID.randomUUID().toString().replace("-", "");
	}

	public static String userDisplayId(String personId) {
		return displayId("U", personId, 6);
	}

	public static String operationsAccountDisplayId(String accountId) {
		return displayId("OP", accountId, 4);
	}

	private static String displayId(String prefix, String internalId, int suffixLength) {
		if (internalId == null || internalId.length() < suffixLength) {
			throw new IllegalArgumentException("Internal identifier is unavailable for display.");
		}
		return prefix + internalId.substring(internalId.length() - suffixLength).toUpperCase(Locale.ROOT);
	}
}
