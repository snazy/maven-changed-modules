/*
 * Copyright (C) 2020 Dremio
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.projectnessie.maven.changedmodules;

import static java.lang.String.format;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.InstantiationStrategy;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

@SuppressWarnings("unused")
@Mojo(
    name = "find",
    defaultPhase = LifecyclePhase.INITIALIZE,
    requiresDependencyResolution = ResolutionScope.NONE,
    threadSafe = true,
    instantiationStrategy = InstantiationStrategy.SINGLETON)
public class ChangedModulesMojo extends AbstractMojo {

  private static final String DEFAULT_SKIP_PROPERTIES =
      "skipTests,"
          + "maven.main.skip,"
          + "maven.test.skip,"
          + "license.skip,"
          + "spotless.check.skip,"
          + "skip,"
          // frontend-maven-plugin
          + "skip.installyarn,"
          + "skip.yarn,"
          + "skip.webpack,"
          + "skip.ember,"
          + "skip.bower,"
          + "skip.gulp,"
          + "skip.grunt,"
          + "skip.jspm,"
          + "skip.karma,"
          + "skip.npm,"
          + "skip.npx,"
          + "skip.pnpm,"
          + "skip.installnodenpm,"
          + "skip.installnodepnpm,"
          // Gatling plugin
          + "gatling.skip"
          // Quarkus plugin
          + "quarkus.build.skip"
          + "quarkus.generate-code.skip"
          // Nessie specific properties
          + "skipLicense,"
          + "skipEnforcer,"
          + "skipCheckstyle";

  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  private MavenProject project;

  @Parameter(defaultValue = "${session}", readonly = true)
  MavenSession mavenSession;

  @Parameter(property = "changed-projects.skip", defaultValue = "false")
  private boolean skip;

  @Parameter(property = "changed-projects.changedFilesFile")
  private File changedFilesFile;

  /** The "current" Git reference. {@code HEAD} is correct in most cases. */
  @Parameter(defaultValue = "HEAD", property = "changed-projects.currentReference")
  private String currentReference;

  /**
   * The Git base reference to calculate the list of changed files from. It should be something like
   * {@code origin/main} or the base branch reference in CI.
   */
  @Parameter(defaultValue = "origin/main", property = "changed-projects.baseReference")
  private String baseReference;

  /**
   * The executable to get the Git commit hash for a reference, {@code %%REFERENCE%%} is replaced
   * with the reference name.
   */
  @Parameter(defaultValue = "git rev-parse %%REFERENCE%%")
  private String executableGetCommitId;

  /**
   * Executable to find the common ancestor / merge base from two references, supplied with the
   * placeholders {@code %%CURRENT_REFERENCE%%} and {@code %%BASE_REFERENCE%%}.
   */
  @Parameter(defaultValue = "git merge-base %%CURRENT_REFERENCE%% %%BASE_REFERENCE%%")
  private String executableFindCommonAncestor;

  /**
   * Executable to generate the list of changed files against a Git commit/reference supplied via
   * the the placeholder {@code %%COMMON_ANCESTOR%%}.
   */
  @Parameter(defaultValue = "git diff %%COMMON_ANCESTOR%% --name-only")
  private String executableFindChangedFiles;

  @SuppressWarnings("MismatchedReadAndWriteOfArray")
  @Parameter(defaultValue = DEFAULT_SKIP_PROPERTIES, property = "changed-projects.skipProperties")
  private String[] disabledProjectProperties;

  private boolean initialized;
  private Set<MavenProject> projectsToBuild;

  @Override
  public synchronized void execute() throws MojoExecutionException {
    if (skip) {
      return;
    }

    if (!initialized) {
      initialize();
    }
    reportDisabled();
  }

  private void initialize() throws MojoExecutionException {
    MavenProject topLevelProject = mavenSession.getTopLevelProject();
    File topLevelBasedir = mavenSession.getTopLevelProject().getBasedir();

    getLog()
        .debug(
            format(
                "Using top-level project's '%s:%s' base directory '%s'",
                topLevelProject.getGroupId(), topLevelProject.getArtifactId(), topLevelBasedir));

    List<Path> changedFiles = collectChangedFiles(topLevelBasedir);

    List<ChangedFileProject> changedFileProjects =
        associateChangedFilesToProjects(topLevelBasedir, changedFiles);

    logChangedFileProjects(changedFileProjects);

    projectsToBuild =
        changedFileProjects.stream()
            .map(ChangedFileProject::getProject)
            .distinct()
            .flatMap(
                p ->
                    Stream.concat(
                        Stream.of(p),
                        mavenSession
                            .getProjectDependencyGraph()
                            .getDownstreamProjects(p, true)
                            .stream()))
            .collect(Collectors.toSet());

    getLog()
        .info(
            format(
                "Project build list:%s",
                projectsToBuild.stream()
                    .map(p -> format("%s:%s", p.getGroupId(), p.getArtifactId()))
                    .collect(Collectors.joining("\n        ", "\n        ", ""))));

    // Apply properties to skip relevant plugin executions on non-included projects (non-parent-pom
    // projects only)
    mavenSession.getAllProjects().stream()
        .filter(p -> !projectsToBuild.contains(p))
        .filter(p -> !"pom".equals(p.getPackaging()))
        .forEach(
            p -> {
              Properties projectProps = p.getProperties();
              for (String disabledProjectProperty : disabledProjectProperties) {
                projectProps.put(disabledProjectProperty, "true");
              }
            });

    initialized = true;
  }

  private void reportDisabled() {
    if (!projectsToBuild.contains(project)) {
      getLog().info("Changed modules plugin: project is disabled");
    }
  }

  private void logChangedFileProjects(List<ChangedFileProject> changedFileProjects) {
    if (getLog().isDebugEnabled()) {
      getLog()
          .debug(
              format(
                  "Changed file details: %s",
                  changedFileProjects.stream()
                      .map(ChangedFileProject::toString)
                      .collect(Collectors.joining("\n        ", "\n        ", ""))));
    } else {
      changedFileProjects.stream()
          .collect(Collectors.groupingBy(ChangedFileProject::getProject))
          .forEach(
              (p, cfps) ->
                  getLog()
                      .info(
                          format(
                              "Changed files of project '%s:%s':%s",
                              p.getGroupId(),
                              p.getArtifactId(),
                              cfps.stream()
                                  .map(ChangedFileProject::getFilePath)
                                  .map(Path::toString)
                                  .collect(Collectors.joining("\n        ", "\n        ", "")))));
    }
  }

  /**
   * Returns the changed files. Each returned string is a path relative to {@code topLevelBasedir}.
   */
  private List<Path> collectChangedFiles(File topLevelBasedir) throws MojoExecutionException {
    if (changedFilesFile == null) {
      getLog().info("Collecting list of changed files from Git...");
      return collectChangedFilesFromGit(topLevelBasedir);
    } else {
      getLog().info(format("Collecting list of changed files from file '%s'...", changedFilesFile));
      try {
        return Files.readAllLines(changedFilesFile.toPath(), StandardCharsets.UTF_8).stream()
            .map(String::trim)
            .filter(s -> !s.isEmpty() && !s.startsWith("#"))
            .map(Paths::get)
            .collect(Collectors.toList());
      } catch (IOException e) {
        throw new MojoExecutionException(format("Failed to read file '%s'", changedFilesFile), e);
      }
    }
  }

  private List<Path> collectChangedFilesFromGit(File topLevelBasedir)
      throws MojoExecutionException {
    getLog()
        .info(
            format(
                "Getting list of changed files of current reference '%s' against '%s'",
                currentReference, baseReference));

    String currentReferenceCommitId =
        executeProcessReturnSingleLine(
                topLevelBasedir, executableGetCommitId, "%%REFERENCE%%", currentReference)
            .trim();
    getLog().info(format("Current reference at '%s'", currentReferenceCommitId));

    String baseReferenceCommitId =
        executeProcessReturnSingleLine(
                topLevelBasedir, executableGetCommitId, "%%REFERENCE%%", baseReference)
            .trim();
    getLog().info(format("Base reference at '%s'", baseReferenceCommitId));

    String commonAncestorCommitId =
        executeProcessReturnSingleLine(
            topLevelBasedir,
            executableFindCommonAncestor,
            "%%CURRENT_REFERENCE%%",
            currentReferenceCommitId,
            "%%BASE_REFERENCE%%",
            baseReferenceCommitId);
    getLog().info(format("Common ancestor is '%s'", commonAncestorCommitId));

    List<String> changedFiles =
        executeProcess(
            topLevelBasedir,
            executableFindChangedFiles,
            "%%COMMON_ANCESTOR%%",
            commonAncestorCommitId);

    return changedFiles.stream().map(Paths::get).collect(Collectors.toList());
  }

  private List<ChangedFileProject> associateChangedFilesToProjects(
      File topLevelBasedir, List<Path> changedFiles) {
    List<ChangedFileProject> changedFileProjects = new ArrayList<>();
    Path topLevelBasedirPath = topLevelBasedir.toPath().toAbsolutePath();
    for (Path changedFile : changedFiles) {
      getLog().debug(format("Finding project match for changed file '%s'...", changedFile));

      int matchLen = 0;
      MavenProject matchProject = null;
      Path matchFilePath = null;

      for (MavenProject project : mavenSession.getAllProjects()) {
        Path projectBaseDir = project.getBasedir().toPath().toAbsolutePath();
        if (!projectBaseDir.startsWith(topLevelBasedirPath)) {
          continue;
        }
        Path projectRelative = topLevelBasedirPath.relativize(projectBaseDir);
        if (changedFile.startsWith(projectRelative)) {
          Path inProjectPath = projectRelative.relativize(changedFile);
          getLog()
              .debug(
                  format(
                      " ... potential match in  '%s:%s' at '%s'",
                      project.getGroupId(), project.getArtifactId(), projectBaseDir));
          if (projectBaseDir.getNameCount() > matchLen) {
            matchFilePath = inProjectPath;
            matchLen = projectBaseDir.getNameCount();
            matchProject = project;
          }
        }
      }

      if (matchProject == null) {
        matchProject = mavenSession.getTopLevelProject();
        matchFilePath = changedFile;
      }

      ChangedFileProject changedFileProject = new ChangedFileProject(matchProject, matchFilePath);

      if (getLog().isDebugEnabled()) {
        getLog().debug(format(" --> %s", changedFileProject));
      }

      changedFileProjects.add(changedFileProject);
    }
    return changedFileProjects;
  }

  private String executeProcessReturnSingleLine(File cwd, String executable, String... replacements)
      throws MojoExecutionException {
    List<String> lines = executeProcess(cwd, executable, replacements);
    if (lines.size() > 1) {
      throw new MojoExecutionException(
          format("Executable '%s' returned more than a single line", executable));
    }
    if (lines.isEmpty()) {
      return "";
    }
    return lines.get(0);
  }

  private List<String> executeProcess(File cwd, String executable, String... replacements)
      throws MojoExecutionException {
    try {
      for (int i = 0; i < replacements.length; i += 2) {
        executable = executable.replace(replacements[i], replacements[i + 1]);
      }

      Process process = new ProcessBuilder(executable.split(" ")).directory(cwd).start();
      ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
      ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
      byte[] buf = new byte[4096];
      int exitCode;
      try (InputStream stdout = process.getInputStream();
          InputStream stderr = process.getErrorStream()) {
        while (true) {
          boolean anyIo = false;

          int av = stdout.available();
          if (av > 0) {
            anyIo = true;
            int rd = stdout.read(buf, 0, Math.min(buf.length, av));
            outBuffer.write(buf, 0, rd);
          }
          av = stderr.available();
          if (av > 0) {
            anyIo = true;
            int rd = stderr.read(buf, 0, Math.min(buf.length, av));
            outBuffer.write(buf, 0, rd);
          }

          try {
            exitCode = process.exitValue();
            if (!anyIo) {
              break;
            }
          } catch (IllegalThreadStateException e) {
            // nessie server still alive
          }

          if (!anyIo) {
            try {
              // Yield CPU for a little while, so this background thread does not consume 100% CPU.
              ChangedModulesMojo.yield();
            } catch (InterruptedException interruptedException) {
              System.err.println("ProcessHandler's watchdog thread interrupted, stopping process.");
              process.destroy();
              exitCode = -2;
              break;
            }
          }
        }

        String errStr = errBuffer.toString(StandardCharsets.UTF_8.name());
        BufferedReader reader =
            new BufferedReader(
                new InputStreamReader(
                    new ByteArrayInputStream(outBuffer.toByteArray()), StandardCharsets.UTF_8));
        String ln;
        List<String> lines = new ArrayList<>();
        while ((ln = reader.readLine()) != null) {
          lines.add(ln);
        }

        if (exitCode != 0) {
          throw new MojoExecutionException(
              format(
                  "Executable '%s' failed to execute, exit code = %d, stderr = '%s', stdout = '%s'",
                  executable, exitCode, errStr, String.join("\n", lines)));
        }

        return lines;
      }
    } catch (MojoExecutionException e) {
      throw e;
    } catch (Exception e) {
      throw new MojoExecutionException(e);
    }
  }

  // Externalized to prevent IntelliJ warning about busy-loop spin (which it actually is)
  private static void yield() throws InterruptedException {
    Thread.sleep(1L);
  }
}
