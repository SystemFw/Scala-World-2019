---
title: How do Fibers work
author: Fabio Labella (SystemFw)
theme: solarized
highlightTheme: solarized-light
revealOptions:
  transition: slide
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

`+` Pure sync side effects  
`+` Sequential composition  
`-` Error Handling  
`-` Asynchrony  
`-` Concurrency  
`-` Stack safety  
`-` Resource safety  

---

<!-- 33 for TL talk (but most of them code) -->
<!-- this: 36 so far (but most of them images so far) -->

<!-- potential plan -->
<!-- UIO -->
<!-- async -->
<!-- section with:  -->
<!--  callstack as logical steps, -->
<!--  async to wrap async tasks -->
<!--  ThreadPool as scheduler -->
<!--  start (fork), sleep -->
<!--  cats-effect 2.0 guide? -->
<!--  where to go: interruption, resource safety, stack safety -->
 
<!-- TODO: -->
<!-- Disclaimer about adherence to api -->
<!-- simple concurrency example -->


<!-- Concurrency -->
<!-- Programming as the composition of independently executing processes -->
<!-- Parallelism -->
<!-- Programming as the simultaneous execution of (possibly related) computations. -->
<!-- Concurrency is about dealing with lots of things at once. -->
<!-- Parallelism is about doing lots of things at once. -->

<!-- -------- -->

<!-- IO -->
<!-- FFI - delay, async, cancelable -->
<!-- combinators: flatMap, handleErroWith, sleep, start -->
<!-- runner: unsafeRunAsync, unsafeRunSync -->
<!-- focus: async, start, sleep, unsafeRunAsync -->

<!-- --------- -->

<!-- the type of async -->
<!-- Embedding async computations -->
<!-- CPS example (addition) -->
<!-- where to insert the thing about runtime loop? on top of this section? -->
<!-- simple concurrency example -->
<!-- `start` vs `fork` -->
<!-- mention interruption? -->
<!-- `sleep`? -->
<!-- what example should we use? -->
<!-- where to put the api for Ec and scheduledEc? -->
<!-- section about Fiber, ContextShift and Timer? -->
<!--  section about real blocking -->

<!-- --- -->
