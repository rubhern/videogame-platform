/** Owns manual inbound HTTP delivery and isolated build-generated OpenAPI transport types. */
@org.springframework.modulith.ApplicationModule(
        displayName = "API Delivery",
        allowedDependencies = {
            "catalogue::application",
            "catalogue::cover",
            "catalogue::releases",
            "catalogue::search",
            "catalogue::details",
            "identity::application",
            "ratings::application"
        })
package com.videogameplatform.api;
