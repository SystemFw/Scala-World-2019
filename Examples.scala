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
        }
      }
      .parJoinUnbounded
      .interruptAfter(10.minutes)
}

object MySyncIO {
  sealed trait IO[+A]
  object IO {
    def apply[A](v: => A): IO[A] = Delay(() => v)

//    class Handler[A, B](f: Throwable => IO[B]) extends (A => IO[B])

    case class FlatMap[B, +A](io: IO[B], k: B => IO[A]) extends IO[A]
    case class Pure[+A](v: A) extends IO[A]
    case class RaiseError(e: Throwable) extends IO[Nothing]
    case class HandleErrorWith[+A](io: IO[A], k: Throwable => IO[A])
        extends IO[A]
    case class Delay[+A](eff: () => A) extends IO[A]

    implicit def instances: MonadError[IO, Throwable] with StackSafeMonad[IO] =
      new MonadError[IO, Throwable] with StackSafeMonad[IO] {
        def pure[A](x: A): IO[A] = Pure(x)
        def handleErrorWith[A](fa: IO[A])(f: Throwable => IO[A]): IO[A] =
          HandleErrorWith(fa, f)
        def raiseError[A](e: Throwable): IO[A] = RaiseError(e)
        def flatMap[A, B](fa: IO[A])(f: A => IO[B]): IO[B] =
          FlatMap(fa, f)
      }
  }

  def read = IO(scala.io.StdIn.readLine)
  def put[A](v: A) = IO(println(v))

  def p =
    for {
      _ <- put("insert your name")
      n <- read
      _ <- put(s"Hello $n")
    } yield ()

  def pr = unsafeRun(p)

  def p1 = IO[Unit](throw new Exception).handleErrorWith(e => put(e))
  def p1r = unsafeRun(p1)

  def p2 = IO[Unit](throw new Exception).attempt
  def p2r = unsafeRun(p2)

  type Stack[A] = List[A]
  implicit class S[A](s: Stack[A]) {
    def push(a: A): Stack[A] = a +: s
    def pop: Option[(A, Stack[A])] = s match {
      case Nil => None
      case x :: xs => (x, xs).some
    }

  }

  def unsafeRun[A](io: IO[A]): A = {
    import IO._
    import scala.util.control.NonFatal

    def loop(current: IO[Any], stack: Stack[Any => IO[Any]]): A = {
      current match {
        case FlatMap(io, k) =>
          loop(io, stack.push(k))
        case Pure(v) =>
          stack.pop match {
            case None => v.asInstanceOf[A]
            case Some((bind, stack)) => loop(bind(v), stack)
          }
        case HandleErrorWith(io, k) =>
          loop(io, stack.push(k.asInstanceOf[Any => IO[Any]]))
        case RaiseError(e) =>
          def findHandler(stack: Stack[Any => IO[Any]])
            : Option[(Any => IO[Any], Stack[Any => IO[Any]])] =
            stack.pop match {
              case None => None
              case v @ Some((handler, _))
                  if (handler.isInstanceOf[Throwable => IO[Any]]) =>
                v
              case Some((_, stack)) => findHandler(stack)
            }

          findHandler(stack) match {
            case None => throw e
            case Some((handler, stack)) => loop(handler(e), stack)
          }
        case Delay(body) =>
          try {
            val res = body()
            loop(Pure(res), stack)
          } catch {
            case NonFatal(e) => loop(RaiseError(e), stack)
          }

      }
    }

    loop(io, Nil)
  }

}
