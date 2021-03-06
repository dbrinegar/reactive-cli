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
import com.lightbend.rp.reactivecli.annotations.kubernetes.{ ConfigMapEnvironmentVariable, FieldRefEnvironmentVariable, SecretKeyRefEnvironmentVariable }
import com.lightbend.rp.reactivecli.annotations._
import com.lightbend.rp.reactivecli.argparse._

import scala.collection.immutable.Seq
import scala.util.{ Failure, Success, Try }

object Deployment {

  object RpEnvironmentVariables {
    /**
     * Creates pod related environment variables using the Kubernetes Downward API:
     *
     * https://kubernetes.io/docs/tasks/inject-data-application/environment-variable-expose-pod-information/#use-pod-fields-as-values-for-environment-variables
     */
    private val PodEnvs = Map(
      "RP_PLATFORM" -> LiteralEnvironmentVariable("kubernetes"),
      "RP_KUBERNETES_POD_NAME" -> FieldRefEnvironmentVariable("metadata.name"),
      "RP_KUBERNETES_POD_IP" -> FieldRefEnvironmentVariable("status.podIP"))

    /**
     * Generates pod environment variables specific for RP applications.
     */
    def envs(annotations: Annotations, externalServices: Map[String, Seq[String]]): Map[String, EnvironmentVariable] =
      PodEnvs ++
        namespaceEnv(annotations.namespace) ++
        appNameEnvs(annotations.appName) ++
        annotations.version.fold(Map.empty[String, EnvironmentVariable])(versionEnvs) ++
        appTypeEnvs(annotations.appType, annotations.modules) ++
        endpointEnvs(annotations.endpoints) ++
        secretEnvs(annotations.secrets) ++
        externalServicesEnvs(annotations.modules, externalServices)

    private[kubernetes] def namespaceEnv(namespace: Option[String]): Map[String, EnvironmentVariable] =
      namespace.fold(Map.empty[String, EnvironmentVariable])(v => Map("RP_NAMESPACE" -> LiteralEnvironmentVariable(v)))

    private[kubernetes] def appNameEnvs(appName: Option[String]): Map[String, EnvironmentVariable] =
      appName.fold(Map.empty[String, EnvironmentVariable])(v => Map("RP_APP_NAME" -> LiteralEnvironmentVariable(v)))

    private[kubernetes] def appTypeEnvs(appType: Option[String], modules: Set[String]): Map[String, EnvironmentVariable] = {
      appType
        .toVector
        .map("RP_APP_TYPE" -> LiteralEnvironmentVariable(_)) ++ (
          if (modules.isEmpty) Seq.empty else Seq("RP_MODULES" -> LiteralEnvironmentVariable(modules.toVector.sorted.mkString(","))))
    }.toMap

    private[kubernetes] def externalServicesEnvs(modules: Set[String], externalServices: Map[String, Seq[String]]): Map[String, EnvironmentVariable] =
      if (!modules.contains(Module.ServiceDiscovery))
        Map.empty
      else
        Map(
          "RP_JAVA_OPTS" -> LiteralEnvironmentVariable(
            externalServices
              .flatMap {
                case (name, addresses) =>
                  // We allow '/' as that's the convention used: $serviceName/$endpoint
                  // We allow '_' as its currently used for Lagom defaults, i.e. "cas_native"

                  val arguments =
                    for {
                      (address, i) <- addresses.zipWithIndex
                    } yield s"-Drp.service-discovery.external-service-addresses.${serviceName(name, Set('/', '_'))}.$i=$address"

                  arguments
              }
              .mkString(" ")))

    private[kubernetes] def versionEnvs(version: Version): Map[String, EnvironmentVariable] = {
      Map(
        "RP_VERSION" -> LiteralEnvironmentVariable(version.version),
        "RP_VERSION_MAJOR" -> LiteralEnvironmentVariable(version.major.toString),
        "RP_VERSION_MINOR" -> LiteralEnvironmentVariable(version.minor.toString),
        "RP_VERSION_PATCH" -> LiteralEnvironmentVariable(version.patch.toString)) ++
        version.patchLabel.fold(Map.empty[String, LiteralEnvironmentVariable]) { v =>
          Map("RP_VERSION_PATCH_LABEL" -> LiteralEnvironmentVariable(v))
        }
    }

    private[kubernetes] def endpointEnvs(endpoints: Map[String, Endpoint]): Map[String, EnvironmentVariable] =
      if (endpoints.isEmpty)
        Map(
          "RP_ENDPOINTS_COUNT" -> LiteralEnvironmentVariable("0"))
      else
        Map(
          "RP_ENDPOINTS_COUNT" -> LiteralEnvironmentVariable(endpoints.size.toString),
          "RP_ENDPOINTS" -> LiteralEnvironmentVariable(
            endpoints.values.toList
              .sortBy(_.index)
              .map(v => envVarName(v.name))
              .mkString(","))) ++
          endpointPortEnvs(endpoints)

    private[kubernetes] def endpointPortEnvs(endpoints: Map[String, Endpoint]): Map[String, EnvironmentVariable] =
      AssignedPort.assignPorts(endpoints)
        .flatMap { assigned =>
          val assignedPortEnv = LiteralEnvironmentVariable(assigned.port.toString)
          val hostEnv = FieldRefEnvironmentVariable("status.podIP")
          Seq(
            s"RP_ENDPOINT_${envVarName(assigned.endpoint.name)}_HOST" -> hostEnv,
            s"RP_ENDPOINT_${envVarName(assigned.endpoint.name)}_BIND_HOST" -> hostEnv,
            s"RP_ENDPOINT_${envVarName(assigned.endpoint.name)}_PORT" -> assignedPortEnv,
            s"RP_ENDPOINT_${envVarName(assigned.endpoint.name)}_BIND_PORT" -> assignedPortEnv,
            s"RP_ENDPOINT_${assigned.endpoint.index}_HOST" -> hostEnv,
            s"RP_ENDPOINT_${assigned.endpoint.index}_BIND_HOST" -> hostEnv,
            s"RP_ENDPOINT_${assigned.endpoint.index}_PORT" -> assignedPortEnv,
            s"RP_ENDPOINT_${assigned.endpoint.index}_BIND_PORT" -> assignedPortEnv)
        }
        .toMap

    private[kubernetes] def secretEnvs(secrets: Seq[Secret]): Map[String, EnvironmentVariable] =
      secrets
        .map { secret =>
          val envName = secretEnvName(secret.namespace, secret.name)
          val envValue = SecretKeyRefEnvironmentVariable(secret.namespace, secret.name)

          envName -> envValue
        }
        .toMap

    private[kubernetes] def secretEnvName(namespace: String, name: String): String =
      s"RP_SECRETS_${namespace}_$name"
        .toUpperCase
        .map(c => if (c.isLetterOrDigit) c else '_')
  }

  /**
   * Represents possible values for imagePullPolicy field within the Kubernetes deployment resource.
   */
  object ImagePullPolicy extends Enumeration {
    val Never, IfNotPresent, Always = Value
  }

  private[kubernetes] val VersionSeparator = "-v"

  implicit def imagePullPolicyEncode = EncodeJson[ImagePullPolicy.Value] {
    case ImagePullPolicy.Never => "Never".asJson
    case ImagePullPolicy.IfNotPresent => "IfNotPresent".asJson
    case ImagePullPolicy.Always => "Always".asJson
  }

  implicit def checkPortNumberEncode = EncodeJson[Check.PortNumber](_.value.asJson)

  implicit def checkServiceNumberEncode = EncodeJson[Check.ServiceName](_.value.asJson)

  implicit def checkPortEncode = EncodeJson[Check.Port] {
    case v: Check.PortNumber => v.asJson
    case v: Check.ServiceName => v.asJson
  }

  implicit def commandCheckEncode = EncodeJson[CommandCheck] { check =>
    Json(
      "exec" -> Json(
        "command" -> check.command.toList.asJson))
  }

  implicit def tcpCheckEncode = EncodeJson[TcpCheck] { check =>
    Json(
      "tcpSocket" -> Json(
        "port" -> check.port.asJson),
      "periodSeconds" -> check.intervalSeconds.asJson)
  }

  implicit def httpCheckEncode = EncodeJson[HttpCheck] { check =>
    Json(
      "httpGet" -> Json(
        "path" -> check.path.asJson,
        "port" -> check.port.asJson),
      "periodSeconds" -> check.intervalSeconds.asJson)
  }

  implicit def checkEncode = EncodeJson[Check] {
    case v: CommandCheck => v.asJson
    case v: TcpCheck => v.asJson
    case v: HttpCheck => v.asJson
  }

  def readinessProbeEncode = EncodeJson[Option[Check]] {
    case Some(check) => Json("readinessProbe" -> check.asJson)
    case _ => jEmptyObject
  }

  def livenessProbeEncode = EncodeJson[Option[Check]] {
    case Some(check) => Json("livenessProbe" -> check.asJson)
    case _ => jEmptyObject
  }

  implicit def literalEnvironmentVariableEncode = EncodeJson[LiteralEnvironmentVariable] { env =>
    Json("value" -> env.value.asJson)
  }

  implicit def fieldRefEnvironmentVariableEncode = EncodeJson[FieldRefEnvironmentVariable] { env =>
    Json(
      "valueFrom" -> Json(
        "fieldRef" -> Json(
          "fieldPath" -> env.fieldPath.asJson)))
  }

  implicit def secretKeyRefEnvironmentVariableEncode = EncodeJson[SecretKeyRefEnvironmentVariable] { env =>
    Json(
      "valueFrom" -> Json(
        "secretKeyRef" -> Json(
          "name" -> env.name.asJson,
          "key" -> env.key.asJson)))
  }

  implicit def configMapEnvironmentVariableEncode = EncodeJson[ConfigMapEnvironmentVariable] { env =>
    Json(
      "valueFrom" -> Json(
        "configMapKeyRef" -> Json(
          "name" -> env.mapName.asJson,
          "key" -> env.key.asJson)))
  }

  implicit def environmentVariableEncode = EncodeJson[EnvironmentVariable] {
    case v: LiteralEnvironmentVariable => v.asJson
    case v: FieldRefEnvironmentVariable => v.asJson
    case v: ConfigMapEnvironmentVariable => v.asJson
    case v: SecretKeyRefEnvironmentVariable => v.asJson
  }

  implicit def environmentVariablesEncode = EncodeJson[Map[String, EnvironmentVariable]] { envs =>
    envs
      .toList
      .sortBy(_._1)
      .map {
        case (envName, env) =>
          Json("name" -> envName.asJson).deepmerge(env.asJson)
      }
      .asJson
  }

  implicit def assignedEncode = EncodeJson[AssignedPort] { assigned =>
    Json(
      "containerPort" -> assigned.port.asJson,
      "name" -> serviceName(assigned.endpoint.name).asJson)
  }

  implicit def endpointsEncode = EncodeJson[Map[String, Endpoint]] { endpoints =>
    AssignedPort.assignPorts(endpoints)
      .toList
      .sortBy(_.endpoint.index)
      .map(_.asJson)
      .asJson
  }

  /**
   * Builds [[Deployment]] resource.
   */
  def generate(
    annotations: Annotations,
    apiVersion: String,
    imageName: String,
    imagePullPolicy: ImagePullPolicy.Value,
    noOfReplicas: Int,
    externalServices: Map[String, Seq[String]],
    deploymentType: DeploymentType): Try[Deployment] =
    (annotations.appName, annotations.version) match {
      case (Some(rawAppName), Some(version)) =>
        // FIXME there's a bit of code duplicate in Service
        val appName = serviceName(rawAppName)
        val appVersionMajor = serviceName(s"$appName$VersionSeparator${version.major}")
        val appVersionMajorMinor = serviceName(s"$appName$VersionSeparator${version.versionMajorMinor}")
        val appVersion = serviceName(s"$appName$VersionSeparator${version.version}")

        val (deploymentName, deploymentLabels, deploymentMatchLabels) =
            deploymentType match {
              case CanaryDeploymentType | BlueGreenDeploymentType =>
                (
                  appVersion,
                  Json(
                    "app" -> appName.asJson,
                    "appVersionMajor" -> appVersionMajor.asJson,
                    "appVersionMajorMinor" -> appVersionMajorMinor.asJson,
                    "appVersion" -> appVersion.asJson),
                  Json("appVersionMajorMinor" -> appVersionMajorMinor.asJson))

              case RollingDeploymentType   =>
                (
                  appName,
                  Json("app" -> appName.asJson),
                  Json("app" -> appName.asJson))
            }

        Success(
          Deployment(
            deploymentName,
            Json(
              "apiVersion" -> apiVersion.asJson,
              "kind" -> "Deployment".asJson,
              "metadata" -> Json(
                "name" -> deploymentName.asJson,
                "labels" -> deploymentLabels)
                .deepmerge(
                  annotations.namespace.fold(jEmptyObject)(ns => Json("namespace" -> serviceName(ns).asJson))),
              "spec" -> Json(
                "replicas" -> noOfReplicas.asJson,
                "selector" -> Json("matchLabels" -> deploymentMatchLabels),
                "template" -> Json(
                  "metadata" -> Json("labels" -> deploymentLabels),
                  "spec" -> Json(
                    "containers" -> List(
                      Json(
                        "name" -> appName.asJson,
                        "image" -> imageName.asJson,
                        "imagePullPolicy" -> imagePullPolicy.asJson,
                        "env" -> (annotations.environmentVariables ++ RpEnvironmentVariables.envs(annotations, externalServices)).asJson,
                        "ports" -> annotations.endpoints.asJson)
                        .deepmerge(annotations.readinessCheck.asJson(readinessProbeEncode))
                        .deepmerge(annotations.healthCheck.asJson(livenessProbeEncode))).asJson))))))
      case _ =>
        Failure(new IllegalArgumentException("Unable to generate Kubernetes Deployment: both application name and version are required"))
    }
}

/**
 * Represents the generated Kubernetes deployment resource.
 */
case class Deployment(name: String, payload: Json) extends GeneratedKubernetesResource {
  val resourceType = "deployment"
}
