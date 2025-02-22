package izanami.configs

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.testkit.TestKit
import izanami.Strategy.FetchWithCacheStrategy
import izanami._
import izanami.scaladsl.{Config, Configs, IzanamiClient}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Minutes, Span}
import org.scalatestplus.mockito.MockitoSugar
import play.api.libs.json.Json

import scala.concurrent.duration.DurationInt

class FetchWithCacheConfigClientSpec extends IzanamiSpec with BeforeAndAfterAll with MockitoSugar with ConfigServer with Eventually {

  implicit val system       = ActorSystem("test")
  implicit val materializer = Materializer.createMaterializer(system)
  implicit override val patienceConfig =
    PatienceConfig(timeout = scaled(Span(1, Minutes)), interval = scaled(Span(50, Millis)))

  override def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)

  "FetchWithCacheFeatureStrategy" should {
    "List configs" in {
      eventually {
        runServer { ctx =>
          //#config-fetch-cache
          val strategy = IzanamiClient(
            ClientConfig(ctx.host)
          ).configClient(
            strategy = FetchWithCacheStrategy(maxElement = 2, duration = 1.second),
            fallback = Configs(
              "test2" -> Json.obj("value" -> 2)
            )
          )
          //#config-fetch-cache

          val initialConfigs = Seq(
            Config("test", Json.obj("value" -> 1))
          )
          ctx.setValues(initialConfigs)

          val configs: Configs = strategy.configs("*").futureValue

          strategy.configs("*").futureValue
          strategy.configs("*").futureValue
          strategy.configs("*").futureValue

          configs.configs must be(initialConfigs)
          ctx.calls.size must be(1)

          configs.get("test") must be(Json.obj("value" -> 1))
          configs.get("test2") must be(Json.obj("value" -> 2))
          configs.get("other") must be(Json.obj())
        }
      }
    }

    "Test feature active" in {
      runServer { ctx =>
        val strategy = IzanamiClient(
          ClientConfig(ctx.host)
        ).configClient(
          strategy = FetchWithCacheStrategy(2, 5.second),
          fallback = Configs(
            "test5" -> Json.obj("value" -> 2)
          )
        )

        val initialConfigs = Seq(
          Config("test1", Json.obj("value" -> 1)),
          Config("test2", Json.obj("value" -> 2)),
          Config("test3", Json.obj("value" -> 3)),
          Config("test4", Json.obj("value" -> 4))
        )

        ctx.setValues(initialConfigs)

        strategy.config("test1").futureValue must be(Json.obj("value" -> 1))
        ctx.calls must have size 1
        strategy.config("test2").futureValue must be(Json.obj("value" -> 2))
        ctx.calls must have size 2
        strategy.config("test1").futureValue must be(Json.obj("value" -> 1))
        ctx.calls must have size 2

        strategy.config("test2").futureValue must be(Json.obj("value" -> 2))
        ctx.calls must have size 2

        strategy.config("test3").futureValue must be(Json.obj("value" -> 3))
        ctx.calls must have size 3

      }
    }
  }

}
