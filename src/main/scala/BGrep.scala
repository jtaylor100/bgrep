
import com.outr.lucene4s._
import com.outr.lucene4s.query.Sort
import org.rogach.scallop.{ScallopConf, ScallopOption}
import scalaj.http._

import scala.concurrent._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.io.StdIn

case class Book(title: String, link: String, description: String, ratings: Int)

// TODO: Allow key to to be defined from ENV.
class Config(arguments: Seq[String]) extends ScallopConf(arguments) {
  val help: ScallopOption[Boolean] = opt[Boolean]()
  val searchTerms: ScallopOption[String] = trailArg[String](required = true)
  val shelf: ScallopOption[String] = trailArg[String](required = true)
  val key: ScallopOption[String] = opt[String](required = true)
  val interactive: ScallopOption[Boolean] = opt[Boolean]()
  verify()
}

// TODO: Add tests for common cases.
object BGrep extends App {
  // TODO: Use auto-generated docs from Scallop
  val usage =
    """
      |Usage: bgrep [OPTION]... SEARCH-TERMS SHELF-ID
      |Search for SEARCH-TERM in each book contained in the goodreads.com shelf SHELF-ID.
      |Example: bgrep software 56943009
      |
      |  -h, --help             Show this help.
      |  -k, --key API-KEY      Key for the Goodreads.com API. See https://www.goodreads.com/api/keys
      |  -i, --interactive      Prompt for search queries instead of exiting after the results for first are shown.
    """.stripMargin

  if (args.length == 0) {
    print(usage)
    System.exit(2)
  }

  // TODO: Learn how to use the arguments config properly (not .get.get)
  val conf = new Config(args)

  if (conf.help.getOrElse(false)) {
    print(usage)
    System.exit(0)
  }

  val baseUrl = s"https://www.goodreads.com/review/list/${conf.shelf.get.get}.xml?key=${conf.key.get.get}&v=2"
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
  lucene.query().filter(conf.searchTerms.get.get).sort(Sort(ratings, reverse = true)).limit(500).search().results.foreach(result => {
    // TODO: Output URL with title, or make information outputted configurable.
    System.out.println(result(title))
  })

  if (conf.interactive.toOption.get) {
    while(true) {
      System.out.print("> ")
      val query = StdIn.readLine()
      System.out.println()
      lucene.query().filter(query).sort(Sort(ratings, reverse = true)).limit(500).search().results.foreach(result => {
        // TODO: Output URL with title, or make information outputted configurable.
        System.out.println(result(title))
      })
    }
  }
}
