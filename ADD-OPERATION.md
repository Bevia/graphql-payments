# Adding a New Operation

A recipe for adding a GraphQL operation to this project. The worked example is `cancelOrder`. Use the same steps for `declineOrder`, `approveOrder`, or any other operation.

Steps 2–6 follow the request path from the outside in:

```text
schema.graphqls → OrderController → OrderService → Order (entity) → OrderRepository → H2
```

---

## Step 1: Decide the behaviour

Write the rules down before you write any code. For `cancelOrder`:

| Current status | Result |
|---|---|
| `PENDING` | Becomes `CANCELLED` |
| `CANCELLED` | Unchanged (a retry is safe) |
| `APPROVED`, `DECLINED` | Error |
| Order not found | `null` |

Answer these questions:

- **Query or mutation?** Reading data is a query. Changing data is a mutation.
- **Arguments?** One `ID`, or several values? If several, use an `input` type (like `CreateOrderInput`).
- **Return type?** Return the changed object so the client can read the new state in the same request.
- **Nullable?** `Order` (no `!`) lets you return `null` for "not found". `Order!` means you must always return an order or an error.
- **Delete or change state?** In payments, change the state and keep the record.

## Step 2: Add it to the schema

File: [`src/main/resources/graphql/schema.graphqls`](src/main/resources/graphql/schema.graphqls)

```graphql
type Mutation {
    createOrder(input: CreateOrderInput!): Order!
    cancelOrder(id: ID!): Order          # ← new
}

enum OrderStatus {
    PENDING
    APPROVED
    DECLINED
    CANCELLED                            # ← new, only if you need a new status
}
```

This is the contract. Names chosen here must match the Java names exactly in steps 3 and 6.

## Step 3: Add the enum value in Java (only if you added one in step 2)

File: [`order/OrderStatus.java`](src/main/java/org/corebaseit/graphqlpayments/order/OrderStatus.java)

```java
public enum OrderStatus {
    PENDING,
    APPROVED,
    DECLINED,
    CANCELLED
}
```

The constant names must be identical to the schema enum values.

## Step 4: Put the business rule in the entity

File: [`order/Order.java`](src/main/java/org/corebaseit/graphqlpayments/order/Order.java)

```java
public void cancel() {
    if (status == OrderStatus.CANCELLED) {
        return;
    }

    if (status != OrderStatus.PENDING) {
        throw new IllegalStateException(
                "Order " + id + " cannot be cancelled because its status is " + status
        );
    }

    status = OrderStatus.CANCELLED;
}
```

The entity owns the rule, so it can't be bypassed: there's no public setter for `status`. Throw `IllegalStateException` when the rule is broken.

## Step 5: Add the service method

File: [`order/OrderService.java`](src/main/java/org/corebaseit/graphqlpayments/order/OrderService.java)

```java
public Order cancel(Long id) {

    Order order = orderRepository.findById(id)
            .orElse(null);

    if (order == null) {
        return null;
    }

    order.cancel();

    return orderRepository.save(order);
}
```

The pattern is always the same: **load → apply the rule → save → return**.

## Step 6: Add the resolver in the controller

File: [`order/OrderController.java`](src/main/java/org/corebaseit/graphqlpayments/order/OrderController.java)

```java
@MutationMapping
public Order cancelOrder(@Argument Long id) {
    return orderService.cancel(id);
}
```

- `@MutationMapping` for mutations, `@QueryMapping` for queries.
- The **method name** must match the schema field name (`cancelOrder`).
- The **parameter name** must match the schema argument name (`id`).
- The controller only delegates. Keep logic in the entity and service.

## Step 7: Errors (usually nothing to do)

[`GraphQlErrorHandler.java`](src/main/java/org/corebaseit/graphqlpayments/GraphQlErrorHandler.java) already turns any `IllegalStateException` into a GraphQL error with classification `BAD_REQUEST` and your message.

You only need to change this file if you throw a different exception type.

## Step 8: Write the tests

File: [`src/test/java/.../order/CancelOrderTests.java`](src/test/java/org/corebaseit/graphqlpayments/order/CancelOrderTests.java). Copy it and adapt it.

Write one test per row of the table from step 1:

```java
@Test
void cancelsPendingOrder() {
    Order order = orderRepository.save(
            new Order(1L, "ORDER-CANCEL-1", new BigDecimal("10.00"), "EUR", OrderStatus.PENDING)
    );

    graphQlTester.document(CANCEL_ORDER)
            .variable("id", order.getId())
            .execute()
            .path("cancelOrder.status").entity(String.class).isEqualTo("CANCELLED");
}
```

Save orders with any status directly through `OrderRepository`. That's how you test cases the API can't create yet (for example, an `APPROVED` order).

## Step 9: Run the tests

```bash
./gradlew test
```

In IntelliJ, right-click `src/test/java` → **Run 'All Tests'**.

## Step 10: Try it in the running app

1. **Restart the app.** The schema is loaded only at startup.
2. Check the startup log for the schema inspection report. All three lists must be empty:
   ```text
   GraphQL schema inspection:
       Unmapped fields: {}
       Unmapped registrations: {}
       Unmapped arguments: {}
   ```
   If `Unmapped fields` lists your new field, the Java method name doesn't match the schema (step 6).
3. Open GraphiQL. The new field appears under `Mutation` in the 📖 Docs panel.
4. Run it:
   ```graphql
   mutation {
     cancelOrder(id: "1") { id status }
   }
   ```

## Step 11: Update the docs

| File | What to add |
|---|---|
| [`graphiql-queries.graphql`](graphiql-queries.graphql) | A ready-to-run example with its Variables JSON |
| [`TEST.md`](TEST.md) | A `curl` example and the rules table (section 5) |
| [`SCHEMA.md`](SCHEMA.md) | The schema, the mapping table row, and the diagram nodes |

---

## Checklist

- [ ] Rules written down (step 1)
- [ ] Schema field added, plus enum value if needed (step 2)
- [ ] Java enum matches the schema enum (step 3)
- [ ] Rule method on the entity (step 4)
- [ ] Service method: load → apply → save (step 5)
- [ ] `@MutationMapping` / `@QueryMapping` method with matching names (step 6)
- [ ] One test per rule; `./gradlew test` passes (steps 8–9)
- [ ] App restarted, schema inspection clean, tried in GraphiQL (step 10)
- [ ] Docs updated (step 11)

## Example: `declineOrder`

`DECLINED` already exists, so steps 2 and 3 only need the new field.

| Step | Change |
|---|---|
| 1 | `PENDING` → `DECLINED`; `DECLINED` → unchanged; `APPROVED`, `CANCELLED` → error; unknown → `null` |
| 2 | `declineOrder(id: ID!): Order` in `type Mutation` |
| 3 | Nothing (`DECLINED` already exists) |
| 4 | `Order.decline()`, same shape as `cancel()` |
| 5 | `OrderService.decline(Long id)`: load → `order.decline()` → save |
| 6 | `@MutationMapping public Order declineOrder(@Argument Long id)` |
| 7 | Nothing |
| 8 | `DeclineOrderTests`, one test per rule |

## Common mistakes

| Symptom | Cause |
|---|---|
| `Validation error (FieldUndefined …)` | The field isn't in the schema, or the app wasn't restarted |
| The field returns `null` and shows under `Unmapped fields` at startup | The Java method name doesn't match the schema field |
| Argument is `null` inside the method | The `@Argument` parameter name doesn't match the schema argument |
| Error mentions an enum value | The Java enum and the schema enum differ |
| Client sees `INTERNAL_ERROR` instead of your message | You threw an exception other than `IllegalStateException`; add a handler method to `GraphQlErrorHandler` |
