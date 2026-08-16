package io.github.char0708.mch.platform.shared;

import jakarta.servlet.http.HttpServletRequest;

public final class RequestIds {

	public static final String ATTRIBUTE = RequestIds.class.getName() + ".requestId";

	private RequestIds() {
	}

	public static String current(HttpServletRequest request) {
		Object value = request.getAttribute(ATTRIBUTE);
		return value instanceof String requestId ? requestId : Identifiers.newId();
	}
}
