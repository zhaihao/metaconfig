package metaconfig.internal

import scala.language.experimental.macros

import scala.annotation.StaticAnnotation
import scala.reflect.macros.blackbox
import metaconfig._
import metaconfig.generic.Field
import metaconfig.generic.Settings
import metaconfig.generic.Surface

object Macros
class Macros(val c: blackbox.Context) {
  import c.universe._
  def assumeClass[T: c.WeakTypeTag]: Type = {
    val T = weakTypeOf[T]
    if (!T.typeSymbol.isClass || !T.typeSymbol.asClass.isCaseClass)
      c.abort(c.enclosingPosition, s"$T must be a case class")
    T
  }

  def params(T: Type): List[Symbol] = {
    val paramss = T.typeSymbol.asClass.primaryConstructor.asMethod.paramLists
    if (paramss.lengthCompare(1) > 0) {
      c.abort(
        c.enclosingPosition,
        s"${T.typeSymbol} has a curried parameter list, which is not supported."
      )
    }
    paramss.head
  }

  def deriveConfCodecImpl[T: c.WeakTypeTag](default: Tree): Tree = {
    val T = assumeClass[T]
    q"""
        _root_.metaconfig.ConfCodec.EncoderDecoderToCodec[$T](
          _root_.metaconfig.generic.deriveEncoder[$T],
          _root_.metaconfig.generic.deriveDecoder[$T]($default)
        )
     """
  }

  def deriveConfEncoderImpl[T: c.WeakTypeTag]: Tree = {
    val T = assumeClass[T]
    val params = this.params(T)
    val writes = params.map { param =>
      val name = param.name.decodedName.toString
      val select = Select(q"value", param.name)
      val encoder =
        q"_root_.scala.Predef.implicitly[_root_.metaconfig.ConfEncoder[${param.info}]]"
      q"($name, $encoder.write($select))"
    }
    val result = q"""
       new ${weakTypeOf[ConfEncoder[T]]} {
         override def write(value: ${weakTypeOf[T]}): _root_.metaconfig.Conf = {
           new _root_.metaconfig.Conf.Obj(
             _root_.scala.List.apply(..$writes)
           )
         }
       }
     """
    result
  }

  def deriveConfDecoderImpl[T: c.WeakTypeTag](default: Tree): Tree = {
    val T = assumeClass[T]
    val Tclass = T.typeSymbol.asClass
    val settings = c.inferImplicitValue(weakTypeOf[Settings[T]])
    if (settings == null || settings.isEmpty) {
      c.abort(
        c.enclosingPosition,
        s"Missing implicit for ${weakTypeOf[Settings[T]]}]. " +
          s"Hint, add `implicit val surface: ${weakTypeOf[Surface[T]]}` " +
          s"to the companion ${T.companion.typeSymbol}"
      )
    }
    val paramss = Tclass.primaryConstructor.asMethod.paramLists
    if (paramss.size > 1) {}
    if (paramss.head.isEmpty)
      return q"_root_.metaconfig.ConfDecoder.constant($default)"

    val (head :: params) :: Nil = paramss
    def next(param: Symbol): Tree = {
      val P = param.info.resultType
      val name = param.name.decodedName.toString
      val getter = T.member(param.name)
      val fallback = q"tmp.$getter"
      val next =
        q"conf.getSettingOrElse[$P](settings.unsafeGet($name), $fallback)"
      next
    }
    val product = params.foldLeft(next(head)) {
      case (accum, param) => q"$accum.product(${next(param)})"
    }
    val tupleExtract = 1.to(params.length).foldLeft(q"t": Tree) {
      case (accum, _) => q"$accum._1"
    }
    var curr = tupleExtract
    val args = 0.to(params.length).map { _ =>
      val old = curr
      curr = curr match {
        case q"$qual._1" =>
          q"$qual._2"
        case q"$qual._1._2" =>
          q"$qual._2"
        case q"$qual._2" =>
          q"$qual"
        case q"t" => q"t"
      }
      old
    }
    val ctor = q"new $T(..$args)"

    val result = q"""
       new ${weakTypeOf[ConfDecoder[T]]} {
         def read(conf: _root_.metaconfig.Conf): ${weakTypeOf[Configured[T]]} = {
             val settings = $settings
             val tmp = $default
             $product.map { t =>
               $ctor
             }
         }
       }
     """
    result
  }

  def deriveSurfaceImpl[T: c.WeakTypeTag]: Tree = {
    val T = weakTypeOf[T]
    if (!T.typeSymbol.isClass || !T.typeSymbol.asClass.isCaseClass)
      c.abort(c.enclosingPosition, s"$T must be a case class")
    val Tclass = T.typeSymbol.asClass
    val ctor = Tclass.primaryConstructor.asMethod
    val argss = ctor.paramLists.map { params =>
      val fields = params.map { param =>
        val paramTpe = param.info.resultType
        val baseAnnots = param.annotations.collect {
          case annot if annot.tree.tpe <:< typeOf[StaticAnnotation] =>
            annot.tree
        }
        val isIterable = paramTpe <:< typeOf[Iterable[_]]
        val finalAnnots =
          if (isIterable) {
            q"new _root_.metaconfig.annotation.Repeated" :: baseAnnots
          } else {
            baseAnnots
          }
        val fieldsParamTpe = c.internal.typeRef(
          NoPrefix,
          weakTypeOf[Surface[_]].typeSymbol,
          paramTpe :: Nil
        )
        val underlyingInferred = c.inferImplicitValue(fieldsParamTpe)
        val underlying =
          if (underlyingInferred == null || underlyingInferred.isEmpty) {
            q"_root_.scala.Nil"
          } else {
            q"$underlyingInferred.fields"
          }

        val field = q"""new ${weakTypeOf[Field]}(
           ${param.name.decodedName.toString},
           ${paramTpe.toString},
           _root_.scala.List.apply(..$finalAnnots),
           $underlying
         )"""
        field
      }
      val args = q"_root_.scala.List.apply(..$fields)"
      args
    }
    val args = q"_root_.scala.List.apply(..$argss)"
    val result = q"new ${weakTypeOf[Surface[T]]}($args)"
    c.untypecheck(result)
  }

}
