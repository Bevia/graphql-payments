package org.corebaseit.graphqlpayments;

import graphql.GraphQLError;
import org.springframework.graphql.data.method.annotation.GraphQlExceptionHandler;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.web.bind.annotation.ControllerAdvice;

/*
By default, Spring for GraphQL hides exception messages from clients
and returns a generic INTERNAL_ERROR.

A broken business rule (for example, cancelling an APPROVED order)
is the client's mistake, not a server failure, so we return its message
in the "errors" array with classification BAD_REQUEST.
 */

@ControllerAdvice
public class GraphQlErrorHandler {

    @GraphQlExceptionHandler
    public GraphQLError handle(IllegalStateException ex) {
        return GraphQLError.newError()
                .errorType(ErrorType.BAD_REQUEST)
                .message(ex.getMessage())
                .build();
    }
}
