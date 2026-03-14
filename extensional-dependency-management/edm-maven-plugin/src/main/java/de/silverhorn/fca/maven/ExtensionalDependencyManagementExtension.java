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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * <h2>Two exclusion modes</h2>
 *
 * <h3>Mode 1 — targeted exclusions ({@code <dependencies>})</h3>
 * <p>
 * Exclusions are declared on a specific carrier dependency. The extension injects the exclusion
 * into every direct {@code <dependency>} declaration whose {@code groupId:artifactId} matches
 * the configured carrier.
 * </p>
 * <p>
 * Use this when you know which direct dependency transitively pulls in the unwanted artifact.
 * </p>
 *
 * <h3>Mode 2 — global exclusions ({@code <globalExclusions>})</h3>
 * <p>
 * The artifact to exclude is declared without a carrier. The extension injects the exclusion into
 * <em>every</em> direct dependency of every reactor project. This guarantees that the artifact
 * cannot enter the resolved set via any transitive path, regardless of which dependency carries it.
 * </p>
 * <p>
 * Use this when the carrier is itself only transitively present (i.e. your project has no direct
 * dependency on it), or when you want a blanket ban regardless of the transitive path.
 * </p>
 *
 * <h2>Configuration</h2>
 *
 * <pre>
 * &lt;plugin&gt;
 *   &lt;groupId&gt;de.silverhorn.fca.maven&lt;/groupId&gt;
 *   &lt;artifactId&gt;edm-maven-plugin&lt;/artifactId&gt;
 *   &lt;extensions&gt;true&lt;/extensions&gt;
 *   &lt;configuration&gt;
 *
 *     &lt;!-- Mode 1: targeted — only applied when undertow-core is a direct dependency --&gt;
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
 *
 *     &lt;!-- Mode 2: global — injected into every direct dependency, regardless of carrier --&gt;
 *     &lt;globalExclusions&gt;
 *       &lt;exclusion&gt;
 *         &lt;groupId&gt;ch.qos.logback&lt;/groupId&gt;
 *         &lt;artifactId&gt;logback-classic&lt;/artifactId&gt;
 *       &lt;/exclusion&gt;
 *       &lt;exclusion&gt;
 *         &lt;groupId&gt;org.slf4j&lt;/groupId&gt;
 *         &lt;artifactId&gt;slf4j-simple&lt;/artifactId&gt;
 *       &lt;/exclusion&gt;
 *     &lt;/globalExclusions&gt;
 *
 *   &lt;/configuration&gt;
 * &lt;/plugin&gt;
 * </pre>
 *
 * <p>
 * Both modes can be combined. Child modules do not need any additional configuration.
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

      // --- Mode 1: targeted exclusions keyed by carrier dependency ---
      Map<String, List<Exclusion>> exclusionsByCarrier = collectTargetedExclusionConfig(projects);

      // --- Mode 2: global exclusions applied to every direct dependency ---
      List<Exclusion> globalExclusions = collectGlobalExclusionConfig(projects);

      if (exclusionsByCarrier.isEmpty() && globalExclusions.isEmpty()) {
         log.debug("[edm] No exclusion configuration found in reactor — skipping.");
         return;
      }

      if (!exclusionsByCarrier.isEmpty()) {
         log.info("[edm] Targeted exclusion rules ({}):", exclusionsByCarrier.size());
         exclusionsByCarrier.forEach((carrier, excls) ->
               log.info("[edm]   {} -> [{}]", carrier,
                     excls.stream().map(e -> e.getGroupId() + ":" + e.getArtifactId())
                          .collect(Collectors.joining(", "))));
      }

      if (!globalExclusions.isEmpty()) {
         log.info("[edm] Global exclusions (injected into every direct dependency): [{}]",
               globalExclusions.stream().map(e -> e.getGroupId() + ":" + e.getArtifactId())
                               .collect(Collectors.joining(", ")));
      }

      for (MavenProject project : projects) {
         applyExclusionsToProject(project, exclusionsByCarrier, globalExclusions);
      }
   }

   // -------------------------------------------------------------------------
   // Configuration reading — Mode 1 (targeted)
   // -------------------------------------------------------------------------

   private Map<String, List<Exclusion>> collectTargetedExclusionConfig(List<MavenProject> projects) {
      Map<String, List<Exclusion>> result = new LinkedHashMap<>();
      for (MavenProject project : projects) {
         Plugin plugin = findEdmPlugin(project);
         if (plugin == null) {
            continue;
         }
         mergeTargetedExclusionsFromDom(result, (Xpp3Dom) plugin.getConfiguration(),
               project.getArtifactId());
         for (PluginExecution execution : plugin.getExecutions()) {
            mergeTargetedExclusionsFromDom(result, (Xpp3Dom) execution.getConfiguration(),
                  project.getArtifactId());
         }
      }
      return result;
   }

   private void mergeTargetedExclusionsFromDom(Map<String, List<Exclusion>> target,
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
            log.warn("[edm] Skipping incomplete <dependency> in {} (missing groupId or artifactId)",
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
               log.warn("[edm] Skipping incomplete <exclusion> under {} in {}",
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

   // -------------------------------------------------------------------------
   // Configuration reading — Mode 2 (global)
   // -------------------------------------------------------------------------

   /**
    * Collects {@code <globalExclusions>/<exclusion>} entries from all projects that declare
    * the plugin. Global exclusions are merged across all projects (de-duplicated by
    * {@code groupId:artifactId}).
    */
   private List<Exclusion> collectGlobalExclusionConfig(List<MavenProject> projects) {
      // Use a Set to de-duplicate across multiple project declarations
      Set<String> seen = new LinkedHashSet<>();
      List<Exclusion> result = new ArrayList<>();

      for (MavenProject project : projects) {
         Plugin plugin = findEdmPlugin(project);
         if (plugin == null) {
            continue;
         }
         mergeGlobalExclusionsFromDom(result, seen, (Xpp3Dom) plugin.getConfiguration(),
               project.getArtifactId());
         for (PluginExecution execution : plugin.getExecutions()) {
            mergeGlobalExclusionsFromDom(result, seen, (Xpp3Dom) execution.getConfiguration(),
                  project.getArtifactId());
         }
      }
      return result;
   }

   private void mergeGlobalExclusionsFromDom(List<Exclusion> target,
                                              Set<String> seen,
                                              Xpp3Dom configDom,
                                              String sourceProject) {
      if (configDom == null) {
         return;
      }
      Xpp3Dom globalExclusionsNode = configDom.getChild("globalExclusions");
      if (globalExclusionsNode == null) {
         return;
      }
      for (Xpp3Dom exclNode : globalExclusionsNode.getChildren("exclusion")) {
         String exclGroupId    = childValue(exclNode, "groupId");
         String exclArtifactId = childValue(exclNode, "artifactId");
         if (exclGroupId == null || exclArtifactId == null) {
            log.warn("[edm] Skipping incomplete <globalExclusion> in {} (missing groupId or artifactId)",
                  sourceProject);
            continue;
         }
         String key = exclGroupId + ":" + exclArtifactId;
         if (seen.add(key)) {
            Exclusion exclusion = new Exclusion();
            exclusion.setGroupId(exclGroupId);
            exclusion.setArtifactId(exclArtifactId);
            target.add(exclusion);
         }
      }
   }

   // -------------------------------------------------------------------------
   // Shared helpers
   // -------------------------------------------------------------------------

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

   private static String childValue(Xpp3Dom node, String childName) {
      Xpp3Dom child = node.getChild(childName);
      return (child != null) ? child.getValue() : null;
   }

   // -------------------------------------------------------------------------
   // Exclusion propagation
   // -------------------------------------------------------------------------

   /**
    * Injects exclusions into the direct {@code <dependencies>} of the given project.
    *
    * <ul>
    *   <li><b>Targeted exclusions</b>: only injected into dependencies whose
    *       {@code groupId:artifactId} matches the configured carrier key.</li>
    *   <li><b>Global exclusions</b>: injected into <em>every</em> direct dependency,
    *       so the excluded artifact cannot enter the resolved set via any transitive path.</li>
    * </ul>
    */
   private void applyExclusionsToProject(MavenProject project,
                                          Map<String, List<Exclusion>> exclusionsByCarrier,
                                          List<Exclusion> globalExclusions) {
      List<Dependency> directDeps = project.getDependencies();
      if (directDeps == null || directDeps.isEmpty()) {
         return;
      }

      for (Dependency dep : directDeps) {
         Set<String> existingKeys = dep.getExclusions().stream()
               .map(e -> e.getGroupId() + ":" + e.getArtifactId())
               .collect(Collectors.toSet());

         List<Exclusion> toAdd = new ArrayList<>();

         // Mode 1: targeted — only for matching carrier
         String depKey = dep.getGroupId() + ":" + dep.getArtifactId();
         List<Exclusion> targeted = exclusionsByCarrier.get(depKey);
         if (targeted != null) {
            targeted.stream()
                  .filter(e -> !existingKeys.contains(e.getGroupId() + ":" + e.getArtifactId()))
                  .forEach(toAdd::add);
         }

         // Mode 2: global — applied to every direct dependency
         // Skip self-exclusion: don't add an exclusion for the dependency itself
         globalExclusions.stream()
               .filter(e -> !existingKeys.contains(e.getGroupId() + ":" + e.getArtifactId()))
               .filter(e -> !(e.getGroupId().equals(dep.getGroupId())
                     && e.getArtifactId().equals(dep.getArtifactId())))
               .forEach(toAdd::add);

         if (!toAdd.isEmpty()) {
            dep.getExclusions().addAll(toAdd);
            log.info("[edm] {}:{} — injected [{}] into {}",
                  project.getGroupId(), project.getArtifactId(),
                  toAdd.stream().map(e -> e.getGroupId() + ":" + e.getArtifactId())
                       .collect(Collectors.joining(", ")),
                  depKey);
         }
      }
   }
}
