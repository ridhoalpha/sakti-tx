package id.go.kemenkeu.djpbn.sakti.tx.starter.util;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

/**
 * Utility for evaluating SpEL expressions in AOP aspects
 */
public class SpelExpressionEvaluator {
    
    private static final ExpressionParser PARSER = new SpelExpressionParser();
    
    public static String evaluate(String expression, ProceedingJoinPoint pjp, String fallback) {
        if (expression == null || expression.trim().isEmpty()) {
            return fallback;
        }
        
        try {
            StandardEvaluationContext context = createContext(pjp);
            Object value = PARSER.parseExpression(expression).getValue(context);
            return value != null ? value.toString() : fallback;
        } catch (Exception e) {
            return expression; // Use as literal if evaluation fails
        }
    }
    
    private static StandardEvaluationContext createContext(ProceedingJoinPoint pjp) {
        StandardEvaluationContext context = new StandardEvaluationContext();
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Object[] args = pjp.getArgs();
        String[] paramNames = signature.getParameterNames();
        
        context.setVariable("args", args);
        for (int i = 0; i < args.length; i++) {
            context.setVariable("a" + i, args[i]);
            if (paramNames != null && i < paramNames.length) {
                context.setVariable(paramNames[i], args[i]);
            }
        }
        
        return context;
    }
}