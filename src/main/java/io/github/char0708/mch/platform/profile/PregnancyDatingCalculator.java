package io.github.char0708.mch.platform.profile;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

final class PregnancyDatingCalculator {

	static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Hong_Kong");
	static final String CLINICIAN_SOURCE = "USER_REPORTED_CLINICIAN_ESTIMATE";
	static final String LMP_SOURCE = "LMP_280_DAY_ESTIMATE";

	private PregnancyDatingCalculator() {
	}

	static DatingResult calculate(LocalDate referenceDate, LocalDate lastMenstrualPeriodDate,
			LocalDate clinicianEstimatedDueDate) {
		LocalDate estimatedDueDate = clinicianEstimatedDueDate != null
				? clinicianEstimatedDueDate
				: lastMenstrualPeriodDate.plusDays(280);
		String source = clinicianEstimatedDueDate != null ? CLINICIAN_SOURCE : LMP_SOURCE;
		long totalDays = 280L - ChronoUnit.DAYS.between(referenceDate, estimatedDueDate);
		int weeks = Math.floorDiv(Math.toIntExact(totalDays), 7);
		int days = Math.floorMod(Math.toIntExact(totalDays), 7);
		return new DatingResult(estimatedDueDate, source, referenceDate, weeks, days,
				weeks + "周+" + days + "天");
	}

	record DatingResult(LocalDate estimatedDueDate, String source, LocalDate asOfDate,
			int weeks, int days, String display) {

		boolean isInsideR1Slice() {
			return weeks == 20 && days >= 0 && days <= 6;
		}
	}
}
