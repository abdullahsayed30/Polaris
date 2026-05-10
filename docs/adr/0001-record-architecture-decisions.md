# 0001 - Record Architecture Decisions

Date: 2026-05-09

## Status

Accepted

## Context

Polaris is meant to be readable as a serious reference architecture, not just a collection of framework examples. The project will make several architectural choices that deserve a short explanation: service boundaries, database ownership, Kafka event choreography, gRPC contracts, gateway responsibilities, deployment packaging, and observability defaults.

## Decision

We will record significant architecture decisions as lightweight ADRs in `docs/adr` using the Nygard format: title, status, context, decision, and consequences. Each ADR should be short enough to read during a code review and concrete enough to explain why the choice was made for Polaris.

## Consequences

Future contributors and reviewers can understand the reasoning without reconstructing it from code. The tradeoff is a small documentation burden whenever the architecture changes, but that burden is intentional because this repository is also a portfolio artifact.
