package com.duma.smartaudits.review;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

class ApprovalRequestValidationTest {
  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void rejectsInvalidCompositeKeyAndOversizedNotesBeforeRepositoryExecution() {
    var request =
        new ReviewQueueController.ApprovalRequest(
            "not-a-sha256", 1, PromotableCategory.INCUMPLIMIENTO_GENERAL, "x".repeat(1001));

    assertThat(validator.validate(request))
        .extracting(violation -> violation.getPropertyPath().toString())
        .containsExactlyInAnyOrder("normalizedCommentHash", "aiResult", "reviewNotes");
  }

  @Test
  void acceptsOnlyTheTypedPromotableCategoryContract() {
    var request =
        new ReviewQueueController.ApprovalRequest(
            "a".repeat(64), 0, PromotableCategory.FUERA_DE_RANGO, null);

    assertThat(validator.validate(request)).isEmpty();
    assertThat(PromotableCategory.values())
        .extracting(Enum::name)
        .containsExactly(
            "IMAGEN_NO_PROCESABLE",
            "IMAGEN_NO_LEGIBLE",
            "FUERA_DE_RANGO",
            "INCUMPLIMIENTO_LIMPIEZA",
            "INCUMPLIMIENTO_GENERAL");
  }
}
