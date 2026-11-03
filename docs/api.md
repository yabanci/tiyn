# API reference

Everything the backend exposes today, read from the code (controllers, DTO records, services, domain rules). Staging base URL: `https://api-staging.tiyn.dev`. Module and use-case background lives in `docs/modules.md` and `docs/use-cases.md`; this file is the contract a client builds against.

## Conventions

- **Transport:** JSON over HTTPS. Timestamps are ISO-8601 UTC instants (`2026-11-01T20:58:13.173252Z`). Ids are UUIDs.
- **Auth:** every endpoint except `POST /auth/google` and `GET /actuator/health/**` needs `Authorization: Bearer <token>`. The token is our own JWT (HS256, issuer `tiyn`, `sub` = user id, `role` = `PARENT` or `TEEN`), valid for `JWT_TTL` (default 7 days). There is no refresh or logout endpoint: when it expires, sign in with Google again.
- **Money:** `long` in **minor units** (tiyn): 1 KZT = 100. Currency is always `"KZT"`. Examples: reward `150000` = 1,500 KZT. Defaults: maximum reward or penalty `10000000` (100,000 KZT, `TASK_MAX_REWARD_AMOUNT`), minimum payout `10000` (100 KZT, `LEDGER_MIN_PAYOUT_AMOUNT`).
- **Errors:** every failure is `{"error": "<code>"}` with an HTTP status. Clients switch on the code; the text is never localised. Cross-cutting codes:

| Status | Code | When |
|---|---|---|
| 400 | `invalid_body` | unreadable or malformed JSON |
| 400 | `invalid_request` | missing parameter or part, malformed path value |
| 401 | `unauthenticated` | missing, expired or invalid token |
| 403 | `forbidden` | wrong role, or not your resource |
| 404 | `not_found` | unknown route or resource |
| 405 / 415 | `method_not_allowed` / `unsupported_media_type` | |
| 413 | `payload_too_large` | multipart above 8 MB |
| 422 | `validation_failed` | bean validation failure |
| 500 | `internal_error` | unexpected; retry is safe only for idempotent calls |

- **Idempotency:** `Idempotency-Key` (a non-blank string, at most 100 characters) is **required** on `POST /tasks/{id}/submit`, `POST /goals/{id}/lock`, `POST /goals/{id}/unlock` and `POST /payouts`, and rejected with 422 `invalid_idempotency_key` when missing. Repeating a call with the same key returns the original result instead of repeating the effect. Reusing a key with a different payload is 422 `invalid_idempotency_key`. Generate one UUID per user action and reuse it on retries. Creation endpoints (`POST /tasks`, `/goals`, `/task-series`) take no key, so a retried request creates a second object.
- **Eventual consistency:** a rewarded approval and an expired penalty reach the wallet through events, usually within seconds. After `approve`, the balance may lag; re-read it instead of assuming.
- **Roles:** `PARENT` and `TEEN`. A teen may be linked to several parents; a parent to several teens. A parent only sees and acts on tasks they created for linked teens.
- **Lists are not paginated** and no ordering is promised.

## Identity

### `POST /auth/google` (public)

Exchange a Google ID token for our token. The token's audience must be one of `GOOGLE_CLIENT_IDS`.

Request:
```json
{ "idToken": "<google id token>", "role": "PARENT", "displayName": "Aigerim" }
```
- `role`: **optional**; required only when the account doesn't exist yet. `PARENT` or `TEEN`, upper-case, **only used the first time** an account signs in. An existing account keeps its role and name, and the fields are ignored.

Client flow: call without `role`. On `422 role_required` (the account is new and nothing was created), ask the user for their role and call again with the same `idToken` and the `role`.
- `displayName`: optional, at most 100 characters; falls back to the Google profile name.

Response `200`:
```json
{ "token": "<our jwt>", "user": { "id": "…", "role": "PARENT", "displayName": "Aigerim", "joinCode": "SXVPUR" } }
```
`joinCode` appears only for parents (six characters from `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`). One Google account is one user with one role.

Errors: 401 `invalid_identity` (bad or wrong-audience token), 422 `role_required` (new account, no `role`), 422 `invalid_role`, 422 `invalid_display_name`, 409 `conflict`, 503 `identity_provider_unavailable`.

### `GET /me`

Returns the same `user` object. 401 `unauthenticated` if the user no longer exists.

### `POST /links/join` (teen)

Link the teen to a parent by the parent's code. Case and surrounding spaces are ignored. Re-joining after an unlink works.

Request `{ "code": "SXVPUR" }`. Response `200`: `{ "parent": { "id": "…", "displayName": "Aigerim" } }`. Keep the parent `id`: payouts are addressed to it.
Errors: 422 `invalid_join_code`, 403 `forbidden` (caller is not a teen), 409 `conflict`.

### `GET /family`

The people the caller is linked to right now, ordered by display name. A parent gets their teens, a teen gets their parents. This is how a parent learns the `teenId` needed to create the first task, and how a teen learns the `parentId` for payouts. Unlinked people are not listed.

Response `200`:
```json
{ "members": [ { "id": "…", "role": "TEEN", "displayName": "Dana" } ] }
```
`members` is empty when nobody is linked. A client should call it after sign-in and after joining or unlinking.

### `DELETE /links/{teenId}` (parent)

Unlink a teen. `204`. Effects, asynchronous: the parent's open tasks for that teen are cancelled, their recurring series close, an open payout to that parent is cancelled (reason `UNLINKED`), and both sides are notified.
Errors: 400 `invalid_teen_id`, 404 `not_found` (no such link), 403 `forbidden` (caller is not a parent).

## Tasks

A task has a state machine: `ASSIGNED` → `SUBMITTED` → `APPROVED` or `REJECTED`; `SUBMITTED` → `ASSIGNED` again on a redo request; `ASSIGNED` or `SUBMITTED` → `CANCELLED`; `ASSIGNED` → `EXPIRED` when the deadline passes (a ticker runs about once a minute). `kind` is `TASK` or `MILESTONE`. A milestone is itself a task with the same states that groups child tasks through `milestoneId`.

`TaskResponse` (fields that are null are omitted):
```json
{
  "id": "…", "teenId": "…", "parentId": "…", "kind": "TASK",
  "title": "Wash dishes", "description": "", "rewardAmount": 150000, "penaltyAmount": 0,
  "currency": "KZT", "state": "ASSIGNED", "deadlineAt": "2026-11-03T18:00:00Z",
  "submittedAt": null, "decidedAt": null, "decisionNote": null,
  "milestoneId": null, "approvedTasks": null, "totalTasks": null
}
```
`approvedTasks` and `totalTasks` appear only on a milestone returned by `GET /tasks/{id}`.

### `POST /tasks` (parent) → `201 TaskResponse`

```json
{ "teenId": "…", "kind": "TASK", "title": "…", "description": "…", "rewardAmount": 150000,
  "penaltyAmount": 0, "currency": "KZT", "deadlineAt": "2026-11-03T18:00:00Z", "milestoneId": null }
```
- `teenId` required and must be linked. `kind` defaults to `TASK`. `title` 1–200 characters after trimming. `currency` must be `KZT`. `rewardAmount` > 0 and at most the maximum; `penaltyAmount` 0 up to the maximum (taken from the wallet on expiry). `deadlineAt` must be in the future.
- `milestoneId` puts the task under an open milestone of the same family; a milestone cannot nest.
- Errors: 403 `forbidden`, 422 `teen_required`, `invalid_kind`, `invalid_title`, `invalid_reward`, `invalid_penalty`, `invalid_deadline`, `invalid_milestone`.

### `GET /tasks`

Teen: their own tasks. Parent: tasks they created, optionally `?teenId=<uuid>`. Both accept `?state=ASSIGNED|SUBMITTED|APPROVED|REJECTED|CANCELLED|EXPIRED`. Returns `TaskResponse[]`. Unknown state: 422 `invalid_filter`.

### `GET /tasks/{id}` → `TaskResponse`

Teen: own task. Parent: own task while the link is active. Others: 403 `forbidden`, unknown id: 404 `not_found`.

### `POST /tasks/{id}/submit` (teen) → `TaskResponse` with state `SUBMITTED`

Header `Idempotency-Key` required. Two forms:
- **Text:** `Content-Type: application/json`, body `{ "text": "…" }`, at most 2000 characters.
- **Image:** `multipart/form-data` with part `file`, JPEG or PNG only, at most 5 MB (the request limit is 8 MB). The server strips EXIF (including GPS), applies the orientation, and re-encodes. Pixel limit 16 million.

Only an `ASSIGNED` task before its deadline can be submitted. Errors: 409 `deadline_passed`, 409 `invalid_transition`, 422 `unsupported_proof` (wrong type, too large, empty or over-long text), 422 `invalid_idempotency_key`, 403 `forbidden`, 413 `payload_too_large`, 503 `media_busy`.

Proof image limits: JPEG or PNG, at most 5 MB, and at most 16 million pixels (for example 4000x4000); a larger image is 422 `unsupported_proof`. The server decodes at most 2 proof images at a time and an upload waits up to 1 second for a free slot; when none frees up it answers 503 `media_busy` and nothing is stored. Retry with the same `Idempotency-Key` after a short pause.

### Decisions (parent), all return `TaskResponse`

| Endpoint | Body | Effect |
|---|---|---|
| `POST /tasks/{id}/approve` | none | `SUBMITTED` → `APPROVED`; the reward is credited to the teen's wallet. Repeating it as the same parent is a no-op. |
| `POST /tasks/{id}/reject` | optional `{ "note": "…" }` (≤ 500) | `SUBMITTED` → `REJECTED`; final. |
| `POST /tasks/{id}/request-redo` | optional `{ "note": "…", "deadlineAt": "…" }` | `SUBMITTED` → `ASSIGNED`. A new deadline must be in the future and inside the milestone's bounds; without one, the existing deadline must still be in the future. |
| `POST /tasks/{id}/cancel` | none | `ASSIGNED` or `SUBMITTED` → `CANCELLED`; cancelling a milestone cancels its open children. |

Errors: 409 `invalid_transition`, 409 `concurrent_modification` (retry), 422 `invalid_note`, 422 `invalid_deadline`, 403 `forbidden`.

### `GET /tasks/{id}/proofs` (parent) → `ProofResponse[]`

```json
[ { "attemptNo": 1, "imageUrl": "https://s3-staging.tiyn.dev/tiyn-proofs/proofs/…?X-Amz-…", "text": null, "createdAt": "…" } ]
```
One entry per submission attempt. `imageUrl` is a presigned URL that stops working after `S3_VIEW_URL_TTL` (default 10 minutes); fetch it right away and do not store it. Teens cannot read proofs (403).

## Recurring tasks

### `POST /task-series` (parent) → `201`

```json
{ "teenId": "…", "title": "…", "description": "…", "rewardAmount": 50000, "penaltyAmount": 0,
  "currency": "KZT", "recurrence": "DAILY", "timezone": "Asia/Almaty", "dueTime": "18:00:00", "startsOn": "2026-11-02" }
```
- `recurrence`: `DAILY` or `WEEKLY` (weekly repeats on `startsOn`'s weekday). `timezone` defaults to `Asia/Almaty`. `dueTime` is required. `startsOn` defaults to today in that zone and cannot be earlier.
- Each occurrence becomes an ordinary task, created by a ticker; the first is created immediately when due.
- Response: `{ id, teenId, title, description, rewardAmount, penaltyAmount, currency, recurrence, timezone, dueTime, startsOn, active }`.
- Errors: 403 `forbidden`, 422 `teen_required`, `invalid_schedule`, plus the title and reward errors above.

### `DELETE /task-series/{id}` (parent) → `204`

Stops new occurrences; existing tasks stay. 404 `not_found`.

## Wallet (teen)

`balance` is what the ledger holds, `locked` is the part reserved for goals, and `available = balance − locked − the amount of an open payout request`. A penalty can make the balance negative.

### `GET /balance`
```json
{ "balanceAmount": 150000, "lockedAmount": 50000, "availableAmount": 100000, "currency": "KZT" }
```
Parents get 403 `forbidden`.

### Savings goals

`GoalResponse`: `{ id, name, targetAmount, savedAmount, currency, state }` with `state` `ACTIVE` or `CLOSED`. Saving into a goal moves money from available to locked; it does not leave the wallet.

| Endpoint | Body | Notes |
|---|---|---|
| `POST /goals` → `201` | `{ "name": "Bike", "targetAmount": 2000000 }` | name 1–100 characters, target > 0 |
| `GET /goals` | | the teen's goals |
| `GET /goals/{id}` | | 404 `not_found` for someone else's goal |
| `POST /goals/{id}/lock` | `{ "amount": 50000 }` + `Idempotency-Key` | saved plus amount must not exceed the target |
| `POST /goals/{id}/unlock` | `{ "amount": 20000 }` + `Idempotency-Key` | cannot unlock more than saved |
| `POST /goals/{id}/close` | none | releases everything saved; repeating it is a no-op |

Errors: 422 `invalid_name`, `invalid_amount`, `exceeds_target`, `insufficient_funds` (not enough available), `insufficient_goal_funds`, `invalid_idempotency_key`; 409 `goal_closed`; 403 `forbidden` (parent).

### Payouts (cash-out requests)

A teen asks a linked parent for money; the parent hands over the cash and confirms. `PayoutResponse`:
```json
{ "id": "…", "teenId": "…", "parentId": "…", "amount": 1000000, "currency": "KZT",
  "state": "REQUESTED", "requestedAt": "…", "resolvedAt": null, "cancelReason": null }
```
`state`: `REQUESTED`, `FULFILLED` or `CANCELLED`; `cancelReason`: `TEEN_CANCELLED`, `DECLINED`, `EXPIRED` or `UNLINKED`. A teen can have **one** open payout at a time. An open request is also held back from `availableAmount`. Requests expire after `LEDGER_PAYOUT_EXPIRY` (default 7 days).

| Endpoint | Caller | Notes |
|---|---|---|
| `POST /payouts` → `201` | teen | `{ "parentId": "…", "amount": 1000000 }` + `Idempotency-Key`; amount at least the minimum and no more than available |
| `GET /payouts?state=` | both | teen: own; parent: addressed to them |
| `GET /payouts/{id}` | both | only participants; others get 404 |
| `POST /payouts/{id}/fulfil` | parent | debits the wallet |
| `POST /payouts/{id}/decline` | parent | |
| `POST /payouts/{id}/cancel` | teen | |

Errors: 422 `parent_required`, `invalid_amount`, `amount_too_small`, `insufficient_funds`, `invalid_filter`, `invalid_idempotency_key`; 409 `payout_already_open`, 409 `invalid_transition`; 403 `forbidden` (wrong role, or the link is gone).

## Teen home screen

### `GET /dashboard` (teen only; parents get 403)

One call for the home screen:
```json
{
  "balance": { "balanceAmount": 0, "lockedAmount": 0, "availableAmount": 0, "currency": "KZT" },
  "pendingAmount": 150000,
  "chores": [ { "id": "…", "kind": "TASK", "title": "…", "description": "…", "rewardAmount": 0, "penaltyAmount": 0, "currency": "KZT", "state": "ASSIGNED", "deadlineAt": "…" } ],
  "milestones": [ { "…": "same fields, plus approvedTasks and totalTasks" } ],
  "goals": [ { "id": "…", "name": "…", "targetAmount": 0, "savedAmount": 0, "currency": "KZT" } ],
  "openPayout": { "id": "…", "parentId": "…", "amount": 0, "currency": "KZT", "requestedAt": "…" }
}
```
`pendingAmount` is the sum of rewards of tasks awaiting a parent's decision. `chores` and `milestones` hold open work plus recently decided tasks (window `DASHBOARD_RECENT_WINDOW`, default 7 days, at most `DASHBOARD_MAX_TASKS` = 50 tasks). `goals` lists active goals. `openPayout` is omitted when there is none.

## Push notifications

### `PUT /devices` → `204`
Register or refresh a device: `{ "token": "<fcm token>", "platform": "ANDROID", "locale": "kk" }`. `token` is at most 4096 characters; `platform` is `ANDROID` or `IOS` (case-insensitive); `locale` is `ru` or `kk`. Repeating it with the same token updates the record. Errors: 422 `invalid_token`, `invalid_platform`, `invalid_locale`.

### `DELETE /devices` → `204`
`{ "token": "…" }`. Call it on sign-out. Tokens that Firebase reports as dead are removed by the server.

### What a push contains
A localised `notification` (title and body in Russian or Kazakh by the device's locale) plus a `data` map of strings: `type` always, and `taskId` or `payoutId` when relevant, for deep links. `type` is one of:

| Recipient | Types |
|---|---|
| teen | `TASK_ASSIGNED`, `TASK_APPROVED`, `TASK_REJECTED`, `TASK_REDO_REQUESTED`, `TASK_CANCELLED`, `TASK_EXPIRED`, `TASK_EXPIRED_WITH_PENALTY`, `PENALTY_APPLIED`, `PAYOUT_FULFILLED`, `PAYOUT_DECLINED`, `PAYOUT_EXPIRED`, `PAYOUT_UNLINKED`, `FAMILY_LINKED_TEEN`, `FAMILY_UNLINKED` |
| parent | `TASK_SUBMITTED`, `PAYOUT_REQUESTED` (high priority), `PAYOUT_CANCELLED_BY_TEEN`, `FAMILY_LINKED_PARENT`, `FAMILY_UNLINKED` |

Each event is sent at most once per recipient, even if the server redelivers it. The push is a nudge, not a source of truth: on receipt, refetch the data.

## Operations

`GET /actuator/health/liveness` and `/readiness` are public and return `{"status":"UP"}`. Nothing else under `/actuator` is exposed.

## Gaps a client will hit

These are facts about the current API, listed so they are decided on purpose and not found in the app:

1. **No parent home screen.** There is no parent dashboard: pending proofs, open payouts and teen balances need several calls.
2. **There is no wallet history.** Nobody can list ledger entries, so the app can show a balance but not why it changed. A teen also cannot read their own submitted proofs (`/proofs` is parent-only).
3. **No pagination, ordering guarantees or `If-Modified-Since`.** Fine at MVP size, painful after months of tasks.
4. **No refresh token.** Sessions last 7 days, then the user signs in with Google again.
5. **Creation endpoints are not idempotent** (`POST /tasks`, `/goals`, `/task-series`), so a retry after a network failure duplicates.
6. **No profile edit, account deletion or data export.** The last matters for the Kazakhstan personal-data law.
7. **Push copy is Russian and Kazakh only,** with no English fallback; the Kazakh text has not been reviewed by a native speaker.
