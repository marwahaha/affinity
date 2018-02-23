/*
 * Copyright 2016-2018 Michal Harish, michal.harish@gmail.com
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

package io.amient

import com.typesafe.config.Config
import io.amient.affinity.avro.record.AvroSerde.AvroConf
import io.amient.affinity.core.actor.Keyspace.KeyspaceConf
import io.amient.affinity.core.cluster.Coordinator.CoorinatorConf
import io.amient.affinity.core.cluster.Node.NodeConf
import io.amient.affinity.core.config._
import io.amient.affinity.core.storage.StateConf

package object affinity {

  object Conf extends Conf {
    override def apply(config: Config): Conf = new Conf().apply(config)
  }

  class Conf extends CfgStruct[Conf](Cfg.Options.IGNORE_UNKNOWN) {
    val Akka: AkkaConf = struct("akka", new AkkaConf)
    val Affi: AffinityConf = struct("affinity", new AffinityConf)
  }

  class AkkaConf extends CfgStruct[AkkaConf](Cfg.Options.IGNORE_UNKNOWN) {
    val Hostname: CfgString = string("remote.netty.tcp.hostname", false)
    val Port: CfgInt = integer("remote.netty.tcp.port", false)
  }

  class AffinityConf extends CfgStruct[AffinityConf] {
    val Avro: AvroConf = struct("avro", new AvroConf())
    val Coordinator: CoorinatorConf = struct("coordinator", new CoorinatorConf)
    val Keyspace: CfgGroup[KeyspaceConf] = group("keyspace", classOf[KeyspaceConf], false)
    val Global: CfgGroup[StateConf] = group("global", classOf[StateConf], false)
    val Node = struct("node", new NodeConf)
  }

}
