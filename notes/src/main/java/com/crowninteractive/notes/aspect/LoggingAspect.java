package com.crowninteractive.notes.aspect;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Aspect
@Component
@Slf4j
public class LoggingAspect {
    // execution: run at execution
    // The first * means match any return type
    // The second * means match any class in the package
    // The third * means match any method in the class
    // The (..) means match any number of arguments

    // Defines the pointcut: where the aspect would intervein
    @Pointcut("execution(* com.crowninteractive.notes.controllers.*.*(..))")
    public void controllerMethods() {}

    @Before("controllerMethods()")
    public void logBefore(JoinPoint joinPoint) {
        log.info("Called controller method: {}",  joinPoint.getSignature().getName());
        log.info("Arguments: {}", Arrays.toString(joinPoint.getArgs()));
    }

    @AfterReturning(pointcut = "controllerMethods()", returning = "result")
    public void logAfterReturning(JoinPoint joinPoint, Object result) {
        log.info("Controller method {} returned: {}",  joinPoint.getSignature().getName(), result);
    }

    @AfterThrowing(pointcut = "controllerMethods()", throwing = "exception")
    public void logException(JoinPoint joinPoint, Throwable exception) {
        log.error("Exception in method {}: {}", joinPoint.getSignature().getName(), exception.getMessage());
    }
}
