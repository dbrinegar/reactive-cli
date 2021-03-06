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

package com.lightbend.rp.reactivecli.runtime

import argonaut.PrettyParams
import com.lightbend.rp.reactivecli.annotations.Annotations
import com.lightbend.rp.reactivecli.argparse.GenerateDeploymentArgs
import com.lightbend.rp.reactivecli.argparse.kubernetes.KubernetesArgs
import com.lightbend.rp.reactivecli.docker.Config
import java.io.PrintStream
import java.nio.file.{ Files, Path }
import scala.util.{ Failure, Success, Try }
import slogging.LazyLogging

package object kubernetes extends LazyLogging {
  /**
   * Valid characters for endpoint environment variable name.
   * The declared endpoint name will be made uppercase, and all characters outside the valid chars range will be
   * swapped with '_'.
   */
  private val ValidEndpointChars =
    (('0' to '9') ++ ('A' to 'Z') ++ ('a' to 'z') ++ Seq('_')).toSet

  /**
   * Valid characters for endpoint service name. The name will be made lowercase, and all characters outside the
   * valid chars range with be swapped with '-'.
   */
  private val ValidEndpointServiceChars =
    ValidEndpointChars - '_' + '-'

  /**
   * These characters will be trimmed from both sides of the string.
   */
  private val EndpointTrimChars = Set('_', '-')

  /**
   * This is the main method which generates the Kubernetes resources.
   */
  def generateResources(config: Config, generateDeploymentArgs: GenerateDeploymentArgs, kubernetesArgs: KubernetesArgs): Try[Seq[GeneratedKubernetesResource]] = {

    def getLabel(config: Config): Try[Map[String, String]] =
      config.config.Labels match {
        case Some(v) if v.nonEmpty => Success(v)
        case _ => Failure(new IllegalArgumentException("Unable to generate Kubernetes resources from empty labels."))
      }

    for {
      label <- getLabel(config)

      annotations = Annotations(label, generateDeploymentArgs)

      namespace <- Namespace.generate(annotations, KubernetesArgs.DefaultNamespaceApiVersion)

      deployment <- Deployment.generate(
        annotations,
        kubernetesArgs.podControllerArgs.apiVersion,
        generateDeploymentArgs.dockerImage.get,
        kubernetesArgs.podControllerArgs.imagePullPolicy,
        kubernetesArgs.podControllerArgs.numberOfReplicas,
        generateDeploymentArgs.externalServices,
        generateDeploymentArgs.deploymentType)

      service <- Service.generate(annotations, kubernetesArgs.serviceArgs.apiVersion, kubernetesArgs.serviceArgs.clusterIp, generateDeploymentArgs.deploymentType)

      ingress <- Ingress.generate(
        annotations,
        kubernetesArgs.ingressArgs.apiVersion,
        kubernetesArgs.ingressArgs.ingressAnnotations,
        kubernetesArgs.ingressArgs.pathAppend)
    } yield namespace.filter(_ => kubernetesArgs.shouldGenerateNamespaces).toSeq ++
      Seq(deployment).filter(_ => kubernetesArgs.shouldGeneratePodControllers) ++
      service.toSeq.filter(_ => kubernetesArgs.shouldGenerateServices) ++
      ingress.toSeq.filter(_ => kubernetesArgs.shouldGenerateIngress)
  }

  /**
   * Accepts the instructions supplied by the `output`, and returns the appropriate function to handle the
   * generated Kubernetes resources.
   */
  def handleGeneratedResources(output: KubernetesArgs.Output): Seq[GeneratedKubernetesResource] => Unit =
    output match {
      case KubernetesArgs.Output.PipeToStream(out) => pipeToStream(out)
      case KubernetesArgs.Output.SaveToFile(path) => saveToFile(path)
    }

  private[kubernetes] def saveToFile(path: Path)(generatedResources: Seq[GeneratedKubernetesResource]): Unit = {
    logger.debug(s"Saving to ${path.toAbsolutePath}")
    if (!Files.exists(path)) {
      Files.createDirectories(path)
    }

    generatedResources.foreach { r =>
      val fileName = s"${r.resourceType}-${r.name}.json"
      val file = path.resolve(fileName)
      val formattedJson = r.payload.pretty(PrettyParams.spaces2.copy(preserveOrder = true))

      logger.debug(fileName)
      Files.deleteIfExists(file)
      Files.write(file, formattedJson.getBytes)
    }

    logger.debug("Done!")
  }

  private[kubernetes] def pipeToStream(out: PrintStream)(generatedResources: Seq[GeneratedKubernetesResource]): Unit =
    generatedResources.foreach { r =>
      val formattedJson = r.payload.pretty(PrettyParams.spaces2.copy(preserveOrder = true))
      out.println("---")
      out.println(formattedJson)
    }

  private[kubernetes] def envVarName(name: String): String =
    name
      .map(c => if (ValidEndpointChars.contains(c)) c else '_')
      .dropWhile(EndpointTrimChars.contains)
      .reverse
      .dropWhile(EndpointTrimChars.contains)
      .reverse
      .toUpperCase

  private[kubernetes] def serviceName(name: String, additionalChars: Set[Char] = Set.empty): String =
    name
      .map(c => if (ValidEndpointServiceChars.contains(c) || additionalChars.contains(c)) c else '-')
      .dropWhile(EndpointTrimChars.contains)
      .reverse
      .dropWhile(EndpointTrimChars.contains)
      .reverse
      .toLowerCase
}
