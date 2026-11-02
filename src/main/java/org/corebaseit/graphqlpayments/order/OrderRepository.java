package org.corebaseit.graphqlpayments.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/*
This is our first actual persistence layer.
Spring Data will implement it for us.
 */

public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findByMerchantId(Long merchantId);
}