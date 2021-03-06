package lila.openingexplorer

import scala.util.Random
import java.io.{ ByteArrayInputStream, ByteArrayOutputStream, InputStream, OutputStream }

import cats.data.Validated
import cats.syntax.option._
import cats.syntax.either._

import chess.Color

case class GameRef(
    gameId: String,
    winner: Option[Color],
    speed: SpeedGroup,
    averageRating: Int
) extends PackHelper {

  def group: (RatingGroup, SpeedGroup) =
    (RatingGroup.find(averageRating), speed)

  def write(stream: OutputStream) = {
    val packedGameId = gameId
      .zip(gameId.indices.reverse)
      .map { case (c, i) =>
        GameRef.base.indexOf(c) * math.pow(GameRef.base.size, i).toLong
      }
      .sum

    val packedSpeed = speed.id << 14

    val packedWinner = winner match {
      case Some(Color.White) => 2 << 12
      case Some(Color.Black) => 1 << 12
      case None              => 0
    }

    // must be between 0 and 4095
    val packedRating = math.min((1 << 12) - 1, math.max(0, averageRating))

    writeUint16(stream, packedWinner | packedSpeed | packedRating)
    writeUint48(stream, packedGameId)
  }

  def pack = {
    val out = new ByteArrayOutputStream()
    write(out)
    out.toByteArray
  }
}

object GameRef extends PackHelper {

  val packSize = 8

  private val base = ('0' to '9') ++ ('a' to 'z') ++ ('A' to 'Z')

  private def unpackGameId(v: Long): String = {
    def decodeGameId(v: Long, res: List[Char] = Nil): List[Char] = {
      val quotient = v / base.size
      if (quotient > 0)
        decodeGameId(quotient, base((v % base.size).toInt) :: res)
      else
        base(v.toInt) :: res
    }

    decodeGameId(v).mkString.reverse.padTo(8, base(0)).reverse
  }

  def read(stream: InputStream): GameRef = {
    val metaXorRating = readUint16(stream)

    val winner = (metaXorRating >> 12) & 0x3 match {
      case 2 => Some(Color.White)
      case 1 => Some(Color.Black)
      case _ => None
    }

    val speed = SpeedGroup.byId.getOrElse((metaXorRating >> 14) & 0x3, SpeedGroup.Classical)

    val averageRating = metaXorRating & 0xfff

    GameRef(
      unpackGameId(readUint48(stream)),
      winner,
      speed,
      averageRating
    )
  }

  def unpack(b: Array[Byte]) = {
    val in = new ByteArrayInputStream(b)
    read(in)
  }

  private def averageRating(tags: chess.format.pgn.Tags): Option[Int] = {
    val ratings = chess.Color.all.flatMap { c => tags(s"${c}Elo").flatMap(_.toIntOption) }
    if (ratings.nonEmpty) Some(ratings.sum / ratings.size) else None
  }

  def fromLichessPgn(game: chess.format.pgn.ParsedPgn): Validated[String, GameRef] = {
    (for {
      gameId <- game.tags("LichessID").toRight("No LichessID header")
      winner <- game.tags.resultColor.toRight("No result")
      speed  <- SpeedGroup.fromPgn(game.tags).toRight("Invalid clock")
      rating <- averageRating(game.tags).toRight("No rating")
    } yield new GameRef(gameId, winner, speed, rating)).toValidated
  }

  def fromMasterPgn(game: chess.format.pgn.ParsedPgn): Validated[String, GameRef] = {
    val gameId = game.tags("LichessID") orElse {
      game.tags("FICSGamesDBGameNo") flatMap (_.toLongOption) map unpackGameId
    } getOrElse Random.alphanumeric.take(8).mkString

    val speed = SpeedGroup.fromPgn(game.tags).getOrElse(SpeedGroup.Classical)

    for {
      winner <- game.tags.resultColor.toValid("No result")
      rating <- averageRating(game.tags).toValid("No rating")
    } yield new GameRef(gameId, winner, speed, rating)
  }
}
