## MERMAID C4 Deployment Diagram

```mermaid
C4Deployment
title Video-Game Platform – Infrastructure View

Node(client, "Client Side") {
    Container(browser,  "Web / Mobile Browser",  "Mozilla, Chrome, Edge")
}

Node(edge, "Edge Tier") {
    Container(cdn,  "Edge CDN",  "Varnish / CloudFront")
}

Node(cluster, "Kubernetes Cluster") {
    Container(apiGw,   "API Gateway", "Kong Ingress")
    Container(keycloak,"Keycloak",    "OIDC")
    Container(svcs,    "Micro-services (5×)", "Spring Boot Pods, HPA")
    Container(kafka,   "Kafka",       "StatefulSet 3×")
    Container(redis,   "Redis",       "StatefulSet")
    Container(pg,      "PostgreSQL",  "StatefulSet 3×")
    Container(mongo,   "MongoDB",     "StatefulSet 3×")
}

Node(observ, "Observability Stack") {
    Container(prom, "Prometheus", "Helm Chart")
    Container(graf, "Grafana",    "Deployment")
    Container(elk,  "ELK Stack",  "Elastic Cloud / ECK")
}

Rel(browser, cdn,  "HTTPS")
Rel(cdn,     apiGw,"HTTPS")
Rel(apiGw,   svcs, "REST")
Rel(svcs,    kafka,"Kafka protocol")
Rel(svcs,    pg,   "JDBC")
Rel(svcs,    mongo,"Driver")
Rel(svcs,    redis,"Cache")
Rel(svcs,    prom, "Scraped metrics")
Rel(svcs,    elk,  "Filebeat logs")
Rel_R(svcs,  keycloak,"JWT introspection")
Rel_L(apiGw, keycloak,"JWT introspection")
Rel_D(prom,  graf, "Dashboards")
Rel_D(elk,   graf, "Loki datasource")
```

## PLANTUML C4 Deployment Diagram

```puml
@startuml
!includeurl https://raw.githubusercontent.com/plantuml-stdlib/C4-PlantUML/master/C4_Deployment.puml
LAYOUT_WITH_LEGEND()

Deployment_Node(browser, "Web / Mobile Browser") {
    Deployment_Node(edge, "Edge CDN", "CloudFront") {
        Container(cdn,"Edge CDN")
    }
}

Deployment_Node(k8s, "Managed Kubernetes Cluster") {
    Deployment_Node(ns, "namespace: videogame") {
        Container(apiGw,   "API Gateway",   "Kong Ingress", "1-3 replicas")
        Container(keycloak,"Keycloak",      "OIDC",         "2 replicas")
        Container(userPod, "User Svc Pod",  "Spring Boot",  "HPA 2-10")
        Container(gamePod, "Game Svc Pod",  "Spring Boot",  "HPA 2-10")
        Container(ratingPod,"Rating Svc Pod","Spring Boot", "HPA 2-10")
        Container(reviewPod,"Review Svc Pod","Spring Boot", "HPA 2-10")
        Container(launchPod,"Launch Svc Pod","Spring Boot", "HPA 2-6")
        ContainerDb(pg,    "PostgreSQL", "3-node StatefulSet")
        ContainerDb(mongo, "MongoDB",    "3-node StatefulSet")
        ContainerQueue(kafka,    "Kafka",      "3-node StatefulSet")
        ContainerQueue(redis,    "Redis",      "StatefulSet")
    }
}

Deployment_Node(obs, "Observability Stack") {
    Container(prom,"Prometheus")
    Container(graf,"Grafana")
    Container(elk, "ELK Stack")
}

Rel(browser, cdn,    "HTTPS")
Rel(cdn,     apiGw,  "HTTPS")
Rel(apiGw,   keycloak,"JWT introspection")
Rel(apiGw,   userPod,"REST")
Rel(userPod, pg,     "JDBC")
Rel(ratingPod, kafka,"rating-created")
Rel(userPod, prom,   "metrics")
Rel_L(prom, graf,    "dashboards")
Rel(elk, graf,       "Loki datasource")
SHOW_LEGEND()
@enduml

```
