# Modules & Boundaries

The system is five bounded contexts. Each is a deep module: a small interface over substantial, hidden implementation. This document defines what each module *is*, what it *offers* (its interface contract), what it *publishes* (its event contract), and the rules that keep the boundaries clean so a module can later be lifted into its own service.

## The two — and only two — ways modules interact

1. **Synchronous:** Module A depends on an **interface** that Module B exports (e.g. `media.Storage`). A receives the implementation by constructor injection. A uses *only* what B publishes in its root package (interfaces, DTOs, event records) — never B's feature sub-packages. Spring Modulith treats a module's root package as its API and every sub-package as internal; `ApplicationModules.verify()` fails the build on a violation.
2. **Asynchronous:** Module A publishes a domain event; Module B subscribes. A and B do not know about each other at all. This is the default for anything that is a *reaction* ("when a task is approved, credit the ledger and notify").

Prefer async for reactions and side effects; use sync only when the caller genuinely needs a result back *now* (e.g. "store this proof image and give me its object key").

## Hard rules (these are what make later extraction mechanical)

- **No shared tables.** Each table is owned by exactly one module. No other module reads or writes it.
- **No cross-context foreign keys.** A `ledger_entries` row references a task by `task_id` as a plain UUID column, not a FK to the task module's table. Cross-context references are by ID only. (Within a single module, FKs are encouraged.)
- **No distributed transaction across modules.** A single DB transaction belongs to one module's service call. Cross-module consistency is achieved with events + idempotency (eventual consistency), never a transaction spanning two modules' tables.
- **IDs are the integration currency.** Modules exchange UUIDs and small value objects in event payloads, not entities.

If you find yourself wanting to JOIN across two modules' tables, that is the signal that either the boundary is wrong or the data should travel in an event. Ask before reaching across.

## Domain model (DDD)

We apply Domain-Driven Design pragmatically: strategic DDD everywhere (bounded contexts, a context map, one language), tactical DDD (aggregates, value objects, domain events) where there are real rules to protect. The rules for writing it are in `docs/conventions.md`.

**Bounded contexts and the context map.** Each module below is a bounded context with its own model; the same word can mean different things in different contexts (a "user" in identity is an account, in notification it is just a recipient with device tokens). Contexts relate in two ways:
- **Published language:** the events in `docs/events-outbox.md` are the shared language between contexts. Task is upstream of ledger and notification; ledger is upstream of notification; identity is upstream of ledger and notification. Downstream contexts translate events into their own model and never depend on the upstream's internals.
- **Customer/supplier:** the few synchronous interfaces (`FamilyDirectory`, `BalanceReader`, `PendingReader`, `media.Storage`) are narrow contracts owned by the supplier.
- **Anti-corruption layer:** external systems (Google ID tokens, FCM, S3, bank apps) sit behind an interface in the module that uses them. Their types and vocabulary never enter an aggregate.

**Aggregates.** An aggregate is a cluster of objects that must be consistent together. Only its root is loaded and saved; other aggregates are referenced by ID.

| Context | Aggregate (root) | Contains | Invariants it protects | Concurrency |
|---|---|---|---|---|
| identity | `User` | join code | one Google subject, one user; a parent has one join code | unique constraints |
| identity | `FamilyLink` | parent id, teen id, removed flag | a parent and a teen are linked at most once; a link joins a `PARENT` to a `TEEN`; first link, relink and no-op are decided inside the aggregate, which also records its own events | primary key |
| task | `Task` | proofs | legal state transitions; tasks in a milestone are one-time and for the same teen | version column |
| task | `TaskSeries` | recurrence rule | one occurrence per period | unique `(series, date)` |
| ledger | `Wallet` | ledger entries (append-only), payouts, savings goals | balance may go negative (a penalty debt); locked never negative; at most one open payout; one reward per task; one debit per payout | wallet row `FOR UPDATE` |
| notification | `DeviceToken` | token, platform, locale | a token belongs to one user | unique token |

The wallet aggregate is deliberately large: "available funds = balance − locked − open payout request" spans payouts, goals and entries, so they share one consistency boundary and one lock.

**Rule of thumb:** one transaction changes one aggregate. Documented exceptions inside a single context: cancelling a milestone also cancels its open tasks, and a payout request touches the wallet and its payout together (same aggregate). Across contexts there is never one transaction; use an event.

**Ubiquitous language.** Use these words in code, SQL, API names, events and docs, and do not invent synonyms.

| Term | Meaning |
|---|---|
| Parent / Teen | The two roles. A parent funds and approves; a teen earns. |
| Join code | A parent's permanent code a teen enters to link to that parent. |
| Link | The relation between one parent and one teen. |
| Task | A piece of work with a reward and a deadline. |
| Milestone | A task of kind milestone; larger goal with its own proof and reward. A task may belong to one. |
| Series / Occurrence | A recurring rule and one concrete task generated from it for a period. |
| Proof | What the teen submits to show a task is done (image or text). |
| Reward | The amount the teen earns when a task is approved. |
| Approve / Reject / Request redo / Cancel / Expire | The parent's review decisions, and the system's deadline outcome. |
| Wallet | A teen's account in one currency. |
| Balance | Money the teen has earned and not yet paid out. |
| Locked | Part of the balance set aside in savings goals. |
| Available | Balance minus locked minus the open payout request; what can be requested or locked. |
| Pending | Rewards of tasks submitted and not yet decided. Owned by task, not ledger. |
| Ledger entry | One immutable line in the wallet's history. |
| Payout | A teen's request to cash out; the parent fulfils it in their bank app and confirms. |
| Penalty | A parent's manual deduction. |
| Savings goal | A target the teen sets aside part of the balance for. |
| Domain event | A past-tense fact one context publishes, e.g. `TaskApproved`. |

---

## 1. Identity & Family

**Responsibility.** User accounts (parent, teen), authentication, and family-group linking (PAR-01). Owns the security context that every other request is authorized against.

**Authentication.** Sign-in with Google. The client obtains a Google ID token and sends it to the backend; identity verifies it and issues the app's own JWT. Google is only the proof of identity, so the provider can be swapped or extended (e.g. Apple) without touching other modules. Parents and teens both register by signing in; the role (`PARENT` or `TEEN`) is chosen at first sign-in and an account has exactly one role for life. Signing in again ignores any role in the request and returns the existing account. `GoogleVerifier` is the anti-corruption layer: the real implementation validates the ID token with Spring Security's `NimbusJwtDecoder` against Google's published keys (issuer, expiry and an audience from `GOOGLE_CLIENT_IDS`, one per platform), and tests use a fake. A malformed or invalid token is 401 `invalid_identity`; an unreachable key set is 503 `identity_provider_unavailable`, logged, so an outage is not mistaken for a bad credential. The application refuses to start when `GOOGLE_CLIENT_IDS` is empty. No email is stored; the display name comes from the request or the Google profile.

**Session token.** After a successful sign-in the backend issues its own JWT: HS256, signed with `JWT_SECRET` (at least 32 bytes), issuer `tiyn`, subject = user id, a `role` claim, valid for `JWT_TTL` (default 7 days). Verification pins the algorithm, requires an expiry, the issuer and a known role, so `alg: none` and other-algorithm tokens are rejected. There is no refresh token: when it expires the client signs in with Google again. The token only proves identity; every parent-to-teen action still checks `areLinked` in the database. Tokens are issued by `platform.security.AccessTokenIssuer` and verified in `platform.security.SecurityConfig` with the same key, so the claim format (`iss`, `sub`, `role`, `iat`, `exp`) lives in one place. `users.idp_sub` holds the identity provider's subject and is provider-neutral. Moving to an identity provider such as Keycloak later changes `GoogleVerifier`, the issuer, the claim converter and the sign-in route; no other module notices, because they only see `Principal(userId, role)`.

**Linking.** Every parent has a permanent **join code** (6 characters, PRD PAR-01) stored on their user row. It uses an alphabet without look-alike characters (no `0/O`, `1/I`) and is generated with a retry on collision. A teen who has signed in enters the parent's code and identity creates the `family_links` row immediately and publishes `FamilyLinked`; entering a code a second time is a no-op. Codes are matched case-insensitively and ignoring surrounding spaces. One code can link any number of children, and a teen can enter a second parent's code. The parent can remove a link later. Code attempts are rate-limited at the ingress and failures never reveal whether a code exists. Accepted risk: the code is permanent and reusable, so anyone who learns it can link to that parent. Parent confirmation of each join (a pending-request step) is deliberately deferred; it would add an identity-owned `join_requests` table without changing other modules.

**Offers (sync interface):**
```java
public interface FamilyDirectory {

    boolean areLinked(UUID parentId, UUID teenId);
}
```
Links are soft-deleted (`removed_at`). `areLinked` and every other lookup must ignore removed links; re-linking a removed pair clears `removed_at` on the existing row and increments its `generation`, which is part of the event key so that a relink produces a new event.
Other modules depend on `FamilyDirectory` to authorize parent→teen actions (e.g. the task module checks a parent may approve a given teen's task). Token verification is not part of identity's API: the `platform` module verifies bearer tokens and hands controllers a `Principal(userId, role)`.

**Concurrency.** Registration, join-code allocation and linking can race. Every unique-rule violation (same Google subject, same join code, same link) is reported by the store as one `ConflictException`, and the service retries the whole transaction up to five times; if it still conflicts the caller gets 409 `conflict`. The recovery is the same in all three cases (the retry finds the winner's row, or draws a fresh code), so nothing depends on Postgres-generated constraint names. The transaction is opened per attempt with a `TransactionTemplate`, because a unique violation aborts the Postgres transaction and a retry needs a fresh one.

**Publishes:**
- `FamilyLinked(parentId, teenId, generation)` (lets other modules react to a new link, e.g. seed a wallet). Consumers dedupe on `(parentId, teenId, generation)`.
- `FamilyUnlinked(parentId, teenId, generation)` (the task module cancels the teen's open tasks for that parent). The generation is the one of the link being removed, and the event type already separates it from `FamilyLinked`.

Both are published in the transaction that locks the link row, so changes to one pair are serialised. Modulith delivers events asynchronously and does not order them across listeners, so consumers must be state-based: they look at the current link, not at the order of events. `generation` is what lets a relink after an unlink count as a new fact.

**HTTP API.** Errors are `{"error": "<code>"}`; bodies are size-limited and reject unknown fields.

| Route | Auth | Purpose | Errors |
|---|---|---|---|
| `POST /auth/google` `{idToken, role?, displayName?}` | none | Sign in or register; returns `{token, user}` | 401 `invalid_identity`, 422 `invalid_role` / `invalid_display_name` |
| `GET /me` | bearer | The caller's account | 401 `unauthenticated` |
| `POST /links/join` `{code}` | teen | Link to the parent who owns the code; returns `{parent: {id, displayName}}` | 403 `forbidden`, 422 `invalid_join_code` (unknown and malformed codes look the same) |
| `GET /family` | bearer | The caller's active links: a parent's teens or a teen's parents, as `{members: [{id, role, displayName}]}` | 401 `unauthenticated` |
| `DELETE /links/{teenId}` | parent | Remove the link to a teen; repeating it is harmless (204) | 403 `forbidden`, 404 `not_found`, 400 `invalid_teen_id` |

**Configuration:** `JWT_SECRET`, `JWT_TTL`, `GOOGLE_CLIENT_IDS` (see `.env.example`).

**Owns:** `users` (including the parents' `join_code`), `family_links`. There is no `families` table: a family is simply the set of links between parents and teens, so a teen can have two parents without a new concept.

---

## 2. Task & Milestone

**Responsibility.** The lifecycle of chores and academic milestones (PAR-02, PAR-03, TEN-02): creation, submission with proof, parental review, cancellation and expiry. This is the primary **state machine** in the system.

A milestone is a task with `kind = MILESTONE`: same FSM, same review flow, different grouping in the teen UI. One table, one FSM.

**Tasks inside a milestone.** A task may belong to a milestone through a nullable `milestone_id` (a self-reference within the task module). Each such task has its own reward and goes through its own review; the milestone is approved separately, with its own proof and reward, and does not auto-complete when its tasks are done. Rules enforced in the aggregate (`Task.assignInMilestone`, error `invalid_milestone`): one level only (a milestone's tasks cannot themselves be milestones or hold tasks), the task has the same parent and teen as the milestone, a task's deadline is not later than its milestone's, the milestone must be `ASSIGNED` when a task is added, and tasks inside a milestone are one-time, never recurring (also enforced by a CHECK). Creating a child and cancelling a milestone both lock the milestone row, so a child can never land under a milestone that is being cancelled. Cancelling a milestone cancels its open tasks in the same transaction, each emitting `TaskCancelled`; cancelling a task does not affect the milestone. Expiry does not cascade: a task's deadline never exceeds its milestone's (a redo cannot push it past, either), so `ASSIGNED` children expire no later than the milestone, and `SUBMITTED` children stay reviewable. Progress (`approvedTasks` of `totalTasks`, cancelled tasks excluded) is computed from the tasks and returned only by `GET /tasks/{id}` on a milestone.

**Task FSM:**
```
 create ──▶ ASSIGNED ──submit(proof)──▶ SUBMITTED ──approve──▶ APPROVED   (terminal)
               ▲  │                        │  │
               │  │                        │  └────reject───▶ REJECTED   (terminal)
               │  │                        │
               │  │                        └─request_redo─┐
               │  └────────────────────────────────────────┘ (back to ASSIGNED)
               │
               ├── expire (system, deadline passed) ──▶ EXPIRED     (terminal)
               └── cancel (parent, from ASSIGNED or SUBMITTED) ──▶ CANCELLED (terminal)
```
There is no `DRAFT`: a task is created directly in `ASSIGNED`. Guards live in the domain: `submit` only from `ASSIGNED`; `approve`/`reject`/`request_redo` only from `SUBMITTED`; `expire` only from `ASSIGNED` (a task submitted before its deadline stays approvable however slow the review); `cancel` from `ASSIGNED` or `SUBMITTED`. Invalid transitions raise `TaskException(INVALID_TRANSITION)` (HTTP 409). Errors are defined out of existence where a retry is natural: repeating the same decision (`approve`, `reject`) or `cancel` by the same parent is a no-op, and `expire` silently does nothing unless the task is `ASSIGNED` and past its deadline, so a tick racing a submit or cancel is harmless. `request-redo` accepts an optional new `deadlineAt`; it is required once the old deadline has passed, must be in the future, and may not exceed the milestone's deadline.

**Recurrence: one row per occurrence.** A recurring task (daily/weekly) is a *series*; each period is its own `tasks` row with `series_id` and `occurrence_date` and a `UNIQUE (series_id, occurrence_date)` constraint. Terminal states never "re-arm" — the next period is a new row. This keeps history, makes the reward key (`REWARD-TASK-{taskID}`) naturally unique per occurrence, and makes materialising the next occurrence idempotent. Period boundaries are computed in the series' own timezone (`task_series.timezone`) and stored in UTC. A missed occurrence expires; it carries no automatic penalty (penalties are manual, PAR-04). **No backfill:** each tick materialises only *today's* occurrence (the date in the series' timezone), and only while its due time is still in the future, so a day the system missed simply never exists. Creating a series materialises today's occurrence in the same transaction. The `series` feature depends on `tasks` through the public `TaskAssignment` interface and the `Occurrence` record; the reverse dependency does not exist. Series tasks cannot belong to a milestone.

**Proof submission.** The client sends the image (multipart) or text (JSON) in the submit request with a required `Idempotency-Key`. The task service passes image bytes to `media.Storage.put` first, outside any transaction, then runs one transaction that claims the key, applies the FSM transition, stores `task_proofs`, and publishes the event. The claim is scoped to the attempt (`taskId:attempt:key`), so a replay within one attempt is a no-op that returns the current task, while the same key after a redo starts a new attempt. Every `put` returns a fresh unique object key, so a replay can never overwrite the object a stored proof points to; the cost is an orphan blob after a failed or replayed submit, which a storage lifecycle rule should expire.

**Offers (sync interface):**
```java
public interface PendingReader {

    long pendingReward(UUID teenId);
}
```
Used by the dashboard module to show "Pending" funds (TEN-01). Pending is task-owned data, so the task module answers it; the ledger never reads task tables.

**Publishes:**
- `TaskAssigned(taskId, teenId, kind, rewardAmount, currency)` → notification (tell the teen: "new task / milestone").
- `TaskSubmitted(taskId, parentId, teenId)` → notification (tell the parent). Events carry identifiers only: the proof itself is read through `GET /tasks/{id}/proofs`, which mints a fresh presigned URL at view time.
- `TaskApproved(taskId, teenId, rewardAmount, currency)` → ledger (credit), notification (tell the teen). *This is the central event of the system* (architecture doc calls it `TaskCompletedEvent`; we name it `TaskApproved` for precision).
- `TaskRejected`, `TaskRedoRequested`, `TaskCancelled`, `TaskExpired` → notification.

**Time-driven work** (`Ticker`, see below): `TaskExpiryTicker` expires `ASSIGNED` tasks past their deadline, each in its own short transaction; `SeriesTicker` materialises today's occurrence of each active recurring series.

**Reacts to** `FamilyUnlinked`: `tasks` cancels the pair's open tasks and `series` closes the pair's series, each in its own listener (separate publications, independent retries). Both re-check `areLinked` first, so a late replay after a relink does nothing.

**HTTP API** (all routes need a bearer token; errors are `{"error": "<code>"}`):

| Route | Caller | Purpose |
|---|---|---|
| `POST /tasks` | parent | create a task or milestone; optional `milestoneId` |
| `GET /tasks?teenId&state` | parent or teen | list (a teen sees only their own; capped at 200) |
| `GET /tasks/{id}` | parent or teen | detail; milestones include progress |
| `POST /tasks/{id}/submit` | teen | multipart `file` or JSON `{text}`; `Idempotency-Key` required |
| `POST /tasks/{id}/approve`, `/reject`, `/request-redo` | parent | review; reject and redo take `{note?}`, redo also `{deadlineAt?}` |
| `POST /tasks/{id}/cancel` | parent | cancel; a milestone cancels its open tasks |
| `GET /tasks/{id}/proofs` | parent | proofs with presigned view URLs |
| `POST /task-series` | parent | create a daily or weekly series (`recurrence`, `dueTime`, optional `timezone`, `startsOn`) |
| `DELETE /task-series/{id}` | parent | close a series (repeatable) |

Error codes: `not_found` 404, `forbidden` 403; `invalid_transition`, `deadline_passed`, `concurrent_modification` 409; `invalid_reward`, `invalid_deadline`, `invalid_title`, `invalid_note`, `invalid_milestone`, `invalid_kind`, `invalid_schedule`, `invalid_filter`, `unsupported_proof`, `invalid_idempotency_key`, `teen_required` 422; `media_busy` 503. Rewards are capped by `tiyn.task.max-reward-amount` (default 10,000,000 minor units).

**Depends on:** `identity.FamilyDirectory` (authorize that the caller parents this teen), `media.Storage` (store the proof image).

**Owns:** `tasks`, `task_series`, `task_proofs`, `idempotency_keys`. Uses an **optimistic version column** on `tasks` to detect concurrent transitions (see `docs/transactions.md`).

---

## 3. Ledger & Wallet

**Responsibility.** The append-only transaction journal and the derived wallet balance (TEN-01, SYS-01). Credits from approved tasks, debits from payouts and penalties (PAR-04), payout requests, and savings-goal locks (TEN-04). This module is the financial source of truth.

**Money representation.** Amounts are `BIGINT` in minor units (tiyn for KZT) and always travel with a `currency` (a Postgres domain `platform.currency`, currently restricted to `KZT`). Allowing another currency is a one-line change to that domain plus the product work around it (wallet per currency, no cross-currency sums, payouts only in the currency the parent can transfer). Payouts and wallets are KZT today because payouts are Kaspi transfers in tenge. A goal priced in another currency would be a display conversion at an exchange rate, not a second ledger currency, and is not built.

**Design:** double-entry-style append-only `ledger_entries` (never updated or deleted — corrections are compensating entries). A `wallets` row per teen and currency caches the balance (no lower bound: a penalty can leave a debt) and is mutated only under `SELECT … FOR UPDATE` (pessimistic lock). Every credit/debit carries an idempotency key with a `UNIQUE` constraint.

**Available balance.** What a teen may request or lock is
`available = balance − sum(open REQUESTED payouts) − funds locked in savings goals`.
Requesting a payout does not debit anything; it only reduces *available* (computed under the wallet lock). A teen may have **at most one** open request, enforced by a partial unique index on `payouts (teen_id) WHERE state = 'REQUESTED'`. The debit entry is written when the parent confirms.

**Debit checks.** Payout requests and goal locks compare the amount with *available* funds under the wallet lock and return a domain error (HTTP 422) if it does not fit. Fulfilling an open payout checks `amount <= balance − locked`. A **penalty** is the one debit with no such check: it may take the balance below zero, and `available` can then be negative until rewards pay the debt down. The only wallet CHECK left is `locked_amount >= 0`; overdraft protection for payouts and goals lives in the row lock and these service checks, covered by the concurrency tests.

**Payout FSM:**
```
REQUESTED ──parent confirms──▶ FULFILLED   (debit entry written; parent's word is trusted)
     │
     └──teen cancels / parent declines / expires──▶ CANCELLED
```
A payout cancelled by the system (it expired) has no `resolved_by`; one cancelled by the teen or declined by the parent records who did it. Every cancellation records a `cancel_reason`. We never verify the bank transfer (no bank API, and we are not a bank), whichever bank the parent uses. Confirmation by the parent is the source of truth; the audit trail records who confirmed and when. A missing transfer is resolved between parent and teen.

**Offers (sync interface):**
```java
public interface BalanceReader {

    Balance balance(UUID teenId);
}
```
`Balance` is a value object with `balanceAmount` (the wallet total: rewards minus payouts and penalties), `lockedAmount` and `availableAmount`.
(Read-only. "Pending" is not here; it comes from `task.PendingReader`. All *writes* arrive via events or payout commands, never a generic "setBalance".)

**Publishes:** `PayoutRequested(payoutId, teenId, parentId, amount, currency)`, `PayoutFulfilled(payoutId, teenId, parentId, amount, currency)`, `PayoutCancelled(payoutId, teenId, parentId, amount, currency, reason)` (reason: `TEEN_CANCELLED`, `DECLINED`, `EXPIRED`, `UNLINKED`), all → notification. `PenaltyApplied(entryId, teenId, taskId, amount, currency)` → notification. The payout store publishes what the `Payout` aggregate recorded.

**Subscribes:** `TaskExpired` → if the task carries a penalty, debit it once (`PENALTY` entry, key `PENALTY-TASK-{taskId}`, `ref_id = taskId`, no actor; a unique index allows one penalty per task). `FamilyUnlinked` → cancel the pair's open payout (`UNLINKED`; state-based, a replay while the pair is linked again does nothing). `TaskApproved` → credit the teen's wallet. The credit is idempotent: the journal entry key is `REWARD-TASK-{taskId}`, checked under the wallet's row lock (which serialises every credit for that teen) and backed by the `UNIQUE` key and a one-reward-per-task index.

**The wallet is created lazily.** The first credit runs `INSERT ... ON CONFLICT DO NOTHING` and then locks the row, so concurrent first credits are safe. There is deliberately no `FamilyLinked` listener: events are delivered unordered, so a credit can arrive before any link handler, and the credit must be able to create the wallet itself. A teen without a wallet reads as a zero balance.

**Penalties (step 6d).** A parent may set an optional `penaltyAmount` (0 to `tiyn.task.max-reward-amount`, default 0) when creating a task or a recurring series; each series occurrence copies it, and `GET /tasks` shows it. If the task expires, `TaskExpired` carries the penalty and the ledger deducts it, even below zero. Submitted, rejected, cancelled (including after an unlink) and approved tasks are never penalized. While `available` is zero or negative, payout requests and goal locks are refused (`insufficient_funds`); unlock, close and rewards still work. If a penalty lands after a payout request and removes its cover, fulfilling returns `insufficient_funds` and the parent can decline (revisit if parents find this surprising).

**Built so far (steps 6a, 6b, 6c-goals, 6d):** the `Wallet` aggregate, the append-only `LedgerEntry` (kinds `TASK_REWARD`, `PAYOUT`, `GOAL_LOCK`, `GOAL_UNLOCK`, `PENALTY`), `RewardListener`, `BalanceReader`/`Balance` and `GET /balance` (teen only; amounts in minor units; `available` already excludes the open payout request). Payouts live in the same `wallet` feature package because fulfilment changes `Payout`, `Wallet` and the journal in one transaction. Savings goals live in the same package for the same reason (a lock changes the wallet, the journal and the goal together). Penalties are task-driven (below); there is no manual penalty endpoint.

**Payout API.** The teen names the parent (who must be linked); the payout is addressed to that parent only.

| Route | Caller | Notes |
|---|---|---|
| `POST /payouts` | teen | `{parentId, amount}` + required `Idempotency-Key` (scoped per teen; replay returns the original) |
| `GET /payouts?state=` | teen (own) / parent (addressed to them) | newest first, capped at 100 |
| `GET /payouts/{id}` | teen owner / addressed parent | anyone else gets `not_found` |
| `POST /payouts/{id}/fulfil` | parent, still linked | debits the wallet; repeating is a no-op |
| `POST /payouts/{id}/decline` | parent, still linked | `DECLINED` |
| `POST /payouts/{id}/cancel` | teen | `TEEN_CANCELLED` |

Errors: `forbidden` 403, `not_found` 404, `invalid_amount` / `amount_too_small` / `insufficient_funds` / `parent_required` / `invalid_idempotency_key` / `invalid_filter` 422, `payout_already_open` / `invalid_transition` 409. Repeating the same cancellation is a no-op; resolving a payout another way is `invalid_transition`. The fulfil debit must fit `balance − locked` (funds locked in goals can never be paid out).

**Savings goals API** (teen only; a parent gets `forbidden`; goals are private to the teen). A goal is a label on part of the wallet's money: locking moves nothing, it writes a `GOAL_LOCK` entry (`balance_delta = 0`, `locked_delta = +amount`) and raises `wallets.locked_amount`, which lowers *available*. The money stays in the total balance. A goal's `savedAmount` is derived: the sum of `locked_delta` over the entries whose `ref_id` is the goal, so the goals and the wallet's locked total cannot drift apart (a test asserts `locked_amount = sum of saved`).

| Route | Notes |
|---|---|
| `POST /goals` | `{name, targetAmount}`; name 1 to 100 characters, target > 0; 201 |
| `GET /goals`, `GET /goals/{id}` | with `savedAmount`; another teen's goal is `not_found`; list capped at 100 |
| `POST /goals/{id}/lock` | `{amount}` + required `Idempotency-Key`; must fit *available* and the goal's remaining target |
| `POST /goals/{id}/unlock` | `{amount}` + required `Idempotency-Key`; at most what the goal holds |
| `POST /goals/{id}/close` | releases everything saved (`GOAL_UNLOCK`, key `GOAL-CLOSE-{goalId}`) and closes; repeating is a no-op |

Errors: `invalid_name`, `exceeds_target`, `insufficient_funds`, `insufficient_goal_funds`, `invalid_amount`, `invalid_idempotency_key` 422; `goal_closed` 409. The ledger key is `GOAL-LOCK:{teenId}:{key}` / `GOAL-UNLOCK:{teenId}:{key}`; a replay with the same goal and amount changes nothing, the same key for a different request is `invalid_idempotency_key`. Every goal command takes the wallet row lock first, so the available check, the saved amounts and `locked_amount` are serialised per teen. Reaching the target does nothing automatically, and goals publish no events yet.

**Config.** `tiyn.ledger.min-payout-amount` (env `LEDGER_MIN_PAYOUT_AMOUNT`, default 10000 minor units = 100 KZT) and `tiyn.ledger.payout-expiry` (env `LEDGER_PAYOUT_EXPIRY`, default `P7D`).

**Lock order.** Every payout command locks the wallet row first and the payout row second, so concurrent commands cannot deadlock; the wallet lock also serialises requests per teen (the partial unique index is the backstop).

**Time-driven work** (`Ticker`): `PayoutExpiryTicker` cancels `REQUESTED` payouts older than `payout-expiry` (reason `EXPIRED`, no `resolved_by`), each in its own transaction.

**Owns:** `ledger_entries`, `wallets`, `payouts`, `savings_goals`. The ledger needs no separate idempotency table: `ledger_entries.idempotency_key` and `payouts.idempotency_key` are `UNIQUE` and are the guard.

**Savings goals.** A goal stores its target only. The amount saved is derived: the sum of `locked_delta` over the ledger entries whose `ref_id` is the goal, so goals and the wallet's locked amount cannot drift apart.

---

## 4. Media Storage

**Responsibility.** S3 client wrapper (SYS-02). Receives proof images from the task service, validates and sanitises them, stores them, and issues short-lived pre-signed GET URLs for review. Proof images pass through the backend (see "Why bytes pass through" below).

**Offers (sync interface):**
```java
public interface Storage {

    String put(String scope, String contentType, InputStream body);

    URI presignView(String objectKey);
}
```
`put` validates (content-type allowlist, size cap), strips metadata such as EXIF/GPS, and stores the image. Each `put` returns a new unique object key; idempotency belongs to the task service, which claims the submit's `Idempotency-Key` in its transaction. `presignView` returns a short-lived GET URL for parent review.
Called by the task module on submission and review.

**Uploads and orphans:** object key = `proofs/{scope}/{uuid}`. A deterministic key was rejected because a replay with a different image would overwrite the object a stored proof points to. The price is an orphan object after a failed or replayed submit; expire unreferenced objects with a storage lifecycle rule.

**Implementations.** Exactly one `Storage` bean is selected by `tiyn.media.storage` (`s3` or `memory`), which has **no default**: a deploy that forgets it fails at startup instead of silently keeping proofs in memory. `memory` (`InMemoryStorage`) is for tests and the `local` profile and loses every proof on restart. `s3` (`S3Storage`, AWS SDK v2, path-style, works against SeaweedFS) writes `proofs/{scope}/{uuid}` and verifies at startup that the bucket exists (create it first, e.g. `aws --endpoint-url http://localhost:8333 s3 mb s3://tiyn-proofs`). View URLs live `S3_VIEW_URL_TTL` (default `PT10M`). Config: `S3_ENDPOINT`, `S3_REGION` (default `us-east-1`), `S3_BUCKET`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`, and optional `S3_PUBLIC_ENDPOINT`: when set, view URLs are signed for that host while uploads still use `S3_ENDPOINT` (for an in-cluster store the client can only reach through an ingress). SDK failures surface as `StorageUnavailableException` (a 500; the client retries with the same `Idempotency-Key`).

**Sanitising.** Both implementations run `ImageSanitizer`: the declared type must match the decoded format, images over 16 million pixels are rejected (a 5 MB file can still decode to a huge bitmap), at most 2 images are decoded at once and an upload waits up to 1 second for a slot before `MediaBusyException` (the task module answers 503 `media_busy`), the EXIF orientation is applied to the pixels, and the image is re-encoded (JPEG quality 0.9, PNG lossless), which drops EXIF/GPS, XMP, ICC and text chunks. Not an image, a truncated file or a type mismatch is `InvalidMediaException` (`unsupported_proof`). Allowed types are `image/jpeg` and `image/png` (WebP was dropped because the JDK cannot decode it; clients must send JPEG or PNG), 5 MB cap. Video is not supported; it would need pre-signed uploads and its own design.

**Why bytes pass through.** The teen client never sees S3, never holds an object key, and cannot submit a key for an object that was never uploaded. The server can enforce type and size and strip location metadata from photos of minors. Cost: image bytes use API bandwidth and memory, and the platform's request-size limit applies (set the ingress body limit accordingly). Clients compress before sending, so a few MB per request is expected. If scale demands it, switching to pre-signed PUT is a change inside this module plus the task submit path.

**Owns:** no DB tables of its own in MVP (object keys are referenced by the task module as plain strings). If upload bookkeeping is later needed, it owns its own `media_objects` table.

**Publishes:** nothing in MVP.

---

## 5. Notification Dispatcher

**Responsibility.** Asynchronous push delivery to FCM/APNs (SYS-03). Purely reactive: it subscribes to domain events and turns them into localized push messages. The *only* module that knows about FCM.

**Offers (sync interface):** none for domain callers. Clients register a device with `PUT /devices {token, platform, locale}` (platform `ANDROID` or `IOS`, locale `ru` or `kk`; upsert by token, a token that moves to another account is reassigned) and remove it with `DELETE /devices` with body `{token}` (only the caller's own; the token stays out of URLs and access logs).

**Subscribes, and who is told:**

| Event | Recipient |
|---|---|
| `TaskAssigned`, `TaskApproved`, `TaskRejected`, `TaskRedoRequested`, `TaskCancelled`, `TaskExpired`, `PenaltyApplied`, `PayoutFulfilled` | teen |
| `TaskSubmitted`, `PayoutRequested` (high priority) | parent |
| `PayoutCancelled` | parent when `TEEN_CANCELLED`; otherwise the teen (`DECLINED`, `EXPIRED`, `UNLINKED`) |
| `FamilyLinked` | both sides |
| `FamilyUnlinked` | teen |

A missed task with a penalty sends the teen the deadline message with the deduction, and `PenaltyApplied` sends its own. Goals publish no events, so they send nothing.

**Localization:** `Messages` renders RU or KK copy from `notification/messages_ru.properties` and `messages_kk.properties`, by the locale stored with the device (unknown means RU). Events carry only ids, so the copy is generic (no titles or names); each push carries a `data` map (`type` plus `taskId` or `payoutId`) for deep links. Amounts come from minor units ("1 500 ₸"). **The Kazakh text needs a native speaker's review.**

**Delivery.** `PushSender` is the only FCM boundary; The sender is selected by `tiyn.notification.sender` (`fcm` or `logging`, **no default**, so a forgotten setting fails at startup). `logging` (`LoggingPushSender`, type and priority only, never the text) is for tests and the `local` profile. `fcm` (`FcmPushSender` over the Firebase Admin SDK) builds a notification plus the `data` map, with high priority on Android and `apns-priority` 10 for payout requests; `UNREGISTERED` and `INVALID_ARGUMENT` become `PushRejectedException` (token deleted), every other FCM error stays transient and is retried. Config: `FCM_CREDENTIALS_FILE` (path to the service-account JSON, mount it as a secret), `FCM_DRY_RUN=true` to validate without delivering. `NotificationService.notify` runs in one transaction: it loads the recipient's devices (none means nothing is claimed and nothing is sent), then claims `(event_key, recipient_id)` in `delivered` (`INSERT ... ON CONFLICT DO NOTHING`); only the claimant sends, so a redelivered or concurrent event never pushes twice. A failure on one device is logged and does not block the others; only if no device could be reached does the exception propagate, which rolls the claim back so the publication stays incomplete and is retried. A token the push service rejects (`PushRejectedException`) is deleted. Trade-off: the send happens inside the listener's transaction (a slow push service holds a pooled connection for that time), and a crash after a successful send but before commit can repeat that push on retry, which is acceptable for a hint. Event keys: `TaskAssigned:{taskId}`, `TaskSubmitted:{taskId}:{attemptNo}`, `TaskRedoRequested:{taskId}:{attemptNo}`, `Task*:{taskId}`, `Payout*:{payoutId}`, `PenaltyApplied:{entryId}`, `Family*:{parentId}:{teenId}:{generation}`. A user with no device is simply skipped.

**Housekeeping.** `DeliveredPurgeTicker` deletes `delivered` rows older than `tiyn.notification.delivered-retention` (env `NOTIFICATION_DELIVERED_RETENTION`, default `P30D`). Stale device tokens are not purged yet.

**Owns:** `device_tokens`, and the `delivered` table, keyed by event and recipient, which makes it idempotent.

---

## 6. Dashboard

**Responsibility.** The teen's home screen in one call (UC-07, TEN-01, TEN-02). It owns no tables and publishes no events; it only composes read contracts of other modules, so the client makes one round trip instead of four (`/balance`, `/tasks`, `/goals`, `/payouts`).

**Endpoint.** `GET /dashboard` (teen only; a parent gets 403):
```json
{
  "balance": {"balanceAmount": 0, "lockedAmount": 0, "availableAmount": 0, "currency": "KZT"},
  "pendingAmount": 0,
  "chores": [],
  "milestones": [],
  "goals": [],
  "openPayout": {"id": "...", "parentId": "...", "amount": 0, "currency": "KZT", "requestedAt": "..."}
}
```
`pendingAmount` is the sum of rewards of `SUBMITTED` tasks. `chores` is every task that is not a milestone; a task inside a milestone carries `milestoneId` so the client nests it. `milestones` carry `approvedTasks` and `totalTasks` ("3 of 5"). `goals` lists active goals with their saved amount. `openPayout` is omitted when the teen has no request open. The dashboard has its own response records; the suppliers' types never cross the module boundary.

**Which tasks.** Open work (`ASSIGNED`, `SUBMITTED`) ordered by deadline, then tasks decided in the last `tiyn.dashboard.recent-window` (env `DASHBOARD_RECENT_WINDOW`, default `P7D`) newest first, capped at `tiyn.dashboard.max-tasks` (env `DASHBOARD_MAX_TASKS`, default 50), so open work is never pushed out by history. "Decided" is `decided_at`, falling back to `updated_at` for tasks that have none (expired). Full history stays on `GET /tasks`.

**Contracts it consumes** (each owned by the supplier, in its root package): `ledger.WalletOverviewReader` (balance, active goals and the open payout, read in one read-only transaction so they are one snapshot), `task.TaskBoardReader` (tasks plus milestone progress in two queries, no N+1), `task.PendingReader`. Pending and balance come from different modules, so for a moment after an approval a reward can show in neither; the UI must not promise "immediately credited".

## Time-driven work

Some state changes happen because time passes, not because a request arrives: expiring overdue tasks, creating the next occurrence of a recurring task, and cancelling stale payout requests. No request wakes the process for these, so something must.

Each module with such work registers a `Ticker` bean and the platform scheduler runs them all:
```java
public interface Ticker {

    void tick(Instant now);
}
```
`tick` must be idempotent and safe to run concurrently with itself: each item is processed in its own short transaction, and transitions are guarded by the FSM (a no-op when the state no longer fits) and the version column. A failure on one item is logged and does not block the rest of the batch. Tickers that take an explicit `now` are tested by calling `tick(Instant)` with chosen instants, never by sleeping.

**Decision: `@Scheduled` plus ShedLock inside the API pods.** `TickScheduler` (in `kz.tiyn.platform.tick`) runs every `Ticker` on a fixed delay (`TICK_INTERVAL`, default one minute). A ShedLock row in `platform.shedlock` makes one replica run each cycle. A ticker that throws is logged and does not stop the others. There is no CronJob: booting a JVM every minute would cost far more than the work, and the lock only avoids duplicate effort, since correctness already comes from idempotent tickers.

Events do not need a ticker to be delivered, but redelivery of failed ones does: `EventResubmitTicker` asks the registry to resubmit publications still incomplete after `tiyn.events.resubmit-after` (default one minute; `docs/events-outbox.md`).

Tickers run one after another under a single lock, so a slow ticker delays the rest. A job that needs its own cadence gets its own `@Scheduled` method and lock name.

---

## Module dependency graph (sync edges only)

```
task.service ──▶ identity.FamilyDirectory
task.service ──▶ media.Storage
dashboard ──▶ ledger.WalletOverviewReader
dashboard ──▶ task.TaskBoardReader
dashboard ──▶ task.PendingReader
notification ──▶ (events of) task, ledger, identity; nothing depends on notification
```
No cycles. All other relationships are event-driven (via Modulith events): task→ledger, task→notification, ledger→notification, identity→ledger, identity→notification.

Keeping the sync graph this thin is deliberate: it is the set of edges you must sever to extract a module. The fewer, the cheaper the extraction.

## Extraction checklist (future — not MVP work)

When a module must become its own service:
1. Its sync interface becomes a network API (gRPC/REST); callers swap the in-process impl for a client.
2. Its event subscriptions/publications externalise the events to a broker with `@Externalized` and `spring-modulith-events-*`; listeners stay as they are.
3. Its tables move with it; because there were no cross-context FKs, nothing in other schemas breaks.
4. Its idempotency guarantees already assume at-least-once, so network redelivery changes nothing.

If every rule in this doc was followed, extraction touches wiring and transport — not domain logic.
