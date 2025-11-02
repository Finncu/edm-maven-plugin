# Maven Dependency Management Extension Plugin

A Maven plugin that extends existing dependency management definitions by adding exclusions, scope, or classifier configurations to dependencies already present in your project's `<dependencyManagement>` section.

## Why This Plugin?

When working with Maven BOMs (Bill of Materials) or parent POMs that define dependency management, you often need to add exclusions to specific dependencies without duplicating the entire dependency declaration. This plugin allows you to configure exclusions and other attributes via plugin parameters instead of modifying the primary `<dependencyManagement>` block directly.

## Why I Created This Plugin

I can't access many dependency versions of third-party dependency versions in child pom's

### Use Cases

- **Add exclusions to BOM-managed dependencies** without redefining them
- **Override scope or classifier** for managed dependencies
- **Keep your POM clean** by separating dependency management extensions from base definitions
- **Centralize exclusion rules** in parent POMs that apply to all child modules
- **Work with imported BOMs** (e.g., Spring Boot, Quarkus) while excluding unwanted transitive dependencies

## Features

- ? **Extend existing dependency management** with exclusions
- ? **Extends** BOM definitions and imports
- ? **Override scope and classifier** for managed dependencies
- ? **Add new dependencies** to dependency management if not present
- ? **Thread-safe** execution
- ? **Runs in VALIDATE phase** by default (early in the lifecycle)
- ? **Works with imported BOMs** (`<scope>import</scope>`)
- ? **Clean logging** to track what's being extended

## Requirements

- Maven 3.0 or higher
- Java 8 or higher

## Installation

Add the plugin to your custom build POM or your custom build tile:

```xml
<build>
    <plugins>
        <plugin>
            <groupId>de.silverhorn.fca.maven</groupId>
            <artifactId>edm-maven-plugin</artifactId>
            <version>1.0.1</version>
            <executions>
                <execution>
                    <id>extend-dependency-management</id>
                    <goals>
                        <goal>extend-dependency-management</goal>
                    </goals>
                    <configuration>
                        <!-- Configuration here -->
                    </configuration>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

## Usage

### Basic Example: Adding Exclusions

Suppose you're using a BOM that includes `undertow-core`, but you want to exclude `xnio-nio`:

```xml
<plugin>
    <groupId>de.silverhorn.fca.maven</groupId>
    <artifactId>edm-maven-plugin</artifactId>
    <version>1.0.1</version>
    <executions>
        <execution>
            <id>extend-dependency-management</id>
            <goals>
                <goal>extend-dependency-management</goal>
            </goals>
            <configuration>
                <dependencies>
                    <dependency>
                        <groupId>io.undertow</groupId>
                        <artifactId>undertow-core</artifactId>
                        <exclusions>
                            <exclusion>
                                <groupId>org.jboss.xnio</groupId>
                                <artifactId>xnio-nio</artifactId>
                            </exclusion>
                        </exclusions>
                    </dependency>
                </dependencies>
            </configuration>
        </execution>
    </executions>
</plugin>
```

### Example: Multiple Exclusions

```xml
<configuration>
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
            <exclusions>
                <exclusion>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-tomcat</artifactId>
                </exclusion>
                <exclusion>
                    <groupId>org.apache.logging.log4j</groupId>
                    <artifactId>log4j-to-slf4j</artifactId>
                </exclusion>
            </exclusions>
        </dependency>
    </dependencies>
</configuration>
```

### Example: Override Scope

```xml
<configuration>
    <dependencies>
        <dependency>
            <groupId>javax.servlet</groupId>
            <artifactId>servlet-api</artifactId>
            <scope>provided</scope>
        </dependency>
    </dependencies>
</configuration>
```

### Example: Override Classifier

```xml
<configuration>
    <dependencies>
        <dependency>
            <groupId>net.java.dev.jna</groupId>
            <artifactId>jna</artifactId>
            <classifier>platform</classifier>
        </dependency>
    </dependencies>
</configuration>
```

### Example: Add New Dependency to Management

If a dependency is not already in dependency management, you can add it by specifying the version:

```xml
<configuration>
    <dependencies>
        <dependency>
            <groupId>com.example</groupId>
            <artifactId>my-library</artifactId>
            <version>1.2.3</version>
            <exclusions>
                <exclusion>
                    <groupId>commons-logging</groupId>
                    <artifactId>commons-logging</artifactId>
                </exclusion>
            </exclusions>
        </dependency>
    </dependencies>
</configuration>
```

### Complete Example with BOM

```xml
<project>
    <dependencyManagement>
        <dependencies>
            <!-- Import a BOM -->
            <dependency>
                <groupId>org.wildfly.bom</groupId>
                <artifactId>wildfly-javaee7</artifactId>
                <version>10.0.0.Final</version>
                <scope>import</scope>
                <type>pom</type>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <build>
        <plugins>
            <plugin>
                <groupId>de.silverhorn.fca.maven</groupId>
                <artifactId>edm-maven-plugin</artifactId>
                <version>1.0.1</version>
                <executions>
                    <execution>
                        <id>extend-bom-dependencies</id>
                        <goals>
                            <goal>extend-dependency-management</goal>
                        </goals>
                        <configuration>
                            <dependencies>
                                <!-- Extend BOM-managed dependency with exclusions -->
                                <dependency>
                                    <groupId>io.undertow</groupId>
                                    <artifactId>undertow-core</artifactId>
                                    <exclusions>
                                        <exclusion>
                                            <groupId>org.jboss.xnio</groupId>
                                            <artifactId>xnio-nio</artifactId>
                                        </exclusion>
                                    </exclusions>
                                </dependency>
                            </dependencies>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>

    <dependencies>
        <!-- Use the extended dependency -->
        <dependency>
            <groupId>io.undertow</groupId>
            <artifactId>undertow-core</artifactId>
            <!-- Version comes from BOM, exclusions from plugin -->
        </dependency>
    </dependencies>
</project>
```

## Configuration Parameters

### `dependencies`

**Type:** `Dependency[]`  
**Required:** Yes  
**Description:** Array of dependency configurations to extend or add to dependency management.

Each dependency can have the following elements:

| Element | Type | Required | Description                                                                            |
|---------|------|----------|----------------------------------------------------------------------------------------|
| `groupId` | String | Yes | The group ID of the dependency                                                         |
| `artifactId` | String | Yes | The artifact ID of the dependency                                                      |
| `version` | String | No | Version (only needed when adding new dependencies)                                     |
| `type` | String | No | Dependency type (default: `jar`) must be the same as in imported BOM or dont configure |
| `scope` | String | No | Override the scope                                                                     |
| `classifier` | String | No | Override the classifier                                                                |
| `exclusions` | List | No | List of exclusions to add                                                              |

## How It Works

1. **Plugin runs in VALIDATE phase** (early in the Maven lifecycle)
2. **Reads existing dependency management** from the project
3. **For each configured dependency:**
   - If it exists in dependency management: **extends** it with exclusions, scope, or classifier
   - If it doesn't exist and has a version: **adds** it to dependency management
   - If it doesn't exist and has no version: **warns** and ignores it
4. **Logs all changes** for transparency

## Plugin Goals

### `extend-dependency-management`

**Default Phase:** `validate`  
**Thread Safe:** Yes

Extends existing dependency management definitions with exclusions and other attributes.

## Logging

The plugin provides informative logging:

```
[INFO] found managed dependency: io.undertow:undertow-core:jar
[INFO] extend management dependency with: io.undertow:undertow-core:jar { exclusions: { org.jboss.xnio:xnio-nio }}
```

Or when adding a new dependency:

```
[INFO] No managed dependency found for com.example:my-library:jar - using dependency in version 1.2.3
```

## Use with Maven Enforcer Plugin

This plugin works great with the [Maven Enforcer Plugin](https://maven.apache.org/enforcer/maven-enforcer-plugin/) to ensure that unwanted dependencies are truly excluded:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-enforcer-plugin</artifactId>
    <version>3.4.1</version>
    <executions>
        <execution>
            <id>enforce-banned-dependencies</id>
            <goals>
                <goal>enforce</goal>
            </goals>
            <configuration>
                <rules>
                    <bannedDependencies>
                        <excludes>
                            <exclude>org.jboss.xnio:xnio-nio</exclude>
                        </excludes>
                    </bannedDependencies>
                </rules>
            </configuration>
        </execution>
    </executions>
</plugin>
```

## Troubleshooting

### Exclusions not applied

**Problem:** The exclusions don't seem to be applied to transitive dependencies.

**Solution:** Make sure you're using the dependency in your `<dependencies>` section. Exclusions in `<dependencyManagement>` only apply when the dependency is actually used.

### Plugin runs too late

**Problem:** The plugin runs after dependencies are already resolved.

**Solution:** The plugin runs in the `validate` phase by default. If you need it earlier, you can't go earlier than `validate` in the standard lifecycle. Consider using a Maven Extension instead (see Advanced Usage below).

### Dependency not found

**Problem:** `No managed dependency found for X - ignoring dependency in case of missing version`

**Solution:** Either:
1. Add the dependency to `<dependencyManagement>` first
2. Or specify a `<version>` in the plugin configuration to add it

## Advanced Usage

### Using in Parent POM

Define the plugin in your parent POM to apply exclusions to all child modules:

```xml
<build>
    <pluginManagement>
        <plugins>
            <plugin>
                <groupId>de.silverhorn.fca.maven</groupId>
                <artifactId>edm-maven-plugin</artifactId>
                <version>1.0.1</version>
                <executions>
                    <execution>
                        <id>extend-dependency-management</id>
                        <goals>
                            <goal>extend-dependency-management</goal>
                        </goals>
                        <configuration>
                            <!-- Common exclusions for all modules -->
                            <dependencies>
                                <dependency>
                                    <groupId>commons-logging</groupId>
                                    <artifactId>commons-logging</artifactId>
                                    <exclusions>
                                        <exclusion>
                                            <groupId>javax.servlet</groupId>
                                            <artifactId>servlet-api</artifactId>
                                        </exclusion>
                                    </exclusions>
                                </dependency>
                            </dependencies>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </pluginManagement>
    
    <plugins>
        <!-- Activate the plugin -->
        <plugin>
            <groupId>de.silverhorn.fca.maven</groupId>
            <artifactId>edm-maven-plugin</artifactId>
        </plugin>
    </plugins>
</build>
```

### Combining with Properties

You can use Maven properties in the configuration:

```xml
<properties>
    <excluded.group>org.jboss.xnio</excluded.group>
    <excluded.artifact>xnio-nio</excluded.artifact>
</properties>

<plugin>
    <groupId>de.silverhorn.fca.maven</groupId>
    <artifactId>edm-maven-plugin</artifactId>
    <version>1.0.1</version>
    <executions>
        <execution>
            <id>extend-dependency-management</id>
            <goals>
                <goal>extend-dependency-management</goal>
            </goals>
            <configuration>
                <dependencies>
                    <dependency>
                        <groupId>io.undertow</groupId>
                        <artifactId>undertow-core</artifactId>
                        <exclusions>
                            <exclusion>
                                <groupId>${excluded.group}</groupId>
                                <artifactId>${excluded.artifact}</artifactId>
                            </exclusion>
                        </exclusions>
                    </dependency>
                </dependencies>
            </configuration>
        </execution>
    </executions>
</plugin>
```

## Comparison with Alternatives

### vs. Direct Exclusions in Dependencies

**Traditional approach:**
```xml
<dependencies>
    <dependency>
        <groupId>io.undertow</groupId>
        <artifactId>undertow-core</artifactId>
        <version>1.3.15.Final</version>
        <exclusions>
            <exclusion>
                <groupId>org.jboss.xnio</groupId>
                <artifactId>xnio-nio</artifactId>
            </exclusion>
        </exclusions>
    </dependency>
</dependencies>
```

**With this plugin:**
```xml
<!-- In parent POM or BOM -->
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.undertow</groupId>
            <artifactId>undertow-core</artifactId>
            <version>1.3.15.Final</version>
        </dependency>
    </dependencies>
</dependencyManagement>

<!-- Plugin configuration (once) -->
<plugin>
    <groupId>de.silverhorn.fca.maven</groupId>
    <artifactId>edm-maven-plugin</artifactId>
    <configuration>
        <dependencies>
            <dependency>
                <groupId>io.undertow</groupId>
                <artifactId>undertow-core</artifactId>
                <exclusions>
                    <exclusion>
                        <groupId>org.jboss.xnio</groupId>
                        <artifactId>xnio-nio</artifactId>
                    </exclusion>
                </exclusions>
            </dependency>
        </dependencies>
    </configuration>
</plugin>

<!-- In child modules (clean!) -->
<dependencies>
    <dependency>
        <groupId>io.undertow</groupId>
        <artifactId>undertow-core</artifactId>
        <!-- Version and exclusions managed centrally -->
    </dependency>
</dependencies>
```

**Benefits:**

- ? Extensional exclusion management
- ? Works with imported BOMs

## Support

If you encounter any issues or have questions:

1. Check the [Troubleshooting](#troubleshooting) section
2. Add a bitbucket comment or contact finncu

## Changelog

### Version 1.0.1
- Initial release
- Support for extending dependency management with exclusions
- Support for overriding scope and classifier
- Support for adding new dependencies to management
- Thread-safe execution
- Comprehensive logging

## Acknowledgments

- Inspired by the need to work cleanly with Maven BOMs
- Built on top of Maven's dependency management system
- Thanks to the Maven community for the excellent plugin API

---

*Autor - finncu*