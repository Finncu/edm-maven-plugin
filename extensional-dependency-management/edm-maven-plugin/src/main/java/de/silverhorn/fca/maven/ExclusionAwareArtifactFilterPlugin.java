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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Exclusion;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

/**
 * Filters the resolved artifact set of the project by removing any artifact that is transitively
 * excluded via the configured {@code <dependencies>} exclusion rules.
 *
 * <p>
 * This Mojo is the second line of defence complementing
 * {@link ExtensionalDependencyManagementPlugin}. While the first Mojo injects exclusions into
 * {@code <dependencyManagement>} at the model level (which governs the dependency tree and the
 * Enforcer plugin), this Mojo operates on the already-resolved artifact set
 * ({@code project.getArtifacts()}) that packaging tools such as {@code maven-shade-plugin} and
 * {@code maven-assembly-plugin} consume directly.
 * </p>
 *
 * <p>
 * The two Mojos share the same {@code <configuration>} block; no additional configuration is
 * required. Bind both goals in a single execution block:
 * </p>
 *
 * <pre>
 * &lt;execution&gt;
 *   &lt;id&gt;extensional-dependency-exclusions&lt;/id&gt;
 *   &lt;goals&gt;
 *     &lt;goal&gt;extend-dependency-management&lt;/goal&gt;
 *     &lt;goal&gt;filter-resolved-artifacts&lt;/goal&gt;
 *   &lt;/goals&gt;
 *   &lt;configuration&gt;
 *     &lt;dependencies&gt;
 *       &lt;dependency&gt;
 *         &lt;groupId&gt;io.undertow&lt;/groupId&gt;
 *         &lt;artifactId&gt;undertow-core&lt;/artifactId&gt;
 *         &lt;exclusions&gt;
 *           &lt;exclusion&gt;
 *             &lt;groupId&gt;org.jboss.xnio&lt;/groupId&gt;
 *             &lt;artifactId&gt;xnio-nio&lt;/artifactId&gt;
 *           &lt;/exclusion&gt;
 *         &lt;/exclusions&gt;
 *       &lt;/dependency&gt;
 *     &lt;/dependencies&gt;
 *   &lt;/configuration&gt;
 * &lt;/execution&gt;
 * </pre>
 *
 * <p>
 * The Mojo runs in the {@code process-resources} phase so that the Maven dependency resolver has
 * already populated {@code project.getArtifacts()} before this Mojo executes, while still running
 * early enough to affect every subsequent packaging goal.
 * </p>
 */
@Mojo(
      name = "filter-resolved-artifacts",
      defaultPhase = LifecyclePhase.PROCESS_RESOURCES,
      requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
      threadSafe = true)
public class ExclusionAwareArtifactFilterPlugin extends AbstractMojo {

   /** Maven project injected by the runtime. */
   @Parameter(property = "project", required = true, readonly = true, defaultValue = "${project}")
   private MavenProject project;

   /**
    * The same {@code <dependencies>} array used by {@link ExtensionalDependencyManagementPlugin}.
    * Each entry's {@code <exclusions>} list is collected into a global exclusion set that is
    * applied to every resolved artifact.
    */
   @Parameter
   private Dependency[] dependencies;

   @Override
   public void execute() throws MojoExecutionException {
      if (dependencies == null || dependencies.length == 0) {
         getLog().debug("filter-resolved-artifacts: no dependencies configured, skipping");
         return;
      }

      // Collect every exclusion declared across all configured dependencies into a flat set.
      // We intentionally treat them as global: if groupId:artifactId appears in *any*
      // exclusion list it must not end up in the resolved artifact set.
      Set<String> globalExclusionKeys = Arrays.stream(dependencies)
            .flatMap(dep -> dep.getExclusions().stream())
            .map(excl -> excl.getGroupId() + ":" + excl.getArtifactId())
            .collect(Collectors.toSet());

      if (globalExclusionKeys.isEmpty()) {
         getLog().debug("filter-resolved-artifacts: no exclusions found in configuration, skipping");
         return;
      }

      getLog().debug("filter-resolved-artifacts: global exclusion keys = " + globalExclusionKeys);

      // --- filter project.getArtifacts() ---
      // This set is what shade/assembly/war plugins read when packaging the final artifact.
      Set<Artifact> originalArtifacts = project.getArtifacts();
      if (originalArtifacts != null && !originalArtifacts.isEmpty()) {
         Set<Artifact> filtered = originalArtifacts.stream()
               .filter(artifact -> !globalExclusionKeys.contains(
                     artifact.getGroupId() + ":" + artifact.getArtifactId()))
               .collect(Collectors.toSet());

         int removed = originalArtifacts.size() - filtered.size();
         if (removed > 0) {
            logRemovedArtifacts(originalArtifacts, filtered);
            project.setArtifacts(filtered);
         }
      }

      // --- filter project.getDependencyArtifacts() ---
      // This narrower set contains only the *direct* dependency artifacts and is also consulted
      // by some packaging tools (e.g. maven-jar-plugin's Class-Path manifest entry generation).
      Set<Artifact> originalDepArtifacts = project.getDependencyArtifacts();
      if (originalDepArtifacts != null && !originalDepArtifacts.isEmpty()) {
         Set<Artifact> filteredDep = originalDepArtifacts.stream()
               .filter(artifact -> !globalExclusionKeys.contains(
                     artifact.getGroupId() + ":" + artifact.getArtifactId()))
               .collect(Collectors.toSet());

         int removed = originalDepArtifacts.size() - filteredDep.size();
         if (removed > 0) {
            project.setDependencyArtifacts(filteredDep);
         }
      }
   }

   /**
    * Logs the artifacts that were removed at DEBUG level (groupId:artifactId:version) and at INFO
    * level as a summary count.
    */
   private void logRemovedArtifacts(Set<Artifact> before, Set<Artifact> after) {
      Set<String> afterKeys = after.stream()
            .map(a -> a.getGroupId() + ":" + a.getArtifactId())
            .collect(Collectors.toSet());

      List<String> removed = before.stream()
            .filter(a -> !afterKeys.contains(a.getGroupId() + ":" + a.getArtifactId()))
            .map(a -> a.getGroupId() + ":" + a.getArtifactId() + ":" + a.getVersion())
            .sorted()
            .collect(Collectors.toList());

      getLog().info("filter-resolved-artifacts: removed " + removed.size()
            + " excluded artifact(s) from resolved set:");
      removed.forEach(gav -> getLog().info("  - " + gav));
   }
}
