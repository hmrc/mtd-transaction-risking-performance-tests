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

import io.gatling.core.Predef._
import io.gatling.core.action.Action
import io.gatling.core.action.builder.ActionBuilder
import io.gatling.core.session.Session
import io.gatling.core.structure.{ChainBuilder, ScenarioContext}
import io.gatling.http.Predef._
import io.gatling.http.request.builder.HttpRequestBuilder
import java.time.Instant
import scala.concurrent.duration._

object Requests {

  val reportPattern =
    "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"

  val correlationPattern = "^[0-9A-Fa-f]{64}$"

  private def authHeader(variable: String): String =
    if (!Settings.auth) ""
    else {
      val token = sys.env.getOrElse(variable, "")
      require(
        token.nonEmpty && !token.startsWith("Bearer "),
        s"Set a raw bearer token in environment variable $variable"
      )
      s"Bearer $token"
    }

  def records(path: String): Vector[Map[String, Any]] = {
    require(path.nonEmpty, "A dataset is required")

    val rows = csv(path).readRecords.toVector
    require(rows.nonEmpty, s"Empty dataset: $path")

    rows.map { row =>
      def value(key: String): String =
        row.getOrElse(key, "").toString

      require(value("vrn").matches("[0-9]{9}"), s"Invalid VRN in $path")
      require(
        value("periodKey").matches("[a-zA-Z0-9#]{4}"),
      )

      row + ("authHeader" -> authHeader(value("tokenEnv")))
    }
  }

  private def post(endpoint: String, path: String): HttpRequestBuilder =
    http(s"#{phase}.#{consumer}.$endpoint")
      .post(s"${Settings.prefix}$path")
      .headers(
        if (Settings.auth) Map("Authorization" -> "#{authHeader}")
        else Map.empty[String, String]
      )
      .requestTimeout(Settings.timeout.seconds)
      .check(
        status.saveAs("httpStatus"),
        responseTimeInMillis.saveAs("latency")
      )

  private def begin(endpoint: String): ChainBuilder = exec { session =>
    Measurements.start(
      session("phase").as[String],
      endpoint,
      session("consumer").as[String]
    )

    // Acknowledgement keeps ids from preceding feedback response
    val clean =
      if (endpoint == "acknowledgement") session
      else session.removeAll("reportId", "correlationId")

    clean
      .removeAll("httpStatus", "latency")
      .set("requestStart", System.nanoTime())
      .markAsSucceeded
  }

  // records the failure before HMRCs exitBlockOnFail exits the journey
  private def finish(endpoint: String): ChainBuilder =
    exec(new ActionBuilder {
      override def build(ctx: ScenarioContext, next: Action): Action =
        new Action {
          override val name: String = s"record-$endpoint"

          override def execute(session: Session): Unit = {
            val elapsed = session("latency").asOption[Int].getOrElse(
              ((System.nanoTime() - session("requestStart").as[Long]) /
                1000000).toInt
            )

            Measurements.finish(
              session("phase").as[String],
              endpoint,
              session("consumer").as[String],
              session("httpStatus").asOption[Int].getOrElse(0),
              !session.isFailed,
              elapsed
            )

            next ! session
          }
        }
    })

  def feedback(noFeedback: Boolean = false): ChainBuilder = {
    val endpoint = if (noFeedback) "no-feedback" else "feedback"

    val request = post(endpoint, "/feedback/#{vrn}")
      .body(ElFileBody("feedback.json"))
      .asJson
      .check(
        status.in((session:Session) =>
          Seq(200, session("allowedStatus").as[Int])
        )
      )
      .checkIf((response, _) => response.status.code == 200) (
        header("X-CorrelationId").exists,
        jsonPath("$.reportId")
          .ofType[String]
          .transform(_.matches(reportPattern))
          .is(true),
        jsonPath("$.reportId").saveAs("reportId"),
        jsonPath("$.englishFeedback").ofType[Seq[Any]].exists,
        jsonPath("$.welshFeedback").ofType[Seq[Any]].exists,
        jsonPath("$..itemNumber")
          .findAll
          .transform(_.forall(_.matches("[0-9]{1,3}")))
          .is(true)
    )
      .checkIf((response, _) => response.status.code == 200 && ! noFeedback) (
        jsonPath("$.correlationId")
          .ofType[String]
          .transform(_.matches(correlationPattern))
          .is(true),
        jsonPath("$.correlationId").saveAs("correlationId"),
        jsonPath("$..itemNumber")
          .findAll
          .transform(_.exists(_ != "0"))
          .is(true)
      )
      .checkIf((response, _) => response.status.code == 200 && noFeedback) (
        jsonPath("$..itemNumber")
          .findAll
          .transform(_.forall(_ == "0"))
          .is(true),
        jsonPath("$.correlationId").notExists
      )
      .checkIf((response, _) => response.status.code == 500) (
        jsonPath("$.code").is("INTERNAL_SERVER_ERROR")
      )

    exec(begin(endpoint)).exec(request).exec(finish(endpoint))
  }

  def acknowledge: ChainBuilder =
    exec(begin("acknowledgement"))
      .exec(
        post(
          "acknowledgement",
          "/acknowledge/#{vrn}/#{reportId}/#{correlationId}"
        )
          .queryParam("presentedDateTime", _ => Instant.now().toString)
          .check(status.is(204))
      )
      .exec(finish("acknowledgement"))

  def prepare(consumer: String): ChainBuilder = exec { session =>
    session
      .set("consumer", consumer)
      .set(
        "phase",
        session("phase").asOption[String]
          .getOrElse(if (Settings.smoke) "smoke" else "run")
      )
      .set(
        "allowedStatus",
        session("allowedStatus").asOption[Int].getOrElse(200)
      )
      .set(
        "authHeader",
        authHeader(session("tokenEnv").asOption[String].getOrElse(""))
      )
  }
}
