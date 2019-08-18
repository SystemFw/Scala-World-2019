import cats._, implicits._
import cats.effect._, concurrent._
import cats.effect.implicits._
import fs2._, io.tcp._
import scala.concurrent.duration._

object Examples extends IOApp {
  def run(args: List[String]) = ExitCode.Success.pure[IO]

  implicit class Runner[A](s: Stream[IO, A]) {
    def yolo: Unit = s.compile.drain.unsafeRunSync
    def yoloV: Vector[A] = s.compile.toVector.unsafeRunSync
  }
  // put("hello").to[F]
  def put[A](a: A): IO[Unit] = IO(println(a))

  def yo =
    Stream
      .repeatEval(put("hello"))
      .interruptAfter(2.seconds)
      .yolo

  def address = ???

  def server[F[_]: Concurrent: Timer: ContextShift](
      group: SocketGroup): Stream[F, Unit] =
    group
      .server(address)
      .map { connection =>
        Stream.resource(connection).flatMap { socket =>
          Stream
            .range(0, 10)
            .map(i => s"Ping no $i \n")
            .covary[F]
            .metered(1.second)
            .through(text.utf8Encode)
            .through(socket.writes())
            .onFinalize(socket.endOfOutput)
            .scope
        }
      }
      .parJoinUnbounded
      .interruptAfter(10.minutes)
}
