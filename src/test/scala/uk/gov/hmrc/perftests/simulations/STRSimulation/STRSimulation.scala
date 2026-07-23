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

import uk.gov.hmrc.performance.simulation.PerformanceTestRunner
import uk.gov.hmrc.perftests.STR.requests.utils.Request
import io.gatling.core.Predef._

class STRSimulation extends PerformanceTestRunner {

  val request = new Request()

  val scn = scenario("STR Performance Test")
    .exec(session => session.set("bearerToken", "your-token-here"))
    .exec(request.generateVrn)
    .exec { session =>
      println("VRN being used: " + session("vrn").as[String])
      session
    }
    .exec(request.postSTR)

  setUp(
    scn.inject(
      atOnceUsers(1)
    )
  )

}
