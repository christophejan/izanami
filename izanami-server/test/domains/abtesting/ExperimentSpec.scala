package domains.abtesting
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.{ChronoField, ChronoUnit}
import akka.actor.ActorSystem
import akka.stream.Materializer
import cats.data.NonEmptyList
import domains.abtesting.events._
import domains.abtesting.events.impl.ExperimentVariantEventInMemoryService
import domains.{AuthorizedPatterns, Key, PatternRights, errors}
import domains.auth.AuthInfo
import domains.apikey.Apikey
import domains.events.{EventStore, Events}
import domains.events.Events.ExperimentCreated
import libs.logs.ZLogger
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import play.api.libs.json.{JsSuccess, JsValue, Json}
import domains.errors.{IdMustBeTheSame, IzanamiErrors, Unauthorized, ValidationError}
import store.memory.InMemoryJsonDataStore
import test.{FakeConfig, IzanamiSpec, TestEventStore}
import zio.blocking.Blocking
import zio.{RIO, ZLayer}

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.util.Random

class ExperimentSpec extends IzanamiSpec with ScalaFutures with IntegrationPatience {
  import ExperimentInstances._

  implicit val actorSystem: ActorSystem = ActorSystem()
  implicit val runtime                  = zio.Runtime.default

  import IzanamiErrors._

  "Experiment" must {

    "Variant must not have changed if variant name changes" in {

      val old = Experiment(
        id = Key("test"),
        name = "",
        enabled = true,
        variants = NonEmptyList.of(
          Variant(id = "A", name = "name A", traffic = Traffic(0.5)),
          Variant(id = "B", name = "name B", traffic = Traffic(0.5))
        )
      )
      val data = Experiment(
        id = Key("test"),
        name = "",
        enabled = true,
        variants = NonEmptyList.of(
          Variant(id = "A", name = "name A' ", traffic = Traffic(0.5)),
          Variant(id = "B", name = "name B", traffic = Traffic(0.5))
        )
      )
      Experiment.isTrafficChanged(old, data) mustBe false
    }

    "Variant must have changed if nb variant is different" in {

      val old = Experiment(
        id = Key("test"),
        name = "",
        enabled = true,
        variants = NonEmptyList.of(
          Variant(id = "A", name = "name", traffic = Traffic(0.5)),
          Variant(id = "B", name = "name", traffic = Traffic(0.5))
        )
      )
      val data = Experiment(
        id = Key("test"),
        name = "",
        enabled = true,
        variants = NonEmptyList.of(
          Variant(id = "A", name = "name", traffic = Traffic(0.3))
        )
      )
      Experiment.isTrafficChanged(old, data) mustBe true
    }

    "Variant must have changed if nb variant are differents" in {

      val old = Experiment(
        id = Key("test"),
        name = "",
        enabled = true,
        variants = NonEmptyList.of(
          Variant(id = "A", name = "name", traffic = Traffic(0.5)),
          Variant(id = "B", name = "name", traffic = Traffic(0.5))
        )
      )
      val data = Experiment(
        id = Key("test"),
        name = "",
        enabled = true,
        variants = NonEmptyList.of(
          Variant(id = "B", name = "name", traffic = Traffic(0.5)),
          Variant(id = "C", name = "name", traffic = Traffic(0.5))
        )
      )
      Experiment.isTrafficChanged(old, data) mustBe true
    }

    "Variant must have changed if traffic changes" in {

      val old = Experiment(
        id = Key("test"),
        name = "",
        enabled = true,
        variants = NonEmptyList.of(
          Variant(id = "A", name = "name", traffic = Traffic(0.5)),
          Variant(id = "B", name = "name", traffic = Traffic(0.5))
        )
      )
      val data = Experiment(
        id = Key("test"),
        name = "",
        enabled = true,
        variants = NonEmptyList.of(
          Variant(id = "A", name = "name", traffic = Traffic(0.3)),
          Variant(id = "B", name = "name", traffic = Traffic(0.5))
        )
      )
      Experiment.isTrafficChanged(old, data) mustBe true
    }

    "simple serialization" in {
      val experiment = Experiment(
        id = Key("test"),
        name = "name",
        enabled = true,
        variants = NonEmptyList.of(
          Variant(id = "A", name = "name A", traffic = Traffic(0.4)),
          Variant(id = "B", name = "name B", traffic = Traffic(0.6))
        )
      )

      Json.toJson(experiment) mustBe Json.parse("""
          |{
          |  "id" : "test",
          |  "name" : "name",
          |  "enabled" : true,
          |  "variants" : [ {
          |    "id" : "A",
          |    "name" : "name A",
          |    "traffic" : 0.4
          |  }, {
          |    "id" : "B",
          |    "name" : "name B",
          |    "traffic" : 0.6
          |  } ]
          |}
        """.stripMargin)

    }

    val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    "complex serialization" in {
      val from = LocalDateTime.now()
      val to   = LocalDateTime.now()

      val experiment = Experiment(
        id = Key("test"),
        name = "name",
        description = Some("desc"),
        enabled = true,
        campaign = Some(ClosedCampaign(from, to, "A")),
        variants = NonEmptyList.of(
          Variant(id = "A",
                  name = "name A",
                  description = Some("desc A"),
                  traffic = Traffic(0.4),
                  currentPopulation = Some(5)),
          Variant(id = "B",
                  name = "name B",
                  description = Some("desc A"),
                  traffic = Traffic(0.6),
                  currentPopulation = Some(6))
        )
      )

      Json.toJson(experiment) mustBe Json.parse(s"""
          |{
          |  "id" : "test",
          |  "name" : "name",
          |  "description" : "desc",
          |  "enabled" : true,
          |  "campaign": {
          |    "from": "${dateFormatter.format(from)}",
          |    "to": "${dateFormatter.format(to)}",
          |    "won": "A"
          |  },
          |  "variants" : [ {
          |    "id" : "A",
          |    "name" : "name A",
          |    "description" : "desc A",
          |    "traffic" : 0.4,
          |    "currentPopulation" : 5
          |  }, {
          |    "id" : "B",
          |    "name" : "name B",
          |    "description" : "desc A",
          |    "traffic" : 0.6,
          |    "currentPopulation" : 6
          |  } ]
          |}
        """.stripMargin)

    }

    "simple deserialization" in {
      val experiment = Experiment(
        id = Key("test"),
        name = "name",
        enabled = true,
        variants = NonEmptyList.of(
          Variant(id = "A", name = "name A", traffic = Traffic(0.4)),
          Variant(id = "B", name = "name B", traffic = Traffic(0.6))
        )
      )

      val json = Json.parse("""
           |{
           |  "id" : "test",
           |  "name" : "name",
           |  "enabled" : true,
           |  "variants" : [ {
           |    "id" : "A",
           |    "name" : "name A",
           |    "traffic" : 0.4
           |  }, {
           |    "id" : "B",
           |    "name" : "name B",
           |    "traffic" : 0.6
           |  } ]
           |}
         """.stripMargin)

      json.validate[Experiment] mustBe JsSuccess(experiment)

    }

    "complex deserialization" in {
      val from = LocalDateTime.now().`with`(ChronoField.MILLI_OF_SECOND, 0)
      val to   = LocalDateTime.now().`with`(ChronoField.MILLI_OF_SECOND, 0)

      val experiment = Experiment(
        id = Key("test"),
        name = "name",
        description = Some("desc"),
        enabled = true,
        campaign = Some(ClosedCampaign(from, to, "A")),
        variants = NonEmptyList.of(
          Variant(id = "A",
                  name = "name A",
                  description = Some("desc A"),
                  traffic = Traffic(0.4),
                  currentPopulation = Some(5)),
          Variant(id = "B",
                  name = "name B",
                  description = Some("desc A"),
                  traffic = Traffic(0.6),
                  currentPopulation = Some(6))
        )
      )

      val json = Json.parse(s"""
           |{
           |  "id" : "test",
           |  "name" : "name",
           |  "description" : "desc",
           |  "enabled" : true,
           |  "campaign": {
           |    "from": "${dateFormatter.format(from)}",
           |    "to": "${dateFormatter.format(to)}",
           |    "won": "A"
           |  },
           |  "variants" : [ {
           |    "id" : "A",
           |    "name" : "name A",
           |    "description" : "desc A",
           |    "traffic" : 0.4,
           |    "currentPopulation" : 5
           |  }, {
           |    "id" : "B",
           |    "name" : "name B",
           |    "description" : "desc A",
           |    "traffic" : 0.6,
           |    "currentPopulation" : 6
           |  } ]
           |}
         """.stripMargin)

      json.validate[Experiment] mustBe JsSuccess(experiment)
    }

    "Traffic must be 100 %" in {
      val experiment = Experiment(
        id = Key("test"),
        name = "name",
        description = Some("desc"),
        enabled = true,
        variants = NonEmptyList.of(
          Variant(id = "A",
                  name = "name A",
                  description = Some("desc A"),
                  traffic = Traffic(0.4),
                  currentPopulation = Some(5)),
          Variant(id = "B",
                  name = "name B",
                  description = Some("desc A"),
                  traffic = Traffic(0.6),
                  currentPopulation = Some(6))
        )
      )

      Experiment.validate(experiment) mustBe Right(experiment)
    }

    "Validation fail if traffic is not 100 %" in {
      val experiment = Experiment(
        id = Key("test"),
        name = "name",
        description = Some("desc"),
        enabled = true,
        variants = NonEmptyList.of(
          Variant(id = "A",
                  name = "name A",
                  description = Some("desc A"),
                  traffic = Traffic(0.4),
                  currentPopulation = Some(5)),
          Variant(id = "B",
                  name = "name B",
                  description = Some("desc A"),
                  traffic = Traffic(0.5),
                  currentPopulation = Some(6))
        )
      )

      Experiment.validate(experiment) mustBe Left(ValidationError.error("error.traffic.not.cent.percent").toErrors)
    }

    "Validation fail if campaign date are wrong" in {
      val from = LocalDateTime.now().plus(2, ChronoUnit.MONTHS).`with`(ChronoField.MILLI_OF_SECOND, 0)
      val to   = LocalDateTime.now().`with`(ChronoField.MILLI_OF_SECOND, 0)

      val experiment = Experiment(
        id = Key("test"),
        name = "name",
        description = Some("desc"),
        enabled = true,
        campaign = Some(ClosedCampaign(from, to, "A")),
        variants = NonEmptyList.of(
          Variant(id = "A",
                  name = "name A",
                  description = Some("desc A"),
                  traffic = Traffic(0.4),
                  currentPopulation = Some(5)),
          Variant(id = "B",
                  name = "name B",
                  description = Some("desc A"),
                  traffic = Traffic(0.6),
                  currentPopulation = Some(6))
        )
      )

      Experiment.validate(experiment) mustBe Left(ValidationError.error("error.campaign.date.invalid").toErrors)
    }

    "read a experiment is forbidden" in {
      val store  = TrieMap.empty[Key, JsValue]
      val events = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val context =
        fakeExperimentContext(store, events, authorizedPatterns = AuthorizedPatterns.of("this" -> PatternRights.C))

      val value: Either[IzanamiErrors, Option[Experiment]] =
        runSync(context, ExperimentService.getById(Key("test")).either)
      value mustBe Left(NonEmptyList.of(Unauthorized(Some(Key("test")))))

    }

    "create a experiment" in {
      val store   = TrieMap.empty[Key, JsValue]
      val events  = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val context = fakeExperimentContext(store, events)

      val experiment = Experiment(
        id = Key("test"),
        name = "name",
        description = Some("desc"),
        enabled = true,
        variants = NonEmptyList.of(
          Variant(id = "A",
                  name = "name A",
                  description = Some("desc A"),
                  traffic = Traffic(0.4),
                  currentPopulation = Some(5)),
          Variant(id = "B",
                  name = "name B",
                  description = Some("desc A"),
                  traffic = Traffic(0.6),
                  currentPopulation = Some(6))
        )
      )
      val value: Either[IzanamiErrors, Experiment] =
        runSync(context, ExperimentService.create(experiment.id, experiment).either)
      value mustBe Right(experiment)

      store.get(experiment.id) mustBe Some(Json.toJson(experiment))
      events must have size 1
      events.head mustBe a[ExperimentCreated]
      events.head.asInstanceOf[ExperimentCreated].experiment mustBe experiment
    }

    "create a experiment is forbidden" in {
      val store  = TrieMap.empty[Key, JsValue]
      val events = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val context =
        fakeExperimentContext(store, events, authorizedPatterns = AuthorizedPatterns.of("*" -> PatternRights.R))

      val experiment = Experiment(
        id = Key("test"),
        name = "name",
        description = Some("desc"),
        enabled = true,
        variants = NonEmptyList.of(
          Variant(id = "A",
                  name = "name A",
                  description = Some("desc A"),
                  traffic = Traffic(0.4),
                  currentPopulation = Some(5)),
          Variant(id = "B",
                  name = "name B",
                  description = Some("desc A"),
                  traffic = Traffic(0.6),
                  currentPopulation = Some(6))
        )
      )
      val value: Either[IzanamiErrors, Experiment] =
        runSync(context, ExperimentService.create(experiment.id, experiment).either)
      value mustBe Left(NonEmptyList.of(Unauthorized(Some(Key("test")))))

    }

    "reject an invalid experiment during creation" in {
      val store   = TrieMap.empty[Key, JsValue]
      val events  = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val context = fakeExperimentContext(store, events)

      val experiment = Experiment(
        id = Key("test"),
        name = "name",
        description = Some("desc"),
        enabled = true,
        variants = NonEmptyList.of(
          Variant(id = "A",
                  name = "name A",
                  description = Some("desc A"),
                  traffic = Traffic(0.4),
                  currentPopulation = Some(5)),
          Variant(id = "B",
                  name = "name B",
                  description = Some("desc A"),
                  traffic = Traffic(0.5),
                  currentPopulation = Some(6))
        )
      )
      val value: Either[IzanamiErrors, Experiment] =
        runSync(context, ExperimentService.create(experiment.id, experiment).either)
      value mustBe Left(ValidationError.error("error.traffic.not.cent.percent").toErrors)

      store.get(experiment.id) mustBe None
      events must have size 0
    }

    "reject an update if ids are not the same" in {
      val store   = TrieMap.empty[Key, JsValue]
      val events  = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val context = fakeExperimentContext(store, events)

      val experiment = Experiment(
        id = Key("test"),
        name = "name",
        description = Some("desc"),
        enabled = true,
        variants = NonEmptyList.of(
          Variant(id = "A",
                  name = "name A",
                  description = Some("desc A"),
                  traffic = Traffic(0.4),
                  currentPopulation = Some(5)),
          Variant(id = "B",
                  name = "name B",
                  description = Some("desc A"),
                  traffic = Traffic(0.6),
                  currentPopulation = Some(6))
        )
      )
      val oldId = Key("oldtest")
      val value: Either[IzanamiErrors, Experiment] =
        runSync(context, ExperimentService.update(oldId, experiment.id, experiment).either)
      value mustBe Left(IdMustBeTheSame(oldId, experiment.id).toErrors)

      store.get(experiment.id) mustBe None
      events must have size 0
    }

    "update a experiment is forbidden" in {
      val store  = TrieMap.empty[Key, JsValue]
      val events = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val context =
        fakeExperimentContext(store, events, authorizedPatterns = AuthorizedPatterns.of("*" -> PatternRights.R))

      val experiment = Experiment(
        id = Key("test"),
        name = "name",
        description = Some("desc"),
        enabled = true,
        variants = NonEmptyList.of(
          Variant(id = "A",
                  name = "name A",
                  description = Some("desc A"),
                  traffic = Traffic(0.4),
                  currentPopulation = Some(5)),
          Variant(id = "B",
                  name = "name B",
                  description = Some("desc A"),
                  traffic = Traffic(0.6),
                  currentPopulation = Some(6))
        )
      )
      val value: Either[IzanamiErrors, Experiment] =
        runSync(context, ExperimentService.update(experiment.id, experiment.id, experiment).either)
      value mustBe Left(NonEmptyList.of(Unauthorized(Some(Key("test")))))

    }

    "delete a experiment is forbidden" in {
      val store  = TrieMap.empty[Key, JsValue]
      val events = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val context =
        fakeExperimentContext(store, events, authorizedPatterns = AuthorizedPatterns.of("*" -> PatternRights.R))

      val value: Either[IzanamiErrors, Experiment] =
        runSync(context, ExperimentService.delete(Key("test")).either)
      value mustBe Left(NonEmptyList.of(Unauthorized(Some(Key("test")))))

    }

    "Affect variant" in {
      val id = Key("test")
      val variantA = Variant(id = "A",
                             name = "name A",
                             description = Some("desc A"),
                             traffic = Traffic(0.4),
                             currentPopulation = Some(5))
      val variantB = Variant(id = "B",
                             name = "name B",
                             description = Some("desc A"),
                             traffic = Traffic(0.6),
                             currentPopulation = Some(6))

      val experiment = Experiment(
        id = id,
        name = "name",
        description = Some("desc"),
        enabled = true,
        variants = NonEmptyList.of(
          variantA,
          variantB
        )
      )

      val variants: Seq[Variant] = (1 to 100)
        .map { i =>
          Experiment.findVariant(experiment, s"client$i")
        }
      val aCount = variants.count(_.id === "A")
      val bCount = variants.count(_.id === "B")
      aCount must equal(40 +- 5)
      bCount must equal(60 +- 5)
    }

    "Variant for an id should remain the same" in {
      val id = Key("test")
      val variantA = Variant(id = "A",
                             name = "name A",
                             description = Some("desc A"),
                             traffic = Traffic(0.4),
                             currentPopulation = Some(5))
      val variantB = Variant(id = "B",
                             name = "name B",
                             description = Some("desc A"),
                             traffic = Traffic(0.6),
                             currentPopulation = Some(6))

      val experiment = Experiment(
        id = id,
        name = "name",
        description = Some("desc"),
        enabled = true,
        variants = NonEmptyList.of(
          variantA,
          variantB
        )
      )
      val choosenVariant = Experiment.findVariant(experiment, s"client1")
      val variants: Seq[Variant] = (1 to 10000)
        .map { i =>
          Experiment.findVariant(experiment, s"client1")
        }
      val aCount = variants.count(_.id === choosenVariant.id)
      val bCount = variants.count(_.id !== choosenVariant.id)
      aCount mustBe 10000
      bCount mustBe 0
    }

    "Variant by client if campaign is on" in {

      val store   = TrieMap.empty[Key, JsValue]
      val events  = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val context = fakeExperimentContext(store, events)

      val from = LocalDateTime.now().minus(1, ChronoUnit.HOURS).`with`(ChronoField.MILLI_OF_SECOND, 0)
      val to   = LocalDateTime.now().plus(1, ChronoUnit.HOURS).`with`(ChronoField.MILLI_OF_SECOND, 0)
      val id   = Key("test")
      val variantA = Variant(id = "A",
                             name = "name A",
                             description = Some("desc A"),
                             traffic = Traffic(0.4),
                             currentPopulation = Some(5))
      val variantB = Variant(id = "B",
                             name = "name B",
                             description = Some("desc A"),
                             traffic = Traffic(0.6),
                             currentPopulation = Some(6))
      val experiment = Experiment(
        id = id,
        name = "name",
        description = Some("desc"),
        enabled = true,
        campaign = Some(CurrentCampaign(from, to)),
        variants = NonEmptyList.of(
          variantA,
          variantB
        )
      )
      store.put(id, Json.toJson(experiment))

      val variants = (1 to 100).map { i =>
        variantFor(context, id, s"client$i")
      }
      val aCount: Int = variants.count(_.id === "A")
      val bCount: Int = variants.count(_.id === "B")
      aCount must equal(40 +- 5)
      bCount must equal(60 +- 5)
    }

    "Variant by client if campaign just closed" in {

      val store   = TrieMap.empty[Key, JsValue]
      val events  = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val context = fakeExperimentContext(store, events)

      val from = LocalDateTime.now().minus(2, ChronoUnit.HOURS).`with`(ChronoField.MILLI_OF_SECOND, 0)
      val to   = LocalDateTime.now().minus(1, ChronoUnit.MINUTES).`with`(ChronoField.MILLI_OF_SECOND, 0)
      val id   = Key("test")
      val variantA = Variant(id = "A",
                             name = "name A",
                             description = Some("desc A"),
                             traffic = Traffic(0.4),
                             currentPopulation = Some(5))
      val variantB = Variant(id = "B",
                             name = "name B",
                             description = Some("desc A"),
                             traffic = Traffic(0.6),
                             currentPopulation = Some(6))
      val experiment = Experiment(
        id = id,
        name = "name",
        description = Some("desc"),
        enabled = true,
        campaign = Some(CurrentCampaign(from, to)),
        variants = NonEmptyList.of(
          variantA,
          variantB
        )
      )
      store.put(id, Json.toJson(experiment))
      val evtId1 = ExperimentVariantEventKey(id, "A", "client1", "test", "1")
      runSync(
        context,
        ExperimentVariantEventService
          .create(evtId1, ExperimentVariantDisplayed(evtId1, id, "client1", variantA, LocalDateTime.now(), 0, "A"))
          .either
      )
      val evtId2 = ExperimentVariantEventKey(id, "A", "client1", "test", "2")
      runSync(context,
              ExperimentVariantEventService
                .create(evtId1, ExperimentVariantWon(evtId2, id, "client1", variantA, LocalDateTime.now(), 0, "A"))
                .either)

      val variants = (1 to 100).map { i =>
        variantFor(context, id, s"client$i")
      }
      val aCount = variants.count(_.id === "A")
      val bCount = variants.count(_.id === "B")
      aCount must equal(100)
      bCount must equal(0)

      val updatedExp = store(id).validate[Experiment].get
      updatedExp.campaign mustBe Some(ClosedCampaign(from, to, "A"))
    }

    "Variant A if campaign is closed" in {

      val store   = TrieMap.empty[Key, JsValue]
      val events  = mutable.ArrayBuffer.empty[Events.IzanamiEvent]
      val context = fakeExperimentContext(store, events)

      val from = LocalDateTime.now().minus(1, ChronoUnit.HOURS).`with`(ChronoField.MILLI_OF_SECOND, 0)
      val to   = LocalDateTime.now().plus(1, ChronoUnit.HOURS).`with`(ChronoField.MILLI_OF_SECOND, 0)
      val id   = Key("test")
      val variantA = Variant(id = "A",
                             name = "name A",
                             description = Some("desc A"),
                             traffic = Traffic(0.4),
                             currentPopulation = Some(5))
      val variantB = Variant(id = "B",
                             name = "name B",
                             description = Some("desc A"),
                             traffic = Traffic(0.6),
                             currentPopulation = Some(6))
      val experiment = Experiment(
        id = id,
        name = "name",
        description = Some("desc"),
        enabled = true,
        campaign = Some(ClosedCampaign(from, to, "A")),
        variants = NonEmptyList.of(
          variantA,
          variantB
        )
      )
      store.put(id, Json.toJson(experiment))

      val variants = (1 to 100).map { i =>
        variantFor(context, id, s"client$i")
      }
      val aCount = variants.count(_.id === "A")
      val bCount = variants.count(_.id === "B")
      aCount mustBe 100
      bCount mustBe 0
    }
  }

  private def variantFor(context: ZLayer[Any, Throwable, ExperimentContext], id: Key, clientId: String): Variant = {
    val r: Either[errors.IzanamiErrors, Variant] = runSync(context, ExperimentService.variantFor(id, clientId).either)
    r.toOption.get
  }

  private def runSync[T](context: ZLayer[Any, Throwable, ExperimentContext], taskR: RIO[ExperimentContext, T]): T =
    runtime.unsafeRun(taskR.provideLayer(context))

  def fakeExperimentContext(
      store: TrieMap[Key, JsValue] = TrieMap.empty[Key, JsValue],
      events: mutable.ArrayBuffer[Events.IzanamiEvent],
      expVariantEventService: ExperimentVariantEventService.Service = expEventsService(),
      authorizedPatterns: AuthorizedPatterns = AuthorizedPatterns.All
  ): ZLayer[Any, Throwable, ExperimentContext] =
    FakeConfig.playModule(actorSystem) ++
    ZLogger.live ++
    ExperimentVariantEventService.value(expVariantEventService) ++
    EventStore.value(new TestEventStore(events)) ++
    ExperimentDataStore.value(new InMemoryJsonDataStore("experiment", store)) ++
    Blocking.live ++
    AuthInfo.value(Apikey("1", "key", "secret", authorizedPatterns, true))

  val namespacesCount = new AtomicInteger()

  def expEventsService(
      events: mutable.ArrayBuffer[Events.IzanamiEvent] = mutable.ArrayBuffer.empty
  ): ExperimentVariantEventService.Service =
    new ExperimentVariantEventInMemoryService(
      s"test_${namespacesCount.incrementAndGet()}"
    )

}
