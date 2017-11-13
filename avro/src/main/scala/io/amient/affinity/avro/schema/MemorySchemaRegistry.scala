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

package io.amient.affinity.avro.schema

import java.util.concurrent.ConcurrentHashMap

import io.amient.affinity.avro.AvroSerde
import org.apache.avro.{Schema, SchemaValidatorBuilder}

import scala.collection.JavaConversions._

class MemorySchemaRegistry extends AvroSerde with AvroSchemaProvider {

  protected val internal = new ConcurrentHashMap[Int, (Class[_], Schema)]()

  private val validator = new SchemaValidatorBuilder().canReadStrategy().validateLatest()

  override private[schema] def registerSchema(cls: Class[_], schema: Schema, existing: List[Schema]): Int = synchronized {
    validator.validate(schema, existing)
    val equivalent = internal.filter(_._2 == (cls, schema))
    if (equivalent.isEmpty) {
      val newId = internal.size
      internal.put(newId, (cls, schema))
      newId
    } else {
      equivalent.keys.max
    }
  }

  override private[schema] def getAllRegistered: List[(Int, Schema)] = {
    internal.mapValues(_._2).toList
  }

  override private[schema] def hypersynchronized[X](f: => X): X = synchronized(f)

}
