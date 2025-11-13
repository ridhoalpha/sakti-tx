package id.go.kemenkeu.djpbn.sakti.starter;

import id.go.kemenkeu.djpbn.sakti.core.IdempotencyManager;
import id.go.kemenkeu.djpbn.sakti.core.SaktiTransactionManager;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.Method;

@Aspect
public class SaktiTransactionalAspect {
    private final SaktiTransactionManager txManager;
    private final IdempotencyManager idempotencyManager;
    private final SaktiProperties props;
    private final ExpressionParser parser = new SpelExpressionParser();

    public SaktiTransactionalAspect(SaktiTransactionManager txManager, IdempotencyManager idempotencyManager, SaktiProperties props) {
        this.txManager = txManager; this.idempotencyManager = idempotencyManager; this.props = props;
    }

    @Around("@annotation(id.go.kemenkeu.djpbn.sakti.starter.SaktiTransactional)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Method method = sig.getMethod();
        SaktiTransactional ann = method.getAnnotation(SaktiTransactional.class);

        StandardEvaluationContext ctx = new StandardEvaluationContext();
        Object[] args = pjp.getArgs(); ctx.setVariable("args", args);
        for (int i = 0; i < args.length; i++) ctx.setVariable("a" + i, args[i]);

        String lockKey = evaluate(ann.lockKey(), ctx, props.getLock().getPrefix() + method.getName());
        String idempKey = ann.idempotencyKey() == null || ann.idempotencyKey().isEmpty() ? null : evaluate(ann.idempotencyKey(), ctx, null);
        long wait = ann.waitMillis() > 0 ? ann.waitMillis() : props.getLock().getWaitMs();
        long lease = ann.leaseMillis() > 0 ? ann.leaseMillis() : props.getLock().getLeaseMs();

        if (idempKey != null && idempotencyManager.checkExists(idempKey)) {
            throw new RuntimeException("Duplicate request (idempotency): " + idempKey);
        }

        return txManager.execute(lockKey, wait, lease, () -> {
            if (idempKey != null) idempotencyManager.markProcessing(idempKey, props.getIdempotency().getTtlSeconds());
            Object res = null;
            try {
                res = pjp.proceed();
                if (idempKey != null) idempotencyManager.markCompleted(idempKey, props.getIdempotency().getTtlSeconds());
                return res;
            } catch (Throwable t) {
                if (idempKey != null) idempotencyManager.rollback(idempKey);
                throw new RuntimeException(t);
            }
        });
    }

    private String evaluate(String expr, StandardEvaluationContext ctx, String fallback) {
        if (expr == null || expr.trim().isEmpty()) return fallback;
        try { return parser.parseExpression(expr).getValue(ctx, String.class); } catch (Exception e) { return expr; }
    }
}
