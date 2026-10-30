/*
 * Copyright 2026 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.perftests.simulations.STRSimulation

import uk.gov.hmrc.performance.simulation.{Journey, PerformanceTestRunner}
import io.gatling.core.Predef._
import io.gatling.core.structure.{ChainBuilder, PopulationBuilder}
import io.gatling.http.Predef._
import uk.gov.hmrc.perftests.{Common, Measurements, Requests, Settings, Workload}

import scala.concurrent.duration._

class STRSimulation extends PerformanceTestRunner {
  private val c = Settings
  private val selected = definitions(labels)
  private val quota = Set("consumer-limits", "downstream-limits")(c.label)
  require(selected.nonEmpty, "No journeys selected")
  selected.filter(_.feeder.nonEmpty).foreach(d => Requests.records(d.feeder))
  if (c.label == "consumer-limits") {
    val a = Requests.records(c.data).map(_("authHeader")).toSet
    val b = Requests.records(c.dataB).map(_("authHeader")).toSet
    require(a.intersect(b).isEmpty, "Use distinct credentials for two confirmed OAuth applications")
  }

  override lazy val constantRateTime: FiniteDuration =
    readProperty("perftest.constantRateTime", if (c.label == "soak") "240" else "5").toInt.minutes
  require(constantRateTime.toSeconds > 1 && rampUpTime.toSeconds > 0 && rampDownTime.toSeconds > 0,
    "Use positive durations in minutes")
  require(loadPercentage > 0, "perftest.loadPercentage must be positive")
  override val httpProtocol = http.baseUrl(Common.baseUrl)
    .acceptHeader("application/vnd.hmrc.1.0+json")
    .userAgentHeader("mtd-transaction-risking-performance-tests")
    .disableCaching.disableFollowRedirect.shareConnections

  private def register(id: String, chain: ChainBuilder): Unit =
    setup(id, id).withActions(chain.actionBuilders.reverse: _*)
  register("prepare", Requests.prepare("A"))
  register("prepare-b", Requests.prepare("B"))
  register("feedback", Requests.feedback())
  register("no-feedback", Requests.feedback(noFeedback = true))
  register("acknowledge", Requests.acknowledge)

  private def consumer(i: Int): String = if (selected(i).parts.contains("prepare-b")) "B" else "A"
  private val endpoints = selected.map(_.parts.collect {
    case "feedback" => "feedback"
    case "no-feedback" => "no-feedback"
    case "acknowledge" => "acknowledgement"
  })
  private def cost(i: Int): Int = endpoints(i).size
  private def policyFor(client: String, policy: String): String = if (client == "A") policy else "success"
  private def plan(name: String, rates: Seq[Double], seconds: Int, effective: Double,
                   policy: String = "success"): Unit = {
    selected.indices.groupBy(consumer).foreach { case (client, indexes) =>
      val counts = indexes.flatMap { i =>
        val count = (rates(i) * effective).toLong
        require(count > 0, "Increase the rate or duration to exercise every selected journey")
        endpoints(i).map(_ -> count)
      }.groupMapReduce(_._1)(_._2)(_ + _)
      Measurements.define(Measurements.Plan(name, client, seconds,
        indexes.map(i => rates (i) * cost(i)).sum, policyFor(client, policy), counts))
    }
  }

  override def setUp(populations: List[PopulationBuilder]): SetUp = {
    val result = super.setUp(populations).assertions(global.allRequests.count.gt(0L),
      forAll.responseTime.percentile(95).lte(c.p95.toInt), forAll.responseTime.percentile(99).lte(c.p99.toInt))
    if (c.smoke) result.assertions(global.allRequests.count.is(selected.indices.map(cost).sum.toLong)) else result
  }
  private def gate(name: String): PopulationBuilder = scenario(s"evaluate-$name").exec { session =>
    Measurements.assess(name)
    Measurements.write()
    // A failed spike must still run recovery before the test fails
    session.set("criteriaFailed", Measurements.hasFailures && !(c.label == "spike" && name == "spike"))
  }.crashLoadGeneratorIf("Measured criteria failed; see target/evidence", s => s("criteriaFailed").as[Boolean])
    .inject(atOnceUsers(1))

  override protected def withInjectedLoad(journeys: Seq[Journey]): Seq[PopulationBuilder] = {
    require(!quota || loadPercentage == 100, "Quota experiments require perftest.loadPercentage=100")
    val duration = c.seconds("stageSeconds", constantRateTime.toSeconds.toInt)
    lazy val recovery = c.seconds("recoverySeconds", 120)
    val modelled = journeys.zipWithIndex.map { case (journey, i) => new Journey {
      override val load: Double = journey.load * (if (quota) 1d
        else if (selected(i).parts.contains("no-feedback")) Workload.target.noFeedbackRps
        else Workload.target.feedbackRps)
      override def builder = journey.builder
    }}
    val rates = modelled.map(_.load * loadFactor)
    def stage(name: String, rates: Seq[Double], seconds: Int,
              policy: String = "success"): Seq[Seq[PopulationBuilder]] = {
      plan(name, rates, seconds, seconds, policy)
      val populations = journeys.zipWithIndex.map { case (journey, i) =>
        val allowed = Map("boundary" -> 429, "throttle" -> 429, "downstream" -> 500)
          .getOrElse(policyFor(consumer(i), policy), 200)
        scenario(s"$name.${selected(i).id}").exec(s => s.set("phase", name).set("allowedStatus", allowed))
          .exec(journey.builder).inject(constantUsersPerSec(rates(i)).during(seconds.seconds))
      }
      Seq(populations, Seq(gate(name)))
    }
    def quiet(name: String, seconds: Int): Seq[Seq[PopulationBuilder]] =
      Seq(Seq(scenario(name).pause(seconds.seconds).inject(atOnceUsers(1))))

    val stages = c.label match {
      case "spike" =>
        stage("baseline", rates, duration) ++
          stage("spike", rates.map(_ * c.number("spikeMultiplier", 4)), c.seconds("spikeSeconds", 30)) ++
          stage("recovery", rates, recovery)
      case "capacity" =>
        val start = c.number("capacityStart", .25)
        val step = c.number("capacityStep", .25)
        val ceiling = c.number("capacityCeiling", 2)
        require(start <= ceiling && (ceiling - start) / step <= 100, "Use at most 101 capacity stages")
        Iterator.iterate(start)(_ + step).takeWhile(_ <= ceiling + 1e-9).toVector.zipWithIndex.flatMap {
          case (factor, i) => stage(f"capacity-${i+1}%03d", rates.map(_ * factor), duration)
        }
    case "consumer-limits" =>
      val window = c.seconds("limitWindowSeconds", 0)
      val seconds = math.max(duration, window * 3)
      def clients(multiplier: Double): Seq[Double] = journeys.indices.map(i =>
        if (consumer(i) == "B") c.number("consumerBLimitRps", 0) * .5
        else c.number("consumerLimitRps", 0) * multiplier)
      stage("warmup", clients(.25).map(_ * .5), c.seconds("warmupSeconds", 30)) ++
        quiet("inital-quota-reset", window * 2) ++ stage("below", clients(.8), seconds) ++
        stage("boundary", clients(1), seconds, "boundary") ++
        stage("above", clients(1.5), seconds, "throttle") ++
        quiet("quota-reset", window * 2) ++ stage("recovery", clients(.5), seconds)
    case "downstream-limits" =>
      val limit = c.number("downstreamLimitRps", 0)
      stage("baseline", Seq(limit * .5), duration) ++
        stage("overload", Seq(limit * 2), duration, "downstream") ++
        quiet("downstream-settle", c.seconds("recoverySettleSeconds", 5)) ++
        stage("recovery", Seq(limit * .5), recovery)
    case _ =>
      val seconds = (rampUpTime + constantRateTime + rampDownTime).toSeconds.toInt
      val effective = constantRateTime.toSeconds + (rampUpTime + rampDownTime).toSeconds / 2d
      plan("run", rates, seconds, effective)
      Seq(super.withInjectedLoad(modelled), Seq(gate("run")))
  }
  val root = scenario("coordinator").pause(1.millisecond).inject(atOnceUsers(1))
  Seq(stages.foldLeft(root)((previous, next) => previous.andThen(next: _*)))
}

  if (c.smoke) plan("smoke", Seq.fill(selected.size)(1d), 1, 1)
  Workload.write(c.out)
    runSimulation()
    after {
      if (c.smoke) Measurements.assess("smoke")
      Measurements.write()
    }
  }
