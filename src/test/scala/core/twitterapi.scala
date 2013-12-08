package core

import akka.actor.{Actor, Props, ActorSystem}
import spray.can.Http
import spray.http._
import spray.http.HttpRequest
import spray.http.HttpResponse
import akka.io.IO
import scala.io.Source

class TwitterApi private(system: ActorSystem, port: Int, body: String) {

  private class Service extends Actor {

    def receive: Receive = {
      case _: Http.Connected =>
        sender ! Http.Register(self)
      case HttpRequest(HttpMethods.POST, _, _, _, _) =>
        sender ! ChunkedResponseStart(HttpResponse(StatusCodes.OK))
        sender ! MessageChunk(body = body)
        sender ! ChunkedMessageEnd()
    }
  }

  private val service = system.actorOf(Props(new Service))
  private val io = IO(Http)(system)
  io ! Http.Bind(service, "localhost", port = port)

  def stop(): Unit = {
    io ! Http.Unbind
    system.stop(service)
    system.stop(io)
  }
}

object TwitterApi {

  def apply(port: Int)(implicit system: ActorSystem): TwitterApi = {
    val body = Source.fromInputStream(getClass.getResourceAsStream("/tweet.json")).mkString
    new TwitterApi(system, port, body)
  }

}
