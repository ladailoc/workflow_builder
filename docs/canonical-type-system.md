# Canonical type system

The platform has one value type system in `shared.domain.value`. Forms, variables, input bindings,
expressions, participant resolvers, and connector mappings must depend on this package rather than
define feature-local value enums.

## Descriptor representation

A scalar descriptor is serialized as:

```json
{"type":"STRING","nullable":false}
```

Collections are recursive and always declare their item contract:

```json
{
  "type": "ARRAY",
  "nullable": true,
  "itemType": {"type": "USER_ID", "nullable": false}
}
```

`FILE_LIST` is the named file collection and has a fixed non-nullable `FILE_REF` item type. Raw
`ARRAY` and feature aliases such as `USER_LIST` are not canonical types; use `ARRAY<USER>` or
`ARRAY<USER_ID>` explicitly.

## Compatibility rules

- Exact types are assignable when source nullability is no broader than target nullability.
- A non-nullable value can be assigned to a nullable target; the reverse is rejected.
- `INTEGER` widens to `NUMBER`; narrowing `NUMBER` to `INTEGER` is rejected.
- `ARRAY<S>` is assignable to `ARRAY<T>` when `S` is assignable to `T`.
- `FILE_LIST` and `ARRAY<FILE_REF>` are structurally compatible.
- Identity strings and reference objects are intentionally different: `USER_ID` is not `USER`.

## Canonical JSON values

| Type | JSON representation |
| --- | --- |
| STRING, ENUM | JSON string |
| NUMBER, INTEGER | JSON number; INTEGER must be integral |
| BOOLEAN | JSON boolean |
| DATE | ISO-8601 local date, for example `2026-09-07` |
| DATETIME | UTC ISO-8601 instant, for example `2026-09-07T12:30:00Z` |
| DURATION | ISO-8601 duration, for example `PT45M` |
| MONEY | `{"amount":12.50,"currency":"USD"}` |
| USER_ID, DEPARTMENT_ID, GROUP_ID | UUID string |
| USER, FILE_REF | Object containing a UUID `id` |
| OBJECT | JSON object |
| ARRAY<T>, FILE_LIST | JSON array validated against the item descriptor |

`CanonicalSchema` represents strict or open object contracts using canonical descriptors.
`CanonicalValueValidator` returns stable issue codes and JSON-style paths and is the shared
design-time/runtime validation API.

Flyway V9 stores WorkflowVariable descriptors as constrained JSONB. PostgreSQL recursively validates
the same exact type names, nullability shape, and collection item contract enforced in Java.

