package zio

import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable
import scala.concurrent.ExecutionContext

trait Fiber[+A] {

  def join: ZIO[A]
}

private final case class FiberContext[A](zio: ZIO[A], startExecutor: ExecutionContext) extends Fiber[A] {
  type Erased = ZIO[Any]
  type Cont = Any => Erased

  sealed trait FiberState
  case class Running(callbacks: List[A => Any]) extends FiberState
  case class Done(result: A) extends FiberState

  private var loop = true
  private var currentZIO: Erased = zio
  private var currentExecutor = startExecutor
  private val stack = mutable.Stack[Cont]()
  private val state: AtomicReference[FiberState] = new AtomicReference(Running(List.empty))

  override def join: ZIO[A] = {
    ZIO.async(callback => await(callback))
  }

  private def complete(result: A): Unit = {
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

  private def await(callback: A => Any): Unit = {
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
        complete(value.asInstanceOf[A])
      } else {
        val cont = stack.pop()
        currentZIO = cont(value)
      }
    }

    while (loop) {
      currentZIO match {
        case ZIO.Succeed(value) =>
          continue(value)
        case ZIO.Effect(f) =>
          continue(f())
        case ZIO.FlatMap(zio, cont) =>
          currentZIO = zio
          stack.push(cont)
        case ZIO.Async(register) =>
          loop = false
          if (stack.isEmpty) {
            register(a => complete(a.asInstanceOf[A]))
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
      }
    }
  }

  currentExecutor.execute(() => run())
}