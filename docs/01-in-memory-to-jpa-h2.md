# Migration 01: From In-Memory Storage to JPA and H2

This migration moves order storage out of GraphQL controllers and into a database-backed persistence layer. The GraphQL contract remains familiar, while application code gains clearer responsibilities and database-generated identifiers.

This is a learning checkpoint before containerizing the application. It describes the current working implementation and compares it with the earlier version in Git.

> **Package clarification:** The requested package was `com.corebaseit.graphqlpayments`, but this checkout declares `org.corebaseit.graphqlpayments` and stores its Java sources under `src/main/java/org/corebaseit/graphqlpayments`. Examples below match those actual declarations. This documentation does not rename the application package.

## 1. Starting architecture: controller-owned storage

Previously, `Order` was a Java record:

```java
package org.corebaseit.graphqlpayments.order;

import java.math.BigDecimal;

public record Order(
        Long id,
        Long merchantId,
        String merchantReference,
        BigDecimal amount,
        String currency,
        OrderStatus status
) {}
```

`OrderController` owned both the collection and the identifier generator:

```java
private final List<Order> orders = new ArrayList<>();
private final AtomicLong idGenerator = new AtomicLong(2);
```

Its constructor added sample orders. Queries returned the list or searched it with a Java stream. Creating an order called `idGenerator.incrementAndGet()`, constructed a record, and added it to the list.

```text
GraphiQL / client
       |
       | POST /api/graphql
       v
Spring GraphQL
       |
       v
OrderController
       |-- List<Order> / ArrayList: stores orders
       |-- AtomicLong: allocates identifiers
       `-- Java streams: search and filter
```

For a merchant's orders, `MerchantController` called `OrderController.orders()` and filtered all returned orders in Java.

### Why move beyond this design?

- **Storage lifetime:** The list belonged to one application process and vanished when that process stopped.
- **Mixed responsibilities:** A GraphQL controller handled request mapping, creation rules, identifiers, and storage.
- **Controller coupling:** `MerchantController` depended on another transport-facing controller to access data.
- **Manual querying:** Finding a merchant's orders required scanning the list in application code.
- **Concurrency and consistency:** `AtomicLong` made counter increments atomic; it did not make the `ArrayList` thread-safe or provide database transactions and constraints.
- **Identifier bookkeeping:** The previous constructor seeded IDs 1, 2, and 3, but the counter started at 2. Its first increment could therefore reuse ID 3. Database-generated primary keys remove this particular manual coordination problem.

Moving to JPA improves the persistence boundary. Using an in-memory H2 database does **not**, by itself, make the data durable across restarts.

## 2. Target architecture and responsibilities

```text
GraphiQL / client
       |
       | POST /api/graphql
       v
Spring GraphQL: schema and execution
       |
       v
Controller / Resolver
@QueryMapping, @MutationMapping, @SchemaMapping
       |
       v
OrderService
       |
       v
OrderRepository
       |
       v
Spring Data JPA
       |
       v
JPA provider (Hibernate) -> SQL
       |
       v
H2 database
```

| Layer | Responsibility in this project |
| --- | --- |
| GraphQL schema and execution | Define the API's types, arguments, nullability, and fields; execute the client's selected fields. |
| Controller / resolver | Map GraphQL operations and fields to service calls; accept GraphQL arguments and parent objects. |
| Business / service | Coordinate order operations and apply current creation choices: merchant 1 and `PENDING` status. |
| Persistence | Expose repository operations; map entities and execute database access through Spring Data JPA and its JPA provider. |
| Database | Store rows, generate identity values, and execute SQL queries and constraints. |

GraphQL does not automatically create database relationships or decide how to fetch a merchant's orders. Our field resolver supplies that behavior.

## 3. Gradle and runtime configuration

This is a **Gradle** project, using the Gradle wrapper and Java 17. The relevant existing dependencies in `build.gradle` are:

```groovy
dependencies {
    implementation 'org.springframework.boot:spring-boot-h2console'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-graphql'
    implementation 'org.springframework.boot:spring-boot-starter-webmvc'
    runtimeOnly 'com.h2database:h2'
}
```

This excerpt omits the existing test dependencies. The JPA starter supplies the persistence integration; H2 supplies the embedded database driver. JPA is the persistence specification, Hibernate is the provider implementing it here, and Spring Data JPA builds repository support on top.

The current `src/main/resources/application.properties` enables GraphiQL and sets the endpoint:

```properties
spring.application.name=graphql-payments
spring.graphql.graphiql.enabled=true
spring.graphql.http.path=/api/graphql
```

The file does not explicitly set a datasource URL or a schema-generation policy. With H2 available and no external datasource configuration, Spring Boot can auto-configure an embedded in-memory database. Environment variables, profiles, or other external configuration can change that behavior. Do not assume a specific database name, file location, or H2 console URL from these properties alone.

## 4. Converting `Order` from a record to an entity

A record provides immutable components and accessors such as `order.id()`. A conventional JPA entity needs a suitable no-argument constructor and provider-managed state. The current implementation uses a class with private fields and getters:

```java
package org.corebaseit.graphqlpayments.order;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long merchantId;
    private String merchantReference;
    private BigDecimal amount;
    private String currency;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    protected Order() {
        // Required by JPA
    }

    public Order(Long merchantId, String merchantReference,
                 BigDecimal amount, String currency, OrderStatus status) {
        this.merchantId = merchantId;
        this.merchantReference = merchantReference;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
    }

    public Long getId() { return id; }
    public Long getMerchantId() { return merchantId; }
    public String getMerchantReference() { return merchantReference; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public OrderStatus getStatus() { return status; }
}
```

### What each part means

| Element | Meaning |
| --- | --- |
| `@Entity` | Makes this class a JPA-managed entity that can be persisted and loaded. |
| `@Table(name = "orders")` | Explicitly maps it to the `orders` table, avoiding the SQL keyword `ORDER` as a table name. |
| `@Id` | Marks `id` as the primary key and entity identifier. |
| `@GeneratedValue(strategy = GenerationType.IDENTITY)` | Lets the database generate the identifier using an identity column when a row is inserted. The application no longer supplies an ID in the constructor. |
| `protected Order()` | Allows the JPA provider to instantiate the entity while discouraging ordinary callers from constructing an empty order. |
| Getters | Expose values to Java callers and allow GraphQL property resolution for fields such as `id`, `amount`, and `status`. Java calls now use `getId()` instead of the former record accessor `id()`. |
| `@Enumerated(EnumType.STRING)` | Stores names such as `PENDING`, `APPROVED`, and `DECLINED`, rather than ordinal positions such as 0, 1, and 2. Reordering enum constants does not change their stored meaning; renaming a constant still needs care with existing data. |

Because `@Id` and the mapping annotations are on fields, JPA uses field access; public setters are not required for this mapping. The public constructor supplies initial values, and JPA manages the identifier.

`merchantId` is currently a plain `Long`, not a `@ManyToOne` relationship. `Merchant` has not become a JPA entity. Also, `merchantId` is not exposed in the current GraphQL `Order` type: a database entity and an API type need not expose identical fields.

## 5. Creating `OrderRepository`

```java
package org.corebaseit.graphqlpayments.order;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findByMerchantId(Long merchantId);
}
```

`Order` identifies the managed entity, and `Long` identifies its primary-key type. Spring Data creates the repository implementation at runtime; we do not write an implementation class for these operations.

### Built-in methods

| Method | Behavior | Current use |
| --- | --- | --- |
| `findAll()` | Returns all order entities as a list, without a promised ordering. | Backs the GraphQL `orders` query. |
| `findById(id)` | Returns `Optional<Order>`, which is empty when no matching row exists. | The service uses `.orElse(null)`, matching the nullable GraphQL `order` field. |
| `save(order)` | Persists a new entity or merges an existing entity according to entity state. Use its returned value. | Saves a newly constructed order and returns it with its generated ID. |

### Derived query: `findByMerchantId`

Spring Data parses the method name: `findBy` means a lookup, and `MerchantId` refers to the Java entity property `merchantId`. The argument becomes the filter value.

Conceptually, the lookup becomes:

```sql
SELECT *
FROM orders
WHERE merchant_id = ?;
```

The `?` is a bound parameter, such as `1`. This is explanatory SQL, not a literal SQL log: the provider may select explicit columns, use aliases, and apply its naming strategy. The essential change is that the database filters matching rows rather than Java loading every order and filtering the collection.

## 6. Why introduce `OrderService`?

The service gives both controllers a shared application-level entry point. Controllers describe GraphQL mappings; the service expresses order operations; the repository handles persistence. This removes controller-to-controller dependency and provides a place to develop business rules without tying them to GraphQL annotations.

The current implementation is deliberately small:

```java
package org.corebaseit.graphqlpayments.order;

import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class OrderService {
    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public List<Order> findAll() {
        return orderRepository.findAll();
    }

    public Order findById(Long id) {
        return orderRepository.findById(id).orElse(null);
    }

    public List<Order> findByMerchantId(Long merchantId) {
        return orderRepository.findByMerchantId(merchantId);
    }

    public Order create(CreateOrderInput input) {
        Order order = new Order(
                1L,
                input.merchantReference(),
                input.amount(),
                input.currency(),
                OrderStatus.PENDING
        );
        return orderRepository.save(order);
    }
}
```

**`createOrder` currently hardcodes `merchantId = 1L`.** The input contains only `merchantReference`, `amount`, and `currency`; the client cannot select the merchant or status. `merchantReference` is an order reference, not the merchant's database identifier.

`@Service` registers the class as a Spring component; it does not automatically make every service operation transactional. The current service has no explicit `@Transactional` boundary. Future operations spanning multiple writes should consider their transaction requirements.

## 7. Refactoring `OrderController`

The controller receives `OrderService` through constructor injection. Its mapping methods now delegate directly:

```java
@QueryMapping
public List<Order> orders() {
    return orderService.findAll();
}

@QueryMapping
public Order order(@Argument Long id) {
    return orderService.findById(id);
}

@MutationMapping
public Order createOrder(@Argument CreateOrderInput input) {
    return orderService.create(input);
}
```

These are excerpts from `org.corebaseit.graphqlpayments.order.OrderController`. The list, counter, seeded records, and stream-based lookup no longer belong to this controller. Existing sample orders from the old constructor are not automatically copied into H2.

```text
createOrder(input)
       |
       v
OrderController.createOrder(input)
       |
       v
OrderService.create(input)
       | constructs Order(merchantId=1, ..., status=PENDING)
       v
OrderRepository.save(order)
       |
       v
Spring Data JPA -> Hibernate -> INSERT into H2
       |                         database generates id
       v
Saved Order -> GraphQL resolves requested fields -> JSON response
```

## 8. Refactoring `MerchantController`

Previously, the field resolver called another controller and filtered its list:

```java
return orderController.orders().stream()
        .filter(order -> order.merchantId().equals(merchant.id()))
        .toList();
```

The current controller injects `OrderService` and delegates the lookup:

```java
private final OrderService orderService;

public MerchantController(OrderService orderService) {
    this.orderService = orderService;
}

@SchemaMapping(typeName = "Merchant", field = "orders")
public List<Order> orders(Merchant merchant) {
    return orderService.findByMerchantId(merchant.id());
}
```

`Merchant` remains a record, so `merchant.id()` is still correct. The resolver receives the parent `Merchant` returned by the root `merchant` query.

```text
BEFORE
Merchant.orders resolver
       -> OrderController.orders()
       -> entire List<Order>
       -> filter in Java by merchantId

AFTER
Merchant.orders resolver
       -> OrderService.findByMerchantId(merchant.id())
       -> OrderRepository.findByMerchantId(merchantId)
       -> Spring Data JPA / Hibernate
       -> SELECT ... FROM orders WHERE merchant_id = ?
       -> matching rows in H2
```

### A GraphQL relationship without a JPA relationship

The GraphQL schema declares `Merchant.orders`, even though the Java `Merchant` record only holds `id` and `name`. The explicit `@SchemaMapping` supplies the orders field. This is a GraphQL field relationship implemented with a repository query, not a mapped JPA association.

```text
POST /api/graphql: merchant(id: "1") { id name orders { id } }
       |
       v
@QueryMapping merchant(1)
       |
       v
Hardcoded Merchant(1, "Corebase Shop")
       |
       | orders is selected
       v
@SchemaMapping Merchant.orders
       |
       v
OrderService.findByMerchantId(1)
       |
       v
OrderRepository -> JPA provider -> H2 lookup
       |
       v
Order entities -> requested order fields -> JSON
```

Requesting `merchant.orders` now performs a real persistence lookup for a returned merchant. If the client omits `orders`, GraphQL does not invoke this field resolver, so this route performs no order lookup. If the root merchant is `null`, there is no parent on which to resolve orders. This selection-driven behavior is GraphQL execution, not JPA lazy loading.

`MerchantController.merchant(id)` still constructs two hardcoded merchants:

| ID | Name |
| --- | --- |
| 1 | Corebase Shop |
| 2 | Tech Store |

Other IDs return `null`. There is no merchant repository or merchant table in this implementation.

## 9. Verifying the migration in GraphiQL

From the project root, start the application with Gradle:

```sh
./gradlew bootRun
```

Open [GraphiQL](http://localhost:8080/graphiql?path=/api/graphql). The editor sends requests to **`POST /api/graphql`**. The following operations match `src/main/resources/graphql/schema.graphqls` and the examples in `graphiql-queries.graphql`.

### Step 1: Create an order using variables

Paste this operation into the query editor:

```graphql
mutation CreateOrder($input: CreateOrderInput!) {
  createOrder(input: $input) {
    id
    merchantReference
    amount
    currency
    status
  }
}
```

Paste this JSON into the Variables panel:

```json
{
  "input": {
    "merchantReference": "ORDER-10004",
    "amount": 99.95,
    "currency": "EUR"
  }
}
```

Execute `CreateOrder`. Expect a generated ID and the submitted values with status `PENDING`. Keep the returned ID; do not assume it is `1` or that IDs are gapless. Repeating the mutation creates another order; it does not update the previous order by reference.

### Step 2: Query the merchant and its persisted orders

```graphql
query MerchantOneWithOrders {
  merchant(id: "1") {
    id
    name
    orders {
      id
      merchantReference
      amount
      currency
      status
    }
  }
}
```

Expect `Corebase Shop` and an orders list containing the newly created order. Compare its ID and reference with the mutation response. The parent merchant comes from hardcoded Java code; the nested orders come from H2 through the repository. Results have no guaranteed order.

### Step 3: Omit the nested field

```graphql
query MerchantWithoutOrdersField {
  merchant(id: "1") {
    id
    name
  }
}
```

Expect only the merchant's ID and name. A breakpoint in `MerchantController.orders(Merchant merchant)` can demonstrate the difference: it is reached for Step 2 and not for Step 3. A correct response alone does not show SQL execution; use a debugger or SQL logging when inspecting the lookup itself.

### Step 4: Check the other hardcoded merchant

```graphql
query MerchantTwoWithOrders {
  merchant(id: "2") {
    id
    name
    orders {
      id
      merchantReference
    }
  }
}
```

Expect `Tech Store` with `orders: []` when all stored orders were created through the current mutation, because that mutation assigns every order to merchant 1. Rows inserted through another route could change that result.

These steps are a repeatable verification procedure, not a claim that a live application was exercised while writing this document. Some comments elsewhere in the project still describe the previous in-memory implementation; the current resolver and repository code define the behavior described here.

## 10. H2 lifetime and current limitations

- **Temporary database:** H2 supports both in-memory and file-backed modes. The current properties do not explicitly configure durable storage. In-memory data is temporary and may disappear on restart or database closure, depending on the effective configuration. Moving from a Java list to H2 does not guarantee persistence across process restarts.
- **Partial migration:** Orders use JPA; merchants remain hardcoded. The numeric `merchantId` field alone does not establish a foreign key or enforce that a merchant exists.
- **Hardcoded creation rules:** Every new order belongs to merchant 1 and starts as `PENDING`.
- **No DataLoader or N+1 optimization yet:** Each invocation of `Merchant.orders` performs its own lookup. The current schema has a single-merchant root query, but repeated aliases or a future list of merchants can cause repeated queries. A future list returning N merchants could cause N separate order lookups, in addition to whatever retrieves the parents. Batching is not automatic.
- **Unbounded reads:** `findAll()` returns every order; neither the exposed list query nor the merchant lookup currently provides pagination or explicit ordering.
- **Validation and precision:** GraphQL enforces declared input types and required fields, but the service does not validate business rules such as positive amounts or supported currencies. Java uses `BigDecimal`, while the current GraphQL schema exposes `Float`; monetary precision and database column precision/scale deserve an explicit policy later.
- **Schema lifecycle:** This checkpoint has no explicit schema migration strategy documented in its datasource properties. Durable deployment should deliberately configure database storage and schema evolution.

## 11. What we learned

1. A GraphQL schema is an API contract, not a database schema. A requested nested field can be backed by a service call even when it is absent from the parent Java record.
2. A controller maps requests; a service coordinates application behavior; a repository exposes persistence operations; the database stores and queries rows.
3. A JPA entity has a provider-managed lifecycle. Its database-generated identifier replaces manual counter management.
4. Spring Data derives a query from an entity property name, allowing filtering to happen in the database.
5. GraphQL invokes selected field resolvers. Omitting `orders` avoids this order lookup, but selecting it for many parents can create a batching problem.
6. Database-backed access and durable storage are separate decisions. H2 in-memory mode is useful for learning without promising long-term data retention.

### Study questions

- Why can both controllers use `OrderService` without calling each other?
- Why is `merchant.id()` still valid while an order now has `getId()`?
- Which layer chooses `PENDING`, and which layer generates the order ID?
- Why can a merchant be returned without querying the orders table?
- What would need to change to retain data across application or container restarts?

## 12. Next migration: containerizing Spring Boot

The next learning document is planned as `02-containerizing-spring-boot.md`.

It should explain building the application with Gradle (`./gradlew bootJar`), packaging the runnable application in a Docker image, running the container, exposing port 8080, and reaching `/api/graphql` from the host. It should also separate image contents, runtime configuration, and database lifetime: containerizing an in-memory H2 application does not make its data durable.

## Source map

- [Gradle build](../build.gradle)
- [Runtime properties](../src/main/resources/application.properties)
- [GraphQL schema](../src/main/resources/graphql/schema.graphqls)
- [GraphiQL operations](../graphiql-queries.graphql)
- [Order entity](../src/main/java/org/corebaseit/graphqlpayments/order/Order.java)
- [Order repository](../src/main/java/org/corebaseit/graphqlpayments/order/OrderRepository.java)
- [Order service](../src/main/java/org/corebaseit/graphqlpayments/order/OrderService.java)
- [Order controller](../src/main/java/org/corebaseit/graphqlpayments/order/OrderController.java)
- [Merchant controller](../src/main/java/org/corebaseit/graphqlpayments/merchant/MerchantController.java)
- [Merchant record](../src/main/java/org/corebaseit/graphqlpayments/merchant/Merchant.java)
