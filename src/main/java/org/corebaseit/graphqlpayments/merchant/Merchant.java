package org.corebaseit.graphqlpayments.merchant;

import org.corebaseit.graphqlpayments.order.Order;

import java.util.List;

public record Merchant(
        Long id,
        String name
       // List<Order> orders

        /*
        This creates an interesting question:
        If Merchant.java doesn't contain orders, where does GraphQL get Merchant.orders from?
        ...
        That's what a field resolver solves.
         */
) {
}