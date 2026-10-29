---
title: "GraphQL in POS payments: a coupling boundary above the system of record"
date: 2026-10-29
description: "Where GraphQL belongs in a POS payments platform, how it reduces stamp coupling between clients and services, and how its resolvers should meet a PostgreSQL or MySQL system of record."
slug: graphql-pos-payments-system-design
image: /diagrams/graphql-pos-payments-architecture.svg
categories: ["Payments", "System Design"]
tags: ["GraphQL", "POS", "coupling", "PostgreSQL", "MySQL", "API design"]
draft: true
---

GraphQL is usually evaluated in payments architecture as a replacement for REST, and occasionally mistaken for a way to let clients query the database. Both framings put it in the wrong place. In a POS payments platform, GraphQL is a contract layer that controls how tightly the platform's many readers (merchant portals, mobile apps, support tools, partner integrations) are coupled to the services and relational stores behind them. Its main structural effect is to move those clients from **stamp coupling**, where each depends on a whole payload, toward data coupling, where each depends only on the fields it selects. It has no role in the authorization path, and it does not replace PostgreSQL or MySQL as the system of record.

This post walks through five mechanisms. It starts with how stamp coupling forms in REST read APIs around orders and transactions, then shows how GraphQL selection sets and field-level usage data change that relationship. It places the GraphQL layer in a POS ecosystem next to the authorization path, event streams, webhooks and settlement files. It then covers how resolvers meet a relational database: batching, keyset pagination, replica reads, idempotent mutations, transaction boundaries and per-merchant isolation, with the differences between PostgreSQL and MySQL that matter for each. It closes with failure modes and design rules.

## Two traffic classes in a POS platform

A POS payments platform carries two kinds of traffic with opposite requirements, and the choice of API style follows from which kind a component serves. The transaction path runs from the terminal through the gateway to the acquirer processor, the card scheme and the issuer. Its messages have fixed layouts (ISO 8583 or a scheme- or host-specific variant), its latency budget is measured in hundreds of milliseconds end to end, and every party already knows exactly which fields it will send and receive. Nothing on that path benefits from a client choosing the shape of a response.

The second class is everything that reads and manages what the transaction path produced. A merchant portal shows today's orders and their latest authorization result. A mobile app shows the last ten payments. A support tool pulls one transaction together with its refunds, disputes, terminal and risk decision. A terminal management console lists devices with their software versions and last heartbeat. A reconciliation screen drills from a settlement batch into its transactions. Partner and ISV integrations each want a different subset of the same data. These clients are numerous, change on their own release cycles, and read related data across several domains. That is the traffic GraphQL was designed for.

## Stamp coupling in payment read APIs

Stamp coupling is the failure mode of the read side, and it is common enough in payment APIs that teams rarely name it. Stevens, Myers and Constantine defined it in 1974 as two modules sharing a whole data structure when each uses only part of it, one step worse than data coupling, where modules share only the individual values they need [1].

A typical REST read endpoint in a payments platform returns the full resource:

```http
GET /transactions/8f3c2a
```

```json
{
  "id": "8f3c2a",
  "merchantId": 1042,
  "terminalId": "T0007731",
  "merchantReference": "ORDER-10001",
  "amount": 49.95,
  "currency": "EUR",
  "status": "APPROVED",
  "authCode": "A1B2C3",
  "rrn": "429312000187",
  "stan": "000187",
  "maskedPan": "411111******1111",
  "cardBrand": "VISA",
  "entryMode": "CONTACTLESS",
  "riskScore": 12,
  "createdAt": "2026-10-29T09:14:03Z"
}
```

The merchant mobile app displays the amount, the status and the last four digits. It still receives, parses and depends on the whole structure, and that dependency has four consequences. The server cannot tell which fields each client uses, so no field can be removed or renamed safely, and payloads only grow. Clients are exposed to changes they do not care about, because a type change in an unused field can still break a strict deserializer. Mobile clients pay bandwidth for data they never read. And teams work around all of this with `?fields=` parameters, `/v2/` paths or one endpoint per screen, each of which adds contract surface that has to be maintained.

Under-fetching produces a second kind of coupling. To show a merchant with its recent transactions and open disputes, the client calls `/merchants/1042`, then `/merchants/1042/transactions?limit=10`, then `/merchants/1042/disputes?status=open`. The client now encodes how the server's resources link together and in what order to call them, so it is coupled to the server's resource layout as well as to its payloads.

## Selection sets as per-client contracts

GraphQL changes the unit of coupling from the resource to the field. The mobile app sends:

```graphql
query RecentPayment($id: ID!) {
  transaction(id: $id) {
    amount
    currency
    status
    card { last4 }
  }
}
```

and receives exactly those fields, nested the same way. The contract between that client and the server is the selection set in its query, which is as close to data coupling as an API gets. Adding `riskScore` or `entryMode` to the `Transaction` type changes nothing for this client, because it never selected them.

The server gains information it never had with fixed payloads, because every request names the fields it reads. Field usage can be recorded per field and per client (persisted query IDs or a client name header make attribution reliable). Retiring a field becomes a measured process: mark it `@deprecated(reason: "...")`, watch its usage fall to zero, then remove it. New types and fields are additive and invisible to existing queries, so the API evolves without `/v2` paths. GitHub documents this model for its public GraphQL API, with scheduled breaking changes announced through a changelog rather than versioned endpoints [2].

Related data arrives in one request, which removes the under-fetching coupling:

```graphql
query MerchantDashboard($id: ID!) {
  merchant(id: $id) {
    name
    transactions(first: 10) {
      edges { node { merchantReference amount currency status } }
    }
    openDisputes { id amount deadline }
    nextPayout { date amount currency }
  }
}
```

The client no longer knows which service owns disputes or that payouts come from a settlement system. The support tool asks for a larger slice of the same graph, the mobile app a smaller one, and no endpoint exists for any single screen.

The same principle applies inside the server, because GraphQL resolves fields on demand. In my `graphql-payments` project, `Merchant` is a Java record with only `id` and `name`. The schema offers `Merchant.orders`, and a separate `@SchemaMapping` resolver supplies it only when a query selects it. A query for `merchant { name }` never touches the orders table, so the merchant code is not stamp-coupled to order data either.

The schema also hides the persistence model. `Order.merchantId` exists in the JPA entity of that project and does not exist in the schema, so no client can come to depend on a foreign key. When the project moved from in-memory Java lists to JPA with H2, the service and repository layers changed completely and no client query changed. That is the property that matters when the store later becomes PostgreSQL or MySQL.

## Where the GraphQL layer sits

The GraphQL layer belongs at the edge of the platform, between the read-and-manage clients and the domain services, and nowhere on the transaction path.

<img src="/diagrams/graphql-pos-edge-placement.svg" alt="POS payments architecture: terminals connect to the gateway and acquirer over ISO 8583 on the transaction path; merchant portal, mobile app, support tool and partner clients connect to a GraphQL gateway, which calls order, transaction, refund, dispute, settlement and terminal-management services, each owning its own PostgreSQL or MySQL database; services publish events to a broker and send webhooks to merchants." />

Two placements are common. A backend-for-frontend (BFF) is a GraphQL service owned by the team that owns the user interfaces, which calls domain services over REST or gRPC [3]. A federated graph lets each domain team own a subgraph (transactions, disputes, settlements) that a gateway composes into one schema, so clients see one API while teams deploy independently [4]. In a smaller system, such as the `graphql-payments` project, the resolvers call an in-process service layer that uses JPA directly. The layering is the same; only the network hops differ.

Each other flow in the platform keeps the protocol built for it:

| Flow | Protocol | Reason |
|---|---|---|
| Terminal → gateway → acquirer → scheme | ISO 8583 or host/scheme formats | Fixed messages, strict latency budgets, certified implementations |
| Service-to-service calls | gRPC or REST | Known contracts, no need for client-selected shapes |
| Internal events (authorized, captured, refunded) | Message broker (Kafka, RabbitMQ) | Asynchronous, durable, decoupled in time |
| Notifications to merchants | Webhooks | Push-based and retryable; the merchant does not poll |
| Settlement and reconciliation files | Batch files over SFTP or object storage | Large volumes, scheduled, often scheme-defined formats |
| Portals, apps, support tools, partner reads | GraphQL | Varied clients reading related data across domains |
| Simple public payment creation | REST with idempotency keys | Well-known retry semantics for integrators [5][6] |

The last row is a judgment call. GraphQL mutations can create payments, but a write in payments must be idempotent, and REST has established conventions for that in the `Idempotency-Key` header [5][6]. In GraphQL the idempotency key has to be designed into the mutation input, which the next section covers.

## GraphQL and the relational system of record

GraphQL is not a database query language. It is a query language over an application-defined graph, and resolvers translate each selected field into calls on services, which in turn issue SQL. PostgreSQL or MySQL remains the system of record for orders, transactions and ledger entries, with ACID transactions, constraints and indexes doing the work they always did. The design questions sit at the boundary between the resolver tree and SQL.

### The schema is not the table layout

A schema generated one-to-one from tables reproduces stamp coupling one layer down. Every column becomes a field, every column rename becomes a breaking API change, and internal codes such as raw ISO 8583 response codes or database status integers leak to clients that will start to depend on them. The schema should describe the domain the clients need (a `Transaction` with a `status` enum and an `authorization` object), and the mapping to tables should stay inside the service layer, where a migration can change it without touching a query.

### Resolver fan-out and the N+1 problem

Field-by-field resolution is what gives clients their narrow contracts, and it is also what multiplies database round trips. A query for 50 merchants that selects `orders` on each runs one query for the merchants and then one per merchant: 51 queries. Against a connection pool sized for a few dozen connections, a handful of such requests in parallel can exhaust the pool and stall unrelated traffic.

The fix is batch loading. The DataLoader pattern collects the keys requested within one execution step and issues a single lookup for all of them [7]. In Spring for GraphQL, `@BatchMapping` does this with one method [8]:

```java
@BatchMapping
public Map<Merchant, List<Order>> orders(List<Merchant> merchants) {
    List<Long> ids = merchants.stream().map(Merchant::id).toList();
    Map<Long, List<Order>> byMerchant = orderRepository.findByMerchantIdIn(ids)
            .stream()
            .collect(Collectors.groupingBy(Order::getMerchantId));
    return merchants.stream().collect(Collectors.toMap(
            m -> m,
            m -> byMerchant.getOrDefault(m.id(), List.of())));
}
```

The repository call becomes one statement, `WHERE merchant_id IN (...)` on either database, or `WHERE merchant_id = ANY(?)` with an array parameter on PostgreSQL, which keeps one prepared statement for any batch size. Both depend on an index on `orders(merchant_id)`.

### Keyset pagination on transaction tables

Transaction tables grow without bound, so every list field needs pagination, and the choice of pagination decides the SQL. Offset pagination (`LIMIT 20 OFFSET 10000`) makes the database scan and discard every skipped row, and pages shift when new transactions arrive between requests. Keyset pagination uses the last row's sort key as the cursor, which the Relay connection specification exposes as opaque `after` cursors on `edges` [9]:

```sql
-- Portable form (PostgreSQL and MySQL)
SELECT id, merchant_reference, amount, currency, status, created_at
FROM transactions
WHERE merchant_id = ?
  AND (created_at < ? OR (created_at = ? AND id < ?))
ORDER BY created_at DESC, id DESC
LIMIT 21;  -- one extra row tells the resolver whether hasNextPage is true
```

With a composite index on `(merchant_id, created_at, id)`, each page costs the same regardless of depth. PostgreSQL also accepts the row-value form `(created_at, id) < (?, ?)`. The cursor should be an encoded `(created_at, id)` pair, opaque to the client, so the sort key can change later without a schema change.

### Replica reads and read-your-writes

Portal and support traffic is read-heavy, so routing GraphQL queries to read replicas is a natural way to protect the primary that serves writes. Asynchronous replication introduces lag, which means a client that creates an order and immediately queries for it may not find it on a replica. GraphQL reduces this problem through the shape of mutations: a mutation returns an object with its own selection set, so the client gets the created order, as committed on the primary, in the mutation response and has no reason to re-query. Queries that must reflect a just-committed write (for example a refund screen after submission) should be routed to the primary explicitly.

For aggregate screens such as a merchant dashboard, resolvers can read from a separate read model: denormalized tables or materialized views maintained from the event stream. This is the CQRS split [10], and GraphQL fits it well because the schema hides whether a field comes from the system of record or a projection.

### Mutations, idempotency and transaction boundaries

The GraphQL specification executes the top-level fields of a mutation serially, in order, while query fields may execute in parallel [11]. Serial execution is not atomicity. A request containing `createRefund` and `updateOrderNote` runs two resolvers, and if the second fails the first has already committed. Each mutation field should therefore be one service call with its own database transaction, and anything that must be atomic belongs in a single mutation field.

Idempotency is enforced in the database, with the key carried in the mutation input:

```graphql
input CreateRefundInput {
  transactionId: ID!
  amount: Money!
  idempotencyKey: String!
}
```

```sql
-- Unique constraint makes a retried request a no-op
ALTER TABLE refunds
  ADD CONSTRAINT uq_refund_idem UNIQUE (merchant_id, idempotency_key);

-- PostgreSQL
INSERT INTO refunds (merchant_id, idempotency_key, transaction_id, amount_minor, currency)
VALUES (?, ?, ?, ?, ?)
ON CONFLICT (merchant_id, idempotency_key) DO NOTHING;

-- MySQL
INSERT INTO refunds (merchant_id, idempotency_key, transaction_id, amount_minor, currency)
VALUES (?, ?, ?, ?, ?)
ON DUPLICATE KEY UPDATE id = id;
```

After the insert, the service reads the row by `(merchant_id, idempotency_key)` and returns it, so a retry receives the original refund rather than a second one [12][13]. A complete implementation also stores a hash of the request parameters and rejects a reused key with different parameters, which is the behavior the IETF draft describes [6].

### Consistency across one query

A single GraphQL query can touch several tables through several resolvers, and by default nothing guarantees they see the same snapshot. PostgreSQL defaults to READ COMMITTED, where each statement sees data committed before it started [14]. MySQL's InnoDB defaults to REPEATABLE READ, but the consistent snapshot only spans one transaction [15], and resolvers in a Spring application often run in separate transactions or on separate connections. A dashboard can therefore show a "today's total" that does not match the transactions listed beneath it if a capture commits between the two resolvers. Where that matters, the screen's aggregate and its list should come from one service call in one read-only transaction, or from the same read-model snapshot.

### Per-merchant isolation

In a GraphQL API, access control cannot be attached to URLs, because every request goes to one URL and can reach any object in the graph. Authorization belongs in the service layer, checked per object: every resolver that loads a transaction verifies that it belongs to the caller's merchant. PostgreSQL adds a second line of defense with row-level security [16]:

```sql
ALTER TABLE transactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE transactions FORCE ROW LEVEL SECURITY;

CREATE POLICY merchant_isolation ON transactions
  USING (merchant_id = current_setting('app.merchant_id')::bigint);
```

The application sets `app.merchant_id` at the start of each transaction (`SET LOCAL`), and a resolver that forgets its check still cannot read another merchant's rows. `FORCE` matters because table owners bypass policies otherwise. MySQL has no native row-level security, so the equivalent is a mandatory `merchant_id` predicate in every repository method, enforced by code review and tests, or per-merchant views.

### PostgreSQL and MySQL differences that touch the GraphQL layer

| Concern | PostgreSQL | MySQL (InnoDB) |
|---|---|---|
| Default isolation | READ COMMITTED [14] | REPEATABLE READ [15] |
| Idempotent insert | `ON CONFLICT ... DO NOTHING` [12] | `ON DUPLICATE KEY UPDATE` [13] |
| Batched key lookup | `= ANY(?)` with array parameter, or `IN (...)` | `IN (...)` |
| Row-level tenant isolation | Native RLS policies [16] | Application predicates or views |
| Keyset predicate | Row-value comparison or expanded form | Expanded form is the safe default |
| Semi-structured fields (e.g. EMV tag data for support views) | `JSONB` with GIN indexes | `JSON` with generated-column indexes |

Neither column makes one database the right choice for a GraphQL API. The GraphQL layer is indifferent to the store as long as resolvers go through a service layer, which is why the `graphql-payments` roadmap can move from H2 to PostgreSQL without a client noticing.

## Failure modes

GraphQL moves coupling into the schema rather than removing it, and most production problems come from forgetting that. Clients depend on type names, field names and field meanings. Renaming `merchantReference` breaks every query that selects it, exactly as renaming a JSON key would.

Semantic changes are the most dangerous, because the schema cannot see them. If `amount` changes from major units (`49.95`) to minor units (`4995`), every query still validates and every client is now wrong. The problem gets worse across currencies, because ISO 4217 defines different minor-unit exponents (two for EUR, zero for JPY, three for BHD) [17]. A `Money` type that carries an integer amount in minor units together with the currency code removes the ambiguity at the schema level.

Monitoring built for REST misses GraphQL failures. A GraphQL response usually returns HTTP 200 with an `errors` array and possibly partial data, so dashboards that alert on 5xx rates see nothing while a resolver fails on every request [18]. Error rates have to be measured from response bodies, per operation and per field.

Unbounded queries turn one request into an expensive workload. A query that nests `merchant → transactions → refunds → transaction → merchant` can multiply rows at each level. Depth limits, complexity scoring, maximum page sizes and, for first-party clients, an allow-list of persisted queries keep cost predictable. Rate limits have to count query cost, because one request can cost one unit of work or ten thousand.

Card data in the schema expands compliance scope. Any API that can return a full PAN is in PCI DSS scope along with everything connected to it [19]. Masked PAN and last four digits are the most a read schema should expose, and the decision should be made in the schema review, not left to a resolver.

Introspection is a reconnaissance tool when left open on a public endpoint, because it hands the full schema, including deprecated and internal-looking fields, to anyone. For partner APIs it is often published deliberately; for first-party BFFs it is usually disabled in production.

## Design guidance

The rules below follow from the mechanisms above, in order of how early in a project they need to be decided.

Put GraphQL at the edge and keep it off the transaction path. Its value comes from serving varied clients that read related data, and the authorization path has neither property. Terminal traffic, internal events, webhooks and settlement files keep their own protocols.

Design the schema from client needs and keep it independent of tables. Mapping types to tables gives back the stamp coupling GraphQL was meant to remove, now with a database schema as the shared structure. Use enums for statuses, a `Money` type for amounts and opaque IDs for every object.

Treat field usage as an operational signal. Attribute every request to a client, record field usage, and retire fields through `@deprecated` and measured usage rather than through versions.

Batch every resolver that runs per parent object, and paginate every list with keyset cursors. Index for the batched lookups and the cursor predicate before the first portal ships.

Make each mutation field one service call in one database transaction, and carry an idempotency key on every mutation that moves money or changes payment state. Enforce the key with a unique constraint in PostgreSQL or MySQL, not in application memory.

Authorize per object in the service layer, and on PostgreSQL add row-level security as a second barrier. On MySQL, make the merchant predicate impossible to omit in the repository layer.

Measure GraphQL health from response bodies, limit query cost, and keep full card data out of the schema.

## What this means for a POS payments platform

The structural argument for GraphQL in payments is narrower than the usual REST-versus-GraphQL debate suggests. It does not make transactions faster, and it does not replace the relational store. It changes who owns the shape of read data: each client owns its selection set, the server owns the schema, and the database schema stays private to the services. That split is what lets a platform add a dispute view to the support tool, a payout widget to the mobile app and a new partner integration without versioning an endpoint or touching a table.

The cost is that the schema becomes the most important shared artifact in the platform, and the database work does not go away. N+1 fan-out, pagination, replica lag, transaction boundaries and tenant isolation all reappear at the resolver boundary and need the same engineering they would need anywhere else. A platform that treats the schema as a reviewed public contract, and the resolver-to-SQL boundary as a performance and security boundary, gets data coupling at the edge with PostgreSQL or MySQL still doing what they do best underneath.

## References

1. W. P. Stevens, G. J. Myers, L. L. Constantine, "Structured Design," *IBM Systems Journal*, vol. 13, no. 2, 1974, pp. 115–139.
2. GitHub, "Breaking changes" and "About the GraphQL API," GitHub Docs. https://docs.github.com/en/graphql
3. Sam Newman, "Backends For Frontends." https://samnewman.io/patterns/architectural/bff/
4. Apollo, "Apollo Federation" documentation. https://www.apollographql.com/docs/federation/
5. Stripe, "Idempotent requests," Stripe API reference. https://docs.stripe.com/api/idempotent_requests
6. IETF HTTPAPI Working Group, "The Idempotency-Key HTTP Header Field," Internet-Draft draft-ietf-httpapi-idempotency-key-header. https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/
7. GraphQL Foundation, DataLoader. https://github.com/graphql/dataloader
8. Spring for GraphQL reference documentation, "Batch Mapping." https://docs.spring.io/spring-graphql/reference/controllers.html
9. Relay, "GraphQL Cursor Connections Specification." https://relay.dev/graphql/connections.htm
10. Martin Fowler, "CQRS." https://martinfowler.com/bliki/CQRS.html
11. GraphQL Specification, October 2021 edition, section 6.2.2 "Mutation." https://spec.graphql.org/October2021/
12. PostgreSQL documentation, "INSERT," ON CONFLICT clause. https://www.postgresql.org/docs/current/sql-insert.html
13. MySQL 8.0 Reference Manual, "INSERT ... ON DUPLICATE KEY UPDATE Statement." https://dev.mysql.com/doc/refman/8.0/en/insert-on-duplicate.html
14. PostgreSQL documentation, "Transaction Isolation." https://www.postgresql.org/docs/current/transaction-iso.html
15. MySQL 8.0 Reference Manual, "Transaction Isolation Levels." https://dev.mysql.com/doc/refman/8.0/en/innodb-transaction-isolation-levels.html
16. PostgreSQL documentation, "Row Security Policies." https://www.postgresql.org/docs/current/ddl-rowsecurity.html
17. ISO 4217, *Codes for the representation of currencies*. https://www.iso.org/iso-4217-currency-codes.html
18. GraphQL Foundation, "GraphQL over HTTP" specification (draft). https://graphql.github.io/graphql-over-http/
19. PCI Security Standards Council, *PCI DSS v4.0.1*. https://www.pcisecuritystandards.org/document_library/
