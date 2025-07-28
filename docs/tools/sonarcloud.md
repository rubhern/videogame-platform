# 🚀 SonarCloud Integration – Quick Guide

> Platform: **SonarCloud (free SaaS edition)**  
> Date: **July 2025**  
> Sample repository: *videogame‑platform* (monorepo with Java 21 + SpringBoot3 microservices)

---

## 1 What is Sonar(SonarQube / SonarCloud)?

Sonar is a *Continuous Inspection* platform that **analyses source code** to measure, track, and improve its quality.  
**SonarCloud** is SonarSource’s fully managed SaaS offering:

* Scans every pull request automatically.
* Calculates key metrics and reports the result via GitHub Checks.
* Stores history and trends without any server maintenance.

---

## 2 What does Sonar detect?

| Category | Description | Examples |
|----------|-------------|----------|
| **Bugs** | Runtime errors that will cause failures in production | NullPointer, incorrect API usage |
| **Vulnerabilities** | Exploitable security risks | SQL Injection, Path Traversal |
| **CodeSmells** | Maintainability problems | Long methods, high cyclomatic complexity |
| **SecurityHotspots** | Code areas that need manual review | Insecure cryptography |
| **Duplications** | Copy‑pasted code increasing change cost | Same block in multiple classes |
| **Coverage** | Percentage of lines covered by tests | — |
| **Reliability / Maintainability ratings** | Letter grades A–E based on technical debt | — |

---

## 3 What is a **QualityGate** and why does it matter?

A **Quality Gate** is a set of **mandatory thresholds** applied to the latest analysis:

* It **must pass** before merging a pull request or deploying to production.  
* It evaluates **only the new code** (delta since the previous version) so legacy debt does not block you.  
* Default example (*Sonar way*):  
  * Coverage ≥ 80%  
  * 0 new Bugs / 0 new Vulnerabilities  
  * 0% new Duplications

> 🚦 If any threshold fails, the GitHub check turns red and the `QualityGate` job in CI blocks the pipeline. This ensures **code quality never degrades with each commit**.

---

## 4 Getting started with SonarCloud

1. **Sign in** with your GitHub account at <https://sonarcloud.io>.  
2. Create an **Organization** (one‑click) and grant access to the `videogame‑platform` repo.  
3. Inside the org, choose **Create project → Analyze new project**, pick the repository, and confirm.  
4. Copy the **ProjectKey** (e.g. `videogame-platform_game-service`).  
5. Navigate to **MyAccount → Security → Generate Token** named `SONAR_TOKEN`.  
6. In GitHub → *Settings → Secrets and variables → Actions*, add:  

   | Name | Scope | Value |
   |------|-------|-------|
   | `SONAR_TOKEN` | *Repository* | `<token>` |
   | `SONAR_PROJECT_KEY` | *Repository* | `videogame-platform_game-service` |

---

## 5 GitHub Actions pipeline (`build-and-push.yml`)

```yaml
- name: 🔎 SonarCloud Scan
  uses: SonarSource/sonarcloud-github-action@v2
  with:
    projectBaseDir: services/${{ matrix.service }}
    args: >
      -Dsonar.projectKey=${{ secrets.SONAR_PROJECT_KEY }}
      -Dsonar.organization=your-org
      -Dsonar.java.binaries=.
      -Dsonar.coverage.exclusions=**/*Test.java,**/*IT.java
  env:
    SONAR_TOKEN: ${{ secrets.SONAR_TOKEN }}

- name: ✅ Quality Gate
  uses: SonarSource/sonarqube-quality-gate-action@v1
  with:
    scanMetadataReportFile: services/${{ matrix.service }}/.scannerwork/report-task.txt
  env:
    SONAR_TOKEN: ${{ secrets.SONAR_TOKEN }}
```

* This runs **before** the `build-and-push` job (push to registry+k3s deploy).  
* If the *QualityGate* fails, GitHub blocks the merge and the following job is skipped.

---

## 6 Local analysis (SonarLint & Maven)

* **IntelliJ IDEA** → Install **SonarLint**, bind it to SonarCloud, and enable “*Automatically sync issues*”.  
* **CLI** (requires JDK 17+):

```bash
mvn --batch-mode verify sonar:sonar   -Dsonar.organization=your-org   -Dsonar.projectKey=$SONAR_PROJECT_KEY   -Dsonar.login=$SONAR_TOKEN   -Dsonar.host.url=https://sonarcloud.io
```

A direct link to the dashboard is printed at the end.

---

## 7 References & next steps

* Official docs: <https://docs.sonarcloud.io/>  
* Quality Gates: <https://docs.sonarcloud.io/improve/quality-gates/>  
* GitHub Marketplace: `sonarcloud-github-action`  
* Maven plugin: `org.sonarsource.scanner.maven:sonar-maven-plugin:3.12.0.5941`  
* **OWASP Top 10 2021** for background on detected vulnerabilities.

---

> 💡 **DevSecOps tip**  
> Combine SonarCloud with dependency scanning (OWASP Dep Check or Snyk) and container analysis (Trivy) for end‑to‑end security coverage in your pipeline.
