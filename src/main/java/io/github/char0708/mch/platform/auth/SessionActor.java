package io.github.char0708.mch.platform.auth;

public record SessionActor(String accountId, AccountRole role, String profileStatus) {

	public static final String REQUEST_ATTRIBUTE = SessionActor.class.getName();
}
