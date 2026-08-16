package io.github.char0708.mch.platform.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class PregnancyDatingCalculatorTests {

	private static final LocalDate REFERENCE_DATE = LocalDate.of(2026, 8, 11);

	@Test
	void lmpFallbackPreservesBothAdmittedBoundariesAndPlusSign() {
		PregnancyDatingCalculator.DatingResult firstDay = PregnancyDatingCalculator.calculate(
				REFERENCE_DATE, REFERENCE_DATE.minusDays(140), null);
		PregnancyDatingCalculator.DatingResult lastDay = PregnancyDatingCalculator.calculate(
				REFERENCE_DATE, REFERENCE_DATE.minusDays(146), null);

		assertThat(firstDay.source()).isEqualTo(PregnancyDatingCalculator.LMP_SOURCE);
		assertThat(firstDay.estimatedDueDate()).isEqualTo(REFERENCE_DATE.minusDays(140).plusDays(280));
		assertThat(firstDay.display()).isEqualTo("20周+0天");
		assertThat(firstDay.isInsideR1Slice()).isTrue();
		assertThat(lastDay.display()).isEqualTo("20周+6天");
		assertThat(lastDay.isInsideR1Slice()).isTrue();
	}

	@Test
	void userReportedClinicianDueDateTakesPriorityOverLmp() {
		LocalDate clinicianDueDate = REFERENCE_DATE.plusDays(137);
		PregnancyDatingCalculator.DatingResult result = PregnancyDatingCalculator.calculate(
				REFERENCE_DATE, REFERENCE_DATE.minusDays(10), clinicianDueDate);

		assertThat(result.estimatedDueDate()).isEqualTo(clinicianDueDate);
		assertThat(result.source()).isEqualTo(PregnancyDatingCalculator.CLINICIAN_SOURCE);
		assertThat(result.display()).isEqualTo("20周+3天");
	}

	@Test
	void adjacentDaysRemainOutsideTheR1Slice() {
		assertThat(PregnancyDatingCalculator.calculate(
				REFERENCE_DATE, REFERENCE_DATE.minusDays(139), null).isInsideR1Slice()).isFalse();
		assertThat(PregnancyDatingCalculator.calculate(
				REFERENCE_DATE, REFERENCE_DATE.minusDays(147), null).isInsideR1Slice()).isFalse();
	}
}
