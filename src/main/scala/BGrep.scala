
import com.outr.lucene4s._
import com.outr.lucene4s.query.Sort
import org.rogach.scallop.{ScallopConf, ScallopOption}
import scalaj.http._

import scala.concurrent._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.io.StdIn

case class Book(title: String, link: String, description: String, ratings: Int)


// TODO: Add tests for common cases.
object BGrep extends App {
  // TODO: Learn how to use the arguments config properly (not .get.get)
  // TODO: Allow key to to be defined from ENV.
  object Config extends ScallopConf(args) {
    version(s"bgrep DEVELOPMENT BUILD by Joshua Taylor")
    banner(
      """Usage: bgrep [OPTION]... QUERY SHELF-ID
        |Search for QUERY in each book contained in the goodreads.com shelf SHELF-ID.
        |Example: bgrep software 56943009""".stripMargin)
    val help: ScallopOption[Boolean] = opt[Boolean]("help", descr = "Display help information")
    val query: ScallopOption[String] = trailArg[String]("query", required = true, descr = "Keywords to search")
    val shelf: ScallopOption[String] = trailArg[String]("shelf-id", required = true)
    val key: ScallopOption[String] = opt[String]("api-key", required = true)
    val interactive: ScallopOption[Boolean] = opt[Boolean]("interactive", descr = "Ask repeatedly for new search terms", default = Some(false))
    verify()
  }

  if (Config.help()) {
    Config.printHelp()
    System.exit(0)
  }

  val baseUrl = s"https://www.goodreads.com/review/list/${Config.shelf.get.get}.xml?key=${Config.key.get.get}&v=2"
  val numberOfBooks = (scala.xml.XML.loadString(Http(baseUrl + "&per_page=1").asString.body) \ "reviews" \@ "total").toInt
  val booksPerPage = 200
  val urls = for (pageNo <- 1 to numberOfBooks / booksPerPage + 1) yield s"$baseUrl&per_page=$booksPerPage&page=$pageNo"

  val bookFutures = urls.map(u => Future {
    // TODO: Docker style status updates.
    System.err.println("Requesting " + u)
    val responseBody = scala.xml.XML.loadString(
      Http(u)
        .timeout(connTimeoutMs = 1000, readTimeoutMs = 50000)
        .asString
        .body
    )
    System.err.println("Response OK " + u)


    (responseBody \ "reviews" \ "review")
      .map(r => r \ "book")
      .map(b =>
        Book((b \ "title").text, (b \ "link").text, (b \ "description").text, (b \ "ratings_count").text.toInt)
      )
  }(ExecutionContext.global))

  val futureBooks = Future.sequence(bookFutures.toList)
  val bList = Await.result(futureBooks, Duration.Inf)

  // TODO: Put the index in a sensible place.
  val lucene = new DirectLucene(Nil, defaultFullTextSearchable = true)
  val title = lucene.create.field[String]("title")
  val link = lucene.create.field[String]("link")
  val description = lucene.create.field[String]("description")
  val ratings = lucene.create.field[Int]("ratings")

  System.err.println("Indexing")
  bList.flatten.foreach(b => lucene.doc().fields(title(b.title), link(b.link), description(b.description), ratings(b.ratings)).index())

  System.err.println("Searching")
  lucene.query().filter(Config.query.get.get).sort(Sort(ratings, reverse = true)).limit(500).search().results.foreach(result => {
    // TODO: Output URL with title, or make information outputted Configigurable.
    System.out.println(result(title))
  })

  if (Config.interactive.toOption.get) {
    while(true) {
      System.out.print("> ")
      val query = StdIn.readLine()
      System.out.println()
      lucene.query().filter(query).sort(Sort(ratings, reverse = true)).limit(500).search().results.foreach(result => {
        // TODO: Output URL with title, or make information outputted Configigurable.
        System.out.println(result(title))
      })
    }
  }
}
