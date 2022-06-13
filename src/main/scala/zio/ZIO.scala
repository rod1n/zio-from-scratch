package zio

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

  def forever: ZIO[E, A] = self *> forever

  def ensuring(finalizer: ZIO[Nothing, Any]): ZIO[E, A] =
    foldCauseZIO(
      cause => finalizer *> ZIO.failCause(cause),
      a     => finalizer *> ZIO.succeed(a))

  def interruptible: ZIO[E, A] =
    ZIO.SetInterruptStatus(self, InterruptionStatus.Interruptible)

  def uninterruptible: ZIO[E, A] =
    ZIO.SetInterruptStatus(self, InterruptionStatus.Uninterruptible)

  def catchAll[E1, A1 >: A](failure: E => ZIO[E1, A1]): ZIO[E1, A1] = {
    foldZIO(e => failure(e), a => ZIO.succeedNow(a))
  }

  def fold[B](failure: E => B, success: A => B): ZIO[E, B] =
    foldZIO(e => ZIO.succeedNow(failure(e)), a => ZIO.succeedNow(success(a)))

  def foldZIO[E1, B](failure: E => ZIO[E1, B], success: A => ZIO[E1, B]): ZIO[E1, B] = {
    foldCauseZIO({
      case Cause.Fail(error)    => failure(error)
      case cause @ Cause.Die(_) => ZIO.failCause(cause)
      case Cause.Interrupt => ZIO.failCause(Cause.Interrupt)
    }, success)
  }

  def foldCauseZIO[E1, B](failure: Cause[E] => ZIO[E1, B], success: A => ZIO[E1, B]): ZIO[E1, B] =
    ZIO.Fold(self, failure, success)

  def unsafeRunSync: Exit[E, A] = {
    var result: Exit[E, A] = null.asInstanceOf[Exit[E, A]]
    val latch = new java.util.concurrent.CountDownLatch(1)
    val zio = self.foldCauseZIO(
      cause => ZIO.succeed {
        result = Exit.Failure(cause)
        latch.countDown()
      },
      a => ZIO.succeed {
        result = Exit.Success(a)
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

  def fail[E](e: => E): ZIO[E, Nothing] = failCause(Cause.Fail(e))

  def failCause[E](cause: => Cause[E]): ZIO[E, Nothing] = ZIO.Fail(() => cause)

  def done[E, A](exit: Exit[E, A]): ZIO[E, A] =
    exit match {
      case Exit.Success(a) => ZIO.succeedNow(a)
      case Exit.Failure(cause) => ZIO.failCause(cause)
    }

  case class SucceedNow[A](value: A) extends ZIO[Nothing, A]

  case class Succeed[A](f: () => A) extends ZIO[Nothing, A]

  case class FlatMap[E, E1 >: E, A, B](zio: ZIO[E, A], f: A => ZIO[E1, B]) extends ZIO[E1, B]

  case class Async[A](register: (A => Any) => Any) extends ZIO[Nothing, A]

  case class Fork[E, A](zio: ZIO[E, A]) extends ZIO[Nothing, Fiber[E, A]]

  case class Shift(executor: ExecutionContext) extends ZIO[Nothing, Unit]

  case class Fail[E](cause: () => Cause[E]) extends ZIO[E, Nothing]

  case class Fold[E, E1, A, B](zio: ZIO[E, A], failure: Cause[E] => ZIO[E1, B], success: A => ZIO[E1, B])
      extends ZIO[E1, B] with (A => ZIO[E1, B]) {

    override def apply(a: A): ZIO[E1, B] = success(a)

    override def toString(): String = s"Fold($zio, $failure, $success)"
  }

  case class SetInterruptStatus[E, A](zio: ZIO[E, A], interruptStatus: InterruptStatus) extends ZIO[E, A]

  private val defaultExecutor = ExecutionContext.global
}


sealed trait Cause[+E]
object Cause {
  final case class Fail[E](error: E) extends Cause[E]
  final case class Die(throwable: Throwable) extends Cause[Nothing]
  case object Interrupt extends Cause[Nothing]
}

sealed trait InterruptStatus { self =>

  def toBoolean: Boolean = self match {
    case InterruptionStatus.Interruptible => true
    case InterruptionStatus.Uninterruptible => false
  }
}
object InterruptionStatus {
  case object Interruptible extends InterruptStatus
  case object Uninterruptible extends InterruptStatus
}

sealed trait Exit[+E, +A]
object Exit {
  final case class Success[A](a: A) extends Exit[Nothing, A]
  final case class Failure[E](cause: Cause[E]) extends Exit[E, Nothing]

  def success[A](a: A): Success[A] = Success(a)
  def fail[E](e: E): Failure[E] = Failure(Cause.Fail(e))
  def die(throwable: Throwable): Failure[Nothing] = Failure(Cause.Die(throwable))
}