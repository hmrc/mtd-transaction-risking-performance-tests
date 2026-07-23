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

package uk.gov.hmrc.perftests.STR.requests

import io.gatling.core.Predef._
import io.gatling.core.structure.ChainBuilder
import io.gatling.http.Predef._
import uk.gov.hmrc.perftests.Common._

class Acknowledge {

  private val strAcknowledgeURL: String = s"$baseUrl/acknowledge/#{vrn}/:reportId/:correlationId"

  val retrieveSTR: ChainBuilder = exec(http("Retrieve STR")
    .get(strAcknowledgeURL)
    .headers(commonHeaders)
    .headers(Map("Gov-Test-Scenario" -> "MULTIPLE_FEEDBACK"))
    .check(status.is(200)))

}
