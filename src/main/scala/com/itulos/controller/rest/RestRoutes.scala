package com.itulos.controller.rest

import akka.pattern.ask
import akka.util.Timeout
import com.itulos.controller.dao.SensorComponent
import com.itulos.controller.domain._
import com.itulos.controller.exception.AlarmStatusEnum
import com.itulos.controller.rest.authenticators.{BasicAuthenticator, SensorEventsAuthenticator, TokenAuthenticator}
import com.itulos.controller.service._
import com.itulos.controller.service.protocol._
import org.json4s.DefaultFormats
import org.json4s.JsonAST.JObject
import spray.httpx.Json4sSupport
import spray.routing._

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

/**
 * Rest API
 */
trait RestRoutes extends HttpService with Json4sSupport with BasicAuthenticator
with TokenAuthenticator with PerRequestCreator with ParameterValidator
with SensorEventsAuthenticator with CORSSupport with SensorComponent {

  val sensorDao = new SensorDao

  implicit def json4sFormats = DefaultFormats

  implicit val timeout = Timeout(10 seconds)

  /* Routes for users management */
  val userRoutes =
    cors {
      pathPrefix("api" / "v1") {
        path("users" / "login") {
          authenticate(basicUserAuthenticator) { authInfo =>
            // --- Login user --- //
            post {
              // Request a new temporary token
              logUserAction(authInfo.user, LOGIN, "foo")
              handleTokensApiRequest(GenerateTokenRq(authInfo.user))
            }
          }
        } ~ path("users" / "logout") {
          authenticate(tokenAuthenticator) { user =>
            post {
              // --- Logout user --- //
              entity(as[JObject]) { data =>
                // Deactivate a token
                logUserAction(user, LOGOUT, "foo")
                val token = data.values("token").toString
                handleTokensApiRequest(DeactivateToken(user, token))
              }
            }
          }
        } ~ path("users" / "token" / "update") {
          authenticate(tokenAuthenticator) { user =>
            parameters('token) { (token) => {
              post {
                handleTokensApiRequest(UpdateTokenExpireTime(user, token))
              }
            }
            }
          }
        } ~ path("users") {
          authenticate(tokenAuthenticator) { user =>
            put {
              // --- Create new user --- //
              entity(as[JObject]) { data =>
                val user = new User()
                user.fromJObject(data, false)
                handleUsersApiRequest(CreateUserRq(user))
              }
            }~post {
              // --- Update user --- //
              entity(as[JObject]) { data =>
                val user = new User()
                user.fromJObject(data, true)
                handleUsersApiRequest(UpdateUserRq(user))
              }
            } ~ delete {
              // --- Delete user user --- //
              entity(as[JObject]) { data =>
                // TODO delete user's history
                val userId = getParameter(data, "oid").get
                handleUsersApiRequest(DeleteUserRq(userId))
              }
            } ~ get {
              // --- Get users --- //
              handleUsersApiRequest(GetUsersRq())
            }
          }
        } ~ path("users" / "me") {
          authenticate(tokenAuthenticator) { user =>
            get {
              // --- Get users --- //
              handleUsersApiRequest(GetUserRq(user.oid.get))
            }
          }
        }
      }
    }


  /** Routes for alarm management **/
  val alarmRoutes =
    cors {
      authenticate(tokenAuthenticator) { user =>
        /* Alarm status history */
        pathPrefix("api" / "v1" / "alarm" / "status" / "history") {
          parameters('limit.as[Int] ? 10, 'offset.as[Int] ? 0) { (limit, offset) => {
            get {
              // --- Get current alarm status --- //
              handleAlarmsApiRequest(GetAlarmStatusHistoryRq(limit, offset))
            }
          }
          }
          /* Alarm status management */
        } ~ pathPrefix("api" / "v1" / "alarm" / "status") {
          get {
            // --- Get current alarm status --- //
            handleAlarmsApiRequest(GetCurrentAlarmStatusRq())
          } ~ put {
            // --- Update alarm status --- //
            entity(as[JObject]) { data =>
              // TODO check 4 digit password
              val status = AlarmStatusEnum.from(getParameter(data, "status").get)
              handleAlarmsApiRequest(UpdateAlarmStatus(status, user))
            }
          }


        }
      }
    }


  /** Routes for settings management **/
  val settingsRoutes =
    cors {
      authenticate(tokenAuthenticator) { user =>

        /* Settings management */
        pathPrefix("api" / "v1" / "settings") {
          get {
            // --- Get settings --- //
            handleSettingsApiRequest(GetSystemSettingsRq())
          } ~ post {
            // --- Update settings --- //
            entity(as[JObject]) { data =>
              val settings = new SystemSettings()
              settings.fromJObject(data)
              handleSettingsApiRequest(UpdateSystemSettingsRq(settings))
            }
          }
        }
      }
    }

  /** Routes for zones management **/
  val zoneRoutes =
    cors {
      authenticate(tokenAuthenticator) { user =>

        /* Zones Status management */
        pathPrefix("api" / "v1" / "zones" / "status") {
          post {
            // --- Update zone status --- //
            entity(as[JObject]) { data =>
              // TODO if system is armed do not allow any updates
              val zoneStatus = new ZoneStatus()
              zoneStatus.fromJObject(data, user)
              handleZoneStatusApiRequest(UpdateZoneStatus(zoneStatus))
            }
          } ~ get {
            // --- Get zone status --- //
            handleZoneStatusApiRequest(GetCurrentZoneStatusRq())
          }

          /* Zones metadata management */
        } ~ pathPrefix("api" / "v1" / "zones") {
          put {
            // --- Create new zone --- //
            entity(as[JObject]) { data =>
              val zone = new Zone()
              zone.fromJObject(data, false)
              handleZonesApiRequest(CreateZoneRq(zone))
            }
          } ~ post {
            // --- Update existing zone --- //
            entity(as[JObject]) { data =>
              val zone = new Zone()
              zone.fromJObject(data, true)
              handleZonesApiRequest(UpdateZoneRq(zone))
            }
          } ~ delete {
            // --- Delete existing zone --- //
            entity(as[JObject]) { data =>
              val id = getParameter(data, "oid")
              handleZonesApiRequest(DeleteZoneRq(id.get))
            }
          } ~ get {
            // --- Get zones --- //
            handleZonesApiRequest(GetZonesRq())
          }
        }
      }
    }

  /** Routes for sensors events management **/
  val sensorEventsRoutes =
    cors {
      /* Sensor events management */
      pathPrefix("api" / "v1" / "sensors" / "events") {
        authenticate(sensorTokenAuthenticator) { user =>
          post {
            // --- Add sensor event --- //
            entity(as[JObject]) { data =>
              val sensorEvent = new SensorEvent()
              sensorEvent.fromJObject(data, false)
              notifyAlarmWatcher(NewSensorEvent(sensorEvent))
              handleSensorEventsApiRequest(AddSensorEventRq(sensorEvent))
            }
          }
        } ~ parameters('sensorId.?, 'limit.as[Int] ? 50, 'offset.as[Int] ? 0) { (sensorId, limit, offset) => {
          authenticate(tokenAuthenticator) { user =>
            // --- Get sensor events --- //
            get {
              handleSensorEventsApiRequest(GetSensorEventsRq(sensorId, limit, offset))
            }
          }
        }
        }

        /* Sensor status management */
      } ~ pathPrefix("api" / "v1" / "sensors" / "status") {
        authenticate(tokenAuthenticator) { user =>
          // --- Get latest event for each sensor --- //
          get {
            val actor = actorRefFactory.actorOf(SensorServiceActor.props())
            val future = actor ? GetAllSensorsRq()
            val result = Await.result(future, timeout.duration).asInstanceOf[GetAllSensorRs]
            val availableSensorIds = result.sensors.map(sensor => sensor.sensorId)
            handleSensorEventsApiRequest(GetSensorLatestEventsRq(availableSensorIds))
          }
        }
      }
    }

  /** Routes for sensors management **/
  val sensorRoutes =
    cors {
      authenticate(tokenAuthenticator) { user =>
        pathPrefix("api" / "v1" / "sensors" / "types") {
          // --- Get all sensor types --- //
          get {
            handleSensorTypesApiRequest(GetAllSensorTypesRq())
          }
        } ~ pathPrefix("api" / "v1" / "sensors") {
          put {
            // --- Register new sensor --- //
            entity(as[JObject]) { data =>
              val sensor = new Sensor()
              sensor.fromJObject(data, false)
              handleSensorApiRequest(CreateSensorRq(sensor))
            }
          } ~ post {
            // --- Update sensor --- //
            entity(as[JObject]) { data =>
              val sensor = new Sensor()
              sensor.fromJObject(data, true)
              handleSensorApiRequest(UpdateSensorRq(sensor))
            }
          } ~ delete {
            // --- Delete sensor --- //
            entity(as[JObject]) { data =>
              val id = getParameter(data, "oid").get
              sensorDao.checkSensorsExistenceByOid(List(id))
              val sensorId = sensorDao.getSensorByObjectId(id).get.sensorId
              actorRefFactory.actorOf(SensorEventServiceActor.props()) ! RemoveSensorEventsForSensor(sensorId)
              actorRefFactory.actorOf(ZoneServiceActor.props()) ! RemoveSensorFromZone(id)
              handleSensorApiRequest(DeleteSensorRq(id))
            }
          } ~ get {
            // --- Get all sensors --- //
            handleSensorApiRequest(GetAllSensorsRq())
          }
        }
      }
    }

  def notifyAlarmWatcher(message: AlarmStatusProtocol): Unit = {
    actorRefFactory.actorSelection("/user/alarmWatcher") ! message
  }

  def logUserAction(user: User, action: UserActionType, ip: String): Unit =
    actorRefFactory.actorOf(UserActionServiceActor.props()) ! LogUserAction(user, action, ip = ip)

  def handleAlarmsApiRequest(message: AlarmStatusProtocol): Route =
    ctx => perRequest(actorRefFactory, ctx, AlarmStatusServiceActor.props(), message)

  def handleUsersApiRequest(message: UserProtocol): Route =
    ctx => perRequest(actorRefFactory, ctx, UserServiceActor.props(), message)

  def handleUserActionApiRequest(message: UserActionProtocol): Route =
    ctx => perRequest(actorRefFactory, ctx, SoundServiceActor.props(), message)

  def handleTokensApiRequest(message: TokenProtocol): Route =
    ctx => perRequest(actorRefFactory, ctx, TokenServiceActor.props(), message)

  def handleSensorTypesApiRequest(message: SensorTypeProtocol): Route =
    ctx => perRequest(actorRefFactory, ctx, SensorTypeServiceActor.props(), message)

  def handleSensorApiRequest(message: SensorProtocol): Route =
    ctx => perRequest(actorRefFactory, ctx, SensorServiceActor.props(), message)

  def handleSensorEventsApiRequest(message: SensorEventProtocol): Route =
    ctx => perRequest(actorRefFactory, ctx, SensorEventServiceActor.props(), message)

  def handleZonesApiRequest(message: ZoneProtocol): Route =
    ctx => perRequest(actorRefFactory, ctx, ZoneServiceActor.props(), message)

  def handleZoneStatusApiRequest(message: ZoneStatusProtocol): Route =
    ctx => perRequest(actorRefFactory, ctx, ZoneStatusServiceActor.props(), message)

  def handleSettingsApiRequest(message: SettingsProtocol): Route =
    ctx => perRequest(actorRefFactory, ctx, SettingsServiceActor.props(), message)


}
