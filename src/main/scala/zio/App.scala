package zio

case class Person(name: String, age: Int)

object Zip {

  def main(args: Array[String]): Unit = {
    val rick =
      (ZIO.succeed("Rick") zip ZIO.succeed(70))
        .map({
          case (name, age) => Person(name, age)
        })
        .unsafeRunSync

    println(s"Hello, ${rick.name}!")
  }
}

object FlatMap {

  def main(args: Array[String]): Unit = {
    def getMorty = ZIO.succeed(Person("Morty", 14))

    def greet(person: Person): ZIO[Unit] = {
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
//    def loop(n: Int, zio: ZIO[Any] = ZIO.succeed()): ZIO[Any]= {
//      if (n == 0) zio
//      else loop(n - 1, zio.map(_ => println("Wubba Lubba Dub-Dub")))
//    }
//    loop(10000).run(_ => ())

    ZIO.succeed(println("Wubba Lubba Dub-Dub"))
      .repeat(10000)
      .unsafeRunSync
  }
}
