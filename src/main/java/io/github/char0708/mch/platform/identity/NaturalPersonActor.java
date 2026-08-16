package io.github.char0708.mch.platform.identity;

public record NaturalPersonActor(String personId, String profileStatus) {

	public static final String PRINCIPAL_TYPE = "NATURAL_PERSON";
	public static final String REQUEST_ATTRIBUTE = NaturalPersonActor.class.getName();
}
