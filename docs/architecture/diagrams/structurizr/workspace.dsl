workspace "VideoGame Platform" "C4 model for the approved private learning MVP target design." {

    model {
        !identifiers flat
        !impliedRelationships false

        visitor = person "Visitor" {
            description "Browses releases, searches games and reads public game pages."
        }

        authenticatedUser = person "Authenticated User" {
            description "Browses the catalogue and manages their own personal ratings."
        }

        operator = person "Operator" {
            description "Operates the platform and triggers bounded catalogue synchronization."
        }

        videoGamePlatform = softwareSystem "VideoGame Platform" {
            description "Spanish-first platform for release discovery, game information and personal ratings."

            webFrontend = container "Web Frontend" {
                description "Renders the Spanish-first user experience and calls the same-origin product API."
                technology "React 19.2, TypeScript, Vite 8.1"
                tags "Web Frontend"
            }

            applicationBackend = container "Application Backend" {
                description "Serves static assets, owns the BFF/API, coordinates the modular monolith and synchronizes the bounded catalogue."
                technology "Java 25, Spring Boot 4.1, Spring Modulith 2.1"
                tags "Application"
            }

            applicationDatabase = container "Application Database" {
                description "Stores catalogue, release, user mapping, rating and synchronization state."
                technology "PostgreSQL 18"
                tags "Database"
            }
        }

        identityProvider = softwareSystem "Identity Provider" {
            description "Keycloak-based system that authenticates users and issues validated identity context."
            tags "External System"
        }

        igdbApi = softwareSystem "IGDB API" {
            description "Provides candidate video-game catalogue and release metadata."
            tags "External System"
        }

        igdbImageCdn = softwareSystem "IGDB Image CDN" {
            description "Serves approved provider cover images directly to the browser."
            tags "External System"
        }

        telemetryPlatform = softwareSystem "Telemetry Platform" {
            description "Stores and exposes application logs, metrics and traces."
            tags "External System"
        }

        visitor -> videoGamePlatform "Browses releases, searches and views games"
        authenticatedUser -> videoGamePlatform "Manages personal ratings"
        operator -> videoGamePlatform "Operates and synchronizes the platform"

        videoGamePlatform -> identityProvider "Delegates authentication and identity"
        videoGamePlatform -> igdbApi "Imports bounded catalogue metadata"
        videoGamePlatform -> igdbImageCdn "References approved cover images"
        videoGamePlatform -> telemetryPlatform "Emits operational telemetry"

        visitor -> webFrontend "Uses" "HTTPS"
        authenticatedUser -> webFrontend "Uses" "HTTPS"
        operator -> applicationBackend "Triggers synchronization and operational commands"

        webFrontend -> applicationBackend "Uses the same-origin product API" "HTTPS/JSON"
        webFrontend -> identityProvider "Follows authorization redirects" "HTTPS/OIDC"
        webFrontend -> igdbImageCdn "Loads approved cover images" "HTTPS"

        applicationBackend -> applicationDatabase "Reads and writes product state" "JDBC"
        applicationBackend -> identityProvider "Exchanges authorization codes and validates identity" "HTTPS/OIDC"
        applicationBackend -> igdbApi "Synchronizes bounded catalogue metadata" "HTTPS"
        applicationBackend -> telemetryPlatform "Exports logs, metrics and traces" "OTLP"


        deploymentEnvironment "Private dev" {

            ownerDevice = deploymentNode "Owner Device" {
                description "Ruben's workstation or another device connected to the private tailnet."
                technology "Windows 11"

                browser = deploymentNode "Web Browser" {
                    technology "Chrome, Edge or equivalent"

                    webFrontendInstance = containerInstance webFrontend
                }
            }

            github = deploymentNode "GitHub" {
                description "Source-control and delivery SaaS."
                technology "GitHub"

                githubActions = infrastructureNode "GitHub Actions" {
                    description "Builds, validates and publishes the immutable multi-architecture image."
                    technology "GitHub Actions"
                }

                ghcr = infrastructureNode "GitHub Container Registry" {
                    description "Stores the application OCI image by commit and digest."
                    technology "GHCR"
                }
            }

            tailscale = deploymentNode "Private Tailnet" {
                technology "Tailscale Personal"

                privateHttps = infrastructureNode "Tailscale HTTPS" {
                    description "Provides private owner-only HTTPS access to the application and identity provider."
                    technology "Tailscale, MagicDNS and HTTPS"
                }
            }

            oci = deploymentNode "OCI Always Free" {
                description "Private persistent development environment."
                technology "Oracle Cloud Infrastructure"

                vm = deploymentNode "Ampere A1 VM" {
                    description "Single private Ubuntu host with a maximum of 2 OCPUs and 12 GB RAM."
                    technology "Ubuntu, ARM64"

                    applicationBackendInstance = containerInstance applicationBackend
                    keycloakInstance = softwareSystemInstance identityProvider

                    postgresql = deploymentNode "PostgreSQL Server" {
                        description "One PostgreSQL server with isolated application and Keycloak databases and roles."
                        technology "PostgreSQL 18"

                        applicationDatabaseInstance = containerInstance applicationDatabase
                        identityDatabaseInstance = infrastructureNode "Identity Database" {
                            description "Stores Keycloak realm, client, session and identity data."
                            technology "PostgreSQL 18"
                            tags "Database"
                        }
                    }

                    otelCollector = infrastructureNode "OpenTelemetry Collector" {
                        description "Receives and exports application telemetry."
                        technology "OpenTelemetry Collector"
                    }

                    backupJob = infrastructureNode "Backup Job" {
                        description "Creates encrypted logical backups of application and Keycloak state."
                        technology "Scheduled or operator-triggered PostgreSQL backup tooling"
                    }
                }

                observability = deploymentNode "OCI Observability Services" {
                    technology "OCI Logging, Monitoring and APM"

                    telemetryPlatformInstance = softwareSystemInstance telemetryPlatform
                }

                objectStorage = infrastructureNode "OCI Object Storage" {
                    description "Stores encrypted PostgreSQL and Keycloak backups outside the VM."
                    technology "OCI Object Storage"
                }

                vault = infrastructureNode "OCI Vault" {
                    description "Stores protected runtime secrets and independently rotatable credentials."
                    technology "OCI Vault"
                }
            }

            igdb = deploymentNode "IGDB Infrastructure" {
                description "External provider infrastructure."
                technology "IGDB / Twitch"

                igdbApiInstance = softwareSystemInstance igdbApi
                igdbImageCdnInstance = softwareSystemInstance igdbImageCdn
            }

            webFrontendInstance -> privateHttps "Uses private HTTPS"
            privateHttps -> applicationBackendInstance "Routes same-origin HTTPS"
            privateHttps -> keycloakInstance "Routes private OIDC HTTPS"

            keycloakInstance -> identityDatabaseInstance "Reads and writes identity state" "JDBC"
            applicationBackendInstance -> vault "Resolves protected runtime secrets" "HTTPS"
            keycloakInstance -> vault "Resolves protected runtime secrets" "HTTPS"

            applicationBackendInstance -> otelCollector "Exports telemetry" "OTLP"
            otelCollector -> telemetryPlatformInstance "Exports telemetry" "OTLP/HTTPS"

            githubActions -> ghcr "Publishes multi-architecture OCI image"
            githubActions -> vm "Orchestrates manually approved deployment" "Tailscale/OCI APIs"
            ghcr -> vm "Provides immutable image pulled by digest"

            backupJob -> applicationDatabaseInstance "Creates logical backup" "PostgreSQL"
            backupJob -> identityDatabaseInstance "Creates logical backup" "PostgreSQL"
            backupJob -> objectStorage "Writes encrypted backup artefacts" "HTTPS"
        }
    }

    views {
        systemContext videoGamePlatform "SystemContext" {
            title "VideoGame Platform — System Context"
            description "People and external systems that interact with VideoGame Platform."
            include *
            autoLayout lr
        }

        container videoGamePlatform "Containers" {
            title "VideoGame Platform — Containers"
            description "Runtime responsibilities and data stores of the private learning MVP."
            include *
            autoLayout lr
        }

        deployment * "Private dev" "PrivateDevDeployment" {
            title "VideoGame Platform — Private Dev Target Deployment"
            description "Approved target design for OCI Always Free with private Tailscale access; not yet implemented."
            include *

            // The logical container relationships are automatically replicated
            // between deployment instances. Hide the direct edges here because
            // traffic is shown through the physical infrastructure nodes instead.
            exclude "webFrontendInstance -> applicationBackendInstance"
            exclude "webFrontendInstance -> keycloakInstance"
            exclude "applicationBackendInstance -> telemetryPlatformInstance"
        }

        styles {
            element "Person" {
                shape person
                background #08427b
                color #ffffff
            }

            element "Software System" {
                background #1168bd
                color #ffffff
            }

            element "External System" {
                background #666666
                color #ffffff
            }

            element "Container" {
                background #438dd5
                color #ffffff
            }

            element "External Container" {
                background #888888
                color #ffffff
            }

            element "Web Frontend" {
                shape webbrowser
            }

            element "Database" {
                shape cylinder
            }

            element "Deployment Node" {
                background #f5f5f5
                color #222222
                stroke #888888
                fontSize 18
                metadata false
                description false
            }

            element "Infrastructure Node" {
                shape roundedbox
                background #dddddd
                color #222222
                width 280
                height 140
                fontSize 16
                metadata false
                description false
            }

            element "Container Instance" {
                width 280
                height 150
                fontSize 16
                metadata false
                description false
            }

            element "Software System Instance" {
                width 280
                height 150
                fontSize 16
                metadata false
                description false
            }

            relationship "Relationship" {
                color #707070
                fontSize 12
                width 150
            }
        }
    }

    configuration {
        // This workspace details one software system: VideoGame Platform.
        // External systems are referenced as black boxes and instantiated only
        // in deployment views when their physical placement matters.
        scope softwaresystem
    }
}
