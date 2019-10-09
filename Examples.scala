import cats._, implicits._
import cats.effect._, concurrent._
import cats.effect.implicits._
import fs2._, io.tcp._
import scala.concurrent.duration._
import scala.util.control.NonFatal

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

object ex0 {
  type IO[A] = IO.Token => A
  object IO {
    def apply[A](a: => A): IO[A] = _ => a

    class Token private[IO] ()
    def unsafeRun[A](fa: IO[A]): A = fa(new Token)
  }

  def read = IO(scala.io.StdIn.readLine)
  def put[A](v: A) = IO(println(v))

  def p =
    for {
      _ <- put("insert your name")
      n <- read
      _ <- put(s"Hello $n")
    } yield ()
}

object stack {
  type Stack[A] = List[A]
  implicit class S[A](s: Stack[A]) {
    def push(a: A): Stack[A] = a +: s
    def pop: Option[(A, Stack[A])] = s match {
      case Nil => None
      case x :: xs => (x, xs).some
    }
  }
}

import stack._

// UIO
object ex1 {
  def read = IO(scala.io.StdIn.readLine)
  def put[A](v: A) = IO(println(v))

  def p =
    for {
      _ <- put("insert your name")
      n <- read
      _ <- put(s"Hello $n")
    } yield ()

  sealed trait IO[+A] {
    def r = IO.unsafeRun(this)
  }
  object IO {
    def apply[A](v: => A): IO[A] = Delay(() => v)

    case class FlatMap[B, +A](io: IO[B], k: B => IO[A]) extends IO[A]
    case class Pure[+A](v: A) extends IO[A]
    case class Delay[+A](eff: () => A) extends IO[A]

    implicit def instances: Monad[IO] =
      new Monad[IO] {
        def pure[A](x: A): IO[A] = Pure(x)
        def flatMap[A, B](fa: IO[A])(f: A => IO[B]): IO[B] = FlatMap(fa, f)
        // ignoring stack safety for now
        def tailRecM[A, B](a: A)(f: A => IO[Either[A, B]]): IO[B] = ???
      }

    def unsafeRun[A](io: IO[A]): A = {
      def loop(current: IO[Any], stack: Stack[Any => IO[Any]]): A =
        current match {
          case FlatMap(io, k) =>
            loop(io, stack.push(k))
          case Delay(body) =>
            val res = body()
            loop(Pure(res), stack)
          case Pure(v) =>
            stack.pop match {
              case None => v.asInstanceOf[A]
              case Some((bind, stack)) => loop(bind(v), stack)
            }
        }
      loop(io, Nil)
    }
  }
}

// SyncIO
object ex2 {
  def read = IO(scala.io.StdIn.readLine)
  def put[A](v: A) = IO(println(v))
  def prompt = put("What's your name?") >> read
  def hello = prompt.flatMap(n => put(s"hello $n"))

  // FlatMap(
  //   FlatMap(
  //     Delay(() => print("name?")),
  //     _ => Delay(() => readLine)
  //   ),
  //   n => Delay(() => println(n))
  // )

  def p =
    for {
      _ <- put("insert your name")
      n <- read
      _ <- put(s"Hello $n")
    } yield ()
  def p1 = IO[Unit](throw new Exception).handleErrorWith(e => put(e))
  def p2 = IO[Unit](throw new Exception).attempt
  def p3 =
    IO[Unit](throw new Exception)
      .map(_.asRight[Throwable])
      .handleError(_.asLeft[Unit])
  def p4 = IO[Throwable](throw new Exception).flatMap(e => put(e))

  sealed trait IO[+A] {
    def r = IO.unsafeRun(this)
  }
  object IO {
    def apply[A](v: => A): IO[A] = Delay(() => v)

    case class FlatMap[B, +A](io: IO[B], k: B => IO[A]) extends IO[A]
    case class Pure[+A](v: A) extends IO[A]
    case class RaiseError(e: Throwable) extends IO[Nothing]
    case class HandleErrorWith[+A](io: IO[A], k: Throwable => IO[A])
        extends IO[A]
    case class Delay[+A](eff: () => A) extends IO[A]

    implicit def instances: MonadError[IO, Throwable] =
      new MonadError[IO, Throwable] {
        def pure[A](x: A): IO[A] = Pure(x)
        def handleErrorWith[A](fa: IO[A])(f: Throwable => IO[A]): IO[A] =
          HandleErrorWith(fa, f)
        def raiseError[A](e: Throwable): IO[A] = RaiseError(e)
        def flatMap[A, B](fa: IO[A])(f: A => IO[B]): IO[B] =
          FlatMap(fa, f)

        // ignoring stack safety for now
        def tailRecM[A, B](a: A)(f: A => IO[Either[A, B]]): IO[B] = ???
      }

    def unsafeRun[A](io: IO[A]): A = {
      sealed trait Bind {
        def isHandler: Boolean = this.isInstanceOf[Bind.H]
      }
      object Bind {
        case class K(f: Any => IO[Any]) extends Bind
        case class H(f: Throwable => IO[Any]) extends Bind
      }

      def loop(current: IO[Any], stack: Stack[Bind]): A =
        current match {
          case FlatMap(io, k) =>
            loop(io, stack.push(Bind.K(k)))
          case Delay(body) =>
            try {
              val res = body() // launch missiles
              loop(Pure(res), stack)
            } catch {
              case NonFatal(e) => loop(RaiseError(e), stack)
            }
          case Pure(v) =>
            stack.dropWhile(_.isHandler) match {
              case Nil => v.asInstanceOf[A]
              case Bind.K(f) :: stack => loop(f(v), stack)
            }
          case HandleErrorWith(io, h) =>
            loop(io, stack.push(Bind.H(h)))
          case RaiseError(e) =>
            // dropping binds on errors until we find an error handler
            // realises the short circuiting semantics of MonadError
            stack.dropWhile(!_.isHandler) match {
              case Nil => throw e
              case Bind.H(handle) :: stack => loop(handle(e), stack)
            }
        }

      loop(io, Nil)
    }
  }
}

// CPS
object ex3 {

  // def add(a: Int, b: Int): Int = ???
  // def double(a: Int): Int = ???

  def add(a: Int, b: Int)(rest: Int => Unit): Unit = {
    val res = a + b
    rest(res)
  }
  def double(a: Int)(rest: Int => Unit): Unit = {
    val res = a * 2
    rest(res)
  }
  def main =
    add(1, 2)(sum => double(sum)(doubled => println(doubled)))

  def unsafeRunAsync[A](io: IO[A], cb: Either[Throwable, A] => Unit): Unit = {
    def loop(
        current: IO[Any],
        stack: Stack[Any => IO[Any]],
        cb: Either[Throwable, A] => Unit): Unit = ???

    loop(io, Nil, cb)
  }
}

// basic async (no stack safety, no single invocation check)
object ex4 {
  sealed trait IO[+A] {
    def r = IO.unsafeRun[A](this, _ => ())
  }
  object IO {
    def apply[A](v: => A): IO[A] = Delay(() => v)
    def async[A](k: (Either[Throwable, A] => Unit) => Unit): IO[A] = Async(k)

    case class FlatMap[B, +A](io: IO[B], k: B => IO[A]) extends IO[A]
    case class Pure[+A](v: A) extends IO[A]
    case class RaiseError(e: Throwable) extends IO[Nothing]
    case class HandleErrorWith[+A](io: IO[A], k: Throwable => IO[A])
        extends IO[A]
    case class Delay[+A](eff: () => A) extends IO[A]
    case class Async[+A](k: (Either[Throwable, A] => Unit) => Unit)
        extends IO[A]

    implicit def instances: MonadError[IO, Throwable] =
      new MonadError[IO, Throwable] {
        def pure[A](x: A): IO[A] = Pure(x)
        def handleErrorWith[A](fa: IO[A])(f: Throwable => IO[A]): IO[A] =
          HandleErrorWith(fa, f)
        def raiseError[A](e: Throwable): IO[A] = RaiseError(e)
        def flatMap[A, B](fa: IO[A])(f: A => IO[B]): IO[B] =
          FlatMap(fa, f)

        // ignoring stack safety for now
        def tailRecM[A, B](a: A)(f: A => IO[Either[A, B]]): IO[B] = ???
      }

    def unsafeRun[A](io: IO[A], cb: Either[Throwable, A] => Unit): Unit = {
      sealed trait Bind {
        def isHandler: Boolean = this.isInstanceOf[Bind.H]
      }
      object Bind {
        case class K(f: Any => IO[Any]) extends Bind
        case class H(f: Throwable => IO[Any]) extends Bind
      }

      def loop(
          current: IO[Any],
          stack: Stack[Bind],
          cb: Either[Throwable, A] => Unit): Unit =
        current match {
          case FlatMap(io, k) =>
            loop(io, stack.push(Bind.K(k)), cb)
          case Delay(body) =>
            try {
              val res = body() // launch missiles
              loop(Pure(res), stack, cb)
            } catch {
              case NonFatal(e) => loop(RaiseError(e), stack, cb)
            }
          case Pure(v) =>
            stack.dropWhile(_.isHandler) match {
              case Nil => cb(Right(v.asInstanceOf[A]))
              case Bind.K(f) :: stack => loop(f(v), stack, cb)
            }
          case HandleErrorWith(io, h) =>
            loop(io, stack.push(Bind.H(h)), cb)
          case RaiseError(e) =>
            // dropping binds on errors until we find an error handler
            // realises the short circuiting semantics of MonadError
            stack.dropWhile(!_.isHandler) match {
              case Nil => cb(Left(e))
              case Bind.H(handle) :: stack => loop(handle(e), stack, cb)
            }
          case Async(asyncProcess) =>
            val restOfComputation = { res: Either[Throwable, Any] =>
              val nextIO = res.fold(RaiseError(_), Pure(_))
              loop(nextIO, stack, cb)
            }

            asyncProcess(restOfComputation)
        }

      loop(io, Nil, cb)
    }
  }
}

object ex5 {
  val ec = scala.concurrent.ExecutionContext.global
  val sec = new java.util.concurrent.ScheduledThreadPoolExecutor(1)

  val shift: IO[Unit] = IO.async { cb =>
    val rightUnit = Right(())
    ec.execute { () =>
      cb(rightUnit)
    }
  }

  def sleep(f: FiniteDuration): IO[Unit] = IO.async { cb =>
    val rightUnit = Right(())
    sec.schedule(new Runnable {
      override def run(): Unit = cb(rightUnit)
    }, f._1, f._2)
  }
  
}
