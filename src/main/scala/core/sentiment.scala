package core

import akka.actor.Actor
import domain.Tweet
import scala.io.Source

trait SentimentSets {
  def positiveWords: Set[String]
  def negativeWords: Set[String]
}

trait SentimentOutput {
  type Category = String

  def outputCount(values: List[Iterable[(Category, Int)]]): Unit
}

trait CSVLoadedSentimentSets extends SentimentSets {
  lazy val positiveWords = loadWords("/positive_words.csv")
  lazy val negativeWords = loadWords("/negative_words.csv")

  private def loadWords(fileName: String): Set[String] = {
    Source.
      fromInputStream(getClass.getResourceAsStream(fileName)).
      getLines().
      map(line => line.split(",")(1)).
      toSet
  }
}

trait SimpleSentimentOutput extends SentimentOutput {
  def outputCount(values: List[Iterable[(Category, Int)]]): Unit = println(values)
}

trait AnsiConsoleSentimentOutput extends SentimentOutput {

  object AnsiControls {
    val EraseDisplay = "\033[2J\033[;H"
    val EraseLine    = "\033[2K\033[;H"
  }

  object AnsiColors {
    val Reset  = "\u001B[0m"
    val Bold   = "\u001B[1m"

    val Red    = "\u001B[31m"
    val Yellow = "\u001B[33m"
    val Cyan   = "\u001B[36m"
    val White  = "\u001B[37m"

    val Green  = "\u001B[32m"
    val Purple = "\u001B[35m"
    val Blue   = "\u001B[34m"

    val allColors = List(White, Purple, Blue, Red, Yellow, Cyan, Green)
  }

  val categoryPadding = 50
  val consoleWidth = 80

  println(AnsiControls.EraseDisplay)

  def outputCount(allValues: List[Iterable[(Category, Int)]]): Unit = {
    print(AnsiControls.EraseDisplay)
    allValues.foreach { values =>
      values.zipWithIndex.foreach {
        case ((k, v), i) =>
          val color = AnsiColors.allColors(i % AnsiColors.allColors.size)
          print(color)
          print(AnsiColors.Bold)
          print(k)
          print(" " * (categoryPadding - k.length))
          print(AnsiColors.Reset)
          println(v)
      }
      println("-" * consoleWidth)
    }
  }
}

class SentimentAnalysisActor extends Actor {
  this: SentimentSets with SentimentOutput =>
  import collection._

  private val counts = mutable.Map[Category, Int]()
  private val languages = mutable.Map[Category, Int]()
  private val places = mutable.Map[Category, Int]()

  private def update(data: mutable.Map[Category, Int])
                    (category: Category, delta: Int): Unit =
    data.put(category, data.getOrElse(category, 0) + delta)

  val updateCounts = update(counts)_
  val updateLanguages = update(languages)_
  val updatePlaces = update(places)_

  def receive: Receive = {
    case tweet: Tweet =>
      val text = tweet.text.toLowerCase
      val positive: Int = if (positiveWords.exists(text contains)) 1 else 0
      val negative: Int = if (negativeWords.exists(text contains)) 1 else 0

      updateCounts("positive", positive)
      updateCounts("negative", negative)
      if (tweet.user.followersCount > 200) {
        updateCounts("positive.gurus", positive)
        updateCounts("negative.gurus", negative)
      }
      updateCounts("all", 1)
      updateLanguages(tweet.user.lang, 1)
      updatePlaces(tweet.place.toString, 1)

      outputCount(List(counts, places, languages))
  }
}