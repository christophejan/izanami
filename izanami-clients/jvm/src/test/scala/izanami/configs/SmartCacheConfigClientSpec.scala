package izanami.configs

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.testkit.TestKit
import com.github.tomakehurst.wiremock.client.WireMock._
import izanami.Strategy.{CacheWithPollingStrategy, CacheWithSseStrategy}
import izanami._
import izanami.scaladsl.ConfigEvent.ConfigUpdated
import izanami.scaladsl.{Config, Configs, IzanamiClient}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Minutes, Seconds, Span}
import org.scalatestplus.mockito.MockitoSugar
import play.api.libs.json.Json

import scala.concurrent.duration.DurationInt

class SmartCacheConfigClientSpec
    extends IzanamiSpec
    with BeforeAndAfterAll
    with MockitoSugar
    with ConfigServer
    with ConfigMockServer
    with Eventually {

  implicit val system       = ActorSystem("test")
  implicit val materializer = Materializer.createMaterializer(system)
  implicit override val patienceConfig =
    PatienceConfig(timeout = scaled(Span(1, Minutes)), interval = scaled(Span(50, Millis)))

  import system.dispatcher

  override def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)

  "SmartCacheConfigStrategy" should {
    "Configs by pattern with polling" in {

      import akka.pattern
      //Init
      val initialConfigs = Seq(
        Config("test1", Json.obj("value" -> 1))
      )

      registerPage(initialConfigs)

      val fallback = Configs(
        "test2" -> Json.obj("value" -> 2)
      )
      val client = IzanamiClient(
        ClientConfig(host)
      )

      //#smart-cache
      val configClient = client.configClient(
        strategy = CacheWithPollingStrategy(
          patterns = Seq("*"),
          pollingInterval = 3.second
        ),
        fallback = fallback
      )
      //#smart-cache

      //Waiting for the client to start polling
      val configs: Configs = pattern
        .after(2.second, system.scheduler) {
          configClient.configs("*")
        }
        .futureValue

      configs.configs must contain theSameElementsAs (fallback.configs ++ initialConfigs)

      //Only one call for the first fetch
      mock.verifyThat(
        getRequestedFor(urlPathEqualTo("/api/configs"))
          .withQueryParam("pattern", equalTo("*"))
          .withQueryParam("page", equalTo("1"))
          .withQueryParam("pageSize", equalTo("200"))
      )
      mock.resetRequests()

      configs.get("test1") must be(Json.obj("value" -> 1))
      configs.get("test2") must be(Json.obj("value" -> 2))
      configs.get("other") must be(Json.obj())

      // As the data are in cache for the pattern, this should not generate an http call
      configClient.config("test1").futureValue must be(Json.obj("value" -> 1))
      configClient.config("test2").futureValue must be(Json.obj("value" -> 2))
      configClient.config("other").futureValue must be(Json.obj())

      mock.find(anyRequestedFor(urlMatching(".*"))) must be(empty)

      //We update the config on thebackend
      val updatedConfigs = Seq(
        Config("test1", Json.obj("value" -> 3))
      )
      registerPage(updatedConfigs)

      //We wait for the polling
      val configsUpdated: Configs = pattern
        .after(2.second, system.scheduler) {
          configClient.configs("*")
        }
        .futureValue

      //Now we have two call : the first fetch and the polling
      mock.verifyThat(
        getRequestedFor(urlPathEqualTo("/api/configs"))
          .withQueryParam("pattern", equalTo("*"))
          .withQueryParam("page", equalTo("1"))
          .withQueryParam("pageSize", equalTo("200"))
      )
      mock.resetRequests()

      configsUpdated.configs must contain theSameElementsAs (fallback.configs ++ updatedConfigs)
      configsUpdated.get("test1") must be(Json.obj("value" -> 3))
      configsUpdated.get("test2") must be(Json.obj("value" -> 2))
      configsUpdated.get("other") must be(Json.obj())

      // As the data are in cache for the pattern, this should not generate an http call
      configClient.config("test1").futureValue must be(Json.obj("value" -> 3))
      configClient.config("test2").futureValue must be(Json.obj("value" -> 2))
      configClient.config("other").futureValue must be(Json.obj())
      mock.find(anyRequestedFor(urlMatching(".*"))) must be(empty)

    }

    "autocreate getting config" in {
      mock.resetRequests()

      val client = IzanamiClient(
        ClientConfig(host)
      )
      //#config-autocreate
      val izanamiClient = client.configClient(
        strategy = CacheWithPollingStrategy(
          patterns = Seq("*"),
          pollingInterval = 3.second
        ),
        fallback = Configs(
          "test" -> Json.obj("value" -> 2)
        ),
        autocreate = true
      )
      //#config-autocreate

      registerNoConfig()
      registerCreateConfig(Config("test", Json.obj("value" -> 2)))

      val futureConfig = izanamiClient.config("test").futureValue

      eventually {
        mock.verifyThat(
          postRequestedFor(urlEqualTo("/api/configs.ndjson"))
            .withRequestBody(equalTo(Json.stringify(Json.obj("id" -> "test", "value" -> Json.obj("value" -> 2)))))
            .withHeader("Content-Type", containing("application/nd-json"))
        )
      }
    }

    "autocreate listing config" in {
      mock.resetRequests()

      val client = IzanamiClient(
        ClientConfig(host)
      ).configClient(
        strategy = CacheWithPollingStrategy(
          patterns = Seq("*"),
          pollingInterval = 3.second
        ),
        fallback = Configs(
          "test" -> Json.obj("value" -> 2)
        ),
        autocreate = true
      )
      registerPage(Seq.empty)
      registerCreateConfig(Config("test", Json.obj("value" -> 2)))

      val configs: Configs = client.configs("*").futureValue

      mock.verifyThat(
        postRequestedFor(urlEqualTo("/api/configs.ndjson"))
          .withRequestBody(equalTo(Json.stringify(Json.obj("id" -> "test", "value" -> Json.obj("value" -> 2)))))
          .withHeader("Content-Type", containing("application/nd-json"))
      )

    }

    "Configs by pattern with sse" in {
      eventually(timeout(60.seconds), interval(1.seconds)) {
        runServer { ctx =>
          import akka.pattern
          //Init
          val initialConfigs = Seq(
            Config("test1", Json.obj("value" -> 1))
          )
          ctx.setValues(initialConfigs)

          val fallback = Configs(
            "test2" -> Json.obj("value" -> 2)
          )
          Thread.sleep(100)
          val strategy = IzanamiClient(
            ClientConfig(ctx.host)
          ).configClient(
            strategy = CacheWithSseStrategy(
              patterns = Seq("*"),
              pollingInterval = None //Some(3.second)
            ),
            fallback = fallback
          )

          //Waiting for the client to start polling
          val configs: Configs = pattern
            .after(1300.milliseconds, system.scheduler) {
              strategy.configs("*")
            }
            .futureValue

          configs.configs must contain theSameElementsAs (fallback.configs ++ initialConfigs)

          //Only one call for the first fetch
          ctx.calls.size must be(1)
          configs.get("test1") must be(Json.obj("value" -> 1))
          configs.get("test2") must be(Json.obj("value" -> 2))
          configs.get("other") must be(Json.obj())

          // As the data are in cache for the pattern, this should not generate an http call
          strategy.config("test1").futureValue must be(Json.obj("value" -> 1))
          strategy.config("test2").futureValue must be(Json.obj("value" -> 2))
          strategy.config("other").futureValue must be(Json.obj())
          ctx.calls.size must be(1)

          // We update config via sse
          ctx.push(
            ConfigUpdated(
              Some(1),
              "test1",
              Config("test1", Json.obj("value" -> 3)),
              Config("test1", Json.obj("value" -> 1))
            )
          )

          //We wait that the events arrive
          val configsUpdated: Configs = pattern
            .after(500.milliseconds, system.scheduler) {
              strategy.configs("*")
            }
            .futureValue

          //With SSE we should only have the first fetch
          ctx.calls.size must be(1)

          configsUpdated.get("test1") must be(Json.obj("value" -> 3))
          configsUpdated.get("test2") must be(Json.obj("value" -> 2))
          configsUpdated.get("other") must be(Json.obj())

          // As the data are in cache for the pattern, this should not generate an http call
          strategy.config("test1").futureValue must be(Json.obj("value" -> 3))
          strategy.config("test2").futureValue must be(Json.obj("value" -> 2))
          strategy.config("other").futureValue must be(Json.obj())
          ctx.calls.size must be(1)
        }
      }
    }
  }

}
