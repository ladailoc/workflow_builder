# Security foundation

Authentication is deny-by-default. `/api/v1/**` requires an authenticated actor;
health probes are the only public endpoints in the initial filter chain. The API
is stateless and has no generated development user, form login, or HTTP Basic
fallback. A real identity-provider adapter must authenticate a token and create
an `AuthenticatedActorPrincipal` before protected business APIs can be used.

## Trusted actor identity

`SpringSecurityActorContextProvider` is the only server-side bridge from Spring
Security to application code. It accepts an authenticated
`AuthenticatedActorPrincipal` and derives roles and permissions from granted
authorities. Controllers and services must use `ActorContextProvider`; an
`actorId` supplied in a body, query parameter, path, or header is never an
authorization identity.

Token adapters added later must validate the issuer, audience, signature, expiry,
and stable actor identifier before constructing the principal. This foundation
does not invent an issuer or a development bypass.

## Authorization

Built-in role keys are `USER`, `WORKFLOW_OWNER`, `WORKFLOW_EDITOR`, `OPERATOR`,
and `ADMIN`. `RoleKey` and `PermissionKey` are validated value types instead of
closed enums, so new keys can be introduced without changing the security core.
Spring authorities use `ROLE_` and `PERM_` prefixes.

Service and controller methods can use
`@PreAuthorize("@platformAuthorization.hasRole('ADMIN')")` or the imperative
`requireRole` / `requirePermission` hooks. Detailed workflow visibility and
resource ownership checks are intentionally deferred. Authentication failures
return 401; an authenticated actor lacking authority receives 403. Both use the
shared API problem representation and correlation identifiers.

`AuditPrincipalProvider` snapshots the trusted actor id, principal name, and
roles for future audit records. It does not create audit persistence yet.

## Frontend boundary

`AuthSessionProvider`, `useAuthSession`, and `AuthRouteGuard` provide session and
UX-gating primitives. The provider currently defaults to anonymous until a real
identity integration supplies a validated session. Client-side guards may hide
or reveal UI only; every operation remains authorized by the backend.
