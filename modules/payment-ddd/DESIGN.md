# Payment DDD — Design Notes

This DESIGN.md captures the DDD-oriented design decisions, domain model, bounded contexts and key flows for the payment-demo module. It's written for research into DDD applied to cross-border payments.

1. Goals
- Focus on domain-first design: aggregates, value objects, domain services, domain events, repositories (contracts), and process managers (Sagas).
- Provide adapters as contracts/stubs to demonstrate the anti-corruption layer; external SDKs or HTTP implementations are optional examples.
- Demonstrate cross-cutting concerns relevant to cross-border payments: routing, currency, idempotency, saga-based refund, webhook normalization, and event-driven orchestration.

2. Bounded Contexts (in this module)
- Payment Context (core domain)
  - Aggregates: Payment, Refund
  - Domain services: RoutingService (example), Idempotency (conceptual)
  - Domain events: PaymentAuthorizedEvent, PaymentCompletedEvent, RefundInitiatedEvent, RefundCompletedEvent
- Integration / Adapter Context (infra)
  - ChannelAdapter interface for all payment channels
  - Concrete adapters: StripeAdapter (example using SDK), PaypalAdapter (example/sandbox client), Domestic adapters (stubs)
- Process Manager / Saga Context
  - RefundSaga: subscribes to RefundInitiatedEvent and orchestrates refund processing (demo synchronous implementation)
- Shared infra
  - InMemoryEventBus (for research/demo) to demonstrate pub/sub and Saga wiring

3. Aggregates and key invariants
- Payment (aggregate root)
  - identity: id, orderId
  - invariant: amount, currency are immutable for the lifecycle represented by a single Payment aggregate
  - state transitions (simplified): CREATED -> PENDING -> AUTHORIZED -> COMPLETED | FAILED | CANCELLED
  - externalId: the channel-specific capture/transaction id
  - concurrency: optimistic locking via @Version
- Refund (aggregate root)
  - paymentId reference (by id) — for demo kept simple (not a full aggregate relationship)
  - states: INITIATED -> PROCESSING -> COMPLETED | FAILED

4. Domain Events and EventBus
- Events are simple POJOs in domain.events package.
- InMemoryEventBus demonstrates publish/subscribe for domain events; in production replace with durable bus (Kafka/Rabbit) and an outbox pattern.

5. Saga: refund orchestration (demo)
- Flow:
  1) Client requests refund -> RefundService.initiateRefund creates Refund entity and publishes RefundInitiatedEvent
  2) RefundSaga (subscribed) receives RefundInitiatedEvent and invokes RefundService.processRefund
  3) ProcessRefund calls adapter.refund(...) and updates Refund state (COMPLETED/FAILED) and publishes RefundCompletedEvent or RefundFailedEvent
- Notes: For research, the Saga is synchronous to simplify demonstration. For realistic systems, Saga should be asynchronous, idempotent and retryable.

6. Adapter contract (Anti-Corruption Layer)
- ChannelAdapter defines:
  - CreateResult createPayment(CreatePaymentCommand)
  - String handleWebhook(String payload, String signatureHeader)
  - (Optional) refund
- Adapters must map channel-specific models to a small, stable contract used by the domain layer (e.g., externalId, success flag, raw response).

7. Webhook normalization
- Each adapter is responsible for verifying and normalizing incoming webhook payloads into a minimal shape that the application layer can consume (e.g. {type, id, raw}).
- ProcessWebhookService demonstrates mapping of normalized webhook events to domain commands/updates.

8. Idempotency and concurrency
- Idempotency is handled at the application service boundary using orderId (demo). For real systems, an explicit idempotency key and store should be used.
- Repository uses optimistic locking (@Version) to demonstrate concurrent update protection.

9. Tests and contract tests
- Domain unit tests cover aggregate behaviors and basic lifecycle transitions.
- Adapter contract tests (in test/ directory) validate that CreatePaymentService works with an adapter implementation that satisfies the ChannelAdapter contract.

10. Research trade-offs & intentionally omitted
- This module intentionally deprioritizes production-grade infra: security, certificate management, PCI, high-availability, real webhooks listeners, and outbox patterns.
- External SDK usage (e.g., stripe-java) remains as an example and can be removed if you prefer a dependency-free research artifact.

Sequence (simplified) — Payment creation through adapter

Client -> API (CreatePaymentService)
  - verify idempotency -> create Payment aggregate (CREATED) -> persist
  - routing -> choose adapter -> adapter.createPayment(command)
  - adapter returns CreateResult -> Payment.markAuthorized(externalId) -> Payment.markCompleted() -> publish PaymentAuthorizedEvent

Sequence (simplified) — Refund Saga

Client -> RefundService.initiateRefund -> create Refund (INITIATED) -> publish RefundInitiatedEvent
RefundSaga -> on RefundInitiatedEvent -> RefundService.processRefund
RefundService.processRefund -> call adapter.refund -> update Refund state -> publish RefundCompletedEvent

11. Next research tasks (if desired)
- Replace InMemoryEventBus with outbox + durable messaging
- Implement adapter contract tests for all channels (mocked responses) demonstrating router behavior under failures
- Implement domain-level compensations for multi-channel flows (e.g., route failover)

