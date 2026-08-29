# payment-ddd: DDD demo module for payments

This module is a minimal Java + Spring Boot implementation to demonstrate DDD patterns applied to a payment gateway aggregator.

Highlights:
- Domain layer with Payment aggregate and domain behaviors
- Application service that orchestrates channel adapters
- Infrastructure adapters for Stripe (real SDK demo) and PayPal (stub)
- Webhook endpoints that receive channel callbacks and normalize them via adapters

Run (local):
- Start Postgres: docker-compose up -d
- Build & run:
  mvn -f modules/payment-ddd/pom.xml spring-boot:run

Env:
- Copy modules/payment-ddd/.env.example -> .env and set STRIPE_API_KEY to your Stripe test key if you want to exercise Stripe flows.

Notes:
- This is a research/demo project focusing on DDD structure. Security, production hardening, and full channel integrations are intentionally minimal.
