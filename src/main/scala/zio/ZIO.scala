package zio

import scala.concurrent.ExecutionContext


sealed trait ZIO[+A] { self =>

  def fork: ZIO[Fiber[A]] = ZIO.Fork(self)

  def as[B](value: => B): ZIO[B] =
    map(_ => value)

  def flatMap[B](f: A => ZIO[B]): ZIO[B] =
    ZIO.FlatMap(self, f)

  def map[B](f: A => B): ZIO[B] =
    flatMap(a => ZIO.succeedNow(f(a)))

  def shift(executor: ExecutionContext): ZIO[Unit] =
    ZIO.Shift(executor)

  def repeat(n: Int): ZIO[Unit] = {
    if (n == 0) ZIO.succeed()
    else self *> repeat(n - 1)
  }

  def zip[B](that: ZIO[B]): ZIO[(A, B)] =
    zipWith(that)(_ -> _)

  def *>[B](that: => ZIO[B]): ZIO[B] =
    zipRight(that)

  def zipRight[B](that: => ZIO[B]): ZIO[B] =
    zipWith(that)((_, b) => b)

  def zipWith[B, C](that: => ZIO[B])(f: (A, B) => C): ZIO[C] =
    for {
      a <- self
      b <- that
    } yield f(a, b)

  def zipPar[B](that: ZIO[B]): ZIO[(A, B)] =
    for {
      fiber <- self.fork
      b <- that
      a <- fiber.join
    } yield (a, b)

  def unsafeRunSync: A = {
    var result: A = null.asInstanceOf[A]
    val latch = new java.util.concurrent.CountDownLatch(1)
    val zio = self.flatMap(a => ZIO.succeed {
      result = a
      latch.countDown()
    })

    zio.unsafeRunFiber
    latch.await()
    result
  }

  private def unsafeRunFiber: Fiber[A] =
    FiberContext(self, ZIO.defaultExecutor)
}



object ZIO {

  def succeedNow[A](value: A): ZIO[A] = Succeed(value)

  def succeed[A](value: => A): ZIO[A] = Effect(() => value)

  def async[A](f: (A => Any) => Any): ZIO[A] = ZIO.Async(f: (A => Any) => Any)

  case class Succeed[A](value: A) extends ZIO[A]

  case class Effect[A](f: () => A) extends ZIO[A]

  case class FlatMap[A, B](zio: ZIO[A], f: A => ZIO[B]) extends ZIO[B]

  case class Async[A](register: (A => Any) => Any) extends ZIO[A]

  case class Fork[A](zio: ZIO[A]) extends ZIO[Fiber[A]]

  case class Shift(executor: ExecutionContext) extends ZIO[Unit]

  private val defaultExecutor = ExecutionContext.global
}