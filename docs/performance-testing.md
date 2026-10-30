# Performance testing guide

## Test Coverage

The suite exercises: 

- Actionable feedback: `POST /feedback/:vrn`, expecting HTTP 200
- No feedback: `POST /feedback/:vrn`, expecting HTTP 200 with no-feedback response
- Acknowledgement: `POST /acknowledge/:vrn/:reportId/:correlationId`, expecting HTTP 204

Acknowledgements use the IDs returned by the preceding feedback request 

## Test modes

| Mode | Purpose |
|---| ---|
| Smoke | Checks basic journey. The default selection sends three HTTP requests. |
| `year-one-peak` | Tests the forecast peak traffic at 51% uptake |
| `mature-uptake` | Tests the forecast peak traffic at 67% uptake |
| `soak` | Checks sustained performance, with a four-hour constant-load phase by default |
| 'spike` | Runs a baseline, a four-times traffic spike and recovery |
| `capacity` | Increases traffic until a stage fails or the configured ceiling is reached | 
|`consumer-limits` | Exercises two applications to check throttling and application isolation |
| `downstream-limits` | Checks behaviour when a downstream quota is exceeded, followed by recovery | 

Select one mode using `-Dperftest.labels=MODE`

Smoke uses `-Dperftest.runSmoke=true` instead. Remove this flag before running a load test

The default label is `year-one-peak`

## Running locally

Start the API, MongoDB and the required stubs before running the tests

The supplied datasets use VRN `123456789` with: 

- Period `18AD` for actionable feedback
- Period `RD04` for no feedback

Run a small load test: 

```bash
sbt -Drunlocal=true \
    -Dperftest.labels=year-one-peak \
    -Dperftest.loadPercentage=5 \
    -Dperftest.constantRateTime=1 \
    gatling:test
```

## Running in Jenkins

Run full staging load tests through the HMRC Performance Jenkins job

To finish once Jenkins Pipeline has been created

## Forecast workload

The default mode assumes:

- 9.2 million annual VAT returns
- 51% year-one uptake and 67% mature uptake
- 10% of customers receiving actionable feedback
- Three feedback/acknowledgement cycles for those customers
- Three no-feedback requests for each remaining customer
- 70% of an average month's traffic occurring with four hours

| Forecast | Total incoming API RPS |
| --- | ---: |
| Year one | 62.72 |
| Mature | 82.40 |

Each run records them in `workload.csv`

## Main configuration 

Pass settings using `-Dproperty=value`

| Property | Default | Purpose |
| --- | --- | ---|
| `perftest.loadPercentage` | `100` | Percentage of forecast load |
| `perftest.rampupTime` | `1` | Ramp-up duration in minutes |
| `perftest.constantRateTime` | `5`; `240` for soak | Constant-load duration in minutes |
| `perftest.rampdownTime` | `1` | Ramp-down in minutes |
| `api.stageSeconds` | `300` for staged modes | Stage duration in seconds |
| `api.spikeMultiplier` | `4` | Spike load multiplier | 
| `api.recoverySeconds` | `120` | Spike/downstream recovery duration |
| `api.p95Ms | `1000` | p95 latency threshold | 
| `api.p99Ms | `2000` | p99 latency threshold | 
| 'perftest.percentageFailureThreshold` | `1` | Overall failure percentage allowed in normal phases |
| 'perftest.requestPercentageFailureThreshold`| `1` | Per-endpoint failure percentage allowed in normal phases |


## Capacity: testing until failure

Use: 
- `capacity-summary.txt` for the last passing and first failing stages
- `stage-summary.csv` for measured RPS, request counts and cumulative volume
- `decisions.txt` for the failure reason

'LIMIT_NOT_REACHED' means all configured stages passed. It does not establish the upper limits.

## Reports 

Gatling reports are written under `target/gatling/`. Open the generated runs `index.html`

| File | Contents |
| --- | --- |
| `workload.csv | Forecast assumptions and unscaled rates |
| `plan.csv` | Planned stages and request volumes |
| `metrics.csv` | Endpoint counts, HTTP statuses and latency | 
|`stage-summary.csv` | Measured RPS, volumes and stage decisions |
| `decisions.txt` | Assessment results and failure reasons |
| `run-properties` | Run settings and assumptions |
| `capacity-summary.txt` | Capactiy outcome, for capacity runs |

## Repository Structure

| File | Responsibility | 
| --- | --- |
| `Settings.scala` | Reads and validates configuration | 
| 'Workload.scala` | Calculate forecast traffic |
| `Requests.scala` | Builds requests and checks responses |
| `Measurements.scala` | Records results and assesses stages | 
| `STRSimulation.scala` | Selects journeys and controls load |
|`src/test/resources/` | Configuration, request bodies and datasets |

