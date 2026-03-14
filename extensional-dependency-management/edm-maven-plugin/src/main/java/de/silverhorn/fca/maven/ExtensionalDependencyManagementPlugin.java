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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Exclusion;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * Extends the existing dependency management definitions by adding exclusions (or other attributes) to dependencies
 * already present in the project's {@code <dependencyManagement>} section.
 *
 * <p>
 * This allows configuration of exclusions via plugin parameters instead of modifying the primary
 * {@code <dependencyManagement>} block directly.
 * </p>
 *
 * <h2>Why two propagation targets?</h2>
 *
 * <p>
 * Maven's dependency resolver does <em>not</em> honour exclusions placed inside
 * {@code <dependencyManagement>}. Exclusions are only evaluated when they appear on a
 * <em>direct</em> {@code <dependency>} declaration in the {@code <dependencies>} section.
 * Therefore this Mojo performs two complementary operations:
 * </p>
 * <ol>
 *   <li><strong>DependencyManagement propagation</strong> – adds the exclusions to the managed
 *       dependency entry so that child modules that inherit this POM will also carry the
 *       exclusion on their direct declarations (once the resolver picks up the managed entry).</li>
 *   <li><strong>Direct-dependency propagation</strong> – for every direct dependency of the
 *       current project whose {@code groupId:artifactId} matches a configured entry, the
 *       exclusions are also added directly to that {@code <dependency>} element.  This is the
 *       path that actually causes the Maven resolver to drop the excluded transitive artifact
 *       from {@code project.getArtifacts()}, which in turn prevents packaging tools such as
 *       {@code maven-shade-plugin} from including it in the final JAR/WAR.</li>
 * </ol>
 *
 * <pre>
 * &lt;dependencies&gt;
 *    &lt;dependency&gt;
 *       &lt;groupId&gt;...&lt;/groupId&gt;
 *       &lt;artifactId&gt;...&lt;/artifactId&gt;
 *       ...
 *       &lt;exclusions&gt;
 *          &lt;exclusion&gt;
 *             &lt;groupId&gt;A&lt;/groupId&gt;
 *             &lt;artifactId&gt;B&lt;/artifactId&gt;
 *          &lt;/exclusion&gt;
 *       &lt;/exclusions&gt;
 *    &lt;/dependency&gt;
 * &lt;/dependencies&gt;
 * </pre>
 */
@Mojo(name = "extend-dependency-management", defaultPhase = LifecyclePhase.VALIDATE, threadSafe = true)
public class ExtensionalDependencyManagementPlugin extends AbstractMojo {

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
      // --- Step 1: DependencyManagement propagation (existing behaviour) ---
      Map<String, Dependency> presentManagementDependencies = project.getDependencyManagement()
            .getDependencies()
            .stream()
            .collect(Collectors.toMap(Dependency::getManagementKey, d -> d));

      for (Dependency dependency : dependencies) {
         Dependency existingDependency = presentManagementDependencies.get(dependency.getManagementKey());
         Optional<Dependency> extensionalDependency = Optional.of(dependency);
         if (existingDependency != null) {
            getLog().info("found managed dependency: " + existingDependency.getManagementKey());
            getLog().info("extend management dependency with: " + buildDependencyStringOf(dependency));
            extensionalDependency.map(Dependency::getExclusions).ifPresent(existingDependency.getExclusions()::addAll);
            extensionalDependency.map(Dependency::getScope).ifPresent(existingDependency::setScope);
            extensionalDependency.map(Dependency::getClassifier).ifPresent(existingDependency::setClassifier);
         } else if (dependency.getVersion() == null) {
            getLog().warn(
               "No managed dependency found for " + dependency.getManagementKey()
                  + " - ignoring dependency in case of missing version");
         } else {
            getLog().info(
               "No managed dependency found for " + dependency.getManagementKey() + " - using dependency in version"
                  + dependency.getVersion());
            project.getDependencyManagement().addDependency(dependency);
         }
      }

      // --- Step 2: Direct-dependency propagation (NEW) ---
      //
      // Maven's resolver only honours <exclusions> on *direct* <dependency> entries, not on
      // <dependencyManagement> entries.  We therefore also inject the configured exclusions into
      // every matching direct dependency of the current project so that the resolver actually
      // drops the excluded transitive artifact from project.getArtifacts().
      propagateExclusionsToDirectDependencies();
   }

   /**
    * For each configured dependency that carries exclusions, find the matching entry in
    * {@code project.getDependencies()} (the direct dependencies of this module) and add the
    * exclusions there as well.
    *
    * <p>
    * The match is performed on {@code groupId:artifactId} only (ignoring version and classifier)
    * because the direct dependency entry typically does not repeat the version when it is managed.
    * </p>
    */
   private void propagateExclusionsToDirectDependencies() {
      // Build a lookup: groupId:artifactId -> configured exclusions to add
      // Only process entries that actually carry exclusions.
      Map<String, List<Exclusion>> exclusionsByGav = java.util.Arrays.stream(dependencies)
            .filter(d -> !d.getExclusions().isEmpty())
            .collect(Collectors.toMap(
                  d -> d.getGroupId() + ":" + d.getArtifactId(),
                  Dependency::getExclusions,
                  // merge lists if the same groupId:artifactId appears more than once
                  (a, b) -> { a.addAll(b); return a; }));

      if (exclusionsByGav.isEmpty()) {
         return;
      }

      List<Dependency> directDependencies = project.getDependencies();
      if (directDependencies == null || directDependencies.isEmpty()) {
         return;
      }

      for (Dependency direct : directDependencies) {
         String key = direct.getGroupId() + ":" + direct.getArtifactId();
         List<Exclusion> toAdd = exclusionsByGav.get(key);
         if (toAdd == null || toAdd.isEmpty()) {
            continue;
         }

         // Avoid duplicate exclusions
         Set<String> existingExclusionKeys = direct.getExclusions().stream()
               .map(EXCLUSION_KEY_BUILDER)
               .collect(Collectors.toSet());

         List<Exclusion> newExclusions = toAdd.stream()
               .filter(e -> !existingExclusionKeys.contains(EXCLUSION_KEY_BUILDER.apply(e)))
               .collect(Collectors.toList());

         if (!newExclusions.isEmpty()) {
            direct.getExclusions().addAll(newExclusions);
            getLog().info("propagated exclusions to direct dependency " + key + ": "
                  + newExclusions.stream().map(EXCLUSION_KEY_BUILDER).collect(Collectors.joining(", ")));
         }
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
