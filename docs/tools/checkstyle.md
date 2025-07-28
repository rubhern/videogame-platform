# Checkstyle Tutorial (2025)

## 1  What Is Checkstyle?

**Checkstyle** is an open‑source static‑analysis tool that scans Java source files and **reports breaches of a coding standard**—formatting, naming, Javadoc, import order, complexity, and more.  
It walks the Abstract Syntax Tree (AST) produced by `javac` and applies a list of *modules* (rules).  
Each rule that fires produces a *violation*, which can be surfaced as a warning or a build‑breaking error.

Typical reasons to adopt it:

* Keep code reviews focused on business logic instead of whitespace.
* Catch bugs such as missing `@Override`, empty `catch` blocks, or duplicated imports early.
* Guarantee that every repository shares the same style guide.

---

## 2  Using Checkstyle with Maven

### 2.1 Add versions in the parent POM

```xml
<properties>
    <!-- Last stable versions – April 2025 -->
    <maven.checkstyle.version>3.6.0</maven.checkstyle.version>
    <checkstyle.version>10.24.0</checkstyle.version>
</properties>
```

### 2.2 Configure the plugin once

```xml
<build>
  <pluginManagement>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-checkstyle-plugin</artifactId>
        <version>${maven.checkstyle.version}</version>

        <!-- pin the engine version you want -->
        <dependencies>
          <dependency>
            <groupId>com.puppycrawl.tools</groupId>
            <artifactId>checkstyle</artifactId>
            <version>${checkstyle.version}</version>
          </dependency>
        </dependencies>

        <configuration>
          <!-- shared rules -->
          <configLocation>${project.basedir}/../dev-platform/checkstyle/google_checks.xml</configLocation>
          <encoding>UTF-8</encoding>
          <consoleOutput>true</consoleOutput>
          <failOnViolation>true</failOnViolation>
          <!-- ignore generated code -->
          <excludeGeneratedSources>true</excludeGeneratedSources>
        </configuration>

        <executions>
          <execution>
            <id>verify-style</id>
            <phase>verify</phase>
            <goals><goal>check</goal></goals>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </pluginManagement>
</build>
```

Each micro‑service then needs only:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-checkstyle-plugin</artifactId>
</plugin>
```

### 2.3 Running

| Action | Command |
| ------ | ------- |
| Verify in CI | `mvn verify` (runs `checkstyle:check`) |
| HTML report | `mvn checkstyle:checkstyle` |
| Ignore failures locally | `mvn -Dcheckstyle.failOnViolation=false verify` |

---

## 3  Spotless — Your Auto‑Formatter Friend

Checkstyle **reports** formatting issues; **Spotless** **fixes** them automatically.

### 3.1 Plugin snippet

```xml
<plugin>
  <groupId>com.diffplug.spotless</groupId>
  <artifactId>spotless-maven-plugin</artifactId>
  <version>2.44.4</version>

  <executions>
    <!-- format before compile -->
    <execution>
      <id>apply-spotless</id>
      <phase>process-sources</phase>
      <goals><goal>apply</goal></goals>
    </execution>

    <!-- block CI if formatting is wrong -->
    <execution>
      <id>check-spotless</id>
      <phase>verify</phase>
      <goals><goal>check</goal></goals>
    </execution>
  </executions>

  <configuration>
    <java>
      <googleJavaFormat>
        <version>1.20.0</version>
      </googleJavaFormat>

      <importOrder>
        <order>com.mycompany,java,javax,org,com</order>
        <wildcardsLast>true</wildcardsLast>
      </importOrder>

      <removeUnusedImports/>
    </java>
  </configuration>
</plugin>
```

**Order matters:** because Spotless hooks into `process-sources`, it runs **before** Checkstyle, so Checkstyle never complains about issues Spotless can auto‑correct.

### 3.2 Daily commands

| Goal | Purpose |
| ---- | ------- |
| `mvn spotless:apply` | Re‑format locally before committing |
| `mvn spotless:check` | Fail CI if any file is mis‑formatted |
| `-DspotlessRatchetFrom=origin/main` | Enforce only on changed files |

---

## 4  IntelliJ IDEA 2025.1 Configuration

1. **Install Checkstyle‑IDEA** → *Settings › Plugins*.  
2. **Add your rules file**: *Settings › Tools › Checkstyle* → ➕ New → pick `google_checks.xml` (or a custom copy).  
   * Tick only that file – untick the default Google profile if you modified it.  
   * Enable *Scan files automatically* for real‑time feedback.  
3. **Exclude generated code** in the same dialog: *Exclude from scan › generated sources*.  
4. **(Optional)** Install *Spotless Apply* plugin or configure *Save Actions* to run `spotlessApply` on save.  
5. Commit `.idea/checkstyle-idea.xml` so every teammate inherits the same configuration.

---

## 5  Recap

* **Checkstyle** = static style checker → breaks the build on violations.  
* **Spotless** = automatic formatter → fixes what Checkstyle would otherwise flag.  
* Wire Spotless to `process-sources` and Checkstyle to `verify` so the build does:  
  1. format → 2. compile → 3. test → 4. style‑verify.  
* Mirror the same XML inside IntelliJ to get instant warnings while coding.

Follow this recipe and your micro‑services will stay clean, diffs will shrink, and debates about curly brackets will become ancient history.
