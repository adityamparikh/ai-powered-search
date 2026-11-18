package dev.aparikh.aipoweredsearch.limits;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * The annotated value must be within a configured {@link InputLimit}.
 *
 * <p>{@code @Max} and {@code @Size} take compile-time constants, so they cannot read {@code
 * search.limits.*}. This constraint names the limit instead, and {@link WithinLimitValidator} reads
 * its current value from {@link InputLimits}. What is measured depends on the type: the length of a
 * {@link CharSequence}, the size of a {@link java.util.Collection}, or the value of a {@link
 * Number}. {@code null} is valid; whether a value is required is a separate concern.
 *
 * <p>On a controller {@code @RequestParam}, Spring MVC's built-in method validation applies it; on
 * a request-body record component, {@code @Valid} on the body does.
 */
@Documented
@Target({FIELD, PARAMETER})
@Retention(RUNTIME)
@Constraint(validatedBy = WithinLimitValidator.class)
public @interface WithinLimit {

  /**
   * The limit to enforce.
   *
   * @return the limit
   */
  InputLimit value();

  /**
   * The message when no limit-specific one is built.
   *
   * @return the message template
   */
  String message() default "exceeds a configured input limit";

  /**
   * Validation groups.
   *
   * @return the groups
   */
  Class<?>[] groups() default {};

  /**
   * Validation payload.
   *
   * @return the payload
   */
  Class<? extends Payload>[] payload() default {};
}
