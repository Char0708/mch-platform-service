package io.github.char0708.mch.platform.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

@Component
public class SyntheticTokenCodec {

	private static final String PREFIX = "syn";
	private final byte[] runtimeKey = new byte[32];

	SyntheticTokenCodec() {
		new SecureRandom().nextBytes(runtimeKey);
	}

	public String issue(String sessionId) {
		return PREFIX + "." + sessionId + "." + sign(sessionId);
	}

	public Optional<String> verifyAndExtract(String token) {
		if (token == null) {
			return Optional.empty();
		}
		String[] parts = token.split("\\.", -1);
		if (parts.length != 3 || !PREFIX.equals(parts[0]) || !parts[1].matches("[0-9a-f]{32}")) {
			return Optional.empty();
		}
		byte[] expected = sign(parts[1]).getBytes(StandardCharsets.US_ASCII);
		byte[] actual = parts[2].getBytes(StandardCharsets.US_ASCII);
		return MessageDigest.isEqual(expected, actual) ? Optional.of(parts[1]) : Optional.empty();
	}

	private String sign(String sessionId) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(runtimeKey, "HmacSHA256"));
			return Base64.getUrlEncoder().withoutPadding()
					.encodeToString(mac.doFinal(sessionId.getBytes(StandardCharsets.US_ASCII)));
		}
		catch (GeneralSecurityException exception) {
			throw new IllegalStateException("Synthetic session signing is unavailable", exception);
		}
	}
}
