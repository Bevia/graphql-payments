package org.corebaseit.graphqlpayments.order;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Controller
public class OrderController {

    private final List<Order> orders = new ArrayList<>();

    private final AtomicLong idGenerator = new AtomicLong(2);

    public OrderController() {
        orders.add(new Order(
                1L,
                1L,
                "ORDER-10001",
                new BigDecimal("49.95"),
                "EUR",
                OrderStatus.APPROVED
        ));

        orders.add(new Order(
                2L,
                1L,
                "ORDER-10002",
                new BigDecimal("125.00"),
                "EUR",
                OrderStatus.PENDING
        ));

        orders.add(new Order(
                3L,
                1L,
                "ORDER-10003",
                new BigDecimal("159.00"),
                "EUR",
                OrderStatus.PENDING
        ));
    }

    @QueryMapping
    public List<Order> orders() {
        return orders;
    }

    @QueryMapping
    public Order order(@Argument Long id) {
        return orders.stream()
                .filter(order -> order.id().equals(id))
                .findFirst()
                .orElse(null);
    }

    //GraphQL does not use HTTP verbs to distinguish reads from writes the same way REST does.
    //query versus mutation is part of the GraphQL operation itself.
    /*

        Query.merchant
               │
               │ @QueryMapping
               ▼
            Merchant(1, "Corebase Shop")
               │
               ├── id   → merchant.id()
               ├── name → merchant.name()
               │
               └── orders
                     │
                     │ @SchemaMapping
                     ▼
                 orders(Merchant merchant)
                     │
                     ▼
             filter by merchantId

     Client asks for field
        ↓
    GraphQL resolves field

    Client does NOT ask for field
            ↓
    GraphQL does NOT resolve field

     */
    @MutationMapping
    public Order createOrder(@Argument CreateOrderInput input) {

        Order order = new Order(
                idGenerator.incrementAndGet(),
                1L,
                input.merchantReference(),
                input.amount(),
                input.currency(),
                OrderStatus.PENDING
        );

        orders.add(order);

        return order;
    }
}