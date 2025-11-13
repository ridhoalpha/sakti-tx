package id.go.kemenkeu.djpbn.sakti.starter;

import java.lang.annotation.*;

@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SaktiTransactional {
    String lockKey();
    String idempotencyKey() default "";
    long waitMillis() default -1; // use default if -1
    long leaseMillis() default -1;
}
