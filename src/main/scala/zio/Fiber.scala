package zio

import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable
import scala.concurrent.ExecutionContext

trait Fiber[+E, +A] {

  def join: ZIO[E, A]
}

private final case class FiberContext[E, A](zio: ZIO[E, A], startExecutor: ExecutionContext) extends Fiber[E, A] {
  type Erased = ZIO[Any, Any]
  type Cont = Any => Erased
  type ErrorHandler = ZIO.Fold[Any, Any, Any, Any]

  sealed trait FiberState
  case class Running(callbacks: List[Either[E, A] => Any]) extends FiberState
  case class Done(result: Either[E, A]) extends FiberState

  private var loop = true
  private var currentZIO: Erased = zio
  private var currentExecutor = startExecutor
  private val stack = mutable.Stack[Cont]()
  private val state: AtomicReference[FiberState] = new AtomicReference(Running(List.empty))

  override def join: ZIO[E, A] = {
    ZIO.async[Either[E, A]](callback => await(callback))
      .flatMap(result =>
        result.fold(e => ZIO.fail(e), a => ZIO.succeed(a))
      )
  }

  private def complete(result: Either[E, A]): Unit = {
    var loop = true;
    while (loop) {
      val oldState = state.get()
      oldState match {
        case Running(callbacks) =>
          if (state.compareAndSet(oldState, Done(result))) {
            callbacks.foreach(callbacks => callbacks(result))
            loop = false
          }
        case Done(_) =>
          throw new IllegalStateException("Fiber being completed multiple times")
      }
    }
  }

  private def await(callback: Either[E, A] => Any): Unit = {
    var loop = true
    while (loop) {
      val oldState = state.get()
      oldState match {
        case Running(callbacks) =>
          loop = !state.compareAndSet(oldState, Running(callback :: callbacks))
        case Done(result) =>
          callback(result)
          loop = false
      }
    }
  }

  def run(): Unit = {

    def continue(value: Any): Unit = {
      if (stack.isEmpty) {
        loop = false
        complete(Right(value.asInstanceOf[A]))
      } else {
        val cont = stack.pop()
        currentZIO = cont(value)
      }
    }

    def findErrorHandler(): ErrorHandler = {
      var handler: ErrorHandler = null
      while((handler eq null) && stack.nonEmpty) {
        stack.pop() match {
          case cont: ErrorHandler =>
            handler = cont
          case _ =>
        }
      }
      handler
    }

    while (loop) {
      currentZIO match {
        case ZIO.SucceedNow(value) =>
          continue(value)
        case ZIO.Succeed(f) =>
          continue(f())
        case ZIO.FlatMap(zio, cont) =>
          currentZIO = zio
          stack.push(cont)
        case ZIO.Async(register) =>
          loop = false
          if (stack.isEmpty) {
            register(a => complete(Right(a.asInstanceOf[A])))
          } else {
            register(a => {
              currentZIO = ZIO.succeedNow(a)
              loop = true
              run()
            })
          }
        case ZIO.Fork(zio) =>
          val fiber = FiberContext(zio, currentExecutor)
          continue(fiber)
        case ZIO.Shift(executor) =>
          currentExecutor = executor
          continue(())
        case ZIO.Fail(e) =>
          val errorHandler = findErrorHandler()
          if (errorHandler eq null) {
            complete(Left(e().asInstanceOf[E]))
          } else {
            currentZIO = errorHandler.failure(e())
          }
        case fold @ ZIO.Fold(zio, _, _) =>
          currentZIO = zio
          stack.push(fold)
      }
    }
  }

  currentExecutor.execute(() => run())
}