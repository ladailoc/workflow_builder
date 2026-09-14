package com.fpt.workflow.ticket.service;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.domain.EventType;
import com.fpt.workflow.runtime.repository.EventRepository;
import com.fpt.workflow.runtime.subworkflow.service.ChildWorkflowMutationBoundary;
import com.fpt.workflow.shared.api.CommandConflictException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class TicketMutationBoundary implements ChildWorkflowMutationBoundary {

  /**
   * A scope token returned by boundary entry methods. Unlike {@link AutoCloseable}, {@code close()}
   * declares no checked exception, so callers can use try-with-resources without an extra catch.
   */
  @FunctionalInterface
  public interface BoundaryScope extends ChildWorkflowMutationBoundary.BoundaryScope {
    @Override
    void close(); // no checked exception
  }

  public record ChildContext(UUID childEventId, UUID parentTicketId, boolean explicitPermission) {}

  private static final ThreadLocal<ChildContext> CURRENT_CONTEXT = new ThreadLocal<>();

  private final EventRepository eventRepository;

  public TicketMutationBoundary(EventRepository eventRepository) {
    this.eventRepository = Objects.requireNonNull(eventRepository, "eventRepository");
  }

  @Override
  public BoundaryScope enterChildWorkflow(UUID childEventId, UUID parentTicketId) {
    ChildContext previous = CURRENT_CONTEXT.get();
    CURRENT_CONTEXT.set(new ChildContext(childEventId, parentTicketId, false));
    return () -> {
      if (previous != null) {
        CURRENT_CONTEXT.set(previous);
      } else {
        CURRENT_CONTEXT.remove();
      }
    };
  }

  public BoundaryScope openExplicitPermissionScope() {
    ChildContext current = CURRENT_CONTEXT.get();
    if (current == null) {
      return () -> {};
    }
    CURRENT_CONTEXT.set(new ChildContext(current.childEventId(), current.parentTicketId(), true));
    return () -> CURRENT_CONTEXT.set(current);
  }

  public boolean isChildWorkflowContext() {
    ChildContext ctx = CURRENT_CONTEXT.get();
    return ctx != null && !ctx.explicitPermission();
  }

  public void requireCanMutateTicket(UUID targetTicketId) {
    ChildContext ctx = CURRENT_CONTEXT.get();
    if (ctx != null && !ctx.explicitPermission()) {
      if (ctx.parentTicketId() != null && ctx.parentTicketId().equals(targetTicketId)) {
        throw new CommandConflictException(
            "CHILD_WORKFLOW_TICKET_MUTATION_FORBIDDEN",
            "Child workflow cannot mutate parent ticket directly; mutations must be explicitly authorized");
      }
    }
  }


  public void requireCanMutateTicket(UUID targetTicketId, UUID callingEventId) {
    requireCanMutateTicket(targetTicketId);
    if (callingEventId != null) {
      Event callingEvent = eventRepository.findById(callingEventId).orElse(null);
      if (callingEvent != null
          && (callingEvent.getEventType() == EventType.CHILD
              || callingEvent.getParentEventId() != null)) {
        if (targetTicketId.equals(callingEvent.getTicketId())) {
          ChildContext ctx = CURRENT_CONTEXT.get();
          if (ctx != null && ctx.explicitPermission()) {
            return;
          }
          throw new CommandConflictException(
              "CHILD_WORKFLOW_TICKET_MUTATION_FORBIDDEN",
              "Child event "
                  + callingEventId
                  + " cannot mutate parent ticket "
                  + targetTicketId
                  + " directly; mutations must be explicitly authorized");
        }
      }
    }
  }
}
