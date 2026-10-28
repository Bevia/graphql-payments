# Running and Testing graphql-payments

This guide covers how to build the project, run the automated tests, start the application, and exercise the GraphQL API by hand with GraphiQL or `curl`.

It reflects the current checkpoint (phase 1): orders are stored in an embedded **H2 in-memory database** through Spring Data JPA. Merchants are still hard-coded in `MerchantController`.

"Testing" here means two separate things:

- **Automated tests:** JUnit tests that start the app, check it, and stop it again. Nothing stays running.
- **Manual testing:** you start the app yourself, leave it running, and send GraphQL requests from the browser (GraphiQL) or `curl`.

You can do both from the terminal or from IntelliJ IDEA, and mix them freely:

| Task | Terminal | IntelliJ IDEA |
|---|---|---|
| Run automated tests | `./gradlew test` | Green ▶ next to the test class |
| Start the app | `./gradlew bootRun` | Green ▶ next to `main` in `GraphqlPaymentsApplication` |
| Stop the app | `Ctrl+C` | Red ■ Stop button |
| Send requests | `curl` (section 5) | GraphiQL in the browser (section 4) |

## Quick start in IntelliJ IDEA

If you prefer the IDE over the terminal, follow these steps. The command-line equivalents are in sections 2 and 3.

### Step 1: Open the project

1. **File → Open…**, select the `graphql-payments` folder (the one containing `build.gradle`), click **Open**.
2. If asked, choose **Trust Project**.
3. Wait for the Gradle sync to finish (progress bar at the bottom right). When it's done, the **Gradle** tool window (elephant icon, right sidebar) shows the project.

### Step 2: Check the JDK

1. **File → Project Structure… → Project**: set **SDK** to a JDK 17 or newer.
2. **Settings → Build, Execution, Deployment → Build Tools → Gradle**: set **Gradle JVM** to the same JDK.

### Step 3: Run the automated tests

1. In the **Project** view, open `src/test/java/org/corebaseit/graphqlpayments/GraphqlPaymentsApplicationTests.java`.
2. Click the green ▶ in the gutter next to `class GraphqlPaymentsApplicationTests` and choose **Run 'GraphqlPaymentsApplicationTests'**.
   - To run every test in the project instead, right-click the `src/test/java` folder → **Run 'All Tests'**.
3. The **Run** window opens at the bottom. A green check next to `contextLoads()` means the test passed. A red mark means it failed; click it to see the stack trace.

### Step 4: Start the application

1. Open `src/main/java/org/corebaseit/graphqlpayments/GraphqlPaymentsApplication.java`.
2. Click the green ▶ next to the `main` method (or the class name) → **Run 'GraphqlPaymentsApplication'**.
3. Watch the **Run** window until you see `Tomcat started on port 8080`. The app is now running and stays running.
4. Next time, use the ▶ button in the top toolbar: IntelliJ remembers the run configuration.

### Step 5: Send requests

Open http://localhost:8080/graphiql?path=/api/graphql in your browser and follow [section 4](#4-test-with-graphiql). Start with the `createOrder` mutation, because the database is empty.

### Step 6: Stop the application

Click the red ■ (Stop) button in the **Run** window or the top toolbar. All orders are lost when the app stops.

> Starting the app while another instance is already running (from IntelliJ or the terminal) fails with `Port 8080 was already in use`. Stop the running instance first. The automated tests don't use port 8080, so you can run them while the app is running.

## 1. Prerequisites

- A JDK. The build uses a Java 17 toolchain (`build.gradle`); a newer JDK on your `PATH` works too.
- Nothing else. Use the Gradle Wrapper (`./gradlew`), which downloads the correct Gradle version on first use.
- Port `8080` free on your machine.

Check your Java version:

```bash
java -version
```

## 2. Run the automated tests

From the project root:

```bash
./gradlew test
```

Expected result: `BUILD SUCCESSFUL`.

The suite currently has one test, `GraphqlPaymentsApplicationTests.contextLoads()`, which starts the full Spring context (GraphQL schema, JPA, H2). If the schema and the controllers disagree, or JPA mapping is broken, this test fails.

The HTML report is written to:

```text
build/reports/tests/test/index.html
```

To force a re-run when Gradle reports the task as `UP-TO-DATE`:

```bash
./gradlew test --rerun-tasks
```

To do a full clean build (compile + tests + jar):

```bash
./gradlew clean build
```

## 3. Start the application

```bash
./gradlew bootRun
```

Wait for a log line similar to `Tomcat started on port 8080`. Stop the application with `Ctrl+C`.

| What | URL |
|---|---|
| GraphQL endpoint | `POST http://localhost:8080/api/graphql` |
| GraphiQL (browser client) | http://localhost:8080/graphiql?path=/api/graphql |

> **Important:** the database starts **empty** on every start. There is no seed data, so `orders` returns `[]` until you create orders. Restarting the application wipes all orders and resets IDs back to `1`.

Merchants are hard-coded and always available:

| ID | Name |
|---|---|
| `1` | Corebase Shop |
| `2` | Tech Store |

Every new order is assigned to merchant `1` and starts with status `PENDING`.

## 4. Test with GraphiQL

Open http://localhost:8080/graphiql?path=/api/graphql. Write an operation in the editor, press **Run** (or `Cmd+Enter`), and the JSON response appears on the right.

### 4.1 The screen

```text
┌────┬──────────────────────────────┬──────────────────────────┐
│ 📖 │  Query editor (you type)     │  Response (JSON)         │
│ 🕘 │                              │                          │
│    │                         [▶]  │                          │
│    ├──────────────────────────────┤                          │
│    │ Variables | Headers          │                          │
└────┴──────────────────────────────┴──────────────────────────┘
```

- **📖 Docs** (left sidebar): lists the whole schema. Click `Query` or `Mutation` to see each field, its arguments and its types.
- **🕘 History**: the operations you've already run, so you can run them again.
- **▶ Run**: runs the operation.
- **Variables / Headers**: tabs below the editor. Variables is where JSON values for `$variables` go.

### 4.2 A first session, step by step

The database is empty after every restart, so start by creating an order.

**1. Create an order.** Clear the editor, then paste:

```graphql
mutation {
  createOrder(input: { merchantReference: "ORDER-1", amount: 49.95, currency: "EUR" }) {
    id
    status
  }
}
```

Press ▶. Expected: `{"data":{"createOrder":{"id":"1","status":"PENDING"}}}`.

**2. List orders.** Replace the text with:

```graphql
{
  orders {
    id
    merchantReference
    amount
    status
  }
}
```

**3. Ask only for the fields you need.** Delete `amount` from the query and run it again. The response no longer includes it: the client chooses the shape of the response.

**4. Use variables.** In the editor:

```graphql
query FindOrder($id: ID!) {
  order(id: $id) { id merchantReference status }
}
```

Then put this in the **Variables** tab at the bottom:

```json
{ "id": "1" }
```

**5. Fetch nested data in one request:**

```graphql
{
  merchant(id: "1") {
    name
    orders { id merchantReference }
  }
}
```

### 4.3 Shortcuts

| Shortcut | What it does |
|---|---|
| `Cmd+Enter` | Run the operation |
| `Ctrl+Space` | Autocomplete fields and arguments from the schema. Try it inside `{ }` |
| `Shift+Ctrl+P` | Prettify (format) the query |
| Hover over a field | Shows its type and description |
| `Cmd+click` a field | Opens that field in the Docs panel |

Red underlines are schema validation errors, such as an unknown field or a missing required argument. They show up before you run anything.

### 4.4 Running the saved examples

1. Paste the whole of [`graphiql-queries.graphql`](graphiql-queries.graphql) into the editor.
2. For operations that use variables (`CreateOrder`, `FindOrder`), copy the JSON shown in the comments into the **Variables** tab.
3. Because the file holds several named operations, ▶ asks which one to run. You can also place the cursor inside an operation and press `Cmd+Enter` to run just that one.

Because the database starts empty, run the operations in this order:

1. `ListOrders` → `[]`
2. `CreateOrderInline` and `CreateOrder` → creates orders `1` and `2`
3. `ListOrders` → now returns both orders
4. `GetOrderInline` / `FindOrder` → returns order `1`
5. `MerchantOneWithOrders` → merchant 1 with both orders
6. `MerchantTwoWithOrders` → merchant 2 with `orders: []`
7. `MerchantWithoutOrdersField` → merchant 1 only; the `Merchant.orders` resolver is not called

> The comments in `graphiql-queries.graphql` still describe the old in-memory data (two pre-loaded orders). With H2, those orders no longer exist until you create them.

## 5. Test with curl

With the application running, open a second terminal. Each request is a `POST` with a JSON body containing `query` and, optionally, `variables`.

### 5.1 List orders (empty on a fresh start)

```bash
curl -s -X POST http://localhost:8080/api/graphql \
  -H 'Content-Type: application/json' \
  -d '{"query":"{ orders { id merchantReference status } }"}'
```

Expected:

```json
{"data":{"orders":[]}}
```

### 5.2 Create an order (using variables)

```bash
curl -s -X POST http://localhost:8080/api/graphql \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "mutation CreateOrder($input: CreateOrderInput!) { createOrder(input: $input) { id merchantReference amount currency status } }",
    "variables": { "input": { "merchantReference": "ORDER-10001", "amount": 49.95, "currency": "EUR" } }
  }'
```

Expected:

```json
{"data":{"createOrder":{"id":"1","merchantReference":"ORDER-10001","amount":49.95,"currency":"EUR","status":"PENDING"}}}
```

### 5.3 Find one order by ID

```bash
curl -s -X POST http://localhost:8080/api/graphql \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "query FindOrder($id: ID!) { order(id: $id) { id merchantReference status } }",
    "variables": { "id": "1" }
  }'
```

Expected:

```json
{"data":{"order":{"id":"1","merchantReference":"ORDER-10001","status":"PENDING"}}}
```

### 5.4 Merchant with nested orders

```bash
curl -s -X POST http://localhost:8080/api/graphql \
  -H 'Content-Type: application/json' \
  -d '{"query":"{ merchant(id: \"1\") { id name orders { id merchantReference } } }"}'
```

Expected:

```json
{"data":{"merchant":{"id":"1","name":"Corebase Shop","orders":[{"id":"1","merchantReference":"ORDER-10001"}]}}}
```

Merchant `2` returns `"orders":[]`, because new orders are always assigned to merchant `1`.

### 5.5 Edge cases worth checking

| Scenario | Request | Expected response |
|---|---|---|
| Unknown order | `{ order(id: "999") { id } }` | `{"data":{"order":null}}` |
| Unknown merchant | `{ merchant(id: "3") { id name } }` | `{"data":{"merchant":null}}` |
| Missing required input field | `mutation { createOrder(input: {merchantReference: "X", currency: "EUR"}) { id } }` | `errors` array with `classification: ValidationError` (`amount` is required) |

GraphQL returns HTTP `200` even for validation errors; always check the `errors` field in the response body.

## 6. Inspecting the H2 database (optional)

The H2 console dependency is on the classpath, but the console is **not enabled**, so `http://localhost:8080/h2-console` returns `404`. To enable it locally, add these lines to `src/main/resources/application.properties`:

```properties
spring.h2.console.enabled=true
spring.datasource.url=jdbc:h2:mem:graphql-payments
```

Restart the app, open http://localhost:8080/h2-console, use JDBC URL `jdbc:h2:mem:graphql-payments`, user `sa`, empty password, and run:

```sql
SELECT * FROM ORDERS;
```

Without a fixed `spring.datasource.url`, Spring Boot generates a random in-memory database name on each start, which makes the console harder to connect to.

## 7. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `Port 8080 was already in use` | Another instance is running. Find and stop it: `lsof -ti tcp:8080 \| xargs kill` |
| `orders` is always `[]` | Expected after a restart; create orders first (section 5.2). |
| GraphiQL loads but requests fail | Make sure the URL includes `?path=/api/graphql`; the endpoint is not the default `/graphql`. |
| Test task shows `UP-TO-DATE` and nothing runs | Use `./gradlew test --rerun-tasks`. |
