# Testing ITube

| Layer | What | Where | Runs |
|---|---|---|---|
| Unit tests | one class, everything else mocked | `backend/*/src/test/java/**/*Test(s).java` | `./mvnw test`, CI |
| **BDD scenarios (Cucumber)** | one whole service, real Spring context, in-memory databases, neighbouring services faked | `backend/*/src/test/resources/features/*.feature` | `./mvnw test`, CI |
| **End-to-end journeys (Cucumber)** | the whole running stack through the API gateway, like a browser | [backend/e2e-tests](../backend/e2e-tests) | by hand against a running stack |
| Load, spike, soak, chaos (k6) | throughput, latency, behaviour under overload and failures | [loadtest](../loadtest) | by hand |
| Frontend | React components | `frontend/src/**/*.test.jsx` | `npx vitest run`, CI |

## BDD with Cucumber

BDD (behaviour-driven development) describes behaviour in plain sentences everyone on the team can read, product
people included, and runs those sentences as tests. A scenario is written in Gherkin:

```gherkin
Scenario: History keeps working while the catalog is down
  Given "Sam" has watched "Rust in production"
  And catalog-service is down
  When "Sam" watches "Rust in production"
  Then the history of "Sam" still has 2 entries, without video details
```

Each line is matched to a Java method (a "step definition") by its text:

```java
@Given("catalog-service is down")
public void catalogIsDown() {
    catalog.setDown(true);
}
```

### The suites

| Service | Feature files | What they cover |
|---|---|---|
| identity | [accounts.feature](../backend/identity-service/src/test/resources/features/accounts.feature) | registration, sign-in, shared session, registration and sign-in while the catalog is down |
| catalog | [sharding.feature](../backend/catalog-service/src/test/resources/features/sharding.feature), [caching-and-resilience.feature](../backend/catalog-service/src/test/resources/features/caching-and-resilience.feature) | data placement on two real databases, ids, feed/search/subscriptions across shards, cache invalidation, payments outage |
| engagement | [engagement.feature](../backend/engagement-service/src/test/resources/features/engagement.feature) | history, likes, comments, behaviour while the catalog is down |
| payments | [entitlements.feature](../backend/payments-service/src/test/resources/features/entitlements.feature) | who may watch members-only videos (a Scenario Outline table), memberships page while the catalog is down |
| everything | [e2e-tests/.../features](../backend/e2e-tests/src/test/resources/features) | a creator uploads (resumable, straight to storage), a viewer watches, likes, comments, subscribes; one sign-in works everywhere; internal APIs are hidden; sign-in rate limits |

### How they're wired

- `bdd/CucumberTest.java` runs every `.feature` file through the JUnit Platform, so `./mvnw test` runs them.
- `bdd/*CucumberConfig.java` (`@CucumberContextConfiguration`) starts the real service once, with the `h2` profile
  (in-memory databases, two of them for the catalog's shards) and `@MockitoBean` stand-ins for the other services.
- The step classes get a fresh instance per scenario, so their fields are per scenario. The databases live for
  the whole run, so scenarios use unique names and ids.
- HTML report per service: `backend/<service>/target/cucumber-report.html` (CI uploads them as an artifact).

### Writing a new scenario

1. Add the scenario to a `.feature` file in the language of the feature ("When a viewer likes a video"), not the
   implementation ("When PUT /api/liked-videos/5").
2. Run `./mvnw -pl <service> test -Dtest=CucumberTest`. Cucumber prints the missing step definitions as code
   snippets.
3. Implement them in the service's `bdd` step class, reusing existing steps where you can.

## End-to-end journeys

They need everything running: start the stack (`.\dev.cmd`), then

```bash
cd backend
./mvnw -pl e2e-tests test -Pe2e                                     # against http://localhost:8080
./mvnw -pl e2e-tests test -Pe2e -De2e.baseUrl=https://itube.<domain> # against a deployed environment
```

Without `-Pe2e` they're skipped, so a normal build doesn't need a running stack. `99-rate-limits.feature` runs last
on purpose: it uses up this machine's sign-in allowance at the gateway for about a minute.
