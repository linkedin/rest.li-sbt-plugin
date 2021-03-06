/*
   Copyright (c) 2018 LinkedIn Corp.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
package com.linkedin.sbtrestli

import java.io.File

import com.linkedin.restli.internal.common.RestliVersion
import com.linkedin.restli.tools.clientgen.RestRequestBuilderGenerator
import org.apache.logging.log4j.{Level => XLevel}
import sbt.Keys._
import sbt._
import sbt.plugins.JvmPlugin

import scala.collection.JavaConverters._

object RestliClientPlugin extends AutoPlugin {
  object autoImport {
    val restliClientApi = settingKey[ProjectReference]("API project containing resource idl files.")
    val restliClientDefaultPackage = settingKey[String]("Default package for client bindings.")
    val restliClientGenerate = taskKey[Seq[File]]("Generates client bindings from API project.")
    val restliClientPackage = taskKey[File]("Packages restli client bindings into *-rest-client.jar")

    val restliClientDefaults: Seq[Def.Setting[_]] = Seq(
      restliClientApi := thisProjectRef.value,
      restliClientDefaultPackage := ""
    )

    val restliClientSettings: Seq[Def.Setting[_]] = Seq(
      includeFilter in restliClientGenerate := "*.restspec.json",
      excludeFilter in restliClientGenerate := HiddenFileFilter,

      sourceDirectory in restliClientGenerate := Def.settingDyn {
        val apiProject = restliClientApi.value
        Def.setting {
          (sourceDirectory in apiProject).value / "idl"
        }
      }.value,
      sourceDirectories in restliClientGenerate := Seq((sourceDirectory in restliClientGenerate).value),
      sourceDirectories ++= (sourceDirectories in restliClientGenerate).value,

      sources in restliClientGenerate := Defaults.collectFiles(
        sourceDirectories in restliClientGenerate,
        includeFilter in restliClientGenerate,
        excludeFilter in restliClientGenerate).value,

      target in restliClientGenerate := file(sourceDirectory.value + "GeneratedRest") / "java",

      PluginCompat.watchSourcesSetting(restliClientGenerate),
      PluginCompat.cleanFilesSetting(restliClientGenerate),

      restliClientGenerate := generate.value,

      sourceGenerators += restliClientGenerate.taskValue,
      managedSourceDirectories += (target in restliClientGenerate).value,
      exportedProducts ++= (sourceDirectories in restliClientGenerate).value,

      artifactClassifier in restliClientPackage := Some("rest-client"),
      publishArtifact in restliClientPackage := true,

      packagedArtifacts in Defaults.ConfigGlobal ++= Classpaths.packaged(Seq(restliClientPackage)).value,
      artifacts in Defaults.ConfigGlobal ++= Classpaths.artifactDefs(Seq(restliClientPackage)).value
    ) ++ Defaults.packageTaskSettings(restliClientPackage, mappings in packageBin)
  }

  import autoImport._

  override def requires: Plugins = JvmPlugin

  override def projectSettings: Seq[Def.Setting[_]] = restliClientDefaults ++
    inConfig(Compile)(restliClientSettings) ++ inConfig(Test)(restliClientSettings)

  private lazy val generate = Def.task {
    val resolverFiles = sourceDirectories.value ++
      managedClasspath.value.files ++
      internalDependencyClasspath.value.files
    val resolverPath = resolverFiles.map(_.getAbsolutePath).mkString(File.pathSeparator)
    val targetDir = (target in restliClientGenerate).value
    val log = streams.value.log

    PluginCompat.setLogLevel(classOf[RestRequestBuilderGenerator].getName, XLevel.WARN)

    targetDir.mkdirs()
    val generatorResult = RestRequestBuilderGenerator.run(
      resolverPath,
      restliClientDefaultPackage.value,
      baseDirectory.value.getAbsolutePath,
      false,
      false,
      RestliVersion.RESTLI_2_0_0,
      null,
      targetDir.getAbsolutePath,
      (sources in restliClientGenerate).value.map(_.getAbsolutePath).toArray
    )

    // RestRequestBuilderGenerator does internal caching. Only print message if files were generated
    val count = generatorResult.getModifiedFiles.size
    if (count > 0) {
      val plural = if (count == 1) "" else "s"
      log.info(s"Generated $count rest request builder$plural in $targetDir")
    }

    generatorResult.getTargetFiles.asScala.toSeq
  }

}
