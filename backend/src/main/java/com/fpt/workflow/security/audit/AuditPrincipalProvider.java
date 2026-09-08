package com.fpt.workflow.security.audit;

import com.fpt.workflow.security.ActorContext;
import com.fpt.workflow.security.ActorContextProvider;
import org.springframework.stereotype.Component;

@Component
public final class AuditPrincipalProvider {

  private final ActorContextProvider actorContextProvider;

  public AuditPrincipalProvider(ActorContextProvider actorContextProvider) {
    this.actorContextProvider = actorContextProvider;
  }

  public AuditPrincipal requireCurrent() {
    ActorContext actor = actorContextProvider.requireActor();
    return new AuditPrincipal(actor.actorId(), actor.principalName(), actor.roles());
  }
}
