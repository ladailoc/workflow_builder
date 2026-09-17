import { describe, expect, it } from "vitest";
import { suggestAutomaticMappings } from "./auto-mapping";

describe("suggestAutomaticMappings", () => {
  it("prioritizes exact keys and keeps mappings one-to-one", () => {
    const mappings = suggestAutomaticMappings(
      [
        { id: "target-title", key: "title", type: "STRING", ordinal: 0 },
        { id: "target-amount", key: "amount", type: "NUMBER", ordinal: 1 },
      ],
      [
        { id: "field-title", key: "title", label: "Subject", type: "STRING" },
        { id: "field-amount", key: "amount", label: "Amount", type: "DECIMAL" },
        { id: "field-other", key: "other", label: "Other", type: "STRING" },
      ],
    );

    expect(mappings).toEqual([
      {
        targetId: "target-title",
        sourceId: "field-title",
        reason: "KEY",
        score: 110,
      },
      {
        targetId: "target-amount",
        sourceId: "field-amount",
        reason: "KEY",
        score: 110,
      },
    ]);
  });

  it("uses type only when there is one compatible source", () => {
    expect(
      suggestAutomaticMappings(
        [{ id: "target", key: "workflow_value", type: "BOOLEAN" }],
        [{ id: "field", key: "enabled", label: "Enabled", type: "BOOLEAN" }],
      ),
    ).toMatchObject([
      { targetId: "target", sourceId: "field", reason: "TYPE" },
    ]);
  });

  it("leaves ambiguous type-only candidates for manual mapping", () => {
    expect(
      suggestAutomaticMappings(
        [{ id: "target", key: "workflow_value", type: "STRING" }],
        [
          { id: "field-a", key: "first", type: "STRING" },
          { id: "field-b", key: "second", type: "STRING" },
        ],
      ),
    ).toEqual([]);
  });

  it("keeps a later exact match from being consumed by an earlier weak match", () => {
    expect(
      suggestAutomaticMappings(
        [
          {
            id: "target-requester",
            key: "requester",
            type: "STRING",
            ordinal: 0,
          },
          { id: "target-id", key: "id", type: "STRING", ordinal: 1 },
        ],
        [
          { id: "field-id", key: "id", type: "STRING", ordinal: 0 },
          {
            id: "field-requester",
            key: "requester_name",
            type: "STRING",
            ordinal: 1,
          },
        ],
      ),
    ).toEqual([
      {
        targetId: "target-requester",
        sourceId: "field-requester",
        reason: "LABEL",
        score: 75,
      },
      {
        targetId: "target-id",
        sourceId: "field-id",
        reason: "KEY",
        score: 110,
      },
    ]);
  });

  it("does not match incompatible fields only because their names are equal", () => {
    expect(
      suggestAutomaticMappings(
        [{ id: "target", key: "amount", type: "NUMBER" }],
        [{ id: "field", key: "amount", type: "STRING" }],
      ),
    ).toEqual([]);
  });
});
