package org.corebaseit.graphqlpayments.order;

import java.math.BigDecimal;

public record CreateOrderInput(
        Long merchantId,
        String merchantReference,
        BigDecimal amount,
        String currency
) {
}
