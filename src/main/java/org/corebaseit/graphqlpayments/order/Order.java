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

    public Order(
            Long merchantId,
            String merchantReference,
            BigDecimal amount,
            String currency,
            OrderStatus status
    ) {
        this.merchantId = merchantId;
        this.merchantReference = merchantReference;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public Long getMerchantId() {
        return merchantId;
    }

    public String getMerchantReference() {
        return merchantReference;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public OrderStatus getStatus() {
        return status;
    }

    /*
    Only a PENDING order can be cancelled.
    Cancelling an already CANCELLED order changes nothing, so retries are safe.
    APPROVED or DECLINED orders cannot be cancelled:
    undoing an approved payment is a void or refund, not a cancel.
     */
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
}
