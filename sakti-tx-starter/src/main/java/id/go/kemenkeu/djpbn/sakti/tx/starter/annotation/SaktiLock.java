package id.go.kemenkeu.djpbn.sakti.tx.starter.annotation;

import java.lang.annotation.*;

@Target({ ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SaktiLock {
    String key();

    long waitTimeMs() default -1;

    long leaseTimeMs() default -1;
}