# In-Memory to Docker Migration

This document is the learning roadmap for evolving `graphql-payments` from an in-memory Spring GraphQL example into a containerized application running with H2 and Kubernetes.

The goal is to make each infrastructure change understandable and verifiable before moving to the next one.

## Starting point

The application currently stores merchants and orders in controller-owned Java collections. H2 and Spring Data JPA dependencies are already present in `build.gradle`, but no entity or repository uses them yet.

The current behavior is:

- The application runs with `./gradlew bootRun`.
- GraphiQL is available at `http://localhost:8080/graphiql?path=/api/graphql`.
- The GraphQL endpoint is `POST /api/graphql`.
- Data resets when the application restarts.

## Migration path

### 1. Establish a baseline

- Run the existing tests.
- Start the application with the Gradle Wrapper.
- Verify the existing queries and `createOrder` mutation in GraphiQL.
- Record the current API behavior before changing persistence.

### 2. Move persistence to H2 and JPA

- Replace Java records used as stored objects with JPA entities where appropriate.
- Add `JpaRepository` interfaces for merchants and orders.
- Move data ownership into a service and repository layer.
- Keep GraphQL controllers focused on resolving API operations.
- Seed the two merchants and two initial orders in a repeatable way.
- Configure H2 and JPA in `application.properties`.

For the first local version, use file-backed H2 so data survives application restarts:

```properties
spring.datasource.url=jdbc:h2:file:./data/graphql-payments
spring.jpa.hibernate.ddl-auto=update
spring.h2.console.enabled=true
```

The H2 console is a development aid. It should not be treated as the application’s public database API.

### 3. Containerize with Docker

- Add a `Dockerfile` that builds and runs the Spring Boot application.
- Add a `.dockerignore` for build output, IDE files, and local database files.
- Build the image and run it locally.
- Pass configuration through environment variables where possible.
- Mount a local data directory if testing file-backed H2 inside Docker.
- Verify GraphiQL and the GraphQL endpoint through the container port.

Example runtime concerns:

```text
host port 8080 → container port 8080
H2 database    → mounted /data directory
```

### 4. Run the container with Kubernetes

- Add a `Deployment` for the application.
- Add a `Service` to expose port 8080 inside the cluster.
- Add configuration through a `ConfigMap` or environment variables.
- Add a `PersistentVolumeClaim` mounted at `/data` if H2 file persistence is required.
- Deploy locally with a Kubernetes distribution such as Docker Desktop Kubernetes or Minikube.
- Verify pod health, logs, GraphiQL access, and data behavior after a pod restart.

For learning, begin with one replica. H2 file storage should not be shared casually by multiple application pods.

### 5. Compare with a production database

After the H2 and Kubernetes flow works, document the changes needed for PostgreSQL:

- Replace the H2 JDBC driver and URL.
- Store credentials in Kubernetes Secrets.
- Use a managed or separately operated database service.
- Add migrations with Flyway or Liquibase.
- Scale application replicas independently of the database.

H2 is suitable for this learning path and single-instance demonstrations. PostgreSQL is the better target when the application needs production durability, concurrent access, backups, and multiple replicas.

## Checkpoints

Each phase is complete when:

1. The existing GraphQL operations still work.
2. The application can be started using documented commands.
3. The data behavior is explicitly tested for that phase.
4. The README and this roadmap describe the current state accurately.

## Decisions to keep visible

| Decision | Learning reason |
|---|---|
| Gradle Wrapper | Reproducible builds without relying on a global Gradle installation |
| H2 file mode | Demonstrates persistence while keeping setup small |
| One Kubernetes replica initially | Avoids unsupported shared writes to one H2 file |
| Service and repository layers | Separates GraphQL resolution from persistence |
| PostgreSQL as the later target | Provides a realistic production migration path |

This roadmap should be updated as each phase is implemented, so it remains a record of both the technical changes and the reasons behind them.