package org.corebaseit.graphqlpayments.order;

import java.math.BigDecimal;

public record CreateOrderInput(
        String merchantReference,
        BigDecimal amount,
        String currency
) {
}
