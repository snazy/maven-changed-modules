# Changed Modules Maven Plugin

Adaptive enablement of Maven plugins during a build based on the changed files.

## Project status

* not released yet,
* untested,
* experimental

## How it works

* Collects the list of changed files, compared to a base reference.
  This includes changes committed to git and uncommitted local changes.
* Alternatively the plugin can take a list of files from a provided file.
* Computes the projects from the list of changed files, adds dependants projects.
* Properties will be set on each project that is *not* in the computed set of
  projects to *disable* certain plugins. For example, the property `skipTests` is
  set to `true` to disable running tests for unaffected projects, or better: to only
  run tests for projects that have been changed and the projects that depend on
  projects with changed files.

## Tips

* Make sure that your CI environment has the configured base reference available.

## Example CI setup

Build your proejct in two stages:
1. Something like `mvn clean install -DskipTests`
2. The following snippet will only let tests (and some other expensive tasks) not run
   by setting project properties (e.g. `skipTest=true`).
   ```
   mvn verify \
     org.projectnessie.change-projects-maven-plugin:changed-project-maven-plugin:0.1-SNAPSHOT:find
   ```

## Configuration

See [Source](./plugin/src/main/java/org/projectnessie/maven/changedmodules/ChangedModulesMojo.java)
