/*
 * Copyright 2016 Michal Harish, michal.harish@gmail.com
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.amient.affinity.avro.record

import java.io.{ByteArrayOutputStream, InputStream, OutputStream}
import java.lang.reflect.{Field, Parameter}
import java.util.UUID
import java.util.function.Supplier

import io.amient.affinity.core.util.{ByteUtils, ThreadLocalCache}
import org.apache.avro.Schema.Type._
import org.apache.avro.generic.GenericData.EnumSymbol
import org.apache.avro.generic._
import org.apache.avro.io.{DecoderFactory, EncoderFactory}
import org.apache.avro.specific.SpecificRecord
import org.apache.avro.util.Utf8
import org.apache.avro.{AvroRuntimeException, LogicalTypes, Schema, SchemaBuilder}

import scala.annotation.StaticAnnotation
import scala.collection.JavaConverters._
import scala.collection.immutable.Seq
import scala.reflect.runtime.universe._
import scala.reflect.runtime.{universe, _}

final class Alias(val aliases: String*) extends StaticAnnotation

final class Union(val index: Int) extends StaticAnnotation

final class Fixed(val len: Int = -1) extends StaticAnnotation

final class Doc(val text: String) extends StaticAnnotation

abstract class AvroRecord extends SpecificRecord with java.io.Serializable {

  @transient lazy val schema: Schema = AvroRecord.inferSchema(getClass)

  @transient private lazy val fields: Map[Int, Field] = AvroRecord.classFieldsCache.getOrInitialize(getClass, schema)

  override def getSchema: Schema = schema

  final override def get(i: Int): AnyRef = {
    AvroRecord.extract(fields(i).get(this), List(schema.getFields.get(i).schema))
  }

  final override def put(i: Int, v: scala.Any): Unit = {
    throw new AvroRuntimeException("Scala AvroRecord is immutable")
  }

}

object AvroRecord extends AvroExtractors {

  def toAvroGeneric(ref: AnyRef): IndexedRecord = {
    val schema: Schema = AvroRecord.inferSchema(ref)
    val fields: Map[Int, Field] = AvroRecord.classFieldsCache.getOrInitialize(ref.getClass, schema)
    return new IndexedRecord {
      override def put(i: Int, o: scala.Any): Unit = {
        throw new AvroRuntimeException("Scala AvroRecord is immutable")
      }

      override def get(i: Int): AnyRef = {
        AvroRecord.extract(fields(i).get(ref), List(schema.getFields.get(i).schema))
      }

      override def getSchema: Schema = schema
    }
  }

  def stringToFixed(value: String, getFixedSize: Int): Array[Byte] = {
    val bytes = value.getBytes()
    val result: Array[Byte] = Array.fill[Byte](getFixedSize)(0)
    val transfer = math.min(bytes.length, result.length)
    ByteUtils.copy(bytes, 0, result, result.length - transfer, transfer)
    result
  }

  def fixedToString(fixed: Array[Byte]): String = {
    new String(fixed).trim()
  }

  val INT_SCHEMA = Schema.create(Schema.Type.INT)
  val BOOLEAN_SCHEMA = Schema.create(Schema.Type.BOOLEAN)
  val LONG_SCHEMA = Schema.create(Schema.Type.LONG)
  val FLOAT_SCHEMA = Schema.create(Schema.Type.FLOAT)
  val DOUBLE_SCHEMA = Schema.create(Schema.Type.DOUBLE)
  val STRING_SCHEMA = Schema.create(Schema.Type.STRING)
  val BYTES_SCHEMA = Schema.create(Schema.Type.BYTES)
  val NULL_SCHEMA = Schema.create(Schema.Type.NULL)

  def write(value: Any, schema: Schema): Array[Byte] = {
    ScalaAvroProjectorCache.getOrInitialize(schema, schema).write(value)
  }

  def write[O <: OutputStream](value: Any, schema: Schema, output: O): O = {
    ScalaAvroProjectorCache.getOrInitialize(schema, schema).write(value, output)
  }

  def read[T](bytes: Array[Byte], schema: Schema): T = read[T](bytes, schema, 0)

  def read[T](bytes: Array[Byte], schema: Schema, offset: Int): T = read(bytes, schema, schema, offset).asInstanceOf[T]

  def read(bytes: Array[Byte], writerSchema: Schema, readerSchema: Schema, offset: Int): Any = {
    ScalaAvroProjectorCache.getOrInitialize(writerSchema, readerSchema).convert(bytes, offset)
  }

  def read(bytes: InputStream, writerSchema: Schema, readerSchema: Schema): Any = {
    ScalaAvroProjectorCache.getOrInitialize(writerSchema, readerSchema).convert(bytes)
  }

  private object fqnMirrorCache extends ThreadLocalCache[String, universe.Mirror] {
    def getOrInitialize(fqn: String): universe.Mirror = {
      getOrInitialize(fqn, new Supplier[Mirror] {
        override def get(): Mirror = runtimeMirror(Class.forName(fqn).getClassLoader)
      })
    }
  }

  private object fqnTypeCache extends ThreadLocalCache[String, Type] {
    def getOrInitialize(fqn: String): Type = {
      getOrInitialize(fqn, new Supplier[Type] {
        override def get(): Type = fqnMirrorCache.getOrInitialize(fqn).staticClass(fqn).selfType
      })
    }
  }

  private object fqnClassMirrorCache extends ThreadLocalCache[String, ClassMirror] {
    def getOrInitialize(fqn: String): ClassMirror = getOrInitialize(fqn, new Supplier[ClassMirror] {
      override def get(): ClassMirror = {
        val typeMirror = universe.runtimeMirror(Class.forName(fqn).getClassLoader)
        val tpe = fqnTypeCache.getOrInitialize(fqn)
        typeMirror.reflectClass(tpe.typeSymbol.asClass)
      }
    })
  }

  private object classFieldsCache extends ThreadLocalCache[Class[_], Map[Int, Field]] {
    def getOrInitialize(cls: Class[_], schema: Schema): Map[Int, Field] = getOrInitialize(cls, new Supplier[Map[Int, Field]] {
      override def get(): Map[Int, Field] = {
        val schemaFields = schema.getFields
        val params: Array[Parameter] = cls.getConstructors()(0).getParameters
        require(params.length == schemaFields.size,
          s"number of constructor arguments (${params.length}) is not equal to schema field count (${schemaFields.size})")

        val declaredFields = cls.getDeclaredFields
        val fields: Map[Int, Field] = params.zipWithIndex.map { case (param, pos) => {
          val field = declaredFields(pos)
          require(param.getType == field.getType,
            s"field `${field.getType}` at position $pos doesn't match expected `$param`")
          field.setAccessible(true)
          pos -> field
        }
        }.toMap
        fields
      }
    })
  }

  private object fqnConstructorCache extends ThreadLocalCache[String, (Seq[Type], MethodMirror)] {
    def getOrInitialize(fqn: String): (Seq[Type], MethodMirror) = {
      getOrInitialize(fqn, new Supplier[(Seq[Type], MethodMirror)] {
        override def get(): (Seq[Type], MethodMirror) = {
          val tpe = fqnTypeCache.getOrInitialize(fqn)
          val constructor = tpe.decl(universe.termNames.CONSTRUCTOR).asMethod
          val params = constructor.paramLists(0).map(_.typeSignature)
          val classMirror = fqnClassMirrorCache.getOrInitialize(fqn)
          val constructorMirror: MethodMirror = classMirror.reflectConstructor(constructor)
          (params, constructorMirror)
        }
      })
    }
  }

  private object enumCache extends ThreadLocalCache[Type, MethodMirror] {
    def getOrInitialize(tpe: Type): MethodMirror = getOrInitialize(tpe, new Supplier[MethodMirror] {
      override def get(): MethodMirror = {
        tpe match {
          case TypeRef(enumType, _, _) =>
            val moduleMirror = currentMirror.reflectModule(enumType.termSymbol.asModule)
            val instanceMirror = currentMirror.reflect(moduleMirror.instance)
            instanceMirror.reflectMethod(enumType.member(TermName("withName")).asMethod)
        }
      }
    })
  }

  private object iterableCache extends ThreadLocalCache[Type, (Iterable[Any]) => Iterable[Any]] {
    def getOrInitialize(tpe: Type): (Iterable[Any]) => Iterable[Any] = {
      getOrInitialize(tpe, new Supplier[Iterable[Any] => Iterable[Any]] {
        override def get(): Iterable[Any] => Iterable[Any] = {
          if (tpe <:< typeOf[Set[_]]) {
            (iterable) => iterable.toSet
          } else if (tpe <:< typeOf[List[Any]]) {
            (iterable) => iterable.toList
          } else if (tpe <:< typeOf[Vector[Any]]) {
            (iterable) => iterable.toVector
          } else if (tpe <:< typeOf[IndexedSeq[Any]]) {
            (iterable) => iterable.toIndexedSeq
          } else if (tpe <:< typeOf[Seq[Any]]) {
            (iterable) => iterable.toSeq
          } else {
            (iterable) => iterable
          }
        }
      })
    }
  }

  private object unionCache extends ThreadLocalCache[Type, (Any) => Any] {
    def getOrInitialize(tpe: universe.Type, schema: Schema, readField: (Any, Schema, universe.Type) => Any): (Any) => Any = {
      getOrInitialize(tpe, new Supplier[Any => Any] {
        val someSchema = if (schema.getTypes.get(0).getType == Schema.Type.NULL) schema.getTypes.get(1) else schema.getTypes.get(0)

        override def get(): Any => Any = {
          if (tpe <:< typeOf[Option[Any]]) {
            (datum) =>
              datum match {
                case null => None
                case some => Some(readField(some, someSchema, tpe.typeArgs(0)))
              }
          } else {
            (datum) =>
              datum match {
                case r: IndexedRecord =>
                  readField(datum, r.getSchema, fqnTypeCache.getOrInitialize(r.getSchema.getFullName()))
                case _ =>
                  throw new NotImplementedError(s"Only Sealed Traits and Options are supported for Avro Unions, got: $datum")
              }
          }
        }
      })
    }
  }

  private object ScalaAvroProjectorCache extends ThreadLocalCache[(Schema, Schema), ScalaAvroProjector] {
    def getOrInitialize(writerSchema: Schema, readerSchema: Schema): ScalaAvroProjector = {
      getOrInitialize((writerSchema, readerSchema), new Supplier[ScalaAvroProjector] {
        override def get() = new ScalaAvroProjector(writerSchema, readerSchema)
      })
    }
  }

  private class ScalaAvroProjector(writerSchema: Schema, readerSchema: Schema) {
    private val projectedSchema = if (readerSchema != null) readerSchema else writerSchema
    private lazy val reader = new GenericDatumReader[Any](writerSchema, projectedSchema)
    private lazy val writer = new GenericDatumWriter[Any](projectedSchema)

    def write[O <: OutputStream](value: Any, output: O): O = {
      val encoder = EncoderFactory.get().binaryEncoder(output, null)
      writer.write(extract(value, List(projectedSchema)), encoder)
      encoder.flush()
      output
    }

    def write(value: Any): Array[Byte] = {
      val output = new ByteArrayOutputStream()
      try {
        write(value, output)
        output.toByteArray
      } finally {
        output.close
      }
    }


    def convert(record: GenericContainer): Any = read(record, projectedSchema)

    def convert(bytes: Array[Byte], offset: Int = 0): Any = {
      val decoder = DecoderFactory.get().binaryDecoder(bytes, offset, bytes.length - offset, null)
      val datum = reader.read(null, decoder)
      //if runtime/readerSchema could not be determinted, the best we can do is return a generic datum how it was written, e.g. Record or primitive
      //TODO this doesn't have any test to protected against regression
      if (readerSchema == null) datum else read(datum, readerSchema)
    }

    def convert(bytes: InputStream): Any = {
      val decoder = DecoderFactory.get().binaryDecoder(bytes, null)
      val datum = reader.read(null, decoder)
      if (readerSchema == null) datum else read(datum, readerSchema)
    }
  }


  /**
    * Read avro value, e.g. GenericRecord or primitive into scala case class or scala primitive
    *
    * @param datum
    * @param schema
    * @return
    */
  def read(datum: Any, schema: Schema): Any = {

    def readField(datum: Any, schema: Schema, tpe: Type): Any = {
      val logicalTypeProp = schema.getProp("logicalType")
      schema.getType match {
        case ENUM => enumCache.getOrInitialize(tpe).apply(datum.asInstanceOf[EnumSymbol].toString)
        case UNION => unionCache.getOrInitialize(tpe, schema, readField)(datum)
        case MAP =>
          val valueSchema = schema.getValueType
          val valueType = tpe.typeArgs(1)
          datum.asInstanceOf[java.util.Map[Utf8, _]].asScala.toMap
            .map { case (k, v) => (k.toString, readField(v, valueSchema, valueType)) }
        case ARRAY =>
          iterableCache.getOrInitialize(tpe) {
            val elementSchema = schema.getElementType
            val elementType = tpe.typeArgs(0)
            datum.asInstanceOf[java.util.Collection[Any]].asScala.map(
              item => readField(item, elementSchema, elementType))
          }
        case RECORD => read(datum, schema)
        case _ if schema.getLogicalType == null && logicalTypeProp != null =>
          val arg = read(datum, schema)
          try {
            val (params: Seq[Type], constructorMirror) = fqnConstructorCache.getOrInitialize(logicalTypeProp)
            if (params.size == 1) {
              constructorMirror(arg)
            } else {
              arg
            }
          } catch {
            case _: ClassNotFoundException => arg
          }
        case _ => read(datum, schema)
      }
    }

    schema.getType match {
      case RECORD if datum == null => null
      case RECORD =>
        val record = datum.asInstanceOf[IndexedRecord]
        val (params: Seq[Type], constructorMirror) = fqnConstructorCache.getOrInitialize(record.getSchema.getFullName)
        val arguments = record.getSchema.getFields.asScala.map { field =>
          readField(record.get(field.pos), field.schema, params(field.pos))
        }
        constructorMirror(arguments: _*)
      case BOOLEAN => datum.asInstanceOf[Boolean]
      case INT => datum.asInstanceOf[Int]
      case NULL => null
      case FLOAT => datum.asInstanceOf[Float]
      case DOUBLE => datum.asInstanceOf[Double]
      case LONG => datum.asInstanceOf[Long]
      case BYTES => ByteUtils.bufToArray(datum.asInstanceOf[java.nio.ByteBuffer])
      case STRING if datum == null => null
      case STRING => datum.asInstanceOf[Utf8].toString
      case FIXED if schema.getProp("logicalType") == "int" || datum.isInstanceOf[Int] =>
        ByteUtils.asIntValue(datum.asInstanceOf[GenericFixed].bytes())
      case FIXED if schema.getProp("logicalType") == "long" || datum.isInstanceOf[Long] =>
        ByteUtils.asLongValue(datum.asInstanceOf[GenericFixed].bytes())
      case FIXED if schema.getProp("logicalType") == "string" =>
        AvroRecord.fixedToString(datum.asInstanceOf[GenericFixed].bytes())
      case FIXED if schema.getProp("logicalType") == "uuid" =>
        ByteUtils.uuid(datum.asInstanceOf[GenericFixed].bytes())
      case FIXED => datum.asInstanceOf[GenericFixed].bytes()
      case invalidTopLevel => throw new IllegalArgumentException(s"$invalidTopLevel is not allowed as a top-level avro type")
    }

  }

  def inferSchema[T: TypeTag]: Schema = inferSchema(typeOf[T])

  def inferSchema(cls: Class[_]): Schema = inferSchema(cls.getName)

  def inferSchema(fqn: String): Schema = {
    fqn match {
      case "null" => NULL_SCHEMA
      case "boolean" => BOOLEAN_SCHEMA
      case "int" => INT_SCHEMA
      case "long" => LONG_SCHEMA
      case "float" => FLOAT_SCHEMA
      case "double" => DOUBLE_SCHEMA
      case "string" => STRING_SCHEMA
      case _ => inferSchema(fqnTypeCache.getOrInitialize(fqn))
    }
  }

  def inferSchema(obj: Any): Schema = {
    obj match {
      case container: GenericContainer => container.getSchema
      case null => AvroRecord.NULL_SCHEMA
      case _: Boolean => AvroRecord.BOOLEAN_SCHEMA
      case _: Byte => AvroRecord.INT_SCHEMA
      case _: Int => AvroRecord.INT_SCHEMA
      case _: Long => AvroRecord.LONG_SCHEMA
      case _: Float => AvroRecord.FLOAT_SCHEMA
      case _: Double => AvroRecord.DOUBLE_SCHEMA
      case _: String => AvroRecord.STRING_SCHEMA
      case _: Array[Byte] => AvroRecord.BYTES_SCHEMA
      case _ =>
        val m = runtimeMirror(obj.getClass.getClassLoader)
        val classSymbol = m.staticClass(obj.getClass.getName)
        AvroRecord.inferSchema(classSymbol.selfType)
    }
  }

  private object typeSchemaCache extends ThreadLocalCache[Type, Schema]()

  def inferSchema(tpe: Type): Schema = {
    typeSchemaCache.getOrInitialize(tpe, new Supplier[Schema] {
      override def get(): Schema = {
        inferSchemaWithoutCache(tpe)
      }
    })
  }

  private def inferSchemaWithoutCache(tpe: Type): Schema = {

    if (tpe =:= definitions.IntTpe) {
      SchemaBuilder.builder().intType()
    } else if (tpe =:= definitions.LongTpe) {
      SchemaBuilder.builder().longType()
    } else if (tpe =:= definitions.BooleanTpe) {
      SchemaBuilder.builder().booleanType()
    } else if (tpe =:= definitions.FloatTpe) {
      SchemaBuilder.builder().floatType()
    } else if (tpe =:= definitions.DoubleTpe) {
      SchemaBuilder.builder().doubleType()
    } else if (tpe =:= typeOf[Array[Byte]]) {
      SchemaBuilder.builder().bytesType()
    } else if (tpe =:= typeOf[String]) {
      SchemaBuilder.builder().stringType()
    } else if (tpe =:= typeOf[Null]) {
      SchemaBuilder.builder().nullType()
    } else if (tpe <:< typeOf[Map[String, Any]]) {
      SchemaBuilder.builder().map().values().`type`(inferSchema(tpe.typeArgs(1)))
    } else if (tpe <:< typeOf[Iterable[Any]]) {
      SchemaBuilder.builder().array().items().`type`(inferSchema(tpe.typeArgs(0)))
    } else if (tpe <:< typeOf[scala.Enumeration#Value]) {
      tpe match {
        case TypeRef(enumType, _, _) =>
          val typeMirror = universe.runtimeMirror(Class.forName(enumType.typeSymbol.asClass.fullName).getClassLoader)
          val moduleMirror = typeMirror.reflectModule(enumType.termSymbol.asModule)
          val instanceMirror = typeMirror.reflect(moduleMirror.instance)
          val methodMirror = instanceMirror.reflectMethod(enumType.member(TermName("values")).asMethod)
          val enumSymbols = methodMirror().asInstanceOf[Enumeration#ValueSet]
          val args = enumSymbols.toSeq.map(_.toString)
          SchemaBuilder.builder().enumeration(enumType.toString.dropRight(5)).symbols(args: _*)
      }
    } else if (tpe <:< typeOf[Option[Any]]) {
      SchemaBuilder.builder().unionOf().nullType().and().`type`(inferSchema(tpe.typeArgs(0))).endUnion()
    } else if (tpe.typeSymbol.isClass && tpe.typeSymbol.asClass.isDerivedValueClass) {
      val constructor = tpe.decl(universe.termNames.CONSTRUCTOR)
      val underlying = constructor.asMethod.paramLists(0).head
      val s = inferSchemaWithoutCache(underlying.typeSignature)
      s.addProp("logicalType", tpe.typeSymbol.asClass.fullName.asInstanceOf[Object])
      s
    } else if (tpe.typeSymbol.isClass && tpe.typeSymbol.asClass.isSealed) {
      var usedIndicies = Set.empty[Int]
      val subtypes = tpe.typeSymbol.asClass.knownDirectSubclasses.toList.sortBy(_.fullName).map { s =>
        val a = s.annotations.find(_.tree.tpe =:= typeOf[Union]).getOrElse{
          throw new IllegalArgumentException(s"Sealed Union type ${s} is not annotated with @Union(index)")
        }
        val i = a.tree.children.tail.map(_.productElement(0).asInstanceOf[Constant].value.asInstanceOf[Int]).head
        if (usedIndicies.contains(i)) {
          throw new IllegalArgumentException(s"Sealed Union type ${s} is defined at @Union(index = ${i}) which is already allocated")
        }
        usedIndicies += i
        i -> inferSchema(s.asType.toType)
      }
      //sealed union types are ordered using the index defined by @Union annotation
      val schemas = subtypes.sortBy(_._1).map(_._2)
      schemas.tail.foldLeft(SchemaBuilder.builder().unionOf().`type`(schemas.head))(_.and.`type`(_)).endUnion()
    } else if (tpe <:< typeOf[AvroRecord] || tpe.typeSymbol.asClass.isCaseClass) {
      val typeMirror = fqnMirrorCache.getOrInitialize(tpe.typeSymbol.asClass.fullName)
      val moduleMirror = typeMirror.reflectModule(tpe.typeSymbol.companion.asModule)
      val companionMirror = typeMirror.reflect(moduleMirror.instance)
      val constructor = tpe.decl(universe.termNames.CONSTRUCTOR)
      val params = constructor.asMethod.paramLists(0)
      val namespace = tpe.typeSymbol.asClass.fullName.split("\\.").dropRight(1).mkString(".")
      val assembler = params.zipWithIndex.foldLeft(SchemaBuilder.record(tpe.toString).namespace(namespace).fields()) {
        case (assembler, (symbol: Symbol, i)) =>
          val fieldName = symbol.name.toString
          val fieldType = symbol.typeSignature
          val fieldSchema = symbol.annotations.find(_.tree.tpe =:= typeOf[Fixed]).map { a =>
            val fixedSize = a.tree.children.tail.collect { case Literal(Constant(size: Int)) => size }.headOption
            fixedSize match {
              case None if fieldType =:= typeOf[Int] => SchemaBuilder.builder().fixed(fieldName).prop("logicalType", "int").size(4)
              case None if fieldType =:= typeOf[Long] => SchemaBuilder.builder().fixed(fieldName).prop("logicalType", "long").size(8)
              case None if fieldType =:= typeOf[UUID] =>
                val s = SchemaBuilder.builder().fixed(fieldName).prop("logicalType", "uuid").size(16)
                LogicalTypes.uuid().addToSchema(s)
                s
              case Some(size) if fieldType =:= typeOf[String] => SchemaBuilder.builder().fixed(fieldName).prop("logicalType", "string").size(size)
              case None if fieldType =:= typeOf[String] => throw new IllegalArgumentException(s"missing fixed size parameterInfo for @Fixed(<int>) $fieldName: $fieldType)")
              case Some(size) if fieldType =:= typeOf[Array[Byte]] => SchemaBuilder.builder().fixed(fieldName).size(size)
              case None if fieldType =:= typeOf[Array[Byte]] => throw new IllegalArgumentException(s"missing fixed size parameterInfo for @Fixed(<int>) $fieldName: $fieldType)")
              case Some(_) => throw new IllegalArgumentException(s"Only fixed string fields can have custom fixed size: @Fixed $fieldName: $fieldType")
              case None => throw new IllegalArgumentException(s"Only int, long and string can be used as fixed fields")
            }
          }.getOrElse {
            if (fieldType =:= typeOf[UUID]) {
              val s = SchemaBuilder.builder().fixed(fieldName).prop("logicalType", "uuid").size(16)
              LogicalTypes.uuid().addToSchema(s)
              s
            } else {
              inferSchemaWithoutCache(symbol.typeSignature)
            }
          }
          val builder = assembler.name(fieldName)
          symbol.annotations.find(_.tree.tpe =:= typeOf[Alias]).foreach {
            a => builder.aliases(a.tree.children.tail.map(_.productElement(0).asInstanceOf[Constant].value.toString): _*)
          }
          symbol.annotations.find(_.tree.tpe =:= typeOf[Doc]).foreach {
            a =>
              val doc = a.tree.children.tail.map(_.productElement(0).asInstanceOf[Constant].value.toString).mkString("\n")
              builder.doc(doc)
          }

          val defaultDef = companionMirror.symbol.typeSignature.member(TermName(s"apply$$default$$${i + 1}"))
          if (defaultDef == NoSymbol) {
            builder.`type`(fieldSchema).noDefault()
          } else {
            val fieldDefaultValue = companionMirror.reflectMethod(defaultDef.asMethod)()
            val extracted = fieldSchema.getType match {
              case Schema.Type.UNION => extract(fieldDefaultValue, fieldSchema.getTypes.asScala.toList)
              case _ => extract(fieldDefaultValue, List(fieldSchema))
            }
            val adaptedFieldSchema = adaptFieldSchema(fieldSchema, extracted)
            builder.`type`(adaptedFieldSchema).withDefault(extracted)
          }
      }
      assembler.endRecord()
    } else {
      throw new IllegalArgumentException("Unsupported scala-avro type " + tpe.toString)
    }
  }

  private def adaptFieldSchema(fieldSchema: Schema, value: AnyRef): Schema = {

    if (fieldSchema.getType == Schema.Type.MAP) {
      if (value == java.util.Collections.emptyMap[String, AnyRef]) {
        fieldSchema
      } else {
        SchemaBuilder.builder().map()
          .values(adaptFieldSchema(fieldSchema.getElementType(), value.asInstanceOf[java.util.Map[String, AnyRef]].values.iterator.next))
      }
    } else if (fieldSchema.getType == Schema.Type.ARRAY) {
      if (value == java.util.Collections.emptyList[AnyRef]) {
        fieldSchema
      } else {
        SchemaBuilder.builder().array()
          .items(adaptFieldSchema(fieldSchema.getElementType(), value.asInstanceOf[java.util.List[AnyRef]].get(0)))
      }
    } else if (fieldSchema.getType == Schema.Type.UNION) {
      val dValSchema = if (value == java.util.Collections.emptyMap[String, AnyRef]) {
        fieldSchema.getTypes.asScala.find(_.getType == Schema.Type.MAP).get
      } else if (value == java.util.Collections.emptyList[AnyRef]) {
        fieldSchema.getTypes.asScala.find(_.getType == Schema.Type.ARRAY).get
      } else {
        inferSchema(value)
      }
      val unionFieldSchema = if (fieldSchema.getTypes.get(0) == dValSchema) {
        fieldSchema
      } else {
        //reorder the fieldSchema such that the schema of the default value is first (this is required by the avro specification regarding union default types)
        fieldSchema.getTypes.asScala
          .filter(_ != dValSchema)
          .foldLeft(SchemaBuilder.builder().unionOf().`type`(dValSchema)) { case (b, st) => b.and().`type`(st) }
          .endUnion()
      }
      unionFieldSchema
    } else {
      fieldSchema
    }
  }

}
