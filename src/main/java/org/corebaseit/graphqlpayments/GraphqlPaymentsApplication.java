package org.corebaseit.graphqlpayments;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class GraphqlPaymentsApplication {

    //We never created POST /graphql ourselves. Spring Boot created it for us automatically.
    //Because you added the Spring for GraphQL and Spring Web dependencies, Spring Boot auto-configures an HTTP GraphQL endpoint at:
    //POST http://localhost:8080/graphql

    //check --> GraphiQL_UI-client.png &
    //graphql-payment-application-init.png

    public static void main(String[] args) {
        SpringApplication.run(GraphqlPaymentsApplication.class, args);
    }

}