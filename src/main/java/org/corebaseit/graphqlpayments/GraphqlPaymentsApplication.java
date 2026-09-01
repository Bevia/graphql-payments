package org.corebaseit.graphqlpayments;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class GraphqlPaymentsApplication {

    //We never created POST /graphql ourselves. Spring Boot created it for us automatically.
    //Because you added the Spring for GraphQL and Spring Web dependencies, Spring Boot auto-configures an HTTP GraphQL endpoint at:
    //POST http://localhost:8080/graphql

    /*
    GraphiQL is just a UI/client. Behind the scenes it sends your query to:
    GraphiQL
       │
       │ HTTP POST
       ▼
    http://localhost:8080/graphql
       │
       │
       ▼
        Spring GraphQL
       │
       ▼
    @QueryMapping
     */

    public static void main(String[] args) {
        SpringApplication.run(GraphqlPaymentsApplication.class, args);
    }

}

/*
when you run:

GraphqlPaymentsApplication.main()

Then roughly:

GraphqlPaymentsApplication.main()
        │
        ▼
SpringApplication.run(...)
        │
        ▼
Spring Boot starts
        │
        ├── scans Java packages
        │
        │
        ├── finds @Controller
        │
        │   └── OrderController
        │
        │
        └── finds GraphQL dependencies
        │
        ▼
Spring GraphQL auto-configuration
        │
        ├── loads:
        │   resources/graphql/schema.graphqls
        │
        ├── builds GraphQL schema
        │
        ├── discovers @QueryMapping methods
        │
        │   ├── orders()
        │
        │   └── order(id)
        │
        └── connects mappings to schema
        │
        ▼
Creates HTTP endpoint
        │
        ▼
POST /api/graphql
        │
        ▼
Application ready
 */
