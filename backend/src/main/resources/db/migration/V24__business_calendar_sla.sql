CREATE TABLE business_calendars (
 id UUID PRIMARY KEY, key VARCHAR(128) NOT NULL UNIQUE, name VARCHAR(255) NOT NULL,
 timezone VARCHAR(64) NOT NULL, status VARCHAR(16) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL, lock_version BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT ck_business_calendar_status CHECK(status IN ('ACTIVE','INACTIVE'))
);
CREATE TABLE business_calendar_hours (
 id UUID PRIMARY KEY, calendar_id UUID NOT NULL REFERENCES business_calendars(id) ON DELETE RESTRICT,
 day_of_week INTEGER NOT NULL, start_time TIME NOT NULL, end_time TIME NOT NULL,
 CONSTRAINT ck_calendar_day CHECK(day_of_week BETWEEN 1 AND 7),
 CONSTRAINT ck_calendar_window CHECK(start_time < end_time),
 CONSTRAINT uq_calendar_window UNIQUE(calendar_id,day_of_week,start_time,end_time)
);
CREATE TABLE business_calendar_holidays (
 id UUID PRIMARY KEY, calendar_id UUID NOT NULL REFERENCES business_calendars(id) ON DELETE RESTRICT,
 holiday_date DATE NOT NULL, name VARCHAR(255) NOT NULL, working_override BOOLEAN NOT NULL DEFAULT FALSE,
 CONSTRAINT uq_calendar_holiday UNIQUE(calendar_id,holiday_date)
);
CREATE TABLE sla_executions (
 id UUID PRIMARY KEY, event_id UUID NOT NULL REFERENCES events(id) ON DELETE RESTRICT,
 node_execution_id UUID REFERENCES node_executions(id) ON DELETE RESTRICT,
 task_id UUID REFERENCES task_executions(id) ON DELETE RESTRICT,
 business_calendar_id UUID REFERENCES business_calendars(id) ON DELETE RESTRICT,
 config_snapshot_json JSONB NOT NULL, status VARCHAR(32) NOT NULL,
 started_at TIMESTAMPTZ NOT NULL, due_at TIMESTAMPTZ NOT NULL, breached_at TIMESTAMPTZ,
 completed_at TIMESTAMPTZ, next_action_at TIMESTAMPTZ, lock_version BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT ck_sla_owner CHECK(node_execution_id IS NOT NULL OR task_id IS NOT NULL),
 CONSTRAINT ck_sla_status CHECK(status IN ('ACTIVE','BREACHED','COMPLETED','CANCELLED')),
 CONSTRAINT ck_sla_due CHECK(due_at >= started_at)
);
CREATE INDEX ix_sla_due ON sla_executions(status,next_action_at,due_at);
