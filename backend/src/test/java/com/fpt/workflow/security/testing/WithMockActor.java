package com.fpt.workflow.security.testing;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.test.context.support.WithSecurityContext;

@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@WithSecurityContext(factory = WithMockActorSecurityContextFactory.class)
public @interface WithMockActor {

  String actorId() default "10000000-0000-4000-8000-000000000001";

  String principalName() default "actor@example.test";

  String[] roles() default {"USER"};

  String[] permissions() default {};
}
