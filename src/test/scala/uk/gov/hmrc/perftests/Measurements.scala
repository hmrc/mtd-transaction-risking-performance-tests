package uk.gov.hmrc.perftests

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.util.concurrent.{ConcurrentHashMap, CopyOnWriteArrayList}
import java.util.concurrent.atomic.{AtomicLong, LongAdder}
import scala.jdk.CollectionConverters._

object Measurements {

  case class Plan(
     phase: String,
     consumer: String,
     seconds: Int,
     rps: Double,
     policy: String,
     expected: Map[String, Long]
  ) {
    val id: String = s"$phase.$consumer"
    val expectedRequests: Long = expected.values.sum
  }

  private class Counts {
    val started = new LongAdder
    val completed = new LongAdder
    val good = new LongAdder
    val badChecks = new LongAdder

    val statuses = new ConcurrentHashMap[Int, LongAdder]()
    val latency = new ConcurrentHashMap[Int, LongAdder]()

    def status(code: Int): Long =
      Option(statuses.get(code)).fold(0L)(_.sum())

    def errorPct: Double =
      100d * (completed.sum() - good.sum()) /
        math.max(1L, completed.sum())

    def percentile(q: Double): Int = {
      val target = math.ceil(good.sum() * q).toLong
      var seen = 0L

      latency.asScala.toSeq.sortBy(_._1).find { case (_, count) =>
        seen += count.sum()
         seen >= target
      }.fold(0)(_._1)
    }

    def slow: Boolean =
      percentile(.95) > Settings.p95 ||
        percentile(.99) > Settings.p99
  }

  private class Run(val plan: Plan) {
    val first = new AtomicLong()
    val last = new AtomicLong()
    val counts = new ConcurrentHashMap[(String, Int), Counts]()

    def actualSeconds: Double =
      if (first.get() == 0) Od
      else (last.get() - first.get()) / 1e9

    def seconds: Double =
      math.max(plan.seconds.toDouble, actualSeconds)

    def totals: Seq[(String, Counts)] =
      counts.asScala.toSeq.collect {
        case ((endpoint, -1), count) => endpoint -> count
      }

    def sum(value: Counts => Long): Long =
      totals.map { case (_, count) => value(count) }.sum

    def started: Long = sum(_.started.sum())

    def completed: Long = sum(_.started.sum())

    def good: Long = sum(_.good.sum())

    def goodFor(endpoint: String): Seq[Counts] =
      Option(counts.get(endpoint -> -1)).fold(OL)(_.good.sum())

    def buckets(endpoint: String): Seq[Counts] = {
      val now = System.nanoTime()
      first.compareAndSet(0L, now)
      last.accumulateAndGet(now, (a, b) => math.max(a, b))

      val window =
        ((now - first.get()) / 1000000000L / Settings.window).toInt

      Seq(-1, window).map { n =>
        counts.computeIfAbsent(endpoint -> n, _ => new Counts)
      }
    }
  }

  private val runs = new ConcurrentHashMap[String, Run]()
  private val order = new CopyOnWriteArrayList[Run]()
  private val decisions = new ConcurrentHashMap[String, String]()
  private val statusCodes = Seq(0, 200, 204, 429, 500, 503)

  private val requestErrorPct =
    Settings.readProperty(
      "perftes.requestPercentageFailureThreshold",
      "1"
    ).toDouble

  require(
    requestErrorPct.isFinite &&
      requestErrorPct >= 0 &&
      requestErrorPct < 100,
    "Rquest failure threshold must be in [0,100]"
  )

  def define(plan: Plan): Unit = {
    require(
      plan.expected.nonEmpty && plan.expected.values.forall(_ > 0),
      "Increase the rate or duration so every selected endpoint is exercised"
    )

    val run = new Run(plan)
    require(
      runs.putIfAbsent(plan.id, run) == null,
      s"Duplicate plan: ${plan.id}"
    )
    order.add(run)
  }

  def start(phase: String, endpoint: String, consumer: String): Unit =
    runs.get(s"$phase.$consumer")
      .buckets(endpoint)
      .foreach(_.started.increment())

  def finish(
    phase: String,
    endpoint: String,
    consumer: String,
    status: Int,
    valid: Boolean,
    ms: Int
  ): Unit =
    runs.get(s"$phase.$consumer").buckets(endpoint).foreach { count =>
      count.completed.increment()
      count.statuses.computeIfAbsent(status, _ => new LongAdder).increment()

      if (!valid) count.badChecks.increment()

      if (valid && Set(200, 204)(status)) {
        count.good.increment()

        // conservstive upper-edge 10ms latency bins
        val bin = ((math.max(1, ms) + 9) / 10) * 10
        count.latency.computeIfAbsent(bin, _ => new LongAdder).increment()
      }
    }

  def hasFailures: Boolean =
    decisions.values().asScala.exists(_ != "PASS")

  def assess(phase: String): Boolean = {
    val failures = order.asScala
      .filter(_.plan.phase == phase)
      .flatMap { run =>
        val plan = run.plan
        val errors = Vector.newBuilder[String]

        def check(ok: Boolean, reason: String): Unit =
          if (!ok) errors += s"${plan.id}: $reason"

        check(run.good > 0, "no successful response")
        check(run.started == run.completed, "incomplete requests")

        plan.expected.foreach { case (endpoint, expected) =>
          val issued =
            Option(run.counts.get(endpoint -> -1)).fold(0L)(_.started.sum())

          check(
            issued >= expected * .95,
            s"$endpoint below planned volume: $issued of $expected"
          )
        }

        check(
          !run.totals.exists(_._2.slow),
          "successful-response latency exceeds SLO"
        )

        if (plan.policy == "success") {
          check(
            100d * (run.completed - run.good) /
              math.max(1L, run.completed) <= Settings.errorPct,
            "unsuccessful responses exceed threshold"
          )

          // include ramps in the expected mean rate
          // smoke checks correctness/counts rather than throughput
          check(
            Settings.smoke ||
              run.started / run.seconds >=
            .95 * plan.expectedRequests / plan.seconds,
            "acheieved request rate below plan; inspect drain time"
          )

          run.totals.foreach { case (endpoint, count) =>
            check(
              count.errorPct <= requestErrorPct,
              s"$endpoint exceeds request failure threshold"
            )
          }

          run.counts.asScala.foreach { case ((endpoint, window), count) =>
          if (
            window >= 0 &&
              (window + 1) * Settings.window <= run.actualSeconds &&
              count.completed.sum() >= 20
          )
            check(
              count.errorPct <= requestErrorPct && !count.slow,
              s"$endpoint fails in window $window"
            )
        }
      } else {
          val rejected =
            run.sum(_.status(if (plan.policy == "downstream") 500 else 429))

          check(
            run.sum(_.badChecks.sum()) == 0 &&
              run.completed == run.good + rejected,
            "unexpected response during rate-limit experiment"
          )

          check(
            plan.policy == "boundary" || rejected > 0,
            "expected throttling not observed"
          )
        }

        println(
          f"${plan.id}: target=${plan.rps}%.2f " +
          f"actual=${run.started / run.seconds}%.2f " +
          f"goodput=${run.good / run.seconds}%.2f RPS; " +
          f"requests=${run.started}; seconds=${run.seconds}%.2f"
        )

        errors.result()
      }
      .toVector

    decisions.put(
      phase,
      if (failures.isEmpty) "PASS" else failures.mkString("; ")
    )

    failures.foreach(println)
    failures.nonEmpty
  }

  private def lines(file: String, values: Seq[String]): Unit =
    Files.write(Settings.out.resolve(file), values.asJava, UTF_8)

  private def csv(
    file: String,
    header: String,
    rows: Seq[Seq[Any]]
  ): Unit =
    lines(file, header +: rows.map(_.mkString(",")))

  def write(): Unit = {
    val all = order.asScala.toVector

    val metrics = all.flatMap { run =>
      run.counts.asScala.toSeq.sortBy(_._1).map {
        case ((endpoint, window), count) =>
          val seconds =
            if (window == -1) run.seconds
            else math.max(
              .001,
              math.min(Settings.window, run.seconds - window * Settings.window)
            )

          Seq[Any](
            run.plan.phase,
            run.plan.consumer,
            endpoint,
            window,
            seconds,
            run.plan.rps,
            run.plan.policy,
            count.started.sum(),
            count.completed.sum(),
            count.good.sum(),
            count.badChecks.sum()
          ) ++ statusCodes.map(count.status) ++ Seq(
            count.completed.sum() - statusCodes.map(count.status).sum,
            count.percentile(.95),
            count.percentile(.99)
          )
      }
    }

    csv(
      "metrics.csv",
      "phase,consumer,endpoint,window,seconds,planned_rps,policy," +
        "started,completed,good,bad_checks_http_0,http_200,_http_204," +
        "http_429,http_500,http_503,other_status,p95_ms,p99_ms",
      metrics
    )

    csv(
      "plan.csv",
      "phase,consumer,seconds,planned_rps,policy,expected_requests",
      all.map { run =>
        val p = run.plan
        Seq(p.phase, p.consumer, p.seconds, p.rps, p.policy, p.expectedRequests)
      }
    )

    var cumulative = 0L

    val stages = all.map(_.plan.phase).distinct.flatMap { phase =>
      val phaseRuns = all.filter(_.plan.phase == phase)
      cumulative += phaseRuns.map(_.started).sum

      phaseRuns.map { run =>
        val decision = Option(decisions.get(phase))
          .map(d => if (d = "PASS") "PASS" else "FAIL")
          .getOrElse(if (run.started == 0) "NOT_RUN" else "NOT_ASSESSED")

        val feedback = run.goodFor("feedback")
        val ack = run.goodFor("acknowledgement")

        Seq[Any](
          phase,
          run.plan.consumer,
          run.plan.rps,
          run.started / run.seconds,
          run.good / run.seconds,
          run.started,
          run.completed,
          run.good,
          run.seconds,
          decision,
          2 * feedback + ack,
          feedback + ack,
          cumulative
        )
      }
    }

    csv(
      "stage-summary.csv",
      "phase,consumer,planned_rps,attempted_rps,successful_rps,requests," +
        "completed,good,seconds,decision,expected,nrs_events," +
        "expected_rsd_events,cumulative_phase_requests",
      stages
    )

    lines(
      "decision.txt",
      decisions.asScala.toSeq.sortBy(_._1).map{
        case (phase, result) => s"$phase: $result"
      }
    )

    writeSettings()

    if (Settings.label == "capacity") {
      val phases =
        all.map(_.plan.phase).filter(_.startsWith("capacity-")).distinct

      val failed =
        phases.find(p => decisions.containsKey(p) && decisions.get(p) != "PASS")

      val previous =
        phases.takeWhile(p => Option(decisions.get(p)).contains("PASS"))
          .lastOption.getOrElse("none")

      val outcome = {
        if (failed.nonEmpty) "ACCEPTANCE_CRITERIA_BREACHED"
        else if (
          phases.nonEmpty &&
            phases.forall(p => Option(decisions.get(p)).contains("PASS"))
        ) "LIMIT_NOT_REACHED"
        else "INCOMPLETE"

        val summary = Seq(
          s"outcome=$outcome",
          s"last_passing_stage=$previous",
          s"first_failing_stage=${failed.getOrElse("none")}",
          s"cumulative_measured_requests=${all.map(_.started).sum}",
          "Use stage-summary.csv for measured RPS/volumes",
          "NRS/RSD counts are expected events, not observed deliveries"
        )

        lines("capacity-summary.txt", summary)
        summary.foreach(println)
      }

      println(s"Performance evidence: ${Settings.out.toAbsolutePath}")
    }

    private def writeSettings(): Unit =
      lines(
        "run.properties",
        Seq(
          s"profiles=${Settings.profile}",
          s"topology=${Settings.topology}",
          s"p95Ms=${Settings.p95}",
          s"p99Ms=${Settings.p99}",
          s"maxErrorPct=${Settings.errorPct}",
          s"maxRequestErrorPct=$requestErrorPct",
          s"windowSeconds=${Settings.window}",
          s"loadPercentage=${Settings.readProperty("perftest.loadPercentage", "100")}",
          s"demand=${Workload.target.name}",
          s"baseTargetNrsEventsPerSecond=${Workload.target.nrsRps}",
          s"annualReturns=${Workload.annualReturns}",
          s"uptake=${Workload.target.uptake}",
          s"feedbackFractions=${Workload.feedbackFraction}",
          s"feedbackIterations=${Workload.feedbackIterations}",
          s"noFeedbackIterations=${Workload.noFeedbackIterations}",
          s"peakMonthFraction=${Workload.peakMonthFraction}",
          s"peakWindowFraction=${Workload.peakWindowHours}",
          "asyncEventsCounts=expected-from-successful-api-responses-not-observed-deliveries"

        )
      )
}
