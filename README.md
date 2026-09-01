# GraphQL Payments

`graphql-payments` is a small Spring Boot learning project for understanding GraphQL through a payments-style domain. The current API can list orders, find one order, create an order, find a merchant, and resolve a merchant's orders as a nested GraphQL field.

The project deliberately uses in-memory data for now. This keeps the focus on GraphQL's schema and resolver model before persistence, batching, and database concerns are introduced.

## Technology stack

- Java 21
- Maven
- Spring Boot 4.1.x
- Spring Web
- Spring for GraphQL
- Spring Data JPA
- H2 Database dependency (included, but not used yet)
- Package root: `com.corebaseit.graphqlpayments`

## What has been built

The GraphQL API currently supports:

- Reading all orders with `Query.orders`
- Reading one order by ID with `Query.order`
- Creating an order with `Mutation.createOrder`
- Reading one merchant by ID with `Query.merchant`
- Resolving `Merchant.orders` separately with `@SchemaMapping`
- Supplying mutation and query values through GraphQL variables
- Selecting only the response fields the client needs

Two merchants are available in memory:

| ID | Name | Current orders |
|---|---|---|
| `1` | Corebase Shop | Orders `1` and `2` |
| `2` | Tech Store | None |

The initial orders are:

| ID | Merchant ID | Reference | Amount | Currency | Status |
|---|---|---|---:|---|---|
| `1` | `1` | `ORDER-10001` | 49.95 | EUR | APPROVED |
| `2` | `1` | `ORDER-10002` | 125.00 | EUR | PENDING |

New orders receive an incrementing ID, start with `PENDING` status, and are currently assigned to merchant `1`.

## Running the project

From the project root, start the application with:

```bash
./mvnw spring-boot:run
```

The GraphQL API is exposed at:

```text
POST http://localhost:8080/api/graphql
```

Open the development client at:

```text
http://localhost:8080/graphiql?path=/api/graphql
```

GraphiQL is a client for exploring the API. It is not the API endpoint itself: the page loads in the browser and sends GraphQL operations to `/api/graphql`.

The relevant application configuration is:

```properties
spring.application.name=graphql-payments
spring.graphql.graphiql.enabled=true
spring.graphql.http.path=/api/graphql
```

Working operations are collected in [`graphiql-queries.graphql`](graphiql-queries.graphql). Paste an operation into GraphiQL, add the accompanying JSON to its **Variables** panel when required, and select the operation to run.

Because the data is held in memory, restarting the application restores the two original orders and resets the generated order IDs.

## Project structure

```text
graphql-payments/
├── pom.xml
├── README.md
├── graphiql-queries.graphql
└── src/
    └── main/
        ├── java/
        │   └── com/corebaseit/graphqlpayments/
        │       ├── GraphqlPaymentsApplication.java
        │       ├── merchant/
        │       │   ├── Merchant.java
        │       │   └── MerchantController.java
        │       └── order/
        │           ├── CreateOrderInput.java
        │           ├── Order.java
        │           ├── OrderController.java
        │           └── OrderStatus.java
        └── resources/
            ├── application.properties
            └── graphql/
                └── schema.graphqls
```

### Main Java types

- `Order` is a Java record containing `id`, `merchantId`, `merchantReference`, `amount`, `currency`, and `status`.
- `OrderStatus` defines `PENDING`, `APPROVED`, and `DECLINED`.
- `CreateOrderInput` receives the values used to create an order.
- `OrderController` owns the current `List<Order>` and resolves order queries and mutations.
- `Merchant` is a Java record containing only `id` and `name`.
- `MerchantController` resolves merchants and the separate `Merchant.orders` field.

## Current GraphQL schema

The schema is the API contract. It defines the operations clients may execute, the available fields, their types, and their nullability.

```graphql
type Query {
    order(id: ID!): Order
    orders: [Order!]!
    merchant(id: ID!): Merchant
}

type Mutation {
    createOrder(input: CreateOrderInput!): Order!
}

type Merchant {
    id: ID!
    name: String!
    orders: [Order!]!
}

type Order {
    id: ID!
    merchantReference: String!
    amount: Float!
    currency: String!
    status: OrderStatus!
}

input CreateOrderInput {
    merchantReference: String!
    amount: Float!
    currency: String!
}

enum OrderStatus {
    PENDING
    APPROVED
    DECLINED
}
```

`merchantId` is present in the Java `Order` record so the application can associate an order with a merchant. It is currently an internal implementation detail and is not exposed on the GraphQL `Order` type.

## Schema concepts

### Object, input, enum, and scalar types

- `type` defines an object clients can read, such as `Order` or `Merchant`.
- `input` defines structured data a client can send, such as `CreateOrderInput`.
- `enum` restricts a value to a known set, such as `OrderStatus`.
- `ID`, `String`, and `Float` are GraphQL scalar types.

The Java and GraphQL types are related, but they have different responsibilities. Java records represent values inside the application. The GraphQL schema controls what the external API exposes.

### Nullability and lists

An exclamation mark means a value cannot be null:

```graphql
id: ID!
```

The orders field uses:

```graphql
orders: [Order!]!
```

This means the list cannot be null and no item inside it may be null. An empty list is valid, which is why merchant `2` returns `orders: []`.

By contrast:

```graphql
order(id: ID!): Order
```

requires an ID argument but allows the result to be null when no matching order exists.

## Query and Mutation

GraphQL separates operations by intent:

- `query` reads data.
- `mutation` changes server-side state.

This is similar to the conceptual difference between reading with a REST `GET` and changing state with `POST`, `PATCH`, or `DELETE`. It is not a direct mapping to HTTP verbs: both GraphQL queries and mutations are commonly sent as HTTP `POST` requests to the same `/api/graphql` endpoint.

The client also chooses the shape of the response. For example, this operation asks for only two fields:

```graphql
query {
    order(id: "1") {
        merchantReference
        status
    }
}
```

The Java resolver can return the complete `Order`, but GraphQL includes only `merchantReference` and `status` in the JSON response.

## Spring GraphQL mappings

### `@QueryMapping`

`@QueryMapping` resolves a field on the root GraphQL `Query` type.

```java
@QueryMapping
public List<Order> orders() {
    return orders;
}
```

This method resolves:

```graphql
type Query {
    orders: [Order!]!
}
```

The `order(...)` and `merchant(...)` methods work the same way for their corresponding schema fields.

### `@MutationMapping`

`@MutationMapping` resolves a field on the root GraphQL `Mutation` type.

```java
@MutationMapping
public Order createOrder(@Argument CreateOrderInput input) {
    // Create, store, and return an Order.
}
```

This method resolves the schema field named `createOrder`. A client operation name such as `CreateOrder` is useful for logs and tracing, but it does not determine the Java method that runs.

### `@Argument`

`@Argument` binds a GraphQL argument to a Java method parameter.

```java
@QueryMapping
public Order order(@Argument Long id) {
    // id contains the value supplied as order(id: ...).
}
```

For the mutation, Spring converts the GraphQL `CreateOrderInput` value into the Java `CreateOrderInput` record. GraphQL validates required fields against the schema before the resolver executes.

### `@SchemaMapping`

`@SchemaMapping` resolves a child field on an object type rather than a root operation.

```java
@SchemaMapping(typeName = "Merchant", field = "orders")
public List<Order> orders(Merchant merchant) {
    return orderController.orders().stream()
            .filter(order -> order.merchantId().equals(merchant.id()))
            .toList();
}
```

This resolves `Merchant.orders`. The Java `Merchant` record intentionally contains only `id` and `name`; it does not have an `orders` property. When the client requests that field, Spring calls this resolver and filters orders by `merchantId`.

Injecting `OrderController` into `MerchantController` is acceptable for this learning stage, but it is not the intended production architecture. A service and repository layer will later replace the controller-to-controller dependency.

## Merchant to Orders nested resolution

The schema exposes a relationship:

```text
Merchant
├── id
├── name
└── orders
    └── Order
```

The current Java `Merchant` object does not carry the order list. GraphQL assembles the requested graph field by field:

```text
Query.merchant
    │
    │ @QueryMapping
    ▼
Merchant(id, name)
    │
    ├── id   → merchant.id()
    ├── name → merchant.name()
    └── orders
          │
          │ @SchemaMapping
          ▼
      filter List<Order> by merchantId
```

This is lazy field resolution. If the client requests `id` and `name` but omits `orders`, GraphQL does not call the `Merchant.orders` resolver.

That behavior is central to GraphQL: resolvers run according to the fields selected by the client, not merely because those fields exist in the schema.

## Request and execution flow

At application startup, Spring Boot scans the package, discovers controllers and mapping annotations, loads `src/main/resources/graphql/schema.graphqls`, connects schema fields to their resolvers, and exposes the configured HTTP endpoint.

When a request runs, the flow is:

```text
GraphiQL, web client, or mobile client
    │
    │ POST /api/graphql
    │ operation + optional variables
    ▼
Spring GraphQL HTTP handling
    │
    ▼
GraphQL parses and validates against schema.graphqls
    │
    ├── invalid operation → GraphQL error; resolver does not run
    │
    └── valid operation
          │
          ▼
      @QueryMapping or @MutationMapping
          │
          ▼
      Java returns an object or list
          │
          ▼
      requested child fields are resolved
          │
          ├── matching Java record accessors
          └── @SchemaMapping where required
          │
          ▼
      response shaped as requested JSON
```

GraphQL is the API layer, not a database. A resolver can obtain data from an in-memory list, a relational database, another API, a cache, or several sources while keeping the same graph-shaped API contract.

## Current in-memory design

`OrderController` currently stores data in a mutable `ArrayList<Order>`. An `AtomicLong` initialized to `2` generates IDs for new orders.

This design is useful for learning because it makes resolver behavior visible with very little infrastructure. It also has deliberate limitations:

- Data disappears when the application restarts.
- Data is not shared across multiple application instances.
- There is no repository, transaction, or database constraint.
- New orders are hardcoded to merchant `1` because `CreateOrderInput` does not yet accept `merchantId`.
- The H2 and Spring Data JPA dependencies are present but not yet used.
- The controller-to-controller dependency is temporary.

## Next steps

The planned progression is:

1. Add a query that returns multiple merchants.
2. Observe how resolving `orders` once per merchant can create the N+1 query problem when each resolution reaches a database.
3. Introduce DataLoader or Spring GraphQL batch mapping to load orders for many merchants together.
4. Add a service layer so GraphQL controllers focus on API resolution rather than data ownership.
5. Replace the in-memory order list with Spring Data JPA repositories.
6. Use PostgreSQL as the persistent database; H2 may remain useful for lightweight tests.
7. Extend `CreateOrderInput` so a client can select the merchant that owns the order.
8. Continue with validation, error handling, tests, and pagination.

The important learning sequence is intentional:

```text
nested fields
    → @SchemaMapping
    → lazy resolution
    → N+1 problem
    → batching/DataLoader
    → service and repository layers
    → JPA and PostgreSQL
```

