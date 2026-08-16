package io.github.char0708.mch.platform.shared;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

import org.springframework.http.HttpStatus;

public final class BusinessTimes {

	private BusinessTimes() {
	}

	public static Instant parseNotFuture(String raw, String field, Instant receivedAt) {
		if (raw == null) {
			throw invalid(field);
		}
		try {
			Instant value = OffsetDateTime.parse(raw).toInstant();
			if (value.isAfter(receivedAt)) {
				throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "BUSINESS_TIME_IN_FUTURE",
						"业务时间不得晚于平台接收时间。",
						java.util.List.of(new ApiException.FieldError(field, "FUTURE_NOT_ALLOWED")));
			}
			return value;
		}
		catch (DateTimeParseException exception) {
			throw invalid(field);
		}
	}

	private static ApiException invalid(String field) {
		return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "业务时间必须是带时区的 RFC 3339 时间。",
				java.util.List.of(new ApiException.FieldError(field, "RFC3339_OFFSET_REQUIRED")));
	}
}
