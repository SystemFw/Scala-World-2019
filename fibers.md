---
title: How do Fibers work
author: Fabio Labella (SystemFw)
theme: solarized
highlightTheme: solarized-light
revealOptions:
  transition: slide
  <!-- slideNumber: true -->
---

# How do Fibers work
A peek under the hood

Note:
So, what I normally do in my talks is present a general idea or
concept and develop progressive code examples around it, treating its
implementation as a black box.
I love abstraction and what it empowers us to do, but at the same time
for many engineers "how does this work?" remains one of most rewarding
questions to ask, so hopefully you will enjoy this journey into the
implementation of the lightweight threading system that powers
cats-effect and fs2.

---

<!-- .slide: data-background="img/logo.png" -->


Note:
My name is Fabio, I'm a principal software engineer at Ovo Energy, we do lots of Scala and lots of FP, so if you fancy moving to London come and talk to me after.


----

## About me

![](img/github.png)

Note:
I'm also an Open source developer as SystemFw, mainly as a core maintainer of fs2 and cats-effect

---

## The big picture

```scala
def server[F[_]: Concurrent: Timer: ContextShift]:Stream[F, Unit] =
  socketGroup.server(address).map { connection =>
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
    }.parJoinUnbounded
     .interruptAfter(10.minutes)
```

Note:

Minimal TCP server represented as a stream of connections, emits one
element for each new connection. We then obtain the socket, and
represent the input process as another stream: we emit elements from 0
to 10, 1 per second (metered), map them to string messages, encode
them and send them through the socket. All these connections are
served concurrently (parJoinUnbounded), and after 10 minutes we
interrupt all processes (interruptAfter), making sure all the
connections shutdown gracefully (endOfOutput) before moving on. This
is compositional, and we can do something else after interrupting this
server.
In addition to offering a high level, declarative model for
concurrency including interruption and resource safety, there is
another interesting aspect to this code: it runs on a fixed number of
threads, even though we serve way more connections than available
threads, and do a lot of sleeping.

----

## Layers

- <!-- .element: class="fragment" --> **fs2**: Declarative, high
   level, safe concurrency. Many combinators and a concept of
   _lifetime_ through `Stream`.
- <!-- .element: class="fragment" --> **cats-effect typeclasses**:
   Specification for low level, foundational primitives.
- <!-- .element: class="fragment" --> **cats-effect `IO`**: Concrete
   implementation of the runtime system.

----

As a user, I highly recommend a higher level model like **fs2**'s.

<!-- .element: class="fragment" !--> But today we are _implementors_,
and we will look at the low level runtime that powers `IO`.


---

## Chapter 1: Asynchrony & Concurrency

A conceptual model

Note:
Stress how several terms like process or thread are going to be used
loosely in this section

----

## Asynchronous process

Traditionally defined as:

> A process that executes outside of the program main flow

----

## Asynchronous process

For our purposes:

> A process that continues its execution in a different place or time
  than the one it started in

Note:
Stress how "place and time" is intentionally vague

----

## Asynchronous process

![](img/async.png)

Note:

An async boundary is extremely general: it could be trasfering the
computation to another node in the network, or sending it to another OS
thread, or putting it at the end of a queue where it gets picked up
later.

----

## Concurrency

Naively:

> Multiple processes executing at the same time...

<!-- .element: class="fragment" !--> ... way too wide for our purposes!

<!-- .element: class="fragment" !--> (and overlaps with parallelism)

----

## Concurrency

> A **program structuring** technique in which there are multiple
  logical threads of control, whose effects are interleaved


----

## Logical thread

![](img/thread.png)

A logical thread is a _sequence_ of _discrete steps_

Note:
    
This might seem obvious, but it has profound consequences.
- Discrete steps allow interleaving
- We can imagine new types of logical threads by imagining
new types of discrete steps
TODO put this point in slide about IO somewhere.

----

### Interleaving

![](img/interleaving2.png)


Note:

- The notion of discrete step naturally gives rise to the notion of
  interleaving.

----

### M:N threading

![](img/M-N.png)

Note:
How is the bottom layer run: segway into next slide


----

### M:N threading

![](img/M-N-tower.png)


----

## Threads are abstractions


> <!-- .element: class="fragment" --> 
  A logical thread offers a **synchronous** interface to an **asynchronous** process

Note:
This gives us modularity

----

![](img/async.png)

----

![](img/async-threads.png)

----

![](img/async-threads2.png)

----

## Blocking

> <!-- .element: class="fragment" --> 
  Blocking at one level means **suspending** at the level below.


----

![](img/blocking1.png)

----

## Recap

- <!-- .element: class="fragment" --> **Async processes** resume somewhere
  else after an async boundary.
- <!-- .element: class="fragment" --> **Logical threads** abstract async
  processes as synchronous sequences of discrete steps.
- <!-- .element: class="fragment" --> Multiple logical threads can be
  **interleaved** to achieve concurrency.
- <!-- .element: class="fragment" --> Blocking means **suspending**
 one layer down. Logical threads at that layer keep running.

---

## Chapter 2: Real world concurrency

----

## Concurrency & parallelism

- <!-- .element: class="fragment" --> **Concurrency**: discrete steps get interleaved.
- <!-- .element: class="fragment" --> **Parallelism**: discrete steps run simultaneously.
- <!-- .element: class="fragment" --> **Independent** of each other.
 
<!-- .element: class="fragment" --> In this talk:   
`parallelism == implementation detail`

Note:
independent of each other (concurrency with no parallelism,
deterministic parallelism with no concurrency).
We will treat parallelism as an implementation detail.

----

## Layers

- <!-- .element: class="fragment" --> **OS Processes**: `M:N` with processors.
  Own execution state, own memory space. 
- <!-- .element: class="fragment" --> **OS/JVM Threads**:  `M:N` with processes.
  Own execution state, shared memory space. 
- <!-- .element: class="fragment" --> **Fibers**: `M:N` with threads.
 Shared execution state, shared memory space.

----

## Cost of blocking

> <!-- .element: class="fragment" -->
  JVM threads are a _scarce resource_.

----

## Semantic blocking

- <!-- .element: class="fragment" --> `Fibers` aren't scarce.
- <!-- .element: class="fragment" --> Blocking a `Fiber` doesn't block the underlying `Thread`.
- <!-- .element: class="fragment" --> Semantic blocking is pervasive.

----

## Scheduling

- <!-- .element: class="fragment" --> Preemptive: scheduler suspends tasks.
- <!-- .element: class="fragment" --> Cooperative: tasks suspend themselves.

----

![](img/scheduler.png)

- <!-- .element: class="fragment" --> Better suited for preemption
- <!-- .element: class="fragment" --> Harder to do `M:N` this way

----

![](img/scheduler2.png)

- <!-- .element: class="fragment" --> `M:N` cooperative scheduling: `Fibers`!

----

## Building blocks

```scala
trait ExecutionContext {
 def execute(runnable: Runnable): Unit
}
```
```scala
// From Java, slightly adapted
trait Runnable {
  def run(): Unit
}
trait ScheduledExecutorService {
  def schedule(runnable: Runnable, delay: FiniteDuration): Unit
}
```

---

## Chapter 3: the IO api

**`IO[A]`** <!-- .element: class="fragment" -->

- <!-- .element: class="fragment" --> Produces one value, fails or never terminates.
- <!-- .element: class="fragment" --> *Referentially transparent* (pure).
- <!-- .element: class="fragment" --> Many algebras (`Monad`, `Concurrent`...).


----

## Api

- <!-- .element: class="fragment" --> **FFI** : wrap side-effects into `IO`
- <!-- .element: class="fragment" --> **Combinators**: build complex `IO`s by composing smaller ones
- <!-- .element: class="fragment" --> **Runners**: translate `IO` to side-effects at the end of the world

----

## FFI

```scala
def delay[A](a: => A): IO[A]
def async[A](k: (Either[Throwable, A] => Unit) => Unit): IO[A]
def asyncF[A](k: (Either[Throwable, A] => Unit) => IO[Unit]): IO[A]
...
```

----

## Combinators

```scala
def pure[A](a: A): IO[A]
def map[A, B](fa: IO[A])(f: A => B): IO[B] 
def flatMap[A, B](fa: IO[A])(f: A => IO[B]): IO[B] 

def handleErrorWith[A](fa: IO[A])(f: Throwable => IO[A]): IO[A] 
def raiseError[A](e: Throwable): IO[A]

def start[A](fa: IO[A]): IO[Fiber[IO, A]]
def race[A, B](fa: IO[A], fb: IO[B]): IO[Either[A, B]]
def sleep(duration: FiniteDuration): IO[Unit] 
def timeout(fa: IO[A])(duration: FiniteDuration): IO[A]

def guarantee[A](fa: IO[A])(finalizer: IO[Unit]): IO[A] 
def bracket[A, B](a: IO[A])(u: A => IO[B])(r: A => IO[Unit]): IO[B]
...
```

----

## Runners

```scala
def run(args: List[String]): IO[ExitCode] // IOApp main
def unsafeRunAsync[A]
     (fa: IO[A])
     (cb: Either[Throwable, A] => Unit): Unit
def unsafeRunSync[A](fa: IO[A]): A // JVM only
```

----

## Simplest IO

- Pure
- Synchronous FFI
- Sequential composition
- ~~Error Handling~~
- ~~Asynchrony~~
- ~~Concurrency~~
- ~~Stack safety~~
- ~~Resource safety~~

----

## Api

```scala
// FFI
def delay[A](a: => A): IO[A]
// combinators
def pure[A](a: A): IO[A]
def flatMap[A, B](fa: IO[A])(f: A => IO[B]): IO[B]
// runners
def unsafeRunSync[A](fa: IO[A]): A
```
- <!-- .element: class="fragment" --> **Isomorphic to `() => A`**
- <!-- .element: class="fragment" --> Data type + interpreter
- <!-- .element: class="fragment" --> Runloop + callstack

----

## Data type

```scala
sealed trait IO[+A]
case class FlatMap[B, +A](io: IO[B], k: B => IO[A]) extends IO[A]
case class Pure[+A](v: A) extends IO[A]
case class Delay[+A](eff: () => A) extends IO[A]
```
```scala
def read = IO(scala.io.StdIn.readLine)
def put[A](v: A) = IO(println(v))
def prompt = put("What's your name?") >> read
def hello = prompt.flatMap(n => put(s"hello $n"))
```
<!-- .element: class="fragment" -->
```scala
FlatMap(
  FlatMap(
    Delay(() => print("name?")),
    _ => Delay(() => readLine)
  ),
  n => Delay(() => println(n))
)
```
<!-- .element: class="fragment" -->

----

## Runloop

- <!-- .element: class="fragment" --> Executes instructions one of at the time
- <!-- .element: class="fragment" --> Keeps track of the current `IO` and the stack of binds.

```scala
source: IO[Any]
callstack: Stack[Any => IO[Any]]
```
<!-- .element: class="fragment" -->

<!-- .element: class="fragment" --> `Any` lets us use a simple data structure over a typed tree.

----

## Runloop

```scala
def flatMap[A, B](fa: IO[A])(f: A => IO[B]): IO[B]
```

```scala
def unsafeRunSync[A](io: IO[A]): A = {
  def loop(current: IO[Any], stack: Stack[Any => IO[Any]]): A =
    current match {
      case FlatMap(io, k) =>
        loop(io, stack.push(k))
      case Delay(body) =>
        val res = body() // launch missiles
        loop(Pure(res), stack)
      case Pure(v) =>
        stack.pop match {
          case None => v.asInstanceOf[A]
          case Some((bind, stack)) => 
            val nextIO = bind(v)
            loop(nextIO, stack)
        }
    }
  loop(io, Stack.empty)
}
```

----

## A further glance 
```scala
case class RaiseError(e: Throwable) extends IO[Nothing]
case class HandleErrorWith[+A](io: IO[A], k: Throwable => IO[A])
    extends IO[A]

def unsafeRunSync[A](io: IO[A]): A = {
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
      case HandleErrorWith(io, h) =>
        loop(io, stack.push(Bind.H(h)))
      case Delay(body) =>
        try {
          val res = body()
          loop(Pure(res), stack)
        } catch {
          case NonFatal(e) => loop(RaiseError(e), stack)
        }
      case Pure(v) =>
        stack.dropWhile(_.isHandler) match {
          case Nil => v.asInstanceOf[A]
          case Bind.K(f) :: stack => loop(f(v), stack)
        }
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
```

---

## Chapter 4: Async

```scala
def async[A](k: (Either[Throwable, A] => Unit) => Unit): IO[A]
```
<!-- .element: class="fragment" -->

- <!-- .element: class="fragment" --> It does **not** introduce asynchrony on its own
- <!-- .element: class="fragment" --> It takes an asynchronous process and exposes it as `IO`
- <!-- .element: class="fragment" --> We'll explain the strange-looking type :)

----

## Continuation passing style

Instead of returning a result, we _call_ the rest of the computation
  with it.

----

## CPS

```scala
def add(a: Int, b: Int): Int = a + b
def double(a: Int): Int = a * 2

```

```scala
def add(a: Int, b: Int)(rest: Int => Unit): Unit = {
  val res = a + b
  rest(res)
}
def double(a: Int)(rest: Int => Unit): Unit = {
  val res = a * 2
  rest(res)
}
def main = add(1, 2){ sum => 
  double(sum) { doubled => 
    println(doubled)
  }
 }
```
<!-- .element: class="fragment" -->

----

## CPS & asynchrony

- <!-- .element: class="fragment" --> `foo: A` _has_ to return an `A` here and now
- <!-- .element: class="fragment" --> But async processes continue somewhere else
- <!-- .element: class="fragment" --> Intrinsically CPS'd: `(A => Unit) => Unit`

```scala
(Either[Throwable, A] => Unit) => Unit // accounts for errors
```
<!-- .element: class="fragment" --> 
```scala
def async[A](k: (Either[Throwable, A] => Unit) => Unit): IO[A]
```
<!-- .element: class="fragment" -->
----

## Async runloop

```scala
def unsafeRunAsync[A](
    io: IO[A],
    cb: Either[Throwable, A] => Unit): Unit = {
  def loop(
      current: IO[Any],
      stack: Stack[Any => IO[Any]],
      cb: Either[Throwable, A] => Unit): Unit = ???

  loop(io, Nil, cb)
}
```
```
throw e // unhandled error in RaiseError
v.asInstanceOf[A] // final result in Pure

// become

cb(Left(e))
cb(Right(v.asInstanceOf[A]))
```
<!-- .element: class="fragment" -->

The final, user provided callback is often just a reporter
<!-- .element: class="fragment" -->

----

### Async runloop

```scala
case class Async[+A](
   k: (Either[Throwable, A] => Unit) => Unit) extends IO[A]
```

```scala
def loop(
    current: IO[Any],
    stack: Stack[Any => IO[Any]],
    cb: Either[Throwable, A] => Unit): Unit = current match {
  ...

  case Async(asyncProcess) =>
    val restOfComputation = { res: Either[Throwable, Any] =>
      val nextIO = res.fold(RaiseError(_), Pure(_))
      loop(nextIO, stack, cb)
     }
    // send the rest somewhere else
    asyncProcess(restOfComputation)

  ...
```

---

## Chapter 5: Assembling fibers

----

![](img/fibers.png)

----

## Runtime system

The runloop needs access to
```scala
ExecutionContext // forking & yielding
ScheduledExecutorService // sleeping
```

```
trait IOApp {
  def ctx: ContextShift[IO] // wraps EC
  def timer: Timer[IO] // wrap SEC

  def run(arg: List[String]) = runloop
}
```
<!-- .element: class="fragment" -->

----

## Yielding

```scala
def shift: IO[Unit] = IO.async { cb =>
  val rightUnit = Right(())
  ec.execute { () =>
    cb(rightUnit)
  }
}
```
<!-- .element: class="fragment" -->

----

## start
```scala
def start[A](io: IO[A]): IO[Fiber[IO, A]]

trait Fiber[F[_], A] {
  def join: F[A]
  def cancel: F[Unit]
}
```
<!-- .element: class="fragment" -->

- <!-- .element: class="fragment" --> `Fiber` is just a handle over a runloop
- <!-- .element: class="fragment" --> `join`: semantically blocks for completion (via `Deferred` and ultimately `Ref` + `async`)
- <!-- .element: class="fragment" -->`cancel`: interruption (runloop stops running on a signal, out of scope)

----

## spawn

- <!-- .element: class="fragment" --> spawns a runloop, without a handle
- <!-- .element: class="fragment" --> Equivalent of `start.void`
- <!-- .element: class="fragment" --> Leaky, but see initial point about fs2 in general

```scala
def spawn[A](io: IO[A]): IO[Unit] = IO.async { cb =>
  (IO.shift >> io).unsafeRunAsync(_ => ())
  cb(Right(()))
}
```
<!-- .element: class="fragment" -->

----

## sleep

- <!-- .element: class="fragment" --> Semantically blocks for a given duration

```scala
def sleep(duration: FiniteDuration): IO[Unit] = IO.async { cb =>
  sec.schedule(
    () => ec.submit(cb(Right(()))),
    duration.length,
    duration.unit
  )
}
```
<!-- .element: class="fragment" -->

----

## Putting it all together

```scala
def put[A](s: String) = IO(println(s))

def periodic(n: Int, t: FiniteDuration, action: IO[Unit]) = {
  def loop(i: Int) =
    if (i == n) ().pure[IO]
    else action >> IO.sleep(t) >> loop(i + 1)
  loop(0)
}

for {
 _ <- periodic(7, 300.millis, put("Fiber 1")).start
 _ <- periodic(10, 200.millis, put("Fiber 2")).start
} yield ()

```

----

## Better
```scala
Stream(
  Stream
   .repeatEval(put("Fiber 1"))
   .metered(300.millis)
   .take(7),
  Stream
   .repeatEval(put("Fiber 2"))
   .metered(200.millis)
   .take(10)
).parJoinUnbounded
```

- <!-- .element: class="fragment" --> Safer, more composable, higher level
- <!-- .element: class="fragment" --> Ultimately based on the same building blocks

---

 ## We haven't talked about

 - Interruption!
 - Resource safety
 - Stack safety
 - Dealing with Thread blocking
 - Fs2's higher level api

---

## Full circle

```scala
def server[F[_]: Concurrent: Timer: ContextShift]:Stream[F, Unit] =
  socketGroup.server(address).map { connection =>
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
    }.parJoinUnbounded
     .interruptAfter(10.minutes)
```

---

## Conclusion: Abstraction is great!

---

## Questions?

- I'm not on Twitter, reach out on Gitter @SystemFw!
- Blog at [https://systemfw.org](https://systemfw.org)
- [Example code](https://github.com/SystemFw/Scala-World-2019/blob/master/Examples.scala)

<!-- 33 for TL talk (but most of them code) -->
<!-- this: 36 so far (but most of them images so far) -->
