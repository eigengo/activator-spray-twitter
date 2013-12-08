#Spray client and tweets

#Spray client and tweets
In this tutorial, I am going to use the Spray Client, Akka IO and core Akka to build an application that
streams tweets and then performs trivial analysis of the received data.
It shows you how to build a simple Akka application with just a few actors, how to use Akka IO to make HTTP
requests, how to wire in OAuth, and how to deal with streaming input. It also demonstrates approaches to testing of
such applications.

Let's begin by showing the overall structure of the code we're building.

![Overall structure](tutorial/overall.png)

The components in blue are actors, the components in orange are traits; and we shall be providing some interesting
implementations of those traits.

#The core
I begin by constructing the core of our system. It contains two actors: one that deals with the HTTP connection to
Twitter, and one that deals with processing of the tweets as they arrive. In code, they are the
``TweetStreamerActor``, and ``SentimentAnalysisActor``. The ``TweetStreamerActor`` needs the URI to connect to and
the reference to the actor that is going to perform the sentiment analysis. Thus, we arrive at

```scala
object TweetStreamerActor {
  val twitterUri = Uri("https://stream.twitter.com/1.1/statuses/filter.json")
}

class TweetStreamerActor(uri: Uri, processor: ActorRef) extends Actor {
  ...
}

class SentimentAnalysisActor extends Actor {
  ...
}
```

For our convenience, I included the ``TweetStreamerActor`` companion object, which contains the uri to Twitter's
streaming API. To construct our application, all we need to do is to instantiate the actors in the right sequence:

```scala
val system    = ActorSystem()
val sentiment = system.actorOf(Props(new SentimentAnalysisActor))
val stream    = system.actorOf(Props(new TweetStreamerActor(TweetStreamerActor.twitterUri, sentiment)))
```

---

#Streaming the tweets
Next up, let's write the core of the ``TweetStreamerActor``: We construct the Akka IO, and then construct
the appropriate ``HttpRequest`` that we send to the given ``uri``.

```scala
class TweetStreamerActor(uri: Uri, processor: ActorRef) extends Actor {
  val io = IO(Http)(context.system)

  def receive: Receive = {
    case query: String =>
      val body = HttpEntity(ContentType(MediaTypes.`application/x-www-form-urlencoded`), s"track=$query")
      val rq = HttpRequest(HttpMethods.POST, uri = uri, entity = body)
      sendTo(io).withResponsesReceivedBy(self)(rq)
    case MessageChunk(entity, _) =>
    case _ =>
  }
}
```

Dissecting the ``TweetStreamerActor``, we first get the reference (an ``ActorRef`` no less!) to the HTTP manager by
calling ``IO(Http)(system.context)``. This actor is responsible for dealing with all HTTP communication; in our case,
we'll be sending the ``HttpRequest``s to it.

And that's precisely what we do in the ``receive`` function. When the ``query: String`` message arrives, we construct
the HTTP request and then call ``sendTo(io).withResponsesReceivedBy(self)(rq)``, which--in human speak--means
_take the IO actor (sendTo(io)), have it send all received responses to this actor (withResponsesReceivedBy(self)),
and apply the request to it (rq)_. And so, Spray client is going to send the responses to the request to this actor,
which means that we have to handle ``ChunkedResponseStart``, ``MessageChunk``, and many other HTTP messages. However,
the only HTTP layer message we're really interested in is the ``MessageChunk``, whose ``entity`` contains the JSON
that represents each tweet.

Being strongly-typed, we would like to deal with our own types, not JSON ``String``s. And so, we must implement an
unmarshaller that can turn the entity into an instance of ``Tweet``. To do that, we'll use Spray JSON module, and define
an instance of the ``Unmarshaller`` typeclass for the type ``Tweet``.

```scala
trait TweetMarshaller {

  implicit object TweetUnmarshaller extends Unmarshaller[Tweet] {
    def apply(entity: HttpEntity): Deserialized[Tweet] = {
      ???
    }
  }
}
```

I will not show all the boring JSON gymnastics; instead, I'll just outline the main points. Our typeclass instance must
implement the ``apply(HttpEntity): Deserialized[Tweet]`` method; ``Deserialized[A]`` is a type alias for
``Either[DeserializationError, T]``.

```scala
trait TweetMarshaller {

  implicit object TweetUnmarshaller extends Unmarshaller[Tweet] {

    def mkUser(user: JsObject): Deserialized[User] = ...

    def mkPlace(place: JsValue): Deserialized[Option[Place]] = ...

    def apply(entity: HttpEntity): Deserialized[Tweet] = {
      Try {
        val json = JsonParser(entity.asString).asJsObject
        (json.fields.get("id_str"), json.fields.get("text"), json.fields.get("place"), json.fields.get("user")) match {
          case (Some(JsString(id)), Some(JsString(text)), Some(place), Some(user: JsObject)) =>
            val x = mkUser(user).fold(x => Left(x), { user =>
              mkPlace(place).fold(x => Left(x), { place =>
                Right(Tweet(id, user, text, place))
              })
            })
            x
          case _ => Left(MalformedContent("bad tweet"))
        }
      }
    }.getOrElse(Left(MalformedContent("bad json")))
  }
}
```

Folding the instances of ``Deserialized[_]``, we arrive at code that can (safely) turn a JSON that may represent a
tweet into an instance of ``Tweet``. Let's now wire add the ``TweetMarshaller`` to our ``TweetStreamerActor`` and
use it when dealing with the ``MessageChunk`` message.


```scala
class TweetStreamerActor(uri: Uri, processor: ActorRef) extends Actor with TweetMarshaller {

  def receive: Receive = {
    ...
    case MessageChunk(entity, _) => TweetUnmarshaller(entity).fold(_ => (), processor !)
    case _ =>
  }
}
```

Notice that I've mixed in the ``TweetMarshaller`` to the actor using the ``with`` keyword. This give me access to
the ``TweetUnmarshaller`` typeclass instance, and I call its ``apply`` method to the received ``entity``. It then
gives me ``Deserialized[Tweet]``, fold the result into ``()`` by "ignoring" the values on the ``Left``s and by
sending the values on the ``Right`` to the ``processor``. This is a terse way of writing the usual pattern match:

```scala
class TweetStreamerActor(uri: Uri, processor: ActorRef) extends Actor with TweetMarshaller {

  def receive: Receive = {
    ...
    case MessageChunk(entity, _) =>
      TweetUnmarshaller(entity) match {
        case Right(tweet) => processor ! tweet
        case _            =>
      }
  }
}
```

Onwards. Let's see if our code runs as expected by writing a test; a test that constructs the HTTP server that serves
the tweet responses like the real Twitter API, and by using the ``TweetStreamerActor`` to check that it can deal
with the received responses. The test, in its entirety fits on just a few lines of code:

```scala
class TweetStreamerActorSpec extends TestKit(ActorSystem()) with SpecificationLike with ImplicitSender {
  sequential

  val port = 12345
  val tweetStream = TestActorRef(
    new TweetStreamerActor(Uri(s"http://localhost:$port/"), testActor))

  "Streaming tweets" >> {

    "Should unmarshal one tweet" in {
      val twitterApi = TwitterApi(port)
      tweetStream ! "quux"  // our TwitterApi does not care

      val tweet = expectMsgType[Tweet]
      tweet.text mustEqual "Aggressive Ponytail #freebandnames"
      tweet.user.lang mustEqual "en"
      tweet.user.id mustEqual "137238150"
      tweet.place mustEqual None
      twitterApi.stop()
      success
    }
  }

}
```

Notice how I make the most of Akka's TestKit and use the ``testActorRef`` as the ``processor`` parameter of the
``TweetStreamerActor``. This allows me to examine the received responses--viz the line ``expectMsgType[Tweet]``. Before
we move on to the real Twitter API, I will show the implementation of the ``TwitterApi``: our test-only HTTP
server that simulates the Twitter API.

```scala
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

  val service = system.actorOf(Props(new Service))
  val io = IO(Http)(system)
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
```

It too uses the Akka IO's Http extension, but this time, to bind the ``Service`` to the ``localhost`` interface
on the given ``port``. Whenever a client connects (by sending the ``Http.Connected`` message), I register the
same actor to handle the requests from that client by replying with ``Http.Register(self)``. (This means that our
``Service`` is a singleton handler: all clients are handled by the same actor.) Because I have registered ``self``
to be the handler for all client connections, I must react to the ``HttpRequest`` messages the clients send.
In my case, I respond to every HTTP POST by chunking the content of the ``/tweet.json`` resource.

##OAuth
Before I can move on to the _big data_ code, I must deal with the authentication that Twitter requires. Twitter
requires OAuth authorization of all requests. Most trivially, this means adding the ``Authorization`` HTTP header
with properly constructed value. To put it even another way, to authorize a ``HttpRequest`` is to take the original
request and return a new ``HttpRequest`` that includes the appropriate header. To do so in the context of OAuth,
I also need to know the consumer token and secret and the token key and secret. And this is enough to allow me to
give the outline in Scala!

```scala
object OAuth {
  case class Consumer(key: String, secret: String)
  case class Token(value: String, secret: String)

  def oAuthAuthorizer(consumer: Consumer, token: Token): HttpRequest => HttpRequest = {
    // magic
    ???
  }
}
```

The shape of the code above matches what I said above: to OAuth-authorize a HttpRequest, I need to know the details of
the _consumer_ and _token_; and the authroization process takes an unauthorized ``HttpRequest`` and adds the
required authorization to it.

I can now add the OAuth authorization to the ``TweetStreamerActor``. However, instead of just "hard coding" it in,
I will define a few traits that will allow me to control the instances of the ``TweetStreamerActor``s. I define

```scala
trait TwitterAuthorization {
  def authorize: HttpRequest => HttpRequest
}
```

And then require that the instances of the ``TweetStreamerActor`` be instantiated with the appropriate implementation
of the ``TwitterAuthorization``. In other words, the Cake pattern with self-type annotations.

```scala
class TweetStreamerActor(uri: Uri, processor: ActorRef) extends Actor with TweetMarshaller {
  this: TwitterAuthorization =>

  ...
}
```

I also provide an implementation of the ``TwitterAuthorization`` that uses the OAuth machinery, and loads the
consumer and token details from a file. (So that you don't include the authentication details in your code.)

```scala
trait OAuthTwitterAuthorization extends TwitterAuthorization {
  import OAuth._
  val home = System.getProperty("user.home")
  val lines = Source.fromFile(s"$home/.twitter/activator").getLines().toList

  val consumer = Consumer(lines(0), lines(1))
  val token = Token(lines(2), lines(3))

  val authorize: (HttpRequest) => HttpRequest = oAuthAuthorizer(consumer, token)
}
```

Notice in particular that I satisfy the ``def authorize: HttpRequest => HttpRequest`` by having the field
``authorize``, whose value is computed by applying the ``oAuthAuthorizer`` to the consumer and token.

