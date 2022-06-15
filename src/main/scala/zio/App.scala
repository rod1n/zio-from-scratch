package zio

case class Person(name: String, age: Int)

object Zip {

  def main(args: Array[String]): Unit = {
      (ZIO.succeed("Rick") zip ZIO.succeed(70))
        .map({
          case (name, age) => Person(name, age)
        })
        .map(rick => println(s"Hello, ${rick.name}!"))
        .unsafeRunSync
  }
}

object FlatMap {

  def main(args: Array[String]): Unit = {
    def getMorty = ZIO.succeed(Person("Morty", 14))

    def greet(person: Person): ZIO[Any, Nothing, Unit] = {
      ZIO.succeed(println(s"Hello, ${person.name}!"))
    }

    val helloMorty = for {
      morty <- getMorty
      _  <- greet(morty)
    } yield ()

    val result = helloMorty.as("Wubba Lubba Dub-Dub")
      .unsafeRunSync

    println(result)
  }
}

object Async {

  def main(args: Array[String]): Unit = {
    val computation = ZIO.async[Int](complete => {
      println(s"Async computation started | ${Thread.currentThread().getName}")
      Thread.sleep(1000)
      val int = scala.util.Random.nextInt(999)
      println(s"$int | ${Thread.currentThread().getName}")
      complete(int)
    })

    val parallelComputation =
      for {
        fiber1 <- computation.fork
        fiber2 <- computation.fork
        _      <- ZIO.succeed(println(s"Computations submitted | ${Thread.currentThread().getName}"))
        a      <- fiber1.join
        b      <- fiber2.join
      } yield (a, b)

    val result = parallelComputation.unsafeRunSync

    println(s"The result is $result | ${Thread.currentThread().getName}")
  }
}

object ZipPar {

  def main(args: Array[String]): Unit = {
    val computation = ZIO.async[Int](complete => {
      println("Async computation started")
      Thread.sleep(1000)
      complete(scala.util.Random.nextInt(999))
    })

    val result = (computation zipPar computation)
      .unsafeRunSync

    println(s"The result is $result")
  }
}

object StackSafety {

  def main(args: Array[String]): Unit = {
    ZIO.succeed(println("Wubba Lubba Dub-Dub"))
      .repeat(10000)
      .unsafeRunSync
  }
}

object ErrorHandling {

  def main(args: Array[String]): Unit = {
    ZIO.fail("Failed")
      .flatMap(_ => ZIO.succeed(println("Will not be printed")))
      .catchAll(_ => ZIO.succeed(println("Recovered from an error")))
      .unsafeRunSync
  }
}

object UnexpectedExceptionHandling {

  def main(args: Array[String]): Unit = {
    val result = ZIO.succeed(List().head)
      .zipRight(ZIO.succeed(println("Will not be printed")))
      .catchAll(_ => ZIO.succeed(println("Will not be printed")))
      .foldCauseZIO[Any, Nothing, Unit](
        cause => ZIO.succeed(println(s"Recovered from an error $cause")) *> ZIO.succeed(1),
        _ => ZIO.succeed(0))
      .unsafeRunSync

    println(result)
  }
}

object Interruption {

  def main(args: Array[String]): Unit = {

    val calculation1 =
      (ZIO.succeed(Thread.sleep(1000)) *> ZIO.succeed(println("Cannot be interrupted")))
        .repeat(5)

    val calculation2 =
      (ZIO.succeed(Thread.sleep(1000)) *> ZIO.succeed(println("Can be interrupted")))
        .forever

    val zio = for {
      fiber <- (calculation1.uninterruptible *> calculation2)
        .ensuring(ZIO.succeed(println("Resources released"))).fork
      _ <- ZIO.succeed(Thread.sleep(3000))
      _ <- fiber.interrupt
    } yield ZIO.succeed(0)

    zio.unsafeRunSync
  }
}

object Environment {

  def main(args: Array[String]): Unit = {
    def greet(person: Person): Unit = println(s"Hello, ${person.name}!")

    val program = for {
      name <- ZIO.service[String]
      age  <- ZIO.service[Int]
      f    <- ZIO.service[Person => Unit]
      _    <- ZIO.succeed(f(Person(name, age)))
    } yield ()

    val env = Has.succeed(greet(_))
    val env1 = env ++ Has.succeed("Morty") ++ Has.succeed(14)
    val env2 = env ++ Has.succeed(70) ++ Has.succeed("Rick")

    program
      .provide(env1)
      .unsafeRunSync

    program
      .provide(env2)
      .unsafeRunSync
  }
}
