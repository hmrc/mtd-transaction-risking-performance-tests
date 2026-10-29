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

package uk.gov.hmrc.perftests

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters._

object Workload {

  private def fraction(key: String, default: Double): Double = {
    val value = Settings.number(key, default)
    require(value <= 1, s"api.$key must be at most 1")
    value
  }

  val annualReturns = Settings.number("annualReturns", 9200000)
  val yearOneUptake = fraction("yearOneUptake", .51)
  val matureUptake = fraction("matureUptake", .67)
  val feedbackFraction = fraction("feedbackFraction", .10)

  require(
    feedbackFraction < 1,
    "Use a feedbackFraction below 1 to retain both workload branches"
  )

  val feedbackIterations = Settings.number("feedbackIterations", 3)

  // Conservative assumption: three no-feedback attempts per customer
  // USe 1 for the alternative
  val noFeedbackIterations = Settings.number("noFeedbackIterations", 3)

  val peakMonthFraction = fraction("peakMonthFraction", .70)
  val peakWindowHours = Settings.number("peakWindowHours", 4)

  private val peakFactor =
    peakMonthFraction / (12 * peakWindowHours * 3600)

  final case class Model(name: String, uptake: Double) {
    require(uptake > 0 && uptake <=1, "Uptake must be in (0, 1]")

    val annualPopulation = annualReturns * uptake
    val annualFeedback =
      annualPopulation * feedbackFraction * feedbackIterations
    val annualNoFeedback =
      annualPopulation * (1 - feedbackFraction) * noFeedbackIterations

    val annualAcknowledgments = annualFeedback
    val annualApi =
      annualNoFeedback + annualFeedback + annualAcknowledgments
    val annualNrs = annualFeedback * 3
    val annualRsd = annualFeedback * 2

      // Actionale feedback cycles, not all feedback endpoint requests
      val feedbackRps = annualFeedback * peakFactor
      val noFeedbackRps = annualNoFeedback * peakFactor
      val acknowledgementRps = feedbackRps
      val feedbackEndpointRps = feedbackRps + noFeedbackRps
      val apiRps = feedbackEndpointRps + acknowledgementRps
      val nrsRps = feedbackRps * 3
      val rsdRps = feedbackRps * 2
    }

    val yearOne = Model("year-one", yearOneUptake)
    val mature = Model("mature", matureUptake)

    private val demand = Settings.label match {
      case "year-one-peak" => "year-one"
      case "mature-peak" => "mature"
      case _ => Settings.text("demand", "mature")
    }

    val target: Model = demand match {
      case "year-one" => yearOne
      case "mature" => mature
      case _ =>
        throw new IllegalArgumentException(
          "api.demand must be year-one or mature"
        )
    }

    // Base forecast before scaling/retries
    // Actual NRS/RSD deliveries require separate telemetry
    def write(directory: Path): Unit = {
      def expected(
        name: String,
        unit: String,
        value: Model => Double
      ): String =
        s"expected,$name,$unit,${value(yearOne)},${value(mature)},${value(target)}"

      def assumption(name: String, unit: String, value: Double): String =
        s"assumption,$name,$unit,$value,$value,$value"

      val rows = Seq(
        "kind,metric,unit,year_one,mature,selected",
        s"selection,demand,name,${yearOne.name},${mature.name},${target.name}",
        assumption("annual_vat_returns", "returns/year", annualReturns),
        s"assumption,uptake,fraction,$yearOneUptake,$matureUptake,${target.uptake}",
        assumption("feedback_customers", "fraction", feedbackFraction),
        assumption("feedback_iterations", "cycles/customer", feedbackIterations),
        assumption("no_feedback_iterations", "attend/customer", noFeedbackIterations),
        assumption("peak_month_fraction", "fraction", peakMonthFraction),
        assumption("peak_window", "hours", peakWindowHours),
        expected("adopted_population", "returns/year", _.annualPopulation),
        expected("actionable_feedback", "cycles/year", _.annualFeedback),
        expected("no_feedback", "calls/year", _.annualNoFeedback),
        expected("acknowledgment", "calls/year", _.annualFeedback),
        expected("incoming_api", "calls/year", _.annualApi),
        expected("nrs_each_event_type", "events/year", _.annualFeedback),
        expected("nrs_total", "events/year", _.annualNrs),
        expected("rsd_total", "submissions/year", _.annualRsd),
        expected("actionable_feedback", "cycles/second", _.feedbackRps),
        expected("no_feedback", "requests/second", _.noFeedbackRps),
        expected("feedback_endpoint_total", "requests/second", _.feedbackEndpointRps),
        expected("acknowledgment", "requests/second", _.acknowledgementRps),
        expected("incoming_api", "request/second", _.apiRps),
        expected("vat_validation", "calls/second", _.feedbackEndpointRps),
        expected("insights", "calls/second", _.feedbackEndpointRps),
        expected("rds_generate", "calls/second", _.feedbackEndpointRps),
        expected("rds_acknowledge", "calls/seconds", _.acknowledgementRps),
        expected("rds_shared", "calls/second",_.apiRps),
        expected("nrs_request_event", "events/second", _.feedbackRps),
        expected("nrs_response_event", "events/second", _.feedbackRps),
        expected("ns_acknowledgment_event", "events/second", _.feedbackRps),
        expected("nrs_total", "events/second", _.nrsRps),
        expected("rsd_total", "submissions/second", _.rsdRps)
      )

      Files.createDirectories(directory)
      Files.write(directory.resolve("workload.csv"), rows.asJava, UTF_8)
    }
}
