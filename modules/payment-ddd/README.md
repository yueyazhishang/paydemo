# Payment DDD (research)

This module is a DDD-focused demo for research into cross-border payment aggregator design.

Important note
- This repository is research-focused. We prioritize domain modeling, aggregates, domain events, and Saga examples. External SDKs and real HTTP integrations are included only as examples and can be removed. You don't need the system to be runnable to benefit from the DDD artifacts.

What is included
- Domain: Payment & Refund aggregates, value objects, domain events
- Application: CreatePaymentService, ProcessWebhookService, RefundService
- Infra: ChannelAdapter interface and example adapters (Stripe, PayPal); PayPal includes a sandbox-style client but is optional for research
- Event bus: InMemoryEventBus demonstrating domain event publication & subscription
- Saga: RefundSaga demonstrating a simple process manager
- Tests: domain-level tests and adapter-contract style unit tests
- DESIGN.md: high-level design notes, sequence flows, bounded contexts and trade-offs

If you are interested in a smaller, dependency-free artifact for publication or peer review, I can remove external SDKs and keep only the domain & contract tests. Currently the code contains example integrations but those are clearly marked.
