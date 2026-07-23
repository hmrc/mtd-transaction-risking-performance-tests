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

import uk.gov.hmrc.performance.conf.ServicesConfiguration

object Common extends ServicesConfiguration {

  val baseUrl: String = baseUrlFor("mtd-transaction-risking")

  val Individual: String = "Individual"
  val Organisation: String = "Organisation"
  val Agent: String = "Agent"

  val commonHeaders: Map[String, String] =
    Map("Accept" -> s"application/vnd.hmrc.1.0+json",
      "User-Agent" -> "mtd-transaction-risking",
      "X-Correlation-Id" -> "test-correlation-id",
      "Authorization" -> s"#{bearerToken}",
      "Content-Type" -> "application/json")

  val headersInsolvent: Map[String, String] =
    Map("Accept" -> s"application/vnd.hmrc.1.0+json",
      "Content-Type" -> "application/json")

}
