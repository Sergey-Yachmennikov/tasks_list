# Block Lender Limit — Flow

How `LenderService.blockLenderLimit(id)` moves an `Application` from `SCORING_APPROVED`
to `LIMIT_BLOCKED`, and how the resulting `ApplicationLimitBlocked` event reaches Kafka
via the transactional outbox.

Two things are deliberately decoupled here:

- The call to the **external lender** happens outside any DB transaction — it's slow and
  unreliable, and a DB transaction should only ever wrap fast local work.
- **Publishing to Kafka** happens outside the request entirely. The request only writes a
  row to the outbox table, in the same local transaction as the status update. A separate
  poller (`OutboxMessageProducer`) relays outbox rows to Kafka on its own schedule. This is
  what avoids the dual-write problem: a single DB commit is atomic, a DB commit plus a
  Kafka publish is not.

```mermaid
sequenceDiagram
    autonumber
    actor Client
    participant Controller as ApplicationRestController
    participant Svc as LenderService
    participant AppRepo as ApplicationRepository (DB)
    participant Lender as LenderClient (external)
    participant OutboxRepo as OutboxRepository (DB)

    Client->>Controller: POST /api/v1/applications/{id}/block-limit
    Controller->>Svc: blockLenderLimit(id)
    Svc->>AppRepo: findById(id)
    AppRepo-->>Svc: Application

    alt already LIMIT_BLOCKED
        Svc-->>Controller: no-op (idempotent retry)
        Controller-->>Client: 204 No Content
    else status is not SCORING_APPROVED
        Svc-->>Controller: throw InvalidApplicationStatusException
        Controller-->>Client: 409 Conflict (ProblemDetail)
    else status is SCORING_APPROVED
        Note over Svc,Lender: outside any DB transaction
        Svc->>Lender: blockLimit(lenderId, appId, amount,<br/>requestId = "app-limit-block:"+id)

        alt lender call fails
            Lender-->>Svc: exception
            Svc-->>Controller: throw LenderBlockingException
            Controller-->>Client: 502 Bad Gateway (ProblemDetail)
        else lender call succeeds
            Lender-->>Svc: LenderBlockResult(blockId)

            Note over Svc,OutboxRepo: local transaction — DB only, short-lived
            Svc->>AppRepo: compareAndSetLimitBlocked(id,<br/>expected=SCORING_APPROVED, blockId)

            alt lost the race (already transitioned concurrently)
                AppRepo-->>Svc: false
                Note right of Svc: requestId made the lender call idempotent,<br/>so the limit is safely blocked either way
                Svc-->>Controller: no-op
                Controller-->>Client: 204 No Content
            else won the compare-and-set
                AppRepo-->>Svc: true — status is now LIMIT_BLOCKED
                Svc->>OutboxRepo: save(OutboxMessage, topic=application-limit-blocked)
                Note right of OutboxRepo: same transaction as the status update —<br/>commits or rolls back together
                Svc-->>Controller: done
                Controller-->>Client: 204 No Content
            end
        end
    end
```

`ApplicationNotFoundException` (application id doesn't exist) follows the same shape as the
other error branches: thrown by `LenderService`, mapped by `ApplicationExceptionHandler` to
`404 Not Found`. Omitted from the diagram above only because it short-circuits before any
branch shown — it's the very first thing checked after `findById`.

## Outbox relay (async, decoupled)

Runs independently of the request above — on its own schedule, potentially on a different
service instance. `lockBatchForPublishing` uses `SELECT ... FOR UPDATE SKIP LOCKED` so
multiple instances share the backlog instead of racing on the same rows.

```mermaid
sequenceDiagram
    autonumber
    participant Poller as OutboxMessageProducer
    participant OutboxRepo as OutboxRepository (DB)
    participant Kafka

    loop every poll-interval-ms
        Poller->>OutboxRepo: lockBatchForPublishing(limit)
        OutboxRepo-->>Poller: batch of NEW messages
        loop each message
            Poller->>Kafka: send(topic, key = applicationId, payload)
            alt publish succeeds
                Poller->>OutboxRepo: markSent(id)
            else publish fails, retries remaining
                Poller->>OutboxRepo: recordFailure(id, error)
                Note right of OutboxRepo: status stays NEW, retryCount++ —<br/>picked up again on a later poll
            else publish fails, retries exhausted
                Poller->>OutboxRepo: markDeadLettered(id, error)
                Note right of OutboxRepo: terminal state, excluded from future batches —<br/>needs a dead-letter path/alert (not yet wired up)
            end
        end
    end
```

Because delivery is at-least-once (a crash between `send` and `markSent` can redeliver),
consumers of `application-limit-blocked` must dedupe on `applicationId`.
