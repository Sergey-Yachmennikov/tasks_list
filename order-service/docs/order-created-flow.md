# Order Created — Flow

Отвечает на ТЗ "Асинхронное взаимодействие микросервисов": как перестать терять заказы и
деньги из-за того, что `AnalyticsService` периодически тормозит/таймаутит.

## Было (проблема из ТЗ)

`OrderService` синхронно вызывает `AnalyticsService.TrackOrder` внутри пути обработки
запроса и ждёт ответа, прежде чем ответить пользователю. Когда `AnalyticsService` подвисает
("Too long action"), `OrderService` не успевает ответить вовремя — клиент/gateway обрывает
соединение, и заказ (а с ним деньги) теряется, хотя с самим заказом всё было в порядке.

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant OrderService
    participant AnalyticsService

    User-->>OrderService: CreateOrder
    OrderService-->>AnalyticsService: TrackOrder
    AnalyticsService->>AnalyticsService: Too long action
    AnalyticsService-->>OrderService: (ответ, но слишком поздно)
    OrderService-->>User: (таймаут / потерянный заказ)
```

## Стало (это реализация в этом модуле)

`OrderService` в одной локальной транзакции сохраняет заказ и пишет строку в transactional
outbox, затем сразу отвечает пользователю — без единого сетевого вызова к `AnalyticsService`
в этом пути. Отдельный асинхронный поллер (`OutboxMessageProducer`, тот же паттерн, что в
`lending-application-service`) релеит событие `OrderCreatedEvent` в Kafka-топик
`order-created`; `AnalyticsService` (внешняя система, вне этого репозитория) вычитывает его
в своём темпе, дедуплицируя по `orderId`, поскольку доставка at-least-once.

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant Controller as OrderRestController
    participant Svc as OrderCreationService
    participant OrderRepo as OrderRepository (DB)
    participant OutboxRepo as OutboxRepository (DB)
    participant Poller as OutboxMessageProducer
    participant Kafka
    participant Analytics as AnalyticsService (внешний)

    User->>Controller: POST /api/v1/orders
    Controller->>Svc: createOrder(command)

    alt items пуст или невалиден
        Svc-->>Controller: throw InvalidOrderRequestException
        Controller-->>User: 400 Bad Request (ProblemDetail)
    else запрос валиден
        Note over Svc,OutboxRepo: одна короткая локальная транзакция — без сетевого I/O внутри
        Svc->>OrderRepo: save(order)
        Svc->>OutboxRepo: save(OutboxMessage, topic=order-created)
        Svc-->>Controller: Order
        Controller-->>User: 201 Created
        Note right of User: ответ не ждёт AnalyticsService вообще — его здесь нет в пути запроса
    end

    loop асинхронно, каждые poll-interval
        Poller->>OutboxRepo: lockBatchForPublishing(limit)
        OutboxRepo-->>Poller: batch of NEW messages
        Poller->>Kafka: send(order-created, key=orderId, payload)
        Kafka-->>Analytics: consume (в своём темпе, at-least-once)
        Note right of Analytics: дедуп по orderId — сообщение может прийти повторно
    end
```

Если `AnalyticsService` временами тормозит или вообще ненадолго лежит — событие просто
дольше лежит в outbox/Kafka и будет обработано позже; ответ пользователю на создание заказа
это никак не задерживает и не блокирует. `OutboxMessageProducer` уже умеет ретраить с backoff
и переводить в dead-letter после исчерпания попыток — то же самое поведение, что и в
`lending-application-service` (см. его `docs/block-lender-limit-flow.md`), логика не менялась.
