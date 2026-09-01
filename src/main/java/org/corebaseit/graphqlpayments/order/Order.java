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

/*
public record Order(
        Long id,
        String merchantReference,
        BigDecimal amount,
        String currency,
        OrderStatus status
) {
}*/
