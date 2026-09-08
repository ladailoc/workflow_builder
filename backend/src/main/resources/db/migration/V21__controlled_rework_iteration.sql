ALTER TABLE activation_tokens
    ADD COLUMN iteration INTEGER NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_activation_tokens_iteration_nonnegative CHECK (iteration >= 0);

ALTER TABLE activation_tokens ALTER COLUMN iteration DROP DEFAULT;
