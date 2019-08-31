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

object ex1 {
  sealed trait IO[+A] {
    def r = unsafeRun(this)
  }
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
  def p2 = IO[Unit](throw new Exception).attempt
  def p3 =
    IO[Unit](throw new Exception)
      .map(_.asRight[Throwable])
      .handleError(_.asLeft[Unit])
  def p4 = IO[Throwable](throw new Exception).flatMap(e => put(e))


  def unsafeRun[A](io: IO[A]): A = {
    import IO._
    import scala.util.control.NonFatal

    type Stack[A] = List[A]
    implicit class S[A](s: Stack[A]) {
      def push(a: A): Stack[A] = a +: s
      def pop: Option[(A, Stack[A])] = s match {
        case Nil => None
        case x :: xs => (x, xs).some
      }
    }

    def findFirstErrorHandler(stack: Stack[Any => IO[Any]]) = {
      var stack_ = stack
      var handler: Option[Throwable => IO[Any]] = Option.empty

      while (stack_ != Nil || handler == None) {
        val r = stack_.pop
        if (r.isDefined) {
          val (h, s) = r.get
          stack_ = s
          if (h.isInstanceOf[Throwable => IO[Any]])
            handler = h.some
        }
      }

      handler.tupleRight(stack_)
    }

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
          findFirstErrorHandler(stack) match {
            case Some((handle, newStack)) => loop(handle(e), newStack)
            case None => throw e
          }

        // stack.dropWhile(!_.isInstanceOf[Throwable => IO[Any]]) match {
        //   case Nil => throw e
        //   case handle :: newStack => loop(handle(e), newStack)
        // }
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
