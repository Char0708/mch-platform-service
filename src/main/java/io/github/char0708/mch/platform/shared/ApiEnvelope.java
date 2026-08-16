package io.github.char0708.mch.platform.shared;

public record ApiEnvelope<T>(String requestId, T data) {
}
