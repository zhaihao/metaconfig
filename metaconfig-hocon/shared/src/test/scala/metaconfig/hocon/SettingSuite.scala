package metaconfig.hocon

import metaconfig.Conf._
import metaconfig._
import org.scalatest.FunSuite

class SettingSuite extends FunSuite {

  case class Permission(read: Boolean, write: Boolean)
  object Permission {
    implicit val decoder: ConfDecoder[Permission] =
      ConfDecoder.instanceF[Permission] { conf =>
        (
          conf.get[Boolean]("read") |@|
            conf.get[Boolean]("write")
        ).map {
          case (a, b) => Permission(a, b)
        }
      }
  }

  case class User(name: String, age: Int, permission: Permission)
  val name = Setting("name")
  val age = Setting("age")

  test("simple") {
    val conf = Obj(
      "age" -> Num(22),
      "name" -> Str("John"),
      "permission" -> Obj(
        "read" -> Bool(true),
        "write" -> Bool(false)
      )
    )
    val user = (
      conf.get[String](name) product
        conf.get[Int](age) product
        conf.get[Permission]("permission")
    ).map {
      case ((a, b), c) =>
        User(a, b, c)
    }.get
    assert(user == User("John", 22, Permission(read = true, write = false)))
  }
}