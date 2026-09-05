/** Owns manual inbound HTTP delivery and isolated build-generated OpenAPI transport types. */
@org.springframework.modulith.ApplicationModule(
        displayName = "API Delivery",
        allowedDependencies = {
            "catalogue::application",
            "catalogue::cover",
            "catalogue::releases",
            "catalogue::search"
        })
package com.videogameplatform.api;
