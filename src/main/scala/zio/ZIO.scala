package zio

import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag


sealed trait ZIO[-R, +E, +A] { self =>

  def fork: ZIO[R, Nothing, Fiber[E, A]] = ZIO.Fork(self)

  def provide(r: R): ZIO[Any, E, A] = ZIO.Provide(self, r)

  def as[B](value: => B): ZIO[R, E, B] =
    map(_ => value)

  def flatMap[R1 <: R, E1 >: E, B](f: A => ZIO[R1, E1, B]): ZIO[R1, E1, B] =
    ZIO.FlatMap(self, f)

  def map[E1 >: E, B](f: A => B): ZIO[R, E1, B] =
    flatMap(a => ZIO.succeedNow(f(a)))

  def shift(executor: ExecutionContext): ZIO[Any, Nothing, Unit] =
    ZIO.Shift(executor)

  def repeat(n: Int): ZIO[R, E, Unit] = {
    if (n == 0) ZIO.succeed()
    else self *> repeat(n - 1)
  }

  def zip[R1 <: R, E1 >: E, B](that: ZIO[R1, E1, B]): ZIO[R1, E1, (A, B)] =
    zipWith(that)(_ -> _)

  def *>[R1 <: R, E1 >: E, B](that: => ZIO[R1, E1, B]): ZIO[R1, E1, B] =
    zipRight(that)

  def zipRight[R1 <: R, E1 >: E, B](that: => ZIO[R1, E1, B]): ZIO[R1, E1, B] =
    zipWith(that)((_, b) => b)

  def zipWith[R1 <: R, E1 >: E, B, C](that: => ZIO[R1, E1, B])(f: (A, B) => C): ZIO[R1, E1, C] =
    for {
      a <- self
      b <- that
    } yield f(a, b)

  def zipPar[R1 <: R, E1 >: E, B](that: ZIO[R1, E1, B]): ZIO[R1, E1, (A, B)] =
    for {
      fiber <- self.fork
      b <- that
      a <- fiber.join
    } yield (a, b)

  def forever: ZIO[R, E, A] = self *> forever

  def ensuring[R1 <: R](finalizer: ZIO[R1, Nothing, Any]): ZIO[R1, E, A] =
    foldCauseZIO(
      cause => finalizer *> ZIO.failCause(cause),
      a     => finalizer *> ZIO.succeed(a))

  def interruptible: ZIO[R, E, A] =
    ZIO.SetInterruptStatus(self, InterruptionStatus.Interruptible)

  def uninterruptible: ZIO[R, E, A] =
    ZIO.SetInterruptStatus(self, InterruptionStatus.Uninterruptible)

  def catchAll[R1 <: R, E1, A1 >: A](failure: E => ZIO[R1, E1, A1]): ZIO[R1, E1, A1] = {
    foldZIO(e => failure(e), a => ZIO.succeedNow(a))
  }

  def fold[B](failure: E => B, success: A => B): ZIO[R, E, B] =
    foldZIO(e => ZIO.succeedNow(failure(e)), a => ZIO.succeedNow(success(a)))

  def foldZIO[R1 <: R, E1, B](failure: E => ZIO[R1, E1, B], success: A => ZIO[R1, E1, B]): ZIO[R1, E1, B] = {
    foldCauseZIO({
      case Cause.Fail(error)    => failure(error)
      case cause @ Cause.Die(_) => ZIO.failCause(cause)
      case Cause.Interrupt => ZIO.failCause(Cause.Interrupt)
    }, success)
  }

  def foldCauseZIO[R1 <: R, E1, B](failure: Cause[E] => ZIO[R1, E1, B], success: A => ZIO[R1, E1, B]): ZIO[R1, E1, B] =
    ZIO.Fold(self, failure, success)

  def unsafeRunSync(implicit ev: Any <:< R): Exit[E, A] = {
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

  private def unsafeRunFiber(implicit ev: Any <:< R): Fiber[E, A] =
    FiberContext(self.asInstanceOf[ZIO[Any, E, A]], ZIO.defaultExecutor)
}

object ZIO {

  def succeedNow[A](value: A): ZIO[Any, Nothing, A] = ZIO.SucceedNow(value)

  def succeed[A](value: => A): ZIO[Any, Nothing, A] = ZIO.Succeed(() => value)

  def service[R](implicit classTag: ClassTag[R]): ZIO[Has[R], Nothing, R] =
    ZIO.accessZIO(env => ZIO.succeed(env.get[R]))

  def environment[R]: ZIO[R, Nothing, R] = ZIO.accessZIO(env => ZIO.succeed(env))

  def accessZIO[R, E, A](f: R => ZIO[R, E, A]): ZIO[R, E, A] = ZIO.Access(f)

  def async[A](f: (A => Any) => Any): ZIO[Any, Nothing, A] = ZIO.Async(f: (A => Any) => Any)

  def fail[R, E](e: => E): ZIO[R, E, Nothing] = ZIO.failCause(Cause.Fail(e))

  def failCause[R, E](cause: => Cause[E]): ZIO[R, E, Nothing] = ZIO.Fail(() => cause)

  def done[R, E, A](exit: Exit[E, A]): ZIO[R, E, A] =
    exit match {
      case Exit.Success(a) => ZIO.succeedNow(a)
      case Exit.Failure(cause) => ZIO.failCause(cause)
    }

  case class SucceedNow[A](value: A) extends ZIO[Any, Nothing, A]

  case class Succeed[R, A](f: () => A) extends ZIO[R, Nothing, A]

  case class FlatMap[R, R1 <: R, E, E1 >: E, A, B](zio: ZIO[R, E, A], f: A => ZIO[R1, E1, B]) extends ZIO[R1, E1, B]

  case class Async[A](register: (A => Any) => Any) extends ZIO[Any, Nothing, A]

  case class Fork[R, E, A](zio: ZIO[R, E, A]) extends ZIO[R, Nothing, Fiber[E, A]]

  case class Shift(executor: ExecutionContext) extends ZIO[Any, Nothing, Unit]

  case class Fail[R, E](cause: () => Cause[E]) extends ZIO[R, E, Nothing]

  case class Fold[R, R1 <: R, E, E1, A, B](zio: ZIO[R, E, A], failure: Cause[E] => ZIO[R1, E1, B], success: A => ZIO[R1, E1, B])
      extends ZIO[R1, E1, B] with (A => ZIO[R1, E1, B]) {

    override def apply(a: A): ZIO[R1, E1, B] = success(a)

    override def toString(): String = s"Fold($zio, $failure, $success)"
  }

  case class SetInterruptStatus[R, E, A](zio: ZIO[R, E, A], interruptStatus: InterruptStatus) extends ZIO[R, E, A]

  case class Provide[R, E, A](zio: ZIO[R, E, A], r: R) extends ZIO[Any, E, A]

  case class Access[R, E, A](f: R => ZIO[R, E, A]) extends ZIO[R, E, A]

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