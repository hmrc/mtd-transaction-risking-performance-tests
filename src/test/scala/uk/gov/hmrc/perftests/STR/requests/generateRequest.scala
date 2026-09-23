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
package utils

import io.gatling.core.Predef._
import io.gatling.core.structure.ChainBuilder
import io.gatling.http.Predef._
import uk.gov.hmrc.perftests.Common._
import uk.gov.hmrc.perftests.utils.TestData

class generateRequest {

  private val strRequestURL: String = s"$baseUrl/feedback/#{vrn}"

  val generateVrn: ChainBuilder =
    exec { session =>
      session.set("vrn", "123456789")
    }

  val postSTRRequest: ChainBuilder = exec(http("Post STR")
    .post(strRequestURL)
    .body(StringBody(authPayload()))
    .headers(commonHeaders)
    // .headers(Map("Gov-Test-Scenario" -> "MULTIPLE_FEEDBACK"))
    .check(status.is(201))
    .check(bodyString.saveAs("responseBody"))
  )
    .exec { session =>
      println("Status: " + session("statusCode").asOption[String].getOrElse("NO STATUS"))
      println("Body: " + session("responseBody").asOption[String].getOrElse("NO BODY"))
      session
    }

  def authPayload(): String = {

    val authPayload =
      s"""
         | {
         |  "periodKey": "A001",
         |  "vatDueSales": 105.5,
         |  "vatDueAcquisitions": -100.45,
         |  "totalVatDue": 5.05,
         |  "vatReclaimedCurrPeriod": 105.15,
         |  "netVatDue": 100.1,
         |  "totalValueSalesExVAT": 300,
         |  "totalValuePurchasesExVAT": 300,
         |  "totalValueGoodsSuppliedExVAT": 3000,
         |  "totalAcquisitionsExVAT": 3000
         | }
     """.stripMargin
    authPayload
  }
}