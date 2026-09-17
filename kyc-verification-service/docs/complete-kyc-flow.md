# Complete KYC — Flow

How `KycService.completeKyc(applicationId, clientId)` moves an `Application` from
`KYC_PENDING` to `KYC_COMPLETED` after confirming KYC status with the external provider
and notifying the client by SMS.

The SMS is sent **before** the status transition is committed, not after. Notification is
mandatory here — a failed send must surface as an error — so if the status were committed
first, a failed SMS would leave the application in `KYC_COMPLETED` already, and a client
retry would hit the idempotent no-op branch below and never re-attempt the SMS. Sending
first means a failed SMS leaves the application untouched at `KYC_PENDING`, so a retry
correctly redoes both steps.

One accepted trade-off from this ordering: unlike `LenderClient.blockLimit` in
`lending-application-service` (which takes a deterministic `requestId`),
`NotificationClient.sendSms` has no idempotency key. Two genuinely concurrent calls for
the same application could both pass the status check and both send an SMS before only
one of them wins the `compareAndSet` below. This is a narrow race, and unlike a duplicate
lender-limit block, the cost of hitting it is a duplicate text message, not duplicated
money — so it's accepted rather than closed with an extra claim-state machine.

```mermaid
sequenceDiagram
    autonumber
    actor Client
    participant Controller as ApplicationRestController
    participant Svc as KycService
    participant AppRepo as ApplicationRepository (DB)
    participant Kyc as KycClient (external)
    participant Notify as NotificationClient (external)

    Client->>Controller: POST /api/v1/applications/{id}/complete-kyc {clientId}
    Controller->>Svc: completeKyc(id, clientId)
    Svc->>AppRepo: findById(id)
    AppRepo-->>Svc: Application

    alt already KYC_COMPLETED
        Svc-->>Controller: no-op (idempotent retry)
        Controller-->>Client: 200 OK
    else status is not KYC_PENDING
        Svc-->>Controller: throw InvalidApplicationStatusException
        Controller-->>Client: 409 Conflict (ProblemDetail)
    else clientId does not match application.clientId
        Svc-->>Controller: throw ApplicationClientMismatchException
        Controller-->>Client: 403 Forbidden (ProblemDetail)
    else status is KYC_PENDING and client matches
        Svc->>Kyc: fetchStatus(kycSessionId)
        Kyc-->>Svc: KycResult(completed, details)

        alt KYC not completed at provider
            Svc-->>Controller: throw KycNotCompletedException
            Controller-->>Client: 409 Conflict (ProblemDetail)
        else KYC completed at provider
            Svc->>Notify: sendSms(clientId, text)
            Notify-->>Svc: NotificationResult

            alt notification failed (ValidationError)
                Svc-->>Controller: throw NotificationFailedException
                Controller-->>Client: 502 Bad Gateway (ProblemDetail)
                Note right of AppRepo: status untouched — still KYC_PENDING,<br/>retry redoes both steps
            else notification succeeded
                Svc->>AppRepo: compareAndSetKycCompleted(id, expected=KYC_PENDING)

                alt lost the race (already transitioned concurrently)
                    AppRepo-->>Svc: false
                    Note right of Svc: SMS for this request was still sent —<br/>accepted duplicate-notification risk, see above
                else won the compare-and-set
                    AppRepo-->>Svc: true — status is now KYC_COMPLETED
                end
                Svc-->>Controller: Application (status=KYC_COMPLETED)
                Controller-->>Client: 200 OK
            end
        end
    end
```

`ApplicationNotFoundException` (application id doesn't exist) follows the same shape as
the other error branches: thrown by `KycService`, mapped by `ApplicationExceptionHandler`
to `404 Not Found`. Omitted from the diagram above only because it short-circuits before
any branch shown — it's the very first thing checked after `findById`.
