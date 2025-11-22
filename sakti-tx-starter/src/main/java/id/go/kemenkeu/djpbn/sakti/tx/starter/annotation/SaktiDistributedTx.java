package id.go.kemenkeu.djpbn.sakti.tx.starter.annotation;

import id.go.kemenkeu.djpbn.sakti.tx.starter.config.SaktiTxProperties;

import java.lang.annotation.*;

@Target({ ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SaktiDistributedTx {
    String lockKey() default "";

    SaktiTxProperties.MultiDb.RollbackStrategy rollbackStrategy() default SaktiTxProperties.MultiDb.RollbackStrategy.COMPENSATING;
}