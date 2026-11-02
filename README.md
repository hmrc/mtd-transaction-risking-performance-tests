# mtd-transaction-risking-performance-tests

Performance test suite for the `mtd-transaction-risking` (https://github.com/hmrc/mtd-transaction-risking, using [performance-test-runner](https://github.com/hmrc/performance-test-runner) under the hood.

This suite tests feedback and acknowledgement journeys under normal, peak and sustained load. It also checks traffic spikes, application rate limits and downstream throttling.

## Nomenclature 
- **VRN**: VAT Registration Number
- **RPS**: Requests per seconds
- **p95 / p99**: Response times within which 95% / 99% of requests complete
- **Actionable feedback**: Feedback that requires an acknowledgement
- **Capacity**: The highest tested load that meets the configured performance criteria

## Technical documentation

### Before running the app

install Java 17 and sbt

Start Mongo Docker container following instructions from the [MDTP Handbook](https://docs.tax.service.gov.uk/mdtp-handbook/documentation/developer-set-up/set-up-mongodb.html).

For local testing, start the services described in:

- mtd-transaction-risking
- mtd-transaction-risking-stub

The tests use `http://localhost:9858` by default

## Running the test suite

Check formatting, compile and run the local smoke tests:

```bash
sbt -Drunlocal=true -Dperftest.runSmoke=true \
  scalafmtCheckAll scalafmtSbtCheck 'Gatling/compile' gatling:test
```

To run only the smoke tests

```bash
sbt -Drunlocal=true -Dperftest.runSmokeTest=true gatling:test
```

Performance modes run separately. Run full staging load tests through HMRC Performance Jenkins

### Further documentation

See the [performance testing guide](docs/performance-testing.md) for test modes, configuration, Jenkins setup and results

### Logging

The default log level for all HTTP requests is set to `WARN`. Configure [logback.xml](src/test/resources/logback.xml) to update this if required.

### WARNING :warning:

Do **NOT** run a full performance test against staging from your local machine. Please [implement a new performance test job](https://docs.tax.service.gov.uk/mdtp-handbook/documentation/mdtp-test-approach/performance-testing/performance-test-a-microservice/index.html) and execute your job from the dashboard in [Performance Jenkins](https://performance.tools.staging.tax.service.gov.uk).

## License

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html").
