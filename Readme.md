[![team project](http://jb.gg/badges/team.svg)](https://confluence.jetbrains.com/display/ALL/JetBrains+on+GitHub)

TeamCity Maven Plugin
=====================

Overview
--------
This Maven plugin helps you assemble and package TeamCity plugins (agent and/or server parts) into a distributable ZIP located under target/teamcity.

If you are new to TeamCity plugin development, see the quick start guide: https://github.com/nskvortsov/teamcity-sdk-maven-plugin/wiki/Developing-TeamCity-plugin

Requirements
-----------
- Maven 3.x
- JDK 8 or newer
- A TeamCity SDK or an existing TeamCity installation to test the resulting plugin

Installation
------------
Add the plugin to the build section of the project that will produce the TeamCity plugin ZIP. You will typically have:
- One module for agent-side code (JAR)
- One module for server-side code (JAR or WAR)
- An optional wrapper/aggregator module that assembles the final plugin ZIP

Key concepts
------------
- spec: Maven coordinate of the module to package, in the form groupId:artifactId:version. You may omit leading parts, e.g. :artifactId refers to an artifact in the same reactor build.
- pluginName: The name of your plugin. By default, it is ${project.artifactId}.
- buildServerResources: Path to your plugin’s server-side resources (JSPs, static files). This path usually ends with the plugin name directory inside src/main/webapp/plugins.
- descriptor: Options controlling the TeamCity plugin descriptor. See org.jetbrains.teamcity.Descriptor in sources for full details.
- removeVersionFromJar: Optional boolean for `<agent>` and `<server>`. When `true`, every JAR copied by that workflow is renamed from `artifactId-version[-classifier].jar` to `artifactId[-classifier].jar`.

Quick start: package an agent plugin
-----------------------------------
Example POM fragment for an agent-side wrapper module that packages an existing agent artifact into the TeamCity plugin distribution:

```xml
<artifactId>my-agent-plugin-wrapper</artifactId>

<dependencies>
  <dependency>
    <groupId>somegroup</groupId>
    <artifactId>my-agent-plugin</artifactId>
    <scope>runtime</scope>
  </dependency>
</dependencies>

<build>
  <plugins>
    <plugin>
      <groupId>org.jetbrains.teamcity</groupId>
      <artifactId>teamcity-maven-plugin</artifactId>
      <configuration>
        <agent>
          <!-- spec syntax: groupId:artifactId:version; parts may be omitted, e.g. :artifactId -->
          <spec>:my-agent-plugin</spec> <!-- Reference to an agent plugin module; if omitted, current project is used -->
          <!-- By default, pluginName is ${project.artifactId} -->
          <pluginName>myPlugin</pluginName>
          <removeVersionFromJar>true</removeVersionFromJar>
          <descriptor>
            <!-- See org.jetbrains.teamcity.Descriptor for all available options -->
            <useSeparateClassloader>true</useSeparateClassloader>
            <pluginDependencies>java-dowser</pluginDependencies>
            <toolDependencies>ant</toolDependencies>
          </descriptor>
        </agent>
      </configuration>
    </plugin>
  </plugins>
</build>
```

Package the server part
-----------------------
Example for a WAR-based webapp that packages server-side classes/resources and optionally bundles the agent distribution into the final plugin ZIP:

```xml
<packaging>war</packaging>
<artifactId>my-server-plugin-webapp</artifactId>

<dependencies>
  <dependency>
    <groupId>somegroup</groupId>
    <artifactId>my-server-plugin</artifactId>
  </dependency>
</dependencies>

<build>
  <plugins>
    <plugin>
      <groupId>org.jetbrains.teamcity</groupId>
      <artifactId>teamcity-maven-plugin</artifactId>
      <configuration>
        <server>
          <spec>somegroup:my-server-plugin</spec>
          <!-- By default, pluginName is ${project.artifactId} -->
          <pluginName>myPlugin</pluginName>
          <removeVersionFromJar>true</removeVersionFromJar>
          <descriptor>
            <!-- See org.jetbrains.teamcity.Descriptor for all available options -->
            <nodeResponsibilitiesAware>true</nodeResponsibilitiesAware>
          </descriptor>
          <buildServerResources>src/main/webapp/plugins/myPlugin</buildServerResources>
          <!-- Can be omitted if it matches webapp/plugins/${project.artifactId} -->
        </server>
      </configuration>
      <dependencies>
        <!-- Include agent part into plugin distribution -->
        <dependency>
          <groupId>somegroup</groupId>
          <artifactId>my-agent-plugin</artifactId>
          <classifier>teamcity-agent-plugin</classifier>
          <type>zip</type>
          <version>${project.version}</version>
        </dependency>
      </dependencies>
    </plugin>
  </plugins>
</build>
```

Package both agent and server from a single aggregator
-----------------------------------------------------
This example shows three modules: agent JAR, server JAR, and an aggregator that uses the plugin to build the final TeamCity plugin ZIP.

```xml
<project>
  <artifactId>my-agent-part</artifactId>
  <type>jar</type>
</project>

<project>
  <artifactId>my-server-part</artifactId>
  <type>jar</type>
</project>

<project>
  <artifactId>my-teamcity-plugin</artifactId>
  <packaging>war</packaging>

  <dependencies>
    <artifactId>my-agent-part</artifactId>
    <scope>runtime</scope>
  </dependencies>

  <dependencies>
    <artifactId>my-server-part</artifactId>
    <scope>runtime</scope>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.jetbrains.teamcity</groupId>
        <artifactId>teamcity-maven-plugin</artifactId>
        <configuration>
          <agent>
            <spec>:my-agent-part</spec>
          </agent>
          <server>
            <spec>:my-server-part</spec>
            <!-- By default, pluginName is ${project.artifactId} -->
            <pluginName>myPlugin</pluginName>
            <!-- Customize plugin descriptor if needed -->
            <descriptor />
            <!-- Path to JSPs and static resources; the path should end with the pluginName directory -->
            <buildServerResources>src/main/webapp/plugins/myPlugin</buildServerResources>
          </server>
          <agent>
            <spec>:my-agent-plugin</spec>
            ...
          </agent>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

Shortest option (WAR with attached classes)
-------------------------------------------
If your server module is a WAR, make sure classes are attached so the plugin can package them. The snippet below shows the minimal setup:

```xml
<project>
  <artifactId>my-agent-part</artifactId>
  <type>jar</type>
</project>

<project>
  <artifactId>my-teamcity-plugin</artifactId>
  <packaging>war</packaging>

  <dependencies>
    <artifactId>my-agent-part</artifactId>
    <scope>runtime</scope>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.jetbrains.teamcity</groupId>
        <artifactId>teamcity-maven-plugin</artifactId>
        <configuration>
          <agent>
            <spec>:my-agent-part</spec>
          </agent>
          <server>
            <!-- If spec is omitted for a WAR project, attachClasses must be enabled to package classes -->
            <!-- By default, pluginName is ${project.artifactId} -->
            <pluginName>myPlugin</pluginName>
            <!-- Path to JSPs and static resources; the path should end with the pluginName directory -->
            <buildServerResources>src/main/webapp/plugins/myPlugin</buildServerResources>
          </server>
        </configuration>
      </plugin>

      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-war-plugin</artifactId>
        <configuration>
          <attachClasses>true</attachClasses>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

How to build
------------
- Run mvn clean package in the aggregator or the module where the plugin is configured.
- The resulting ZIP will be created under target/teamcity.

Install into TeamCity
---------------------
- Copy the produced ZIP into the TeamCity data directory under plugins/.
- Restart the TeamCity server.
- Verify in Administration -> Plugins that your plugin is loaded.

Notes
-----
- When using shorthand spec values like :artifactId, ensure the referenced module participates in the same Maven reactor build.
- pluginName defaults to ${project.artifactId}; specify it explicitly if your web resources live under a different folder name.
- Ensure buildServerResources points to src/main/webapp/plugins/<pluginName> (or the equivalent location in your project).
