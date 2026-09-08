package com.fpt.workflow.shared.transaction;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.transaction.annotation.Transactional;

/**
 * Short transaction for a critical state transition. A command must never wait for a human or
 * perform a network call while this transaction is open.
 */
@Documented
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Transactional(
    rollbackFor = Exception.class,
    timeoutString = "${platform.transaction.command-timeout-seconds:10}")
public @interface TransactionalCommand {}
