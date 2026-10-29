package org.corebaseit.graphqlpayments.order;

import org.springframework.stereotype.Service;

import java.util.List;

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

        Order order = new Order(
                1L, // merchant for now
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
