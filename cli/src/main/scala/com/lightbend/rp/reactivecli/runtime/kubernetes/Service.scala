/*
 * Copyright 2017 Lightbend, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.lightbend.rp.reactivecli.runtime.kubernetes

import argonaut._
import Argonaut._
import com.lightbend.rp.reactivecli.annotations._
import com.lightbend.rp.reactivecli.argparse._
import scala.util.{ Failure, Success, Try }

object Service {
  def encodeEndpoint(endpoint: Endpoint, port: AssignedPort): Json = {
    val protocol = endpoint match {
      case v: HttpEndpoint => "TCP"
      case v: TcpEndpoint => "TCP"
      case v: UdpEndpoint => "UDP"
    }

    Json(
      "name" -> serviceName(endpoint.name).asJson,
      "port" -> port.port.asJson,
      "protocol" -> protocol.asJson,
      "targetPort" -> port.port.asJson)
  }

  implicit def encodeEndpoints = EncodeJson[Map[String, Endpoint]] { endpoints =>
    val ports = AssignedPort.assignPorts(endpoints)
    val encoded =
      for {
        (name, endpoint) <- endpoints
        port <- ports.find(_.endpoint == endpoint)
      } yield encodeEndpoint(endpoint, port)

    encoded.toList.asJson
  }

  /**
   * Generates the [[Service]] resource.
   */
  def generate(annotations: Annotations,
               apiVersion: String,
               clusterIp: Option[String],
               deploymentType: DeploymentType): Try[Option[Service]] =
    if (annotations.endpoints.isEmpty)
      Success(None)
    else
      (annotations.appName, annotations.version) match {
        case (Some(rawAppName), Some(version)) =>
          // FIXME there's a bit of code duplicate in Deployment
          val appName = serviceName(rawAppName)
          val appVersion = serviceName(s"$appName${Deployment.VersionSeparator}${version.version}")

          val selector =
            deploymentType match {
              case CanaryDeploymentType    => "app" -> appName.asJson
              case RollingDeploymentType   => "app" -> appName.asJson
              case BlueGreenDeploymentType => "appVersion" -> appVersion.asJson
            }

          Success(
            Some(
              Service(
                appName,
                Json(
                  "apiVersion" -> apiVersion.asJson,
                  "kind" -> "Service".asJson,
                  "metadata" -> Json(
                    "labels" -> Json(
                      "app" -> appName.asJson),
                    "name" -> appName.asJson)
                    .deepmerge(
                      annotations.namespace.fold(jEmptyObject)(ns => Json("namespace" -> serviceName(ns).asJson))),
                  "spec" -> Json(
                    "clusterIP" -> clusterIp.getOrElse("None").asJson,
                    "ports" -> annotations.endpoints.asJson,
                    "selector" -> Json(selector))))))
        case _ =>
          Failure(new IllegalArgumentException("Unable to generate Service resource: application name is required"))
      }

}

/**
 * Represents the generated Kubernetes service resource.
 */
case class Service(name: String, payload: Json) extends GeneratedKubernetesResource {
  val resourceType = "service"
}
