package uk.gov.hmrc.perftests

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import java.util.UUID
import java.util.concurrent.{ConcurrentHashMap, Executors, ScheduledExecutorService, TimeUnit}
import scala.util.control.NonFatal
import java.io.IOException

object Authentication {
  private val tokens = new ConcurrentHashMap[String, String]()
  @volatile private var problem: Option[String] = None
  private var refresher: Option[ScheduledExecutorService] = None
  private def newClient(): HttpClient =
    HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(10))
      .build()
  @volatile private var client = newClient()
  private def ioFailureDetail(error: IOException): String =
    Iterator.iterate[Throwable](error)(_.getCause)
      .takeWhile(_ != null)
      .take(6)
      .map(_.getClass.getSimpleName)
      .mkString(" -> ")
  private def sendLogin(
    request: HttpRequest,
    attempt: Int = 1
  ): HttpResponse[Void] = {
    try client.send(request, HttpResponse.BodyHandlers.discarding())
    catch {
      case error: IOException if attempt < 3 =>
        System.err.println(
          s"Test login send attempt $attempt/3 failed: ${ioFailureDetail(error)}; retrying}"
        )
        TimeUnit.SECONDS.sleep(attempt.toLong)
        client = newClient()
        sendLogin(request, attempt + 1)

      case error: IOException =>
        System.err.println(
          s"Test login failed after $attempt send attempts: ${ioFailureDetail(error)}"
        )
        throw error
    }
  }

  def externalToken(variable: String): String = {
    val token = sys.env.getOrElse(variable, "").trim
    require(token.nonEmpty && !token.startsWith("Bearer "),
      s"Consumer quota tests need a raw OAuth token in environment variable $variable")
    s"Bearer $token"
  }

  def check(): Unit = require(problem.isEmpty,
    problem.getOrElse("") + "; this run cannot establish API capacity")

  def header(vrn: String, tokenEnv: String): String = {
    check()
    if (Settings.consumerLimits) externalToken(tokenEnv)
    else Option(tokens.get(vrn)).getOrElse(throw new IllegalStateException(s"No test session for VRN $vrn"))
  }

  private def login(vrn: String, credId: String): Unit = {
    val body =
      s"""{
      "credId":"$credId", "affinityGroup":"Organisation", "credentialRole":"User",
      "credentialStrength":"strong", "confidenceLevel":50, "excludeGnapToken":true,
      "enrolments":[{"key":"HMRC-MTD-VAT","state":"Activated",
        "identifiers":[{"key":"VRN","value":"$vrn"}]}]
  }"""

    val request = HttpRequest.newBuilder(URI.create(Common.loginUrl))
      .timeout(Duration.ofSeconds(Settings.timeout))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(body))
      .build()

    val response = sendLogin(request)
    require(response.statusCode() == 201, s"Test login failed: HTTP ${response.statusCode()}")

    val bearer = response.headers().firstValue("Authorization").orElse("").trim
    require(bearer.matches("Bearer\\s+\\S+"), "Test login returned no valid Authorization header")
    tokens.put(vrn, bearer)
  }

  def start(rows: Seq[Map[String, Any]]): Unit = {
    Common.validateService()

    if (Settings.consumerLimits)
      rows.foreach(row => externalToken(row.getOrElse("tokenEnv", "").toString))
    else {
      Common.validationLogin()
      val users = rows.map(_("vrn").toString).distinct.map(_ -> UUID.randomUUID().toString)
      users.foreach { case (vrn, cred) => login(vrn, cred) }

      val interval = Settings.seconds("authRefreshSeconds", 600)
      val executor = Executors.newSingleThreadScheduledExecutor((task: Runnable) => {
        val thread = new Thread(task, "test-login-refresh")
        thread.setDaemon(true)
        thread
      })

      refresher = Some(executor)
      executor.scheduleWithFixedDelay(new Runnable {
        override def run(): Unit = if (problem.isEmpty) {
          try {
            println("Test login renewal started")
            users.foreach { case (vrn, cred) => login(vrn, cred)}
            println("Test login renewal succeeded")
          }
          catch {
            case NonFatal(error) =>
              val message = Option(error.getMessage).getOrElse("")

              val safeDetail =
                if (message.matches(
                  "requirement failed: Test login failed: HTTP [0-9]{3}"
                ))
                  message.stripPrefix("requirement failed: ")
                else if (
                  message ==
                    "requirement failed: Test login returned no valid Authorization header"
                )
                  "Test login returned no valid Authorization header"
                else
                  error.getClass.getSimpleName

              val failure = s"Test login refresh failed: $safeDetail"
              problem = Some(failure)
              System.err.println(failure)
          }
        }
      }, interval, interval, TimeUnit.SECONDS)

      println(s"Created ${users.size} test session(s); refresh interval ${interval}s. Tokens are not logged.")
    }
  }

  def close(): Unit = refresher.foreach(_.shutdownNow())
}

