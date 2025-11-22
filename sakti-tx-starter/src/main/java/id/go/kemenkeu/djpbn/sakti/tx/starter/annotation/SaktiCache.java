package id.go.kemenkeu.djpbn.sakti.tx.starter.annotation;

import java.lang.annotation.*;

@Target({ ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SaktiCache {
    String key();

    long ttlSeconds() default -1;

    Class<?> returnType() default Object.class;
}