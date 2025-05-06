# 🗄️ Installing and Using **Sonatype Nexus Repository Manager – Community Edition**

> Version tested: **Nexus 3.67** (May 2025)
> Works on Linux, macOS, Windows (via Docker Desktop or native ZIP install).

---

## 1  Prerequisites

| Tool                                              | Minimum version | Check              |
| ------------------------------------------------- | --------------- | ------------------ |
| **Java 11 LTS** (only if you do *manual* install) | 11.0.22         | `java -version`    |
| **Docker Engine / Desktop** *(recommended)*       | 24.x            | `docker --version` |
| 4 GB RAM free                                     |                 | —                  |
| 10 GB disk (initial)                              |                 | —                  |

---

## 2  Quick install with Docker

```bash
# 1) Pull and start
docker run -d \
  --name nexus \
  -p 8081:8081 \
  -p 5000:5000 \
  -v nexus-data:/nexus-data \   # named volume that persists config & blobs
  sonatype/nexus3:latest

# 2) Tail logs until you see "Started Sonatype Nexus"
docker logs -f nexus
```

---

## 3  First‑time setup in the UI

1. Browse to [http://localhost:8081](http://localhost:8081).
2. **Unlock Nexus**:

    ```bash
    cat $(docker volume inspect nexus-data -f '{{ .Mountpoint }}')/admin.password
    ```
    
    Paste the one‑time password, press **Sign in**.

3. Set a new **admin** password.
4. Choose **Skip** for anonymous telemetry unless you agree.

---

## 4  Creating repositories (if not existing)

### 4.1 Maven *hosted* repo (for your internal artifacts)

1. **Repositories → Repositories → Create repository**
2. Type: **maven2 (hosted)**
3. Name: `maven-releases`
4. Version policy: *Release*
5. Write policy: *Allow redeploy* (or *Allow once* for stricter).
6. Layout: *Maven2*, blob store: *default*, **Create repository**.

Repeat for `maven-snapshots` with *Snapshot* policy.

### 4.2 Maven *proxy* repo (caches Maven Central)

1. Create **maven2 (proxy)**
2. Name: `maven-central`
3. Remote URL: [https://repo1.maven.org/maven2/](https://repo1.maven.org/maven2/)
4. Storage → Maximum component age: e.g. 30 days.
5. **Create**.

### 4.3 Group repo (convenient single URL)

1. Create **maven2 (group)**
2. Name: `maven-public`
3. Add members: first `maven-releases`, then `maven-snapshots`, then `maven-central`.
4. **Create**.

---

## 5  Configure Maven to use Nexus

Place in `~/.m2/settings.xml`:

```xml
<settings>
  <mirrors>
    <mirror>
      <id>nexus</id>
      <mirrorOf>*</mirrorOf>
      <url>http://localhost:8081/repository/maven-public/</url>
    </mirror>
  </mirrors>

  <servers>
    <server>
      <id>nexus-releases</id>
      <username>admin</username>
      <password>••••</password>
    </server>
    <server>
      <id>nexus-snapshots</id>
      <username>admin</username>
      <password>••••</password>
    </server>
  </servers>
</settings>
```

Publish:

```bash
mvn clean deploy -DaltDeploymentRepository=nexus-snapshots::default::http://localhost:8081/repository/maven-snapshots/
```

---

## 6  Docker registry via Nexus

1. Create **docker (hosted)**, name `docker-hosted`, HTTP Port *5000*.
2. In *Docker Clients* tick “V2 signature”.
3. `docker login localhost:5000 -u admin`.
4. Tag & push:

   ```bash
   docker tag alpine localhost:5000/demo/alpine:3.20
   docker push localhost:5000/demo/alpine:3.20
   ```

---

## 7  User & Role management

* **Security → Users → Create local user**
  *Id:* `ci`, *Password:* `***`, assign role **nx-deployment**.
* For LDAP/OIDC → `Security → Realms` and enable provider, then configure under `Administration → Capabilities`.

---

## 8  Upgrading Nexus

```bash
docker pull sonatype/nexus3:latest
docker stop nexus && docker rm nexus
docker run -d --name nexus -p 8081:8081 -p 5000:5000 \
  -v nexus-data:/nexus-data \
  sonatype/nexus3:latest
```

---

## 9  Backup & restore

```bash
# Backup blob + database
docker run --rm --volumes-from nexus -v $(pwd):/backup busybox \
  tar czf /backup/nexus-backup-$(date +%F).tgz /nexus-data

# Restore
docker stop nexus && docker rm nexus
docker volume rm nexus-data
docker volume create nexus-data
tar xzf nexus-backup-2025-05-06.tgz -C $(docker volume inspect nexus-data -f '{{ .Mountpoint }}')
docker run -d --name nexus -p 8081:8081 -p 5000:5000 \
  -v nexus-data:/nexus-data \
  sonatype/nexus3:latest
```

---

## 10  References & next steps

* Official docs: [https://help.sonatype.com/repomanager3](https://help.sonatype.com/repomanager3)
* REST API: `http://localhost:8081/service/rest/swagger.json`
* Consider Sonatype IQ for vulnerability scanning (paid).

---
