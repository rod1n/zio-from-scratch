package zio

import scala.reflect.ClassTag

final case class Has[A](map: Map[String, Any])

object Has {

  def succeed[A](a: A)(implicit classTag: ClassTag[A]): Has[A] =
    Has(Map(classTag.toString -> a))

  implicit class HasOps[Self <: Has[_]](self: Self) {
    def get[A](implicit ev: Self <:< Has[A], classTag: ClassTag[A]): A =
      self.map(classTag.toString()).asInstanceOf[A]

    def ++[That <: Has[_]](that: That): Self with That =
      Has(self.map ++ that.map).asInstanceOf[Self with That]
  }
}



