package org.corebaseit.graphqlpayments.order;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.List;

/*
Our GraphQL controller now knows nothing about storage.
OrderController
      │
      │ "Give me order 1"
      ▼
 OrderService
      │
      │ "Find order 1"
      ▼
OrderRepository
      │
      ▼
     H2
 */

@Controller
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

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
}