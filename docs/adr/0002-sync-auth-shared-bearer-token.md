# Sync Auth via Shared Bearer Token

The backend authenticates `/api/streeter` requests with a static, named bearer token and already has a provisioned `streeter-android` token slot. The client sends no `Authorization` header, so sync only works while the backend runs in `PERMISSIVE` mode; flipping the backend to `STRICT` would break sync.

We close the gap with a shared bearer token sent on every sync request. This is purely a transport gate: it introduces **no User/Account concept** — the walk pool stays global and single-tenant (the server is authoritative only for walk metadata + GPS Trace; see [CONTEXT.md](../../CONTEXT.md)). The token is user-entered in Settings so it can be rotated without a rebuild, stored in a `SyncAuthTokenStore` singleton in `data/` backed by plain app-private `SharedPreferences` (excluded from auto-backup). `NetworkModule` injects the store into the Ktor client and, in the `defaultRequest { }` block, attaches `Authorization: Bearer <token>` when the token is non-blank. A blank token omits the header entirely and lets the server's `PERMISSIVE`/`STRICT` policy decide — no client-side gating.

Rollout keeps prod `PERMISSIVE` until a final manual flip to `STRICT`, so there is no window where sync breaks: ship the client, enter the real token on-device, then flip `INBOXA_AUTH_MODE=STRICT` separately.

## Considered Options

- **Ktor `Auth` / `bearer` plugin.** Rejected: its token caching fights a user-editable static token. Reading the token from the store on every request via `defaultRequest { }` keeps rotation immediate.
- **`EncryptedSharedPreferences`.** Rejected: deprecated, and the gain is marginal for a single shared transport token on an app-private prefs file already excluded from backup.
- **Client-side gating on a blank token** (block requests until a token is set). Rejected: omitting the header and deferring to the server's `PERMISSIVE`/`STRICT` policy keeps the client dumb and avoids a second source of truth for the auth decision.
- **Distinguish 401 from transient failures and surface auth errors to the user.** Deferred to a follow-up issue (sync silently rested in `SYNC_FAILED`); safe because prod stays `PERMISSIVE` until the manual `STRICT` flip. Resolved in [ADR-0004](0004-surface-sync-auth-failures.md) (issue #19).
