/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package de.silverhorn.fca.maven;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Exclusion;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

//@Mojo(name = "sniff-dependency-versions", defaultPhase = LifecyclePhase.VALIDATE, threadSafe = true)
public class DependencyVersionSnifferPlugin extends AbstractMojo {

   private static final Function<? super Exclusion, String> EXCLUSION_KEY_BUILDER =
         exclusion -> exclusion.getGroupId() + ":" + exclusion.getArtifactId();
   /**
    * Should always be supplied by the Maven runner - provides the Maven project structure + dependency mappings
    */
   @Parameter(property = "project", required = true, readonly = true, defaultValue = "${project}")
   private MavenProject project;
   @Parameter()
   private Dependency[] dependencies;

   public void execute() {
      Map<String, Dependency> presentManagementDependencies = project.getDependencyManagement()
            .getDependencies()
            .stream()
            .collect(Collectors.toMap(Dependency::getManagementKey, d -> d));

      for (Dependency dependency : dependencies) {
         Dependency existingDependency = presentManagementDependencies.get(dependency.getManagementKey());
         Optional<Dependency> extensionalDependency = Optional.of(dependency);
         if (existingDependency != null && existingDependency.getVersion() != null) {
            getLog().info("found managed dependency: " + existingDependency.getManagementKey());
            getLog().info("extend management dependency with: " + buildDependencyStringOf(dependency));
            project.getProperties()
                  .setProperty(
                     dependency.getGroupId() + ":" + dependency.getArtifactId() + ".version",
                     existingDependency.getVersion());
         } else if (dependency.getVersion() == null)
            getLog().warn(
               "No managed dependency found for " + dependency.getManagementKey()
                  + " - ignoring dependency in case of missing version");
      }
   }

   private String buildDependencyStringOf(Dependency dependency) {
      String version, scope, classifier;
      return dependency.getManagementKey() + ((version = dependency.getVersion()) != null ? ":" + version : "") + " {"
         + ((scope = dependency.getScope()) != null ? " scope: " + scope : "")
         + ((classifier = dependency.getClassifier()) != null ? " classifier:" + classifier : "")
         + ((version = dependency.getVersion()) != null ? ":" + version : "")
         + (dependency.getExclusions().isEmpty() ? "" : " exclusions: { "
            + dependency.getExclusions().stream().map(EXCLUSION_KEY_BUILDER).collect(Collectors.joining(", ")) + " }")
         + "}";
   }
}
