package org.corebaseit.graphqlpayments.order;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/*
Our architecture has now improved considerably:
GraphQL
   │
   ▼
OrderController
   │
   ▼
OrderService
   │
   ▼
OrderRepository
   │
   ▼
Spring Data JPA
   │
   ▼
H2
 */

@Service
public class OrderService {

    /*
    Merchants are still hard-coded in MerchantController (1 and 2).
    Until merchants move to the database, this list must match it.
     */
    private static final Long DEFAULT_MERCHANT_ID = 1L;
    private static final Set<Long> KNOWN_MERCHANT_IDS = Set.of(1L, 2L);

    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public List<Order> findAll() {
        return orderRepository.findAll();
    }

    public Order findById(Long id) {
        return orderRepository.findById(id)
                .orElse(null);
    }

    public List<Order> findByMerchantId(Long merchantId) {
        return orderRepository.findByMerchantId(merchantId);
    }

    public Order create(CreateOrderInput input) {

        Long merchantId = input.merchantId() != null
                ? input.merchantId()
                : DEFAULT_MERCHANT_ID;

        if (!KNOWN_MERCHANT_IDS.contains(merchantId)) {
            throw new IllegalStateException("Merchant " + merchantId + " does not exist");
        }

        Order order = new Order(
                merchantId,
                input.merchantReference(),
                input.amount(),
                input.currency(),
                OrderStatus.PENDING
        );

        return orderRepository.save(order);
    }

    public Order cancel(Long id) {

        Order order = orderRepository.findById(id)
                .orElse(null);

        if (order == null) {
            return null;
        }

        order.cancel();

        return orderRepository.save(order);
    }
}
