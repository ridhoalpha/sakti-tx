package id.go.kemenkeu.djpbn.sakti.tx.starter.annotation;

import java.lang.annotation.*;

/**
 * Skip automatic tracking for specific repository methods
 * Use case: readonly operations, audit logs, etc.
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SkipTracking {
    String reason() default "";
}