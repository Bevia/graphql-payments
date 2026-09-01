package org.corebaseit.graphqlpayments.merchant;

import org.corebaseit.graphqlpayments.order.Order;
import org.corebaseit.graphqlpayments.order.OrderController;
import org.corebaseit.graphqlpayments.order.OrderStatus;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.math.BigDecimal;
import java.util.List;

/*
GraphQL is traversing an object graph:
Merchant
   │
   ├── id
   ├── name
   └── orders
         │
         ├── Order
         │    ├── merchantReference
         │    └── status
         │
         └── Order
              ├── merchantReference
              └── status
 */

/*
In graphiql:
http://localhost:8080/graphiql?path=/api/graphql

query {
    merchant(id: "1") {
        id
        name
        orders {
            id
            merchantReference
            amount
            status
        }
    }
}
 */

@Controller
public class MerchantController {

    //let's inject the OrderController'
    private final OrderController orderController;

    //constructor injection
    public MerchantController(OrderController orderController) {
        this.orderController = orderController;
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

    /*
    MerchantController
            │
            ├── @QueryMapping
            │   merchant(id)
            │
            │   Resolves:
            │   Query.merchant
            │
            └── @SchemaMapping
                orders(merchant)

                Resolves:
                Merchant.orders
     */

    @SchemaMapping(typeName = "Merchant", field = "orders")
    public List<Order> orders(Merchant merchant) {

        return orderController.orders().stream()
                .filter(order -> order.merchantId().equals(merchant.id()))
                .toList();
    }


/*public class MerchantController {

    @QueryMapping
    public Merchant merchant(@Argument Long id) {

        List<Order> orders = List.of(
                new Order(
                        1L,
                        "ORDER-10001",
                        new BigDecimal("49.95"),
                        "EUR",
                        OrderStatus.APPROVED
                ),
                new Order(
                        2L,
                        "ORDER-10002",
                        new BigDecimal("125.00"),
                        "EUR",
                        OrderStatus.PENDING
                )
        );

        return new Merchant(
                id,
                "Corebase Shop",
                orders
        );
    }*/
}