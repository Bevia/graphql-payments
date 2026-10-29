package org.corebaseit.graphqlpayments.order;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.graphql.test.tester.GraphQlTester;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureGraphQlTester
class CancelOrderTests {

    @Autowired
    private GraphQlTester graphQlTester;

    @Autowired
    private OrderRepository orderRepository;

    private static final String CANCEL_ORDER = """
            mutation CancelOrder($id: ID!) {
                cancelOrder(id: $id) {
                    id
                    status
                }
            }
            """;

    @Test
    void cancelsPendingOrder() {
        Order order = orderRepository.save(
                new Order(1L, "ORDER-CANCEL-1", new BigDecimal("10.00"), "EUR", OrderStatus.PENDING)
        );

        graphQlTester.document(CANCEL_ORDER)
                .variable("id", order.getId())
                .execute()
                .path("cancelOrder.status").entity(String.class).isEqualTo("CANCELLED");

        assertThat(orderRepository.findById(order.getId()))
                .get()
                .extracting(Order::getStatus)
                .isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void cancellingTwiceKeepsOrderCancelled() {
        Order order = orderRepository.save(
                new Order(1L, "ORDER-CANCEL-2", new BigDecimal("10.00"), "EUR", OrderStatus.PENDING)
        );

        graphQlTester.document(CANCEL_ORDER).variable("id", order.getId()).execute();

        graphQlTester.document(CANCEL_ORDER)
                .variable("id", order.getId())
                .execute()
                .path("cancelOrder.status").entity(String.class).isEqualTo("CANCELLED");
    }

    @Test
    void rejectsCancellingApprovedOrder() {
        Order order = orderRepository.save(
                new Order(1L, "ORDER-CANCEL-3", new BigDecimal("10.00"), "EUR", OrderStatus.APPROVED)
        );

        graphQlTester.document(CANCEL_ORDER)
                .variable("id", order.getId())
                .execute()
                .errors()
                .expect(error -> error.getErrorType() == ErrorType.BAD_REQUEST
                        && error.getMessage().contains("cannot be cancelled"))
                .verify()
                .path("cancelOrder").valueIsNull();

        assertThat(orderRepository.findById(order.getId()))
                .get()
                .extracting(Order::getStatus)
                .isEqualTo(OrderStatus.APPROVED);
    }

    @Test
    void returnsNullForUnknownOrder() {
        graphQlTester.document(CANCEL_ORDER)
                .variable("id", 999_999)
                .execute()
                .path("cancelOrder").valueIsNull();
    }
}
