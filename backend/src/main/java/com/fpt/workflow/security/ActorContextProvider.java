package com.fpt.workflow.security;

import java.util.Optional;

public interface ActorContextProvider {

  Optional<ActorContext> currentActor();

  ActorContext requireActor();
}
