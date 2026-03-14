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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Exclusion;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maven build extension that propagates configured dependency exclusions to every project in the
 * reactor <em>before</em> the dependency resolver runs.
 *
 * <h2>Motivation</h2>
 *
 * <p>
 * Maven's {@code <dependencyManagement>} section does not honour {@code <exclusions>} — exclusions
 * are only evaluated when they appear on a <em>direct</em> {@code <dependency>} declaration.
 * This means that a parent POM cannot centrally exclude a transitive artifact for all child
 * modules via {@code <dependencyManagement>} alone.
 * </p>
 *
 * <h2>How it works</h2>
 *
 * <p>
 * This extension hooks into {@link #afterProjectsRead(MavenSession)} which is called after all
 * POMs have been read and the project graph has been built, but <em>before</em> any dependency
 * resolution takes place. At this point the extension:
 * </p>
 * <ol>
 *   <li>Scans every project in the reactor for a configuration of
 *       {@code de.silverhorn.fca.maven:edm-maven-plugin} that contains a
 *       {@code <dependencies>} block with {@code <exclusions>}.</li>
 *   <li>Collects a map of {@code groupId:artifactId → List<Exclusion>} from that
 *       configuration.</li>
 *   <li>For every project in the reactor, iterates over its <em>direct</em>
 *       {@code <dependencies>} and adds the configured exclusions to any dependency whose
 *       {@code groupId:artifactId} matches.</li>
 * </ol>
 *
 * <p>
 * Because this happens before resolution, the Maven resolver will honour the injected exclusions
 * and will never add the excluded transitive artifacts to {@code project.getArtifacts()}.
 * Packaging tools such as {@code maven-shade-plugin} and {@code maven-assembly-plugin} therefore
 * never see the excluded artifacts.
 * </p>
 *
 * <h2>Configuration</h2>
 *
 * <p>
 * Configure the plugin once in the parent POM with {@code <extensions>true</extensions>}:
 * </p>
 *
 * <pre>
 * &lt;plugin&gt;
 *   &lt;groupId&gt;de.silverhorn.fca.maven&lt;/groupId&gt;
 *   &lt;artifactId&gt;edm-maven-plugin&lt;/artifactId&gt;
 *   &lt;extensions&gt;true&lt;/extensions&gt;
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
 * &lt;/plugin&gt;
 * </pre>
 *
 * <p>
 * Child modules do not need any additional configuration. The extension automatically applies
 * the exclusions to all modules in the reactor.
 * </p>
 */
@Named("extensional-dependency-management-extension")
@Singleton
public class ExtensionalDependencyManagementExtension extends AbstractMavenLifecycleParticipant {

   private static final String PLUGIN_GROUP_ID    = "de.silverhorn.fca.maven";
   private static final String PLUGIN_ARTIFACT_ID = "edm-maven-plugin";

   private final Logger log = LoggerFactory.getLogger(ExtensionalDependencyManagementExtension.class);

   @Override
   public void afterProjectsRead(MavenSession session) throws MavenExecutionException {
      List<MavenProject> projects = session.getProjects();
      if (projects == null || projects.isEmpty()) {
         return;
      }

      // Collect the exclusion configuration from every project that declares the plugin.
      // We merge configurations from all projects so that a child POM can add further exclusions
      // on top of what the parent already declared.
      Map<String, List<Exclusion>> exclusionsByGav = collectExclusionConfig(projects);

      if (exclusionsByGav.isEmpty()) {
         log.debug("[edm] No exclusion configuration found in reactor — skipping.");
         return;
      }

      log.info("[edm] Propagating {} exclusion rule(s) to all reactor projects:", exclusionsByGav.size());
      exclusionsByGav.forEach((gav, excls) ->
            log.info("[edm]   {} -> [{}]", gav,
                  excls.stream().map(e -> e.getGroupId() + ":" + e.getArtifactId())
                       .collect(Collectors.joining(", "))));

      // Apply the collected exclusions to the direct dependencies of every project.
      for (MavenProject project : projects) {
         applyExclusionsToProject(project, exclusionsByGav);
      }
   }

   // -------------------------------------------------------------------------
   // Configuration reading
   // -------------------------------------------------------------------------

   /**
    * Scans all projects for plugin configurations of {@code edm-maven-plugin} and merges the
    * declared {@code <dependencies>/<dependency>/<exclusions>} into a single map.
    *
    * @param projects all projects in the reactor
    * @return map of {@code "groupId:artifactId" → List<Exclusion>}; never {@code null}
    */
   private Map<String, List<Exclusion>> collectExclusionConfig(List<MavenProject> projects) {
      Map<String, List<Exclusion>> result = new LinkedHashMap<>();

      for (MavenProject project : projects) {
         Plugin plugin = findEdmPlugin(project);
         if (plugin == null) {
            continue;
         }

         // Read top-level <configuration> block
         mergeExclusionsFromDom(result, (Xpp3Dom) plugin.getConfiguration(), project.getArtifactId());

         // Also read <configuration> blocks inside <executions>
         for (PluginExecution execution : plugin.getExecutions()) {
            mergeExclusionsFromDom(result, (Xpp3Dom) execution.getConfiguration(), project.getArtifactId());
         }
      }

      return result;
   }

   /**
    * Finds the {@code edm-maven-plugin} entry in the given project's build plugins (including
    * inherited plugin management entries).
    */
   private Plugin findEdmPlugin(MavenProject project) {
      if (project.getBuild() == null) {
         return null;
      }
      for (Plugin plugin : project.getBuild().getPlugins()) {
         if (PLUGIN_GROUP_ID.equals(plugin.getGroupId())
               && PLUGIN_ARTIFACT_ID.equals(plugin.getArtifactId())) {
            return plugin;
         }
      }
      return null;
   }

   /**
    * Parses a {@code <configuration>} DOM node and merges any
    * {@code <dependencies>/<dependency>/<exclusions>/<exclusion>} entries into {@code target}.
    */
   private void mergeExclusionsFromDom(Map<String, List<Exclusion>> target,
                                        Xpp3Dom configDom,
                                        String sourceProject) {
      if (configDom == null) {
         return;
      }

      Xpp3Dom dependenciesNode = configDom.getChild("dependencies");
      if (dependenciesNode == null) {
         return;
      }

      for (Xpp3Dom depNode : dependenciesNode.getChildren("dependency")) {
         String groupId    = childValue(depNode, "groupId");
         String artifactId = childValue(depNode, "artifactId");

         if (groupId == null || artifactId == null) {
            log.warn("[edm] Skipping incomplete <dependency> entry in {} (missing groupId or artifactId)",
                  sourceProject);
            continue;
         }

         String depKey = groupId + ":" + artifactId;

         Xpp3Dom exclusionsNode = depNode.getChild("exclusions");
         if (exclusionsNode == null) {
            continue;
         }

         for (Xpp3Dom exclNode : exclusionsNode.getChildren("exclusion")) {
            String exclGroupId    = childValue(exclNode, "groupId");
            String exclArtifactId = childValue(exclNode, "artifactId");

            if (exclGroupId == null || exclArtifactId == null) {
               log.warn("[edm] Skipping incomplete <exclusion> under {} in {} (missing groupId or artifactId)",
                     depKey, sourceProject);
               continue;
            }

            Exclusion exclusion = new Exclusion();
            exclusion.setGroupId(exclGroupId);
            exclusion.setArtifactId(exclArtifactId);

            target.computeIfAbsent(depKey, k -> new ArrayList<>()).add(exclusion);
         }
      }
   }

   private static String childValue(Xpp3Dom node, String childName) {
      Xpp3Dom child = node.getChild(childName);
      return (child != null) ? child.getValue() : null;
   }

   // -------------------------------------------------------------------------
   // Exclusion propagation
   // -------------------------------------------------------------------------

   /**
    * Injects the collected exclusions into the direct {@code <dependencies>} of the given project.
    *
    * <p>
    * Only dependencies whose {@code groupId:artifactId} matches a key in {@code exclusionsByGav}
    * are modified. Duplicate exclusions are silently skipped.
    * </p>
    */
   private void applyExclusionsToProject(MavenProject project,
                                          Map<String, List<Exclusion>> exclusionsByGav) {
      List<Dependency> directDeps = project.getDependencies();
      if (directDeps == null || directDeps.isEmpty()) {
         return;
      }

      for (Dependency dep : directDeps) {
         String depKey = dep.getGroupId() + ":" + dep.getArtifactId();
         List<Exclusion> toAdd = exclusionsByGav.get(depKey);
         if (toAdd == null || toAdd.isEmpty()) {
            continue;
         }

         Set<String> existingKeys = dep.getExclusions().stream()
               .map(e -> e.getGroupId() + ":" + e.getArtifactId())
               .collect(Collectors.toSet());

         List<Exclusion> newExclusions = toAdd.stream()
               .filter(e -> !existingKeys.contains(e.getGroupId() + ":" + e.getArtifactId()))
               .collect(Collectors.toList());

         if (!newExclusions.isEmpty()) {
            dep.getExclusions().addAll(newExclusions);
            log.info("[edm] {}:{} — injected exclusion(s) [{}] into direct dependency {}",
                  project.getGroupId(), project.getArtifactId(),
                  newExclusions.stream().map(e -> e.getGroupId() + ":" + e.getArtifactId())
                               .collect(Collectors.joining(", ")),
                  depKey);
         }
      }
   }
}
