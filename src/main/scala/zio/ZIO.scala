package zio

import zio.ZIO.succeedNow

import scala.concurrent.ExecutionContext


sealed trait ZIO[+E, +A] { self =>

  def fork: ZIO[Nothing, Fiber[E, A]] = ZIO.Fork(self)

  def as[B](value: => B): ZIO[E, B] =
    map(_ => value)

  def flatMap[E1 >: E, B](f: A => ZIO[E1, B]): ZIO[E1, B] =
    ZIO.FlatMap(self, f)

  def map[E1 >: E, B](f: A => B): ZIO[E1, B] =
    flatMap(a => ZIO.succeedNow(f(a)))

  def shift(executor: ExecutionContext): ZIO[Nothing, Unit] =
    ZIO.Shift(executor)

  def repeat(n: Int): ZIO[E, Unit] = {
    if (n == 0) ZIO.succeed()
    else self *> repeat(n - 1)
  }

  def zip[E1 >: E, B](that: ZIO[E1, B]): ZIO[E1, (A, B)] =
    zipWith(that)(_ -> _)

  def *>[E1 >: E, B](that: => ZIO[E1, B]): ZIO[E1, B] =
    zipRight(that)

  def zipRight[E1 >: E, B](that: => ZIO[E1, B]): ZIO[E1, B] =
    zipWith(that)((_, b) => b)

  def zipWith[E1 >: E, B, C](that: => ZIO[E1, B])(f: (A, B) => C): ZIO[E1, C] =
    for {
      a <- self
      b <- that
    } yield f(a, b)

  def zipPar[E1 >: E, B](that: ZIO[E1, B]): ZIO[E1, (A, B)] =
    for {
      fiber <- self.fork
      b <- that
      a <- fiber.join
    } yield (a, b)

  def catchAll[E1, A1 >: A](failure: E => ZIO[E1, A1]): ZIO[E1, A1] = {
    foldZIO(e => failure(e), a => succeedNow(a))
  }

  def fold[B](failure: E => B, success: A => B): ZIO[E, B] =
    foldZIO(e => succeedNow(failure(e)), a => succeedNow(success(a)))

  def foldZIO[E1, B](failure: E => ZIO[E1, B], success: A => ZIO[E1, B]): ZIO[E1, B] =
    ZIO.Fold(self, failure, success)

  def unsafeRunSync: Either[E, A] = {
    var result: Either[E, A] = null.asInstanceOf[Either[E, A]]
    val latch = new java.util.concurrent.CountDownLatch(1)
    val zio = self.foldZIO(
      e => ZIO.succeed {
        result = Left(e)
        latch.countDown()
      },
      a => ZIO.succeed {
        result = Right(a)
        latch.countDown()
      }
    )

    zio.unsafeRunFiber
    latch.await()
    result
  }

  private def unsafeRunFiber: Fiber[E, A] =
    FiberContext(self, ZIO.defaultExecutor)
}



object ZIO {

  def succeedNow[A](value: A): ZIO[Nothing, A] = SucceedNow(value)

  def succeed[A](value: => A): ZIO[Nothing, A] = Succeed(() => value)

  def async[A](f: (A => Any) => Any): ZIO[Nothing, A] = ZIO.Async(f: (A => Any) => Any)

  def fail[E](e: => E): ZIO[E, Nothing] = ZIO.Fail(() => e)

  case class SucceedNow[A](value: A) extends ZIO[Nothing, A]

  case class Succeed[A](f: () => A) extends ZIO[Nothing, A]

  case class FlatMap[E, E1 >: E, A, B](zio: ZIO[E, A], f: A => ZIO[E1, B]) extends ZIO[E1, B]

  case class Async[A](register: (A => Any) => Any) extends ZIO[Nothing, A]

  case class Fork[E, A](zio: ZIO[E, A]) extends ZIO[Nothing, Fiber[E, A]]

  case class Shift(executor: ExecutionContext) extends ZIO[Nothing, Unit]

  case class Fail[E](e: () => E) extends ZIO[E, Nothing]

  case class Fold[E, E1, A, B](zio: ZIO[E, A], failure: E => ZIO[E1, B], success: A => ZIO[E1, B])
      extends ZIO[E1, B] with (A => ZIO[E1, B]) {
    
    override def apply(a: A): ZIO[E1, B] = success(a)
  }

  private val defaultExecutor = ExecutionContext.global
}