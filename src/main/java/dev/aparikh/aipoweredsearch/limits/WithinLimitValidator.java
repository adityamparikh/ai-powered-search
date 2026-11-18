package dev.aparikh.aipoweredsearch.limits;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Checks a value against the {@link InputLimit} named by {@link WithinLimit}.
 *
 * <p>Spring creates constraint validators through the application context, so the current {@link
 * InputLimits} is injected rather than fixed at compile time. This also means the validator works
 * only where Spring builds the {@code Validator}; a plain {@code
 * Validation.buildDefaultValidatorFactory()} cannot instantiate it.
 */
final class WithinLimitValidator implements ConstraintValidator<WithinLimit, Object> {

  private final InputLimits limits;
  private @Nullable InputLimit limit;

  WithinLimitValidator(InputLimits limits) {
    this.limits = limits;
  }

  @Override
  public void initialize(WithinLimit annotation) {
    this.limit = annotation.value();
  }

  @Override
  public boolean isValid(@Nullable Object value, ConstraintValidatorContext context) {
    if (value == null) {
      return true;
    }
    InputLimit checked = Objects.requireNonNull(limit, "initialize() was not called");
    if (checked.measure(value) <= checked.max(limits)) {
      return true;
    }
    // The template holds only the limit's own text, never the rejected value, so nothing a client
    // sends is interpolated.
    context.disableDefaultConstraintViolation();
    context.buildConstraintViolationWithTemplate(checked.describe(limits)).addConstraintViolation();
    return false;
  }
}
