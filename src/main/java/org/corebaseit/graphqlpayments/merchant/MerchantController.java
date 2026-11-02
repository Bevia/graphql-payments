package org.corebaseit.graphqlpayments.merchant;

import org.corebaseit.graphqlpayments.order.Order;
import org.corebaseit.graphqlpayments.order.OrderService;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;


import java.util.List;

/*
Now if GraphiQL sends:
GraphQL
   │
   │ Query.merchant
   ▼
@QueryMapping
merchant(1)
   │
   ▼
Merchant
id=1
name="Corebase Shop"
   │
   │ client requested "orders"
   ▼
@SchemaMapping
Merchant.orders
   │
   ▼
OrderService
findByMerchantId(1)
   │
   ▼
OrderRepository
findByMerchantId(1)
   │
   ▼
Spring Data JPA
   │
   ▼
H2
 */

@Controller
public class MerchantController {

    private final OrderService orderService;

    public MerchantController(OrderService orderService) {
        this.orderService = orderService;
    }

    @QueryMapping
    public Merchant merchant(@Argument Long id) {

        if (id.equals(1L)) {
            return new Merchant(1L, "Corebase Shop");
        }

        if (id.equals(2L)) {
            return new Merchant(2L, "Tech Store");
        }

        return null;
    }

    @SchemaMapping(typeName = "Merchant", field = "orders")
    public List<Order> orders(Merchant merchant) {
        return orderService.findByMerchantId(merchant.id());
    }
}