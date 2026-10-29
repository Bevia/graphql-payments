package org.corebaseit.graphqlpayments.order;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.graphql.test.tester.GraphQlTester;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureGraphQlTester
class CreateOrderTests {

    @Autowired
    private GraphQlTester graphQlTester;

    @Autowired
    private OrderRepository orderRepository;

    private static final String CREATE_ORDER = """
            mutation CreateOrder($input: CreateOrderInput!) {
                createOrder(input: $input) {
                    id
                    status
                }
            }
            """;

    private static final String MERCHANT_ORDER_REFERENCES = """
            query MerchantOrders($id: ID!) {
                merchant(id: $id) {
                    orders {
                        merchantReference
                    }
                }
            }
            """;

    @Test
    void createsOrderForGivenMerchant() {
        graphQlTester.document(CREATE_ORDER)
                .variable("input", Map.of(
                        "merchantId", "2",
                        "merchantReference", "TS-CREATE-1",
                        "amount", 19.99,
                        "currency", "EUR"))
                .execute()
                .path("createOrder.status").entity(String.class).isEqualTo("PENDING");

        graphQlTester.document(MERCHANT_ORDER_REFERENCES)
                .variable("id", "2")
                .execute()
                .path("merchant.orders[*].merchantReference")
                .entityList(String.class)
                .contains("TS-CREATE-1");
    }

    @Test
    void defaultsToMerchantOneWhenMerchantIdIsOmitted() {
        String id = graphQlTester.document(CREATE_ORDER)
                .variable("input", Map.of(
                        "merchantReference", "CS-CREATE-1",
                        "amount", 49.95,
                        "currency", "EUR"))
                .execute()
                .path("createOrder.id").entity(String.class).get();

        assertThat(orderRepository.findById(Long.valueOf(id)))
                .get()
                .extracting(Order::getMerchantId)
                .isEqualTo(1L);
    }

    @Test
    void rejectsUnknownMerchant() {
        graphQlTester.document(CREATE_ORDER)
                .variable("input", Map.of(
                        "merchantId", "3",
                        "merchantReference", "UNKNOWN-1",
                        "amount", 10.00,
                        "currency", "EUR"))
                .execute()
                .errors()
                // createOrder returns Order! (non-null), so GraphQL also reports
                // a NonNullableFieldWasNullError next to our BAD_REQUEST error.
                .satisfy(errors -> assertThat(errors).anyMatch(error ->
                        error.getErrorType() == ErrorType.BAD_REQUEST
                                && error.getMessage().equals("Merchant 3 does not exist")));
    }
}
