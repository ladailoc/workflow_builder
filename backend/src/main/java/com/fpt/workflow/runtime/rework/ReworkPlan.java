package com.fpt.workflow.runtime.rework;

import com.fpt.workflow.definition.rework.ReworkExhaustionAction;
import java.util.UUID;

public record ReworkPlan(
    boolean exhausted,
    ReworkExhaustionAction exhaustionAction,
    String exhaustionPort,
    UUID cycleId,
    int iteration,
    String itemToken,
    UUID splitScopeId,
    UUID joinScopeId) {}
