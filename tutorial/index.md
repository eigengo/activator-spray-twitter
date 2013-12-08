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
Before I can move on to the _big data_ code, I must deal with the authentication that Twitter requires.

To save the tweets, we need to _connect_ to the correct keyspace, which gives us the Cassandra ``Session``. Because we try to be as efficient as possible, we will take advantage of Cassandra's ``PreparedStatement``s and ``BoundStatement``s. The ``PreparedStatement`` is a pre-chewed CQL statement, a ``BoundStatement`` is a ``PreparedStatemnt`` whose parameter values are set.

So, this gives us the hint of the what the ``saveTweet`` function needs to do.

```scala
class TweetWriterActor(cluster: Cluster) extends Actor {
  val session = cluster.connect(Keyspaces.akkaCassandra)
  val preparedStatement = session.prepare("INSERT INTO tweets(key, user_user, text, createdat) VALUES (?, ?, ?, ?);")

  def saveTweet(tweet: Tweet): Unit =
    session.executeAsync(preparedStatement.bind(tweet.id.id, tweet.user.user, tweet.text.text, tweet.createdAt))

  def receive: Receive = {
    case tweets: List[Tweet] =>
    case tweet: Tweet        =>
  }
}
```

The only thing that remains to be done is to use it in the ``receive`` partial function.

```scala
class TweetWriterActor(cluster: Cluster) extends Actor {
  val session = cluster.connect(Keyspaces.akkaCassandra)
  val preparedStatement = session.prepare("INSERT INTO tweets(key, user_user, text, createdat) VALUES (?, ?, ?, ?);")

  def saveTweet(tweet: Tweet): Unit =
    session.executeAsync(preparedStatement.bind(tweet.id.id, tweet.user.user, tweet.text.text, tweet.createdAt))

  def receive: Receive = {
    case tweets: List[Tweet] => tweets foreach saveTweet
    case tweet: Tweet        => saveTweet(tweet)
  }
}
```

So, we have code that saves instances of ``Tweet`` to the keyspace in our Cassandra cluster.

#Reading from Cassandra
Reading the data is ever so slightly more complex: we would like to support the _count_ and _find all_ operations. Then, we need to be able to construct Cassandra queries; then, given a Cassandra ``Row``, we need to be able to turn it into our ``Tweet`` object. Naturally, we also want to take advantage of the asynchronous nature of the Cassandra driver. Luckily, things won't be that complex. Let me begin with the structure of the ``TweetReaderActor``.

```scala
object TweetReaderActor {
  case class FindAll(maximum: Int = 100)
  case object CountAll
}

class TweetReaderActor(cluster: Cluster) extends Actor {
  val session = cluster.connect(Keyspaces.akkaCassandra)
  val countAll  = new BoundStatement(session.prepare("select count(*) from tweets;"))

  def receive: Receive = {
    case FindAll(maximum)  =>
      // reply with List[Tweet]
    case CountAll =>
      // reply with Long
  }
}
```

In the companion object, I have defined the ``FindAll`` and ``CountAll`` messages that our actor will react to; I have also left in the code that gives us the ``Session`` and then used the ``Session`` to construct a ``BoundStatement`` that counts all rows. Next up, we need to be able to construct an instance of ``Tweet`` given a ``Row``.

```scala
class TweetReaderActor(cluster: Cluster) extends Actor {
  ...

  def buildTweet(r: Row): Tweet = {
    val id = r.getString("key")
    val user = r.getString("user_user")
    val text = r.getString("text")
    val createdAt = r.getDate("createdat")
    Tweet(id, user, text, createdAt)
  }
  ...
}
```

Again, nothing too dramatic: we simply pick the values of the columns in the row and use them to make an instance of ``Tweet``. Now, let's wire in the Cassandra magic. We would like to _execute_ (asynchronously) some _query_; map the _rows_ returned from that query execution to turn them into the _tweets_; and then _pipe_ the result to the _sender_. (The italic text gives plenty of hints, so let's just get the code in.)

```scala
class TweetReaderActor(cluster: Cluster) extends Actor {
  val session = cluster.connect(Keyspaces.akkaCassandra)
  val countAll  = new BoundStatement(session.prepare("select count(*) from tweets;"))

  import scala.collection.JavaConversions._
  import cassandra.resultset._
  import context.dispatcher
  import akka.pattern.pipe

  def buildTweet(r: Row): Tweet = {...}

  def receive: Receive = {
    case FindAll(maximum)  =>
      val query = QueryBuilder.select().all().from(Keyspaces.akkaCassandra, "tweets").limit(maximum)
      session.executeAsync(query) map(_.all().map(buildTweet).toList) pipeTo sender
    case CountAll =>
      session.executeAsync(countAll) map(_.one.getLong(0)) pipeTo sender
  }
}
```

Let me dissect the ``FindAll`` message handler. First, I construct the ``query`` using
Cassandra's ``QueryBuilder``. This is ordinary Cassandra code.

What follows is much more interesting: I call the ``executeAsync`` method on the ``session``,
which returns ``ResultSetFuture``. Using implicit conversion in ``cassandra.resultset._``, I turn
 the ``ResultSetFuture`` into Scala's ``Future[ResultSet]``. This allows me to use the ``Future.map`` method to turn the ``ResultSet`` into ``List[Tweet]``.


Calling ``session.executeAsync(query) map`` expects as its parameter a function from ``ResultSet`` to some type ``B``. In our case, ``B`` is ``List[Tweet]``. The ``ResultSet`` contains the method ``all()``, which returns ``java.util.List[Row]``. To be able to ``map`` over the ``java.util.List[Row]``, we need to turn it into the Scala ``List[Row]``. To do so, we bring in the implicit conversions in ``scala.collection.JavaConversions``. And now, we can complete the parameter of the ``Future.map`` function.


``session.executeAsync(query) map(_.all().map(buildTweet).toList)`` therefore gives us ``Future[List[Tweet]]``, which is tantalizingly close to what we need. We do not want to block for the result, and we are too lazy to use the ``onSuccess`` function, because all that it would do is to pass on the result to the ``sender``. So, instead, we _pipe_ the success of the future to the ``sender``! That completes the picture, explaining the entire line ``session.executeAsync(query) map(_.all().map(buildTweet).toList) pipeTo sender``.

#Connecting to Cassandra
Before I move on, I need to explain where the ``Cluster`` value comes from. Thinking about the system we are writing, we may need to have different values of ``Cluster`` for tests and for the main system. Moreover, the test ``Cluster`` will most likely need some special setup. Because I can't decide just yet, I'd simply define that there is a ``CassandraCluster`` trait that returns the ``Cluster``; and to give implementations that do the right thing: one that loads the configuration from the ``ActorSystem``'s configuration, and one that is hard-coded to be used in tests.

```scala
trait CassandraCluster {
  def cluster: Cluster
}
```

The configuration-based implementation and the test configuration differ only in the values they use to make the ``Cluster`` instance.

```scala
// in src/scala/main
trait ConfigCassandraCluster extends CassandraCluster {
  def system: ActorSystem

  private def config = system.settings.config

  import scala.collection.JavaConversions._
  private val cassandraConfig = config.getConfig("akka-cassandra.main.db.cassandra")
  private val port = cassandraConfig.getInt("port")
  private val hosts = cassandraConfig.getStringList("hosts").toList

  lazy val cluster: Cluster =
    Cluster.builder().
      addContactPoints(hosts: _*).
      withCompression(ProtocolOptions.Compression.SNAPPY).
      withPort(port).
      build()
}

// in src/scala/test
trait TestCassandraCluster extends CassandraCluster {
  def system: ActorSystem

  private def config = system.settings.config

  import scala.collection.JavaConversions._
  private val cassandraConfig = config.getConfig("akka-cassandra.test.db.cassandra")
  private val port = cassandraConfig.getInt("port")
  private val hosts = cassandraConfig.getStringList("hosts").toList

  lazy val cluster: Cluster =
    Cluster.builder().
      addContactPoints(hosts: _*).
      withPort(port).
      withCompression(ProtocolOptions.Compression.SNAPPY).
      build()

}
```

This allows me to mix in the appropriate trait and get the properly configured ``Cluster``. But there's a little twist when it comes to tests: for the tests, I want to have the cluster in a well-known state. To solve this, I create the ``CleanCassandra`` trait that resets the ``Cluster`` given by some ``CassandraCluster.cluster``.

```scala
trait CleanCassandra extends SpecificationStructure {
  this: CassandraCluster =>

  private def runClq(session: Session, file: File): Unit = {
    val query = Source.fromFile(file).mkString
    query.split(";").foreach(session.execute)
  }

  private def runAllClqs(): Unit = {
    val session = cluster.connect(Keyspaces.akkaCassandra)
    val uri = getClass.getResource("/").toURI
    new File(uri).listFiles().foreach { file =>
      if (file.getName.endsWith(".cql")) runClq(session, file)
    }
    session.shutdown()
  }

  override def map(fs: => Fragments) = super.map(fs) insert Step(runAllClqs())
}
```

When I mix in this trait into my test, it registers the ``runAllClqs()`` steps to be executed _before_ all other steps in the test.

#Testing
And so, I can write my first test that verifies that the ``TwitterReaderActor`` and ``TwitterWriterActor`` indeed work as expected. The body of the test is rather long, but it is not too difficult to conceptually follow what is happening.

```scala
class TweetActorsSpec extends TestKit(ActorSystem())
  with SpecificationLike with TestCassandraCluster with CleanCassandra with ImplicitSender {
  sequential

  val writer = TestActorRef(new TweetWriterActor(cluster))
  val reader = TestActorRef(new TweetReaderActor(cluster))

  "Slow & steady" >> {
    def write(count: Int): List[Tweet] = {
      val tweets = (1 to count).map(id => Tweet(id.toString, "@honzam399", "Yay!", new Date))
      tweets.foreach(writer !)
      Thread.sleep(1000)    // wait for the tweets to hit the db
      tweets.toList
    }

    "Single tweet" in {
      val tweet = write(1).head

      reader ! FindAll(1)
      val res = expectMsgType[List[Tweet]]
      res mustEqual List(tweet)
    }

    "100 tweets" in {
      val writtenTweets = write(100)

      reader ! FindAll(100)
      val readTweets = expectMsgType[List[Tweet]]
      readTweets must containTheSameElementsAs(writtenTweets)
    }
  }

}
```

We are mixing in a lot of components to assemble the test. First of all, we ar extending the ``TestKit``, giving it an ``ActorSystem()`` as constructor parameter; we next mix in Specs2's ``SpecificationLike``, then our Cassandra test environment, completing the picture with the ``ImplicitSender`` to allow us to examine the responses.

The actual body of the ``"Slow & steady"`` specification verifies that we can write & read single and 100 tweets.

Before you run the test, you must make sure that you have Cassandra running and that you've created the right keyspaces. To make your life easier, you can simply run the CQL scripts in ``src/data``. You need to run--in sequence:

```
keyspaces.cql
Then, in the correct keyspace:
   tables.cql
   words.cql
```

#Scanning tweets
Onwards! Now that we know that we can safely store and retrieve the tweets from Cassandra, we need to write the component that is going to download them. In our system, this is the ``TweetScannerActor``. It receives a message of type ``String``, and it performs the HTTP request to download the tweets. (To keep this tutorial simple, I'm using the convenient Twitter proxy at ["http://twitter-search-proxy.herokuapp.com/search/tweets](http://twitter-search-proxy.herokuapp.com/search/tweets?q=). In any case, the task for the scanner actor is to construct the HTTP request, receive the response, turn it into ``List[Tweet]`` and send that list to the ``ActorRef`` of the ``TweetWriterActor``.

```scala
class TweetScannerActor(tweetWrite: ActorRef, queryUrl: String => String)
  extends Actor with TweetMarshaller {

  import context.dispatcher
  import akka.pattern.pipe

  private val pipeline = sendReceive ~> unmarshal[List[Tweet]]

  def receive: Receive = {
    case query: String => pipeline(Get(queryUrl(query))) pipeTo tweetWrite
  }
}
```

It is actually that simple! We use Spray-Client to construct the HTTP pipeline, which makes HTTP request (``sendReceive``), and passes the raw HTTP response to be unmarshalled (that is, turned into instance of types in our systems).

The ``pipeline`` starts its job when it is applied to ``HttpRequest``; in our case, ``Get(url: String)`` represents a mechanism that can construct such ``HttpRequest``s. When applied to the ``query``, the function ``queryUrl`` returns the actual URL for the pipeline to work on.

Execution of the ``pipeline`` returns ``Future[List[Tweet]]``, which we can happily ``pipeTo`` the ``tweetWrite`` actor.

The only job that remains is for us to implement the unmarshaller. In Spray-Client's case unmarshaller is a typeclass and the implementation is an instance of the typeclass. The easiest way to think about typeclasses is to imagine that typeclass is a _trait_ which defines behaviour for some type, and that the typeclass instance is the implementation of that trait for some type.

In Spray-Client's case, the typeclass is ``trait Unmarshaller[A]``, whose ``apply`` method takes ``HttpEntity`` and returns ``Deserialized[A]``. The name ``apply`` should ring some bells--and indeed, ``Unmarshaller[A]`` is in essence an alias for ``trait Unmarshaller[A] extends (HttpEntity => Deserialized[A])``. (_Yes, you can_ extend _(A => B) in Scala, which is syntactic sugar for_ ``trait Unmarshaller[A] extends Function1[HttpEntity, Deserialized[A]]``.) Now, the ``unmarshal`` directive we used earlier is defined as

```scala
def unmarshal[A : Unmarshaller]: HttpResponse => A
```

The ``: Unmarshaller`` is a context bound on the type parameter ``A``, which causes the compiler to expand the function into

```scala
def unmarshal[A](implicit ev: Unmarshaller[A]): HttpResponse => A
```

The ``unmarshal`` function expects an instance of the typeclass ``Unmarshaller`` for some type ``A``; in our case, we specify the type ``A`` to be ``List[Tweet]``. We can make a mental substitution of ``A`` for ``List[Tweet]`` and arrive at ``unmarshal[List[Tweet]](implicit ev: Unmarshaller[List[Tweet]]): ...``. To make the application work, there needs to be a value of type ``Unmarshaller[List[Tweet]]`` in the current implicit scope. When we give such value, we say that we are giving instance of the ``Unmarshaller`` typeclass.

```scala
trait TweetMarshaller {
  type Tweets = List[Tweet]

  implicit object TweetUnmarshaller extends Unmarshaller[Tweets] {

    val dateFormat = new SimpleDateFormat("EEE MMM d HH:mm:ss Z yyyy")

    def mkTweet(status: JsValue): Deserialized[Tweet] = {
      val json = status.asJsObject
      ...
    }

    def apply(entity: HttpEntity): Deserialized[Tweets] = {
      val json = JsonParser(entity.asString).asJsObject
      ...
    }
  }

}
```

Our typeclass instance is the ``TweetUnmarshaller`` singleton, which extends ``Unmarshaller[Tweets]``. Notice that I have also defined a type alias ``type Tweets = List[Tweet]`` so that I don't have to write too many square brackets. By extending ``Unmarshaller[Tweets]``, we must implement the ``apply`` method, which is applied to the ``HttpEntity`` and should return either deserialized tweets or indicate an error.

We nearly have everything in place. But how do we satisfy ourselves that the ``TweetScannerActor`` indeed works?

#Testing the ``TweetScannerActor``
To test the scanner fully, we would like to use a well-known service. But where do we get it? We can't really use the live service, because the tweets keep changing. It seems that the only way would be for us to implement a mock service and use it in our tests.

```scala
class TweetScanActorSpec extends TestKit(ActorSystem())
  with SpecificationLike with ImplicitSender {

  sequential

  val port = 12345
  def testQueryUrl(query: String) = s"http://localhost:$port/q=$query"

  val tweetScan = TestActorRef(new TweetScannerActor(testActor, testQueryUrl))

  "Getting all 'typesafe' tweets" >> {

    "should return more than 10 last entries" in {
      val twitterApi = TwitterApi(port)
      tweetScan ! "typesafe"
      Thread.sleep(1000)
      val tweets = expectMsgType[List[Tweet]]
      tweets.size mustEqual 4
      twitterApi.stop()
      success
    }
  }
}
```

When constructing the ``TweetScannerActor``, we give it the ``testActor`` and a function that returns URLs on ``localhost`` on some ``port``. In the body of the example, we start the mock ``TwitterApi`` on the given port; and use our ``TweetScannerActor`` to make the HTTP request. Because we gave the ``testActor`` as the writer ``ActorRef``, we should now be able to see the ``List[Tweet]`` that would have been sent to the ``TweetWriterActor``.

Because our mock tweetset contains four tweets, we can make the assertion that the list indeed contains four tweets. (I leave more extensive testing as exercise for the reader.)

#Main
I am now satisfied that the components in the system work as expected; I can therefore assemble the ``App`` object, which brings everything together in a command-line interface. I give you the ``Main`` object:

```scala
object Main extends App with ConfigCassandraCluster {
  import Commands._
  import akka.actor.ActorDSL._

  def twitterSearchProxy(query: String) = s"http://twitter-search-proxy.herokuapp.com/search/tweets?q=$query"

  implicit lazy val system = ActorSystem()
  val write = system.actorOf(Props(new TweetWriterActor(cluster)))
  val read = system.actorOf(Props(new TweetReaderActor(cluster)))
  val scan = system.actorOf(Props(new TweetScannerActor(write, twitterSearchProxy)))

  // we don't want to bother with the ``ask`` pattern, so
  // we set up sender that only prints out the responses to
  // be implicitly available for ``tell`` to pick up.
  implicit val _ = actor(new Act {
    become {
      case x => println(">>> " + x)
    }
  })

  @tailrec
  private def commandLoop(): Unit = {
    Console.readLine() match {
      case QuitCommand                => return
      case ScanCommand(query)         => scan ! query.toString

      case ListCommand(count)         => read ! FindAll(count.toInt)
      case CountCommand               => read ! CountAll

      case _                          => return
    }

    commandLoop()
  }

  // start processing the commands
  commandLoop()

  // when done, stop the ActorSystem
  system.shutdown()

}
```

We have the main ``commandLoop()`` function, which reads the line from standard input, matches it against the commands and sends the appropriate messages to the right actors. It also mixes in the "real" source of Cassandra ``Cluster`` values and specifies the live function that constructs the URL to retrieve the tweets.

#For interested readers: ``TwitterApi``
The ``TwitterApi`` is the mock version of the real Twitter Proxy API. It makes it easy to write repeatable and independent tests of the ``TweetScannerActor``. Under the hood, it is implemented using Spray-Can and the HTTP Akka Extension. The intention is that upon construction it binds to the given port and responds to every GET request with the given body. To shutdown the API, you must call the ``stop()`` method. To give me greater control over the construction of the class, I define the constructor as private and give a companion object whose ``apply`` method returns properly constructed and bound ``TwitterApi``.

```scala
class TwitterApi private(system: ActorSystem, port: Int, body: String) {

  val blackHoleActor = system.actorOf(Props(new Actor {
    def receive: Receive = Actor.emptyBehavior
  }))

  private class Service extends Actor {

    def receive: Receive = {
      case _: Http.Connected =>
        sender ! Http.Register(self)
      case HttpRequest(HttpMethods.GET, _, _, _, _) =>

        sender ! HttpResponse(entity = HttpEntity(body))
      case _ =>
    }
  }

  private val service = system.actorOf(Props(new Service).withRouter(RoundRobinRouter(nrOfInstances = 50)))
  private val io = IO(Http)(system)
  io.tell(Http.Bind(service, "localhost", port = port), blackHoleActor)

  def stop(): Unit = {
    io.tell(Http.Unbind, blackHoleActor)
    system.stop(service)
    system.stop(io)
  }
}

object TwitterApi {

  def apply(port: Int)(implicit system: ActorSystem): TwitterApi = {
    val body = Source.fromInputStream(getClass.getResourceAsStream("/tweets.json")).mkString
    new TwitterApi(system, port, body)
  }

}
```

Calling ``TwitterApi(1234)`` with an implicit ``ActorSystem`` in scope (for example in a ``TestKit`` test) loads the body from a well-known location on the classpath and then constructs the ``TwitterApi`` instance, passing it the ``ActorSystem``, ``port``, and ``body``. In the body of the ``TwitterApi`` class, I have an ``Actor`` that serves the HTTP requests, which is then used in the ``Bind`` message sent to the ``io`` extension.

The service is bound to the HTTP server until the ``stop()`` method is called. The ``stop()`` method unbinds the ``service``, and stops it and the ``io`` extension. (You would typically do this at the end of your example.)

#For interested readers: ``sentiment.R``
Now, let's complete the picture with some mood analysis in R. I am trying to find if people are happy or unhappy about the tweets. To do so, I use a list of positive and negative words, which I store in my Cassandra ``positivewords`` and ``negativewords`` tables.