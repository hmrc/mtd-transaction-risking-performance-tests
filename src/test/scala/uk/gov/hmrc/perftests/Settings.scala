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
import java.nio.file.{Files, Paths}
import uk.gov.hmrc.performance.conf.Configuration

object Settings extends Configuration {
  def text(key: String, default: String): String =
    readProperty(s"api.$key", default).trim

  def number(key: String, default: Double): Double = {
    val n = text(key, default.toString).toDouble
    require(n.isFinite && n > 0, s"api.$key must be finite and positive")
    n
  }

  def seconds(key: String, default: Int): Int = {
    val n = number(key, default).toInt
    require(n > 0, s"api.$key must be at least one second")
    n
  }

  val smoke = readProperty("perftest.runSmokeTest", "false").toBoolean
  private val requested = readProperty("perftest.labels", "").trim
  val label = if (smoke || requested.isEmpty) "year-one-peak" else requested

  require(Set("year-one-peak", "mature-peak", "soak", "spike", "capacity",
    "consumer-limits", "downstream-limits")(label), "Select one supported perftest.labels value")

  val profile = if (smoke) "smoke" else label
  val experiment = !smoke && Set("spike", "capacity", "consumer-limits", "downstream-limits")(label)
  val consumerLimits = !smoke && label == "consumer-limits"

  val topology = text("topology", "unknown")


  require(
    Set("unknown", "downstream-stub", "integrated", "shortcut-stub")(topology),
    "api.topology must be unknown, downstream-stub, integrated or shortcut-stub"
  )

  require(
    topology != "shortcut-stub" || smoke,
    "Shortcut stubs are only suitable for smoke tests"
  )

  if(!smoke && Set("capacity", "consumer-limits", "downstream-limits")(label))
    require(topology != "unknown", "Confirm api.topology before a capacity or rate-limit experiment")

  val prefix = text("pathPrefix", "").stripSuffix("/")
  require(prefix.isEmpty || prefix.startsWith("/"), "api.pathPrefix must start with /")

  val data = readProperty("testData.consumerA", "data/consumer-a.csv")
  val dataB = readProperty("testData.consumerB", "data/consumer-b.csv")
  val timeout = seconds("timeoutSeconds", 10)
  val p95 = number("p95Ms", 1000)
  val p99 = number("p99Ms", 2000)
  val window = seconds("windowSeconds", 30)

  val errorPct = readProperty("perftest.percentageFailureThreshold", "1").toInt

  require(
    errorPct >= 0 && errorPct < 100,
    "Failure threshold must be in [0,100]"
  )

  if (consumerLimits) {
    require(text("consumerApplicationsConfirmed", "false").toBoolean,
      "Confirm two distinct OAuth applications and the route enforcing their quotas")
    number("consumerLimitRps", 0)
    number("consumerBLimitRps", 0)
    seconds("limitWindowSeconds", 0)
  }

  if (label == "downstream-limits" && !smoke) {
    require(
      text("limiterConfigured", "false").toBoolean,
      "Configure a downstream limiter first"
    )
    number("downstreamLimitRps", 0)
  }

  val out = Paths.get(
    text("out", s"target/gatling-evidence/$profile-${System.currentTimeMillis()}")
  )

  Files.createDirectories(out)
  Files.write(out.resolve("setup.txt"),
    s"profile=$profile\ntopology=$topology\nCapacity conclusions require confirmed dependencies and telemetry.\n"
      .getBytes(UTF_8))

}
